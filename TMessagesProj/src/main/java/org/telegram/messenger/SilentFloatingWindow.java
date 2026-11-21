package org.telegram.messenger;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.SilentFloatingActivity;
import org.telegram.utils.pip.PipCoordinator;
import org.telegram.utils.pip.PipEnvironment;
import org.telegram.utils.settings.SharedSettings;

/**
 * SilentGram: fully usable floating window. NOT Android system PiP — system
 * PiP reserves taps for its own menu, so the app can never be used inside
 * it. This is the virtual-Android-apps approach instead: a
 * TYPE_APPLICATION_OVERLAY window above everything, with the whole Telegram
 * UI running on a private virtual display and rendered into a TextureView
 * in the overlay. Touches on the TextureView are forwarded to the floating
 * activity's view hierarchy (in-process dispatch, no injection permission),
 * so taps, scroll and typing work inside the small window.
 *
 * Architecture (all verified against the platform APIs):
 * - VirtualDisplay: DisplayManager.createVirtualDisplay MUST use
 *   VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY — without it Android treats the
 *   display as a screen-sharing display and throws SecurityException
 *   (needs CAPTURE_VIDEO_OUTPUT / CAPTURE_SECURE_VIDEO_OUTPUT or a
 *   MediaProjection token). Flag PUBLIC is kept so the soft keyboard
 *   (another app's window) can render on the display for typing.
 * - Host: SilentFloatingActivity (a LaunchActivity subclass — LaunchActivity
 *   itself is singleTask, so it can never be the second instance). It is
 *   launched with ActivityOptions.setLaunchDisplayId(displayId).
 * - The TextureView OWNS its SurfaceTexture (v2.0.21): the virtual
 *   display is created from the texture the TextureView delivers in
 *   onSurfaceTextureAvailable, never from a pre-created SurfaceTexture
 *   handed in with setSurfaceTexture(). The old pattern left the
 *   TextureView's hardware layer unbound on the user's device — the
 *   display produced frames into a texture the TextureView never
 *   rendered, so the window stayed fully transparent (the background
 *   showed straight through it). The TextureView's own texture is
 *   guaranteed bound to its hardware layer.
 * - The virtual display is the WHOLE PHONE (v2.0.22): created ONCE at
 *   the full screen size and never resized. The TextureView's
 *   SurfaceTexture buffer is the window's content size (the
 *   TextureView sets it to its own view size on layout), so the
 *   compositor scales the entire phone UI down proportionally into
 *   the window — edge-to-edge, the whole design shrinking as the
 *   window shrinks, never a zoomed/cropped chunk. A resize is only
 *   a window-size change: the display is untouched, so frames keep
 *   flowing and the content re-fits on every resize. The old 1:1
 *   design (display at window size + virtualDisplay.resize() at
 *   gesture end) stalled frames on the user's device: a small view
 *   flashed on resize, then vanished, and a second resize showed
 *   nothing. resizeDisplayToWindow() is a no-op by design now.
 * - Frame production is retried, not just reported: if a frame check
 *   still sees no frames, the display is re-created from the live
 *   texture (and the activity re-launched if it is not on the
 *   display) and checked again — a stale display/surface binding on
 *   the device is rebuilt instead of leaving a transparent window.
 *   Recovery is cooldown-based (10s), and a check runs ~2s after
 *   every resize/move stops, so frames must persist across EVERY
 *   resize — not just the first one (the user's repro: frames appear
 *   once at the first resize stop, then die on the next touch).
 * - Minimize "−" in the top bar hides only the overlay window; the
 *   display + activity keep running, and a sidebar pill at the right
 *   edge restores the window at its previous size/position/content.
 * - The main LaunchActivity finishes when the floating one starts
 *   (Telegram's own closeOtherAppActivities rule); the connection is
 *   process-level and stays alive.
 *
 * Honest limits (device test decides the details):
 * - The soft keyboard should render inside the window (it is drawn on the
 *   virtual display); if an OEM blocks IME on virtual displays, typing
 *   falls back to a hardware keyboard / voice input.
 *   Note: VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY (required so the display
 *   can be created at all) may itself keep the IME off the display on
 *   some devices — whether the keyboard appears inside the window is a
 *   device-test question.
 * - The virtual display is fixed portrait; rotating the phone does not
 *   rotate the floating window (it is re-clamped to stay on screen).
 * - Group calls / live streams keep their own content PiP and win over
 *   the floating window, as before.
 */
public class SilentFloatingWindow {

    private static SilentFloatingWindow instance;

    public static SilentFloatingWindow getInstance() {
        if (instance == null) {
            instance = new SilentFloatingWindow();
        }
        return instance;
    }

    private boolean active;
    private WindowManager windowManager;
    private DisplayManager displayManager;
    private FrameLayout rootView;
    private TextureView textureView;
    private WindowManager.LayoutParams windowParams;
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private VirtualDisplay virtualDisplay;
    private SilentFloatingActivity floatingActivity;

    private int fullWidth;
    private int fullHeight;
    private int densityDpi;
    private float density;
    private int screenWidthPx;
    private int screenHeightPx;
    private int windowWidthPx;
    private int windowHeightPx;
    private int barHeightPx;
    /** Current virtual-display size = the FULL phone size (v2.0.22).
     * The TextureView shows it scaled down to the window; forwardTouch
     * maps window coordinates back with these values. */
    private int displayWidthPx;
    private int displayHeightPx;

    private long openDialogId;
    private boolean openDialogIsUser;
    private Runnable launchWatchdog;
    private Runnable frameCheck;
    /** v2.0.22: frame recovery is cooldown-based, not once-per-start.
     * The user's repro: frames appear exactly once (first resize stop)
     * then die on the next interaction — a stall after ANY resize/move
     * must be recoverable, so recovery may run again 10s after the last
     * attempt instead of once per start(). */
    private long lastFrameRecoveryAtMs;
    /** v2.0.22: true while the floating activity's onPause() is running.
     * Fragment pause-dismissals (popups/dialogs) are suppressed only in
     * that window — Home pauses the floating activity and the popup on
     * top must survive it; fragment-cover pauses still dismiss normally.
     * Set/cleared by SilentFloatingActivity around super.onPause(). */
    private boolean pauseDismissalsSuppressed;
    /** v2.0.22: true while the window is minimized to the sidebar pill.
     * Only the overlay is hidden; the virtual display + activity keep
     * running untouched, so restore shows the exact same content. */
    private boolean minimized;
    private View pillView;
    private WindowManager.LayoutParams pillParams;
    private boolean contentPinching;
    /** Root view the current gesture's ACTION_DOWN went to (popup root,
     * dialog decor, or the activity decor). MOVE/UP keep going to the
     * same window, so a popup scroll that starts inside the popup stays
     * inside it. Cleared on UP/CANCEL. */
    private View touchTarget;
    /** Why the last start() failed (null = no failure recorded). Shown on
     * screen when the toggle is ON but the floating window could not start,
     * so a device test names the failing step instead of silently falling
     * back. */
    private String lastStartError;
    /** Result of the last frame check ("OK: ..." / "FAILED: ...", null =
     * not run yet) and when it ran — read by the debug report. */
    private volatile String lastFrameCheck;
    private volatile long lastFrameCheckAtMs;

    private SilentFloatingWindow() {
    }

    public boolean isActive() {
        return active;
    }

    public boolean isMinimized() {
        return minimized;
    }

    public boolean isPauseDismissalsSuppressed() {
        return pauseDismissalsSuppressed;
    }

    public void setPauseDismissalsSuppressed(boolean value) {
        pauseDismissalsSuppressed = value;
    }

    public String getLastStartError() {
        return lastStartError;
    }

    /** Read-only state for the debug report. -1 = no display. */
    public int getVirtualDisplayId() {
        try {
            return virtualDisplay != null && virtualDisplay.getDisplay() != null
                    ? virtualDisplay.getDisplay().getDisplayId() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Display the floating activity is running on, -1 = no activity. */
    public int getActivityDisplayId() {
        try {
            return floatingActivity != null
                    ? floatingActivity.getWindowManager().getDefaultDisplay().getDisplayId() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Last frame-check result ("OK: ..." / "FAILED: ..."), null = not run yet. */
    public String getLastFrameCheck() {
        return lastFrameCheck;
    }

    /** Wall-clock time of the last frame check, 0 = never ran. */
    public long getLastFrameCheckAtMs() {
        return lastFrameCheckAtMs;
    }

    public int getWindowWidthPx() {
        return windowWidthPx;
    }

    public int getWindowHeightPx() {
        return windowHeightPx;
    }

    public int getWindowX() {
        return windowParams != null ? windowParams.x : 0;
    }

    public int getWindowY() {
        return windowParams != null ? windowParams.y : 0;
    }

    public int getDisplayWidthPx() {
        return displayWidthPx;
    }

    public int getDisplayHeightPx() {
        return displayHeightPx;
    }

    public int getScreenWidthPx() {
        return screenWidthPx;
    }

    public int getScreenHeightPx() {
        return screenHeightPx;
    }

    public void setFloatingActivity(SilentFloatingActivity activity) {
        floatingActivity = activity;
        if (launchWatchdog != null) {
            AndroidUtilities.cancelRunOnUIThread(launchWatchdog);
            launchWatchdog = null;
        }
    }

    /**
     * Enter the floating window from the given (foreground) activity.
     * Synchronous: the overlay, the virtual display and the activity launch
     * all happen here, so a Home press cannot strand the user. Returns false
     * when the window could not be started (caller falls back to system PiP).
     */
    public boolean start(Activity activity) {
        lastStartError = null;
        lastFrameRecoveryAtMs = 0;
        if (active) {
            // Safety net: if the window was minimized to the pill and
            // the toggle is hit again, bring it back instead of
            // silently staying minimized with no pill on screen.
            if (minimized) {
                restoreFromMinimized();
            }
            return true;
        }
        if (activity == null || activity.isFinishing()) {
            lastStartError = "no activity";
            return false;
        }
        if (!PipEnvironment.canDrawOverlays(activity)) {
            lastStartError = "overlay permission missing";
            return false;
        }
        PipCoordinator coordinator = PipCoordinator.getInstance();
        if (coordinator.hasSystemPipSession() || coordinator.findFloating() != null) {
            lastStartError = "another PiP window is active";
            return false;
        }
        final Context ctx = ApplicationLoader.applicationContext;
        try {
            windowManager = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            displayManager = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (windowManager == null || displayManager == null) {
                lastStartError = "system service missing";
                return false;
            }
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            density = dm.density;
            densityDpi = dm.densityDpi;
            screenWidthPx = dm.widthPixels;
            screenHeightPx = dm.heightPixels;
            fullWidth = AndroidUtilities.displaySize.x;
            fullHeight = AndroidUtilities.displaySize.y;
            if (fullWidth <= 0 || fullHeight <= 0) {
                fullWidth = dm.widthPixels;
                fullHeight = dm.heightPixels;
            }
            if (fullWidth <= 0 || fullHeight <= 0) {
                lastStartError = "no display size";
                return false;
            }
            barHeightPx = AndroidUtilities.dp(36);

            // Remember which screen to reopen (chat list, or the open chat).
            // v2.0.22: look beneath any sheet fragment — a sheet over a
            // chat must not hide the chat underneath.
            openDialogId = 0;
            openDialogIsUser = false;
            try {
                ChatActivity top = LaunchActivity.getTopChatActivity();
                if (top != null) {
                    long did = top.getDialogId();
                    if (did != 0) {
                        openDialogId = did;
                        openDialogIsUser = did > 0;
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }

            // Window size: persisted width, else 65% of the screen width.
            int savedWidthDp = SharedSettings.silentFloatingWidthDp.get();
            int defaultWidthPx = (int) (fullWidth * 0.65f);
            windowWidthPx = savedWidthDp > 0 ? AndroidUtilities.dp(savedWidthDp) : defaultWidthPx;
            windowWidthPx = clampWidth(windowWidthPx);
            windowHeightPx = contentHeightForWidth(windowWidthPx);

            // Position: persisted, else right side, vertically centred.
            int savedXDp = SharedSettings.silentFloatingXDp.get();
            int savedYDp = SharedSettings.silentFloatingYDp.get();
            int startX = savedXDp > 0 ? AndroidUtilities.dp(savedXDp) : fullWidth - windowWidthPx - AndroidUtilities.dp(16);
            int startY = savedYDp > 0 ? AndroidUtilities.dp(savedYDp) : (screenHeightPx - windowHeightPx) / 2;

            // The TextureView OWNS its SurfaceTexture (v2.0.21): the
            // virtual display is created from the texture the TextureView
            // hands us in onSurfaceTextureAvailable — never from a
            // pre-created `new SurfaceTexture(0)` handed in with
            // setSurfaceTexture(). The old pattern left the hardware layer
            // unbound on the user's device: the display produced frames
            // into a texture the TextureView never rendered, so the window
            // stayed fully transparent (background visible through it).
            // v2.0.22: the display is the WHOLE PHONE (fullWidth x
            // fullHeight), created once and never resized. The
            // TextureView's buffer is the window's content size, so
            // the compositor scales the full UI down into the window,
            // edge-to-edge. Creation happens in the listener (see
            // createVirtualDisplayAndLaunch), so the overlay is added
            // first and the activity launch waits for the texture.
            displayWidthPx = fullWidth;
            displayHeightPx = fullHeight;

            buildOverlay(ctx);
            windowParams.x = clampX(startX);
            windowParams.y = clampY(startY);
            windowManager.addView(rootView, windowParams);
            rootView.requestFocus();
            active = true;

            // The floating activity is launched from the TextureView's
            // SurfaceTextureListener once its texture is ready (see
            // createVirtualDisplayAndLaunch) — not here.

            // Watchdog: if the texture never becomes available, or the
            // activity never appears (e.g. the launch was blocked), do
            // not leave an empty transparent window — go back to the
            // fullscreen app instead.
            launchWatchdog = () -> {
                launchWatchdog = null;
                if (floatingActivity == null && active) {
                    lastStartError = "floating activity never started (no texture / display failed)";
                    FileLog.e("SilentFloatingWindow: floating activity never started, recovering");
                    teardown();
                    relaunchMain(0);
                }
            };
            AndroidUtilities.runOnUIThread(launchWatchdog, 5000);
            return true;
        } catch (Exception e) {
            lastStartError = "error: " + String.valueOf(e.getMessage());
            FileLog.e(e);
            teardown();
            return false;
        }
    }

    private void launchFloatingActivity(Context ctx) {
        Intent intent = new Intent(ctx, SilentFloatingActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (openDialogId != 0) {
            intent.setAction("com.tmessages.openchat");
            if (openDialogIsUser) {
                intent.putExtra("userId", openDialogId);
            } else {
                intent.putExtra("chatId", -openDialogId);
            }
        }
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(virtualDisplay.getDisplay().getDisplayId());
        ctx.startActivity(intent, options.toBundle());
    }

    /**
     * (Re)create the virtual display from the given live texture at the
     * FULL phone size. Releases any previous display/surface first.
     * Returns the new display id, or -1 on failure. The caller
     * re-launches the floating activity when it is not on the new
     * display. v2.0.22: do NOT set the buffer size here — the
     * TextureView owns its SurfaceTexture and sets the buffer size to
     * its own view size on layout, so the full-size display is scaled
     * down into window-size buffers by the compositor.
     */
    private int rebindDisplayToTexture(SurfaceTexture texture) {
        displayWidthPx = fullWidth;
        displayHeightPx = fullHeight;
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
            }
        } catch (Exception ignored) {
        }
        virtualDisplay = null;
        try {
            if (surface != null) {
                surface.release();
            }
        } catch (Exception ignored) {
        }
        surface = null;
        surfaceTexture = texture;
        try {
            surface = new Surface(surfaceTexture);
            virtualDisplay = displayManager.createVirtualDisplay(
                    "SilentGram Floating", displayWidthPx, displayHeightPx, densityDpi, surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            if (virtualDisplay != null && virtualDisplay.getDisplay() != null) {
                int displayId = virtualDisplay.getDisplay().getDisplayId();
                FileLog.d("SilentFloatingWindow: virtual display id=" + displayId + " size=" + displayWidthPx + "x" + displayHeightPx);
                return displayId;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return -1;
    }

    /** Re-launch the floating activity when it is not running on the
     * current virtual display (e.g. after a display rebind). */
    private void relaunchFloatingActivityIfNeeded() {
        boolean activityOnDisplay = false;
        try {
            activityOnDisplay = floatingActivity != null && !floatingActivity.isFinishing()
                    && virtualDisplay != null && virtualDisplay.getDisplay() != null
                    && floatingActivity.getWindowManager().getDefaultDisplay().getDisplayId() == virtualDisplay.getDisplay().getDisplayId();
        } catch (Exception ignored) {
        }
        if (!activityOnDisplay) {
            try {
                launchFloatingActivity(ApplicationLoader.applicationContext);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    /**
     * Called from the TextureView's SurfaceTextureListener once the
     * TextureView owns a live SurfaceTexture. Creates the virtual display
     * from THAT texture (never a pre-created one) at the FULL phone
     * size, then launches the floating activity on it. The 5s
     * frame check is scheduled here, so it measures 5s of real activity
     * runtime, not 5s from the overlay being added.
     *
     * v2.0.22: if a display already exists but the texture is a NEW one
     * (the TextureView re-created it on a resize/move, or on restore
     * after minimize detached the view), the display is still bound to
     * the DEAD texture and no more frames ever arrive — rebind the
     * display to the new texture instead of leaving a transparent
     * window. Same texture + existing display = nothing to do.
     */
    private void createVirtualDisplayAndLaunch(Context ctx, SurfaceTexture texture, int width, int height) {
        if (!active || texture == null || width <= 0 || height <= 0) {
            return;
        }
        if (virtualDisplay != null) {
            if (texture == surfaceTexture) {
                return;
            }
            FileLog.e("SilentFloatingWindow: texture re-created, rebinding display");
            if (rebindDisplayToTexture(texture) >= 0) {
                relaunchFloatingActivityIfNeeded();
                scheduleFrameCheck(2000);
            } else {
                lastStartError = "virtual display failed";
                AndroidUtilities.runOnUIThread(() -> recoverToMain());
            }
            return;
        }
        try {
            int displayId = rebindDisplayToTexture(texture);
            if (displayId < 0) {
                lastStartError = "virtual display failed";
                FileLog.e("SilentFloatingWindow: virtual display creation failed");
                AndroidUtilities.runOnUIThread(() -> recoverToMain());
                return;
            }
            launchFloatingActivity(ctx);

            // Frame check: if the virtual display never delivers a frame,
            // the window stays transparent. Sample the TextureView once —
            // a live app always has non-black pixels (app bar, text,
            // avatars). Logs the exact state (virtual display id, whether
            // the activity is running and on which display, surface and
            // texture validity, TextureView size, pixels sampled) and, on
            // failure, toasts it too, so a device test names the failing
            // step instead of leaving a silent transparent window; it
            // tears nothing down.
            scheduleFrameCheck(5000);
        } catch (Exception e) {
            lastStartError = "error: " + String.valueOf(e.getMessage());
            FileLog.e(e);
            AndroidUtilities.runOnUIThread(() -> recoverToMain());
        }
    }

    /** Post a frame check after delayMs. Cancels any pending check first,
     * so a burst of resize events ends in exactly one check ~delayMs
     * after the LAST one — the check that proves frames survived the
     * resize (v2.0.22: frames must persist across EVERY resize/move). */
    private void scheduleFrameCheck(long delayMs) {
        if (frameCheck != null) {
            AndroidUtilities.cancelRunOnUIThread(frameCheck);
            frameCheck = null;
        }
        frameCheck = () -> runFrameCheck();
        AndroidUtilities.runOnUIThread(frameCheck, delayMs);
    }

    /** Frame check ~2s after a resize/move stops (see runFrameCheck). */
    private void schedulePostResizeFrameCheck() {
        if (!active || minimized) {
            return;
        }
        scheduleFrameCheck(2000);
    }

    /**
     * One frame check: sample the TextureView for non-black pixels. On
     * failure, retry with a 10s cooldown (lastFrameRecoveryAtMs):
     * re-create the virtual display from the live texture and re-launch
     * the activity if it is not on the display, then check again in 5s.
     * A failure inside the cooldown only toasts — no teardown, so the
     * window stays usable for taps/drag even if the picture is missing.
     * Skipped while minimized (the detached TextureView samples 0
     * pixels and would trigger a spurious recovery).
     */
    private void runFrameCheck() {
        frameCheck = null;
        if (!active || minimized || textureView == null) {
            return;
        }
        try {
            int vdId = -1;
            try {
                if (virtualDisplay != null && virtualDisplay.getDisplay() != null) {
                    vdId = virtualDisplay.getDisplay().getDisplayId();
                }
            } catch (Exception ignored) {
            }
            boolean activityRunning = floatingActivity != null && !floatingActivity.isFinishing();
            int activityDisplayId = -1;
            try {
                if (floatingActivity != null) {
                    activityDisplayId = floatingActivity.getWindowManager().getDefaultDisplay().getDisplayId();
                }
            } catch (Exception ignored) {
            }
            boolean surfaceValid = surface != null && surface.isValid();
            boolean textureAvailable = textureView.isAvailable();
            int tvW = textureView.getWidth();
            int tvH = textureView.getHeight();
            android.graphics.Bitmap bmp = textureView.getBitmap(32, 32);
            int sampled = 0;
            int lit = 0;
            if (bmp != null) {
                for (int y = 0; y < bmp.getHeight(); y += 2) {
                    for (int x = 0; x < bmp.getWidth(); x += 2) {
                        sampled++;
                        if ((bmp.getPixel(x, y) & 0x00FFFFFF) != 0) {
                            lit++;
                        }
                    }
                }
                bmp.recycle();
            }
            String state = "vd=" + vdId + " activity=" + (activityRunning ? "running" : "none")
                    + " activityDisplay=" + activityDisplayId
                    + " surfaceValid=" + surfaceValid
                    + " textureAvailable=" + textureAvailable
                    + " view=" + tvW + "x" + tvH
                    + " pixels=" + lit + "/" + sampled;
            lastFrameCheck = (lit == 0 ? "FAILED: " : "OK: ") + state;
            lastFrameCheckAtMs = System.currentTimeMillis();
            if (lit != 0) {
                FileLog.d("SilentFloatingWindow: frame check OK: " + state);
                return;
            }
            lastStartError = "floating content black (no frames)";
            FileLog.e("SilentFloatingWindow: frame check FAILED, no frames: " + state);
            // v2.0.22: recovery is cooldown-based (10s), not once-per-start
            // — the user's repro kills frames on EVERY resize, so a stall
            // after any resize must be recoverable, not just the first.
            long now = SystemClock.uptimeMillis();
            if (now - lastFrameRecoveryAtMs < 10000) {
                SilentToast.show(ApplicationLoader.applicationContext,
                        "Floating: no picture (" + state + ")", android.widget.Toast.LENGTH_LONG);
                return;
            }
            lastFrameRecoveryAtMs = now;
            FileLog.e("SilentFloatingWindow: retrying virtual display");
            if (surfaceTexture == null || displayManager == null) {
                SilentToast.show(ApplicationLoader.applicationContext,
                        "Floating: no picture (" + state + ")", android.widget.Toast.LENGTH_LONG);
                return;
            }
            int newVdId = rebindDisplayToTexture(surfaceTexture);
            if (newVdId < 0) {
                SilentToast.show(ApplicationLoader.applicationContext,
                        "Floating: no picture (" + state + ")", android.widget.Toast.LENGTH_LONG);
                return;
            }
            relaunchFloatingActivityIfNeeded();
            scheduleFrameCheck(5000);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Tear the floating window down and bring the fullscreen app back.
     * Used when the virtual display or the activity launch fails after
     * the overlay is already up. Failure sites post this to the UI
     * thread so no teardown runs inside a draw pass. */
    private void recoverToMain() {
        if (!active) {
            return;
        }
        teardown();
        relaunchMain(0);
    }


    /** v2.0.22: intentionally a NO-OP. The virtual display is created
     * ONCE at the FULL phone size and is never resized: the TextureView
     * re-scales the picture automatically when its own size changes (its
     * SurfaceTexture buffer size tracks its view size, and the compositor
     * scales the full display down into it), so a resize is only a
     * window-size change. The old behaviour — virtualDisplay.resize() at
     * gesture end — stalled frames on the user's device (a small view
     * flashed, then vanished; a second resize showed nothing). Kept as a
     * method so the resize-gesture call sites do not change. */
    private void resizeDisplayToWindow() {
        // No-op by design (see above).
    }

    /** Back to the fullscreen app, reopening the same chat when known. */
    public void expand() {
        if (!active && floatingActivity == null) {
            return;
        }
        long dialogId = 0;
        SilentFloatingActivity fa = floatingActivity;
        if (fa != null && !fa.isFinishing()) {
            dialogId = fa.silentGetTopDialogId();
        }
        relaunchMain(dialogId);
        if (fa != null && !fa.isFinishing()) {
            try {
                fa.finish();
            } catch (Exception e) {
                FileLog.e(e);
                teardown();
            }
        } else {
            teardown();
        }
    }

    /** Dismiss the window. The user lands on the home screen. */
    public void dismiss() {
        if (!active && floatingActivity == null) {
            return;
        }
        SilentFloatingActivity fa = floatingActivity;
        if (fa != null && !fa.isFinishing()) {
            try {
                fa.finish();
            } catch (Exception e) {
                FileLog.e(e);
                teardown();
            }
        } else {
            teardown();
        }
    }

    /** v2.0.22: minimize to the sidebar pill. ONLY the overlay window
     * is hidden (windowManager.removeView) — the virtual display and
     * the floating activity keep running untouched, so restore shows
     * the exact same screen, size and position. The TextureView's
     * texture is released on detach by design; the display is rebound
     * to the new texture when the view re-attaches (see
     * createVirtualDisplayAndLaunch). */
    public void minimize() {
        if (!active || minimized || rootView == null || windowManager == null) {
            return;
        }
        try {
            windowManager.removeView(rootView);
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        minimized = true;
        showPill();
    }

    /** Restore the floating window from the sidebar pill, at its
     * previous size/position/content. */
    public void restoreFromMinimized() {
        if (!minimized || windowManager == null) {
            return;
        }
        try {
            if (pillView != null) {
                windowManager.removeView(pillView);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        pillView = null;
        pillParams = null;
        minimized = false;
        if (rootView == null) {
            teardown();
            relaunchMain(0);
            return;
        }
        try {
            windowManager.addView(rootView, windowParams);
            rootView.requestFocus();
        } catch (Exception e) {
            FileLog.e(e);
            teardown();
            relaunchMain(0);
            return;
        }
        // The TextureView re-attaches with a fresh texture; confirm
        // frames are flowing again after the rebind.
        schedulePostResizeFrameCheck();
    }

    /** Small sidebar pill at the right edge (same idea as the
     * quick-actions pill): dark, semi-transparent, does not cover the
     * screen. Tap restores the floating window. */
    private void showPill() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            int pillW = AndroidUtilities.dp(36);
            int pillH = AndroidUtilities.dp(72);
            FrameLayout pill = new FrameLayout(ctx);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(0xCC1B1B1B);
            // Round the LEFT corners only (right edge sits flush).
            bg.setCornerRadii(new float[]{
                    AndroidUtilities.dp(18), AndroidUtilities.dp(18),
                    0, 0, 0, 0,
                    AndroidUtilities.dp(18), AndroidUtilities.dp(18)});
            pill.setBackground(bg);
            ImageView icon = new ImageView(ctx);
            icon.setImageResource(R.drawable.photo_expand);
            icon.setColorFilter(0xFFFFFFFF);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setContentDescription("Restore floating window");
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            pill.addView(icon, iconLp);
            pill.setOnClickListener(v -> restoreFromMinimized());
            pillParams = new WindowManager.LayoutParams();
            pillParams.width = pillW;
            pillParams.height = pillH;
            pillParams.gravity = Gravity.TOP | Gravity.LEFT;
            pillParams.x = screenWidthPx - pillW;
            pillParams.y = Math.max(0, (screenHeightPx - pillH) / 2);
            pillParams.format = PixelFormat.TRANSLUCENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pillParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                pillParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            }
            pillParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            pillView = pill;
            windowManager.addView(pillView, pillParams);
        } catch (Exception e) {
            FileLog.e(e);
            pillView = null;
            pillParams = null;
        }
    }

    /** BACK key from the overlay window. True = consumed. */
    public boolean handleBack() {
        if (!active) {
            return false;
        }
        SilentFloatingActivity fa = floatingActivity;
        if (fa != null && !fa.isFinishing() && fa.silentGetFragmentCount() > 1) {
            try {
                fa.onBackPressed();
            } catch (Exception e) {
                FileLog.e(e);
            }
            return true;
        }
        dismiss();
        return true;
    }

    /** Called from SilentFloatingActivity.finish(). */
    public void onFloatingActivityFinished() {
        teardown();
    }

    /** Called from SilentFloatingActivity.onDestroy(). Backstop for kills. */
    public void onFloatingActivityDestroyed(SilentFloatingActivity activity) {
        if (floatingActivity == activity) {
            teardown();
        }
    }

    private void relaunchMain(long dialogId) {
        Context ctx = ApplicationLoader.applicationContext;
        Intent intent = new Intent(ctx, LaunchActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (dialogId != 0) {
            intent.setAction("com.tmessages.openchat");
            if (dialogId > 0) {
                intent.putExtra("userId", dialogId);
            } else {
                intent.putExtra("chatId", -dialogId);
            }
        }
        try {
            ctx.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void teardown() {
        if (launchWatchdog != null) {
            AndroidUtilities.cancelRunOnUIThread(launchWatchdog);
            launchWatchdog = null;
        }
        if (frameCheck != null) {
            AndroidUtilities.cancelRunOnUIThread(frameCheck);
            frameCheck = null;
        }
        active = false;
        minimized = false;
        lastFrameRecoveryAtMs = 0;
        try {
            if (pillView != null && windowManager != null) {
                windowManager.removeView(pillView);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        pillView = null;
        pillParams = null;
        try {
            if (rootView != null && windowManager != null) {
                windowManager.removeView(rootView);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        rootView = null;
        textureView = null;
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        virtualDisplay = null;
        try {
            if (surface != null) {
                surface.release();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        surface = null;
        // The SurfaceTexture is NOT released here: since v2.0.21 the
        // TextureView owns it, and it releases the texture itself when
        // the view is detached (onSurfaceTextureDestroyed returns false).
        // Releasing it here would pull the texture out from under the
        // TextureView's hardware layer.
        surfaceTexture = null;
        if (LaunchActivity.instance == floatingActivity) {
            LaunchActivity.instance = null;
        }
        floatingActivity = null;
    }

    // ---------- overlay window ----------

    private void buildOverlay(Context ctx) {
        windowParams = new WindowManager.LayoutParams();
        windowParams.width = windowWidthPx;
        windowParams.height = windowHeightPx;
        windowParams.gravity = Gravity.TOP | Gravity.LEFT;
        windowParams.format = PixelFormat.TRANSLUCENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            windowParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            windowParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        }
        // Focusable (BACK key) but not touch-modal: taps outside the window
        // still reach the home screen underneath.
        windowParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        rootView = new FrameLayout(ctx) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    // Consume the whole gesture; act on key-up.
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        return handleBack();
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }

            @Override
            protected void onConfigurationChanged(Configuration newConfig) {
                super.onConfigurationChanged(newConfig);
                reclampToScreen();
            }
        };
        rootView.setFocusable(true);
        rootView.setFocusableInTouchMode(true);
        rootView.setClipToOutline(true);
        rootView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dp(14));
            }
        });

        // Content: the virtual display's surface, scaled to the window.
        FrameLayout contentContainer = new FrameLayout(ctx);
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        contentLp.topMargin = barHeightPx;
        rootView.addView(contentContainer, contentLp);

        textureView = new TextureView(ctx);
        // The TextureView OWNS its SurfaceTexture (v2.0.21): the virtual
        // display is created from the texture delivered here, never from
        // a pre-created SurfaceTexture handed in with setSurfaceTexture()
        // — on the user's device that left the hardware layer unbound and
        // no frame ever reached the window (fully transparent).
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                if (width > 0 && height > 0) {
                    createVirtualDisplayAndLaunch(ctx, texture, width, height);
                } else {
                    // Texture ready before layout: wait one layout pass,
                    // then use the view's real size.
                    textureView.post(() -> {
                        if (active && virtualDisplay == null && textureView.getWidth() > 0 && textureView.getHeight() > 0) {
                            createVirtualDisplayAndLaunch(ctx, textureView.getSurfaceTexture(), textureView.getWidth(), textureView.getHeight());
                        }
                    });
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                // v2.0.22: the TextureView re-sizes its own buffer here;
                // the virtual display is FULL-size and never resized, so
                // the compositor re-scales the picture into the new
                // buffer size automatically. Prove frames survived the
                // resize: check again ~2s after the last size change.
                schedulePostResizeFrameCheck();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                FileLog.e("SilentFloatingWindow: TextureView surface destroyed while active=" + active + " minimized=" + minimized);
                if (active && !minimized) {
                    // The display's surface is dead: fall back to the
                    // fullscreen app instead of a transparent window.
                    // Minimized is exempt: removing the overlay detaches
                    // the TextureView and destroys its texture by design
                    // — the display is rebound to the new texture on
                    // restore (see createVirtualDisplayAndLaunch).
                    AndroidUtilities.runOnUIThread(() -> recoverToMain());
                }
                // False: the TextureView owns this texture and releases
                // it itself. We must never release it in teardown().
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            }
        });
        textureView.setOnTouchListener(new ContentTouchListener());
        contentContainer.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top bar: drag handle + expand + close. It sits ABOVE the app area
        // (not over it), so no part of the Telegram UI is ever covered.
        FrameLayout topBar = new FrameLayout(ctx);
        topBar.setBackgroundColor(0xE61B1B1B);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeightPx);
        barLp.gravity = Gravity.TOP;
        rootView.addView(topBar, barLp);

        View grip = new View(ctx);
        GradientDrawable gripBg = new GradientDrawable();
        gripBg.setShape(GradientDrawable.RECTANGLE);
        gripBg.setColor(0x66FFFFFF);
        gripBg.setCornerRadius(AndroidUtilities.dp(2));
        grip.setBackground(gripBg);
        FrameLayout.LayoutParams gripLp = new FrameLayout.LayoutParams(
                AndroidUtilities.dp(36), AndroidUtilities.dp(4));
        gripLp.gravity = Gravity.CENTER;
        topBar.addView(grip, gripLp);

        ImageView closeButton = new ImageView(ctx);
        closeButton.setImageResource(R.drawable.ic_ab_close);
        closeButton.setColorFilter(0xFFFFFFFF);
        closeButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        closeButton.setContentDescription("Close");
        closeButton.setOnClickListener(v -> dismiss());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                barHeightPx, barHeightPx);
        closeLp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        closeLp.leftMargin = AndroidUtilities.dp(4);
        topBar.addView(closeButton, closeLp);

        ImageView expandButton = new ImageView(ctx);
        expandButton.setImageResource(R.drawable.photo_expand);
        expandButton.setColorFilter(0xFFFFFFFF);
        expandButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        expandButton.setContentDescription("Expand");
        expandButton.setOnClickListener(v -> expand());
        FrameLayout.LayoutParams expandLp = new FrameLayout.LayoutParams(
                barHeightPx, barHeightPx);
        expandLp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        expandLp.rightMargin = AndroidUtilities.dp(4);
        topBar.addView(expandButton, expandLp);

        // Minimize "−" (v2.0.22): PC-style, left of Expand. A TextView
        // glyph — the profile_level_minus drawables are triangles, not
        // a minus sign, so no drawable fits the top bar.
        android.widget.TextView minimizeButton = new android.widget.TextView(ctx);
        minimizeButton.setText("−");
        minimizeButton.setTextColor(0xFFFFFFFF);
        minimizeButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
        minimizeButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        minimizeButton.setGravity(Gravity.CENTER);
        minimizeButton.setContentDescription("Minimize");
        minimizeButton.setOnClickListener(v -> minimize());
        FrameLayout.LayoutParams minimizeLp = new FrameLayout.LayoutParams(
                barHeightPx, barHeightPx);
        minimizeLp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        minimizeLp.rightMargin = barHeightPx + AndroidUtilities.dp(4);
        topBar.addView(minimizeButton, minimizeLp);

        topBar.setOnTouchListener(new BarTouchListener());

        // Bottom-right resize grip (diagonal lines).
        ResizeGripView resizeGrip = new ResizeGripView(ctx);
        FrameLayout.LayoutParams resizeLp = new FrameLayout.LayoutParams(
                AndroidUtilities.dp(28), AndroidUtilities.dp(28));
        resizeLp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        rootView.addView(resizeGrip, resizeLp);
        resizeGrip.setOnTouchListener(new ResizeTouchListener());
    }

    // ---------- touch forwarding ----------

    /** Forward a touch from the TextureView into the floating activity.
     * Topmost window first (v2.0.22): a showing ActionBarPopupWindow or
     * dialog is a SEPARATE WindowManager window with its own decor —
     * the old code dispatched only to the activity decor, so taps on a
     * popup fell through to the background content behind it and the
     * popup could not scroll. The scaled event is in virtual-display
     * coordinates, as is a top window's getLocationOnScreen, so the
     * event is offset into that window before dispatch. */
    private boolean forwardTouch(MotionEvent event) {
        SilentFloatingActivity fa = floatingActivity;
        if (fa == null || fa.isFinishing() || textureView == null) {
            return false;
        }
        View decor;
        try {
            decor = fa.getWindow() != null ? fa.getWindow().getDecorView() : null;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
        if (decor == null || textureView.getWidth() <= 0 || textureView.getHeight() <= 0) {
            return false;
        }
        // The TextureView shows the virtual display scaled to the
        // window's content size; the transform maps window coordinates
        // back to display coordinates (recomputed from the current
        // display size so it stays right after a resize).
        float scaleX = displayWidthPx / (float) textureView.getWidth();
        float scaleY = displayHeightPx / (float) textureView.getHeight();
        MotionEvent copy = MotionEvent.obtain(event);
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY);
        copy.transform(matrix);
        try {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                touchTarget = null;
            }
            View target = touchTarget;
            if (target != null && !target.isAttachedToWindow()) {
                // Target window went away mid-gesture: re-resolve.
                target = null;
                touchTarget = null;
            }
            if (target == null) {
                target = findTopTouchTarget(copy.getX(), copy.getY(), decor);
                if (target == null) {
                    // Outside a showing popup/dialog: the tap is consumed
                    // there (popup dismissed when outside-touchable) and
                    // must never reach the content behind it.
                    return true;
                }
                if (action == MotionEvent.ACTION_DOWN) {
                    touchTarget = target;
                }
            }
            if (target != decor) {
                // Offset into the top window's own coordinates.
                int[] loc = new int[2];
                target.getLocationOnScreen(loc);
                copy.offsetLocation(-loc[0], -loc[1]);
            }
            try {
                target.dispatchTouchEvent(copy);
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchTarget = null;
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            copy.recycle();
        }
        return true;
    }

    /** Topmost window containing (x, y) in virtual-display coordinates:
     * most-recent showing ActionBarPopupWindow, else the showing dialog,
     * else the activity decor. Returns null when the point is outside a
     * showing popup — the caller consumes the event there (popup
     * dismissed when outside-touchable) and never forwards it to the
     * content behind. */
    private View findTopTouchTarget(float x, float y, View decor) {
        try {
            ActionBarPopupWindow popup = ActionBarPopupWindow.getTopShowingPopup();
            if (popup != null) {
                View root = null;
                try {
                    if (popup.getContentView() != null) {
                        root = popup.getContentView().getRootView();
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                if (root != null && root.isAttachedToWindow() && hitTest(root, x, y)) {
                    return root;
                }
                // Outside a showing popup: emulate PopupWindow's
                // outside-touch behaviour — dismiss it when it is
                // outside-touchable, and never let the tap through to
                // the content behind it.
                if (popup.isOutsideTouchable()) {
                    try {
                        popup.dismiss();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
                return null;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            Dialog dialog = getTopShowingDialog();
            if (dialog != null && dialog.getWindow() != null) {
                View dialogDecor = dialog.getWindow().getDecorView();
                if (dialogDecor != null && dialogDecor.isAttachedToWindow()) {
                    // A dialog decor covers the whole display (dimmed
                    // background included), so every point hits it: the
                    // dialog's own touch handling dismisses on an
                    // outside tap when cancelable.
                    return dialogDecor;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return decor;
    }

    private boolean hitTest(View view, float x, float y) {
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        return x >= loc[0] && x < loc[0] + view.getWidth()
                && y >= loc[1] && y < loc[1] + view.getHeight();
    }

    /** Showing dialog on top, if any: the top fragment's visibleDialog
     * first, else the last showing dialog in the activity's registry. */
    private Dialog getTopShowingDialog() {
        try {
            BaseFragment top = LaunchActivity.getLastFragment();
            if (top != null) {
                Dialog d = top.getVisibleDialog();
                if (d != null && d.isShowing()) {
                    return d;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            if (floatingActivity != null && floatingActivity.visibleDialogs != null) {
                for (int i = floatingActivity.visibleDialogs.size() - 1; i >= 0; i--) {
                    Dialog d = floatingActivity.visibleDialogs.get(i);
                    if (d != null && d.isShowing()) {
                        return d;
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    /** Content: one finger drives the app inside; two fingers pinch-resize
     * the window anywhere (v2.0.18 — pinch on the content, not only on the
     * top bar). While pinching, touches never reach the app.
     */
    private class ContentTouchListener implements View.OnTouchListener {
        private final ScaleGestureDetector pinchDetector = new ScaleGestureDetector(
                ApplicationLoader.applicationContext, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        contentPinching = true;
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        applyWindowWidth((int) (windowWidthPx * detector.getScaleFactor()));
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        contentPinching = false;
                        persistGeometry();
                        resizeDisplayToWindow();
                        schedulePostResizeFrameCheck();
                    }
                });

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            pinchDetector.onTouchEvent(event);
            if (contentPinching || event.getPointerCount() >= 2) {
                // Second finger down: cancel the touch the app may already
                // have received, so a half-started scroll does not stick.
                if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                    forwardTouchCancel();
                }
                return true;
            }
            return forwardTouch(event);
        }
    }

    /** Send ACTION_CANCEL into the floating activity (ends a half-started gesture).
     * Goes to the same top window the gesture went to (popup/dialog),
     * not blindly to the activity decor. */
    private void forwardTouchCancel() {
        SilentFloatingActivity fa = floatingActivity;
        if (fa == null || fa.isFinishing()) {
            return;
        }
        View decor;
        try {
            decor = fa.getWindow() != null ? fa.getWindow().getDecorView() : null;
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        if (decor == null) {
            return;
        }
        View target = touchTarget;
        if (target == null || !target.isAttachedToWindow()) {
            target = decor;
        }
        touchTarget = null;
        MotionEvent cancel = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0, 0, 0);
        try {
            target.dispatchTouchEvent(cancel);
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            cancel.recycle();
        }
    }

    // ---------- drag / resize ----------

    private void updateLayout() {
        // v2.0.22: while minimized the overlay is detached — updating
        // its layout throws and the catch would tear the window down.
        if (minimized || rootView == null || windowManager == null) {
            return;
        }
        try {
            windowManager.updateViewLayout(rootView, windowParams);
        } catch (Exception e) {
            // Overlay permission revoked (or the window died): tear down and
            // try to bring the app back fullscreen.
            FileLog.e(e);
            teardown();
            relaunchMain(0);
        }
    }

    private int contentHeightForWidth(int widthPx) {
        return (int) (widthPx * (fullHeight / (float) fullWidth)) + barHeightPx;
    }

    private int clampWidth(int widthPx) {
        // v2.0.21: 120dp minimum, so the window can shrink to a small
        // corner bubble (was 240dp — half the screen on most phones).
        int min = AndroidUtilities.dp(120);
        int maxByHeight = (int) ((screenHeightPx - barHeightPx - AndroidUtilities.dp(48)) * (fullWidth / (float) fullHeight));
        int max = Math.min(fullWidth, maxByHeight);
        if (max < min) {
            max = min;
        }
        return Math.max(min, Math.min(max, widthPx));
    }

    private int clampX(int x) {
        int margin = AndroidUtilities.dp(80);
        return Math.max(-windowWidthPx + margin, Math.min(screenWidthPx - margin, x));
    }

    private int clampY(int y) {
        int margin = AndroidUtilities.dp(80);
        return Math.max(0, Math.min(screenHeightPx - margin, y));
    }

    private void applyWindowWidth(int newWidthPx) {
        windowWidthPx = clampWidth(newWidthPx);
        windowHeightPx = contentHeightForWidth(windowWidthPx);
        if (windowParams.x + windowWidthPx > screenWidthPx) {
            windowParams.x = Math.max(0, screenWidthPx - windowWidthPx);
        }
        if (windowParams.y + windowHeightPx > screenHeightPx) {
            windowParams.y = Math.max(0, screenHeightPx - windowHeightPx);
        }
        windowParams.width = windowWidthPx;
        windowParams.height = windowHeightPx;
        updateLayout();
    }

    private void persistGeometry() {
        if (density <= 0) {
            return;
        }
        try {
            SharedSettings.silentFloatingWidthDp.set((int) (windowWidthPx / density));
            SharedSettings.silentFloatingXDp.set((int) (windowParams.x / density));
            SharedSettings.silentFloatingYDp.set((int) (windowParams.y / density));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Rotation / screen change while floating: keep the window on screen. */
    private void reclampToScreen() {
        if (!active || rootView == null) {
            return;
        }
        try {
            DisplayMetrics dm = ApplicationLoader.applicationContext.getResources().getDisplayMetrics();
            screenWidthPx = dm.widthPixels;
            screenHeightPx = dm.heightPixels;
            windowWidthPx = clampWidth(windowWidthPx);
            windowHeightPx = contentHeightForWidth(windowWidthPx);
            windowParams.width = windowWidthPx;
            windowParams.height = windowHeightPx;
            windowParams.x = clampX(windowParams.x);
            windowParams.y = clampY(windowParams.y);
            updateLayout();
            resizeDisplayToWindow();
            schedulePostResizeFrameCheck();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Top bar: one finger drags the window, two fingers pinch-resize it. */
    private class BarTouchListener implements View.OnTouchListener {
        private int dragPointerId = -1;
        private float dragStartRawX;
        private float dragStartRawY;
        private int dragStartWinX;
        private int dragStartWinY;
        private boolean pinching;
        private final ScaleGestureDetector pinchDetector = new ScaleGestureDetector(
                ApplicationLoader.applicationContext, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        pinching = true;
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        applyWindowWidth((int) (windowWidthPx * detector.getScaleFactor()));
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        pinching = false;
                        persistGeometry();
                        resizeDisplayToWindow();
                        schedulePostResizeFrameCheck();
                    }
                });

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            pinchDetector.onTouchEvent(event);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                dragPointerId = event.getPointerId(0);
                dragStartRawX = event.getRawX();
                dragStartRawY = event.getRawY();
                dragStartWinX = windowParams.x;
                dragStartWinY = windowParams.y;
                return true;
            }
            if (pinching || event.getPointerCount() >= 2) {
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE && dragPointerId >= 0) {
                int index = event.findPointerIndex(dragPointerId);
                if (index < 0) {
                    return true;
                }
                windowParams.x = clampX((int) (dragStartWinX + (event.getRawX() - dragStartRawX)));
                windowParams.y = clampY((int) (dragStartWinY + (event.getRawY() - dragStartRawY)));
                updateLayout();
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_UP) {
                // The drag finger lifted while pinching: re-anchor the drag
                // on the remaining finger so the window does not jump.
                // (getRawX(int) is API 29+; use view-relative coords + the
                // view's screen position instead, so API 24 stays safe.)
                int remaining = event.getPointerCount() > 1 ? (event.getPointerId(0) == dragPointerId ? 1 : 0) : -1;
                if (remaining >= 0) {
                    int[] loc = new int[2];
                    v.getLocationOnScreen(loc);
                    dragPointerId = event.getPointerId(remaining);
                    dragStartRawX = loc[0] + event.getX(remaining);
                    dragStartRawY = loc[1] + event.getY(remaining);
                    dragStartWinX = windowParams.x;
                    dragStartWinY = windowParams.y;
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragPointerId = -1;
                persistGeometry();
                return true;
            }
            return true;
        }
    }

    /** Bottom-right corner: drag to resize (aspect locked to the display). */
    private class ResizeTouchListener implements View.OnTouchListener {
        private float resizeStartRawX;
        private float resizeStartRawY;
        private int resizeStartWidth;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                resizeStartRawX = event.getRawX();
                resizeStartRawY = event.getRawY();
                resizeStartWidth = windowWidthPx;
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                int dw = (int) (event.getRawX() - resizeStartRawX);
                applyWindowWidth(resizeStartWidth + dw);
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                persistGeometry();
                resizeDisplayToWindow();
                schedulePostResizeFrameCheck();
                return true;
            }
            return true;
        }
    }

    /** Classic diagonal-lines resize grip. */
    private static class ResizeGripView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ResizeGripView(Context context) {
            super(context);
            paint.setColor(0xBFFFFFFF);
            paint.setStrokeWidth(AndroidUtilities.dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float step = AndroidUtilities.dp(7);
            for (int i = 1; i <= 3; i++) {
                float o = step * i;
                canvas.drawLine(w - o, h - AndroidUtilities.dp(2), w - AndroidUtilities.dp(2), h - o, paint);
            }
        }
    }

}
