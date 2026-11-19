package org.telegram.messenger;

import android.app.Activity;
import android.app.ActivityOptions;
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
 * - VirtualDisplay: DisplayManager.createVirtualDisplay needs NO permission
 *   and NO MediaProjection token for a blank/private display (the token is
 *   only for capturing the main screen). Flag PUBLIC so the soft keyboard
 *   (another app's window) can render on the display for typing.
 * - Host: SilentFloatingActivity (a LaunchActivity subclass — LaunchActivity
 *   itself is singleTask, so it can never be the second instance). It is
 *   launched with ActivityOptions.setLaunchDisplayId(displayId).
 * - The activity lays out at full-screen size on the virtual display; the
 *   TextureView scales it down to the window, so the whole app is visible
 *   in miniature, proportional — the layout fits the window by construction.
 * - The main LaunchActivity finishes when the floating one starts
 *   (Telegram's own closeOtherAppActivities rule); the connection is
 *   process-level and stays alive.
 *
 * Honest limits (device test decides the details):
 * - The soft keyboard should render inside the window (it is drawn on the
 *   virtual display); if an OEM blocks IME on virtual displays, typing
 *   falls back to a hardware keyboard / voice input.
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

    private long openDialogId;
    private boolean openDialogIsUser;
    private Runnable launchWatchdog;

    private SilentFloatingWindow() {
    }

    public boolean isActive() {
        return active;
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
        if (active) {
            return true;
        }
        if (activity == null || activity.isFinishing()) {
            return false;
        }
        if (!PipEnvironment.canDrawOverlays(activity)) {
            return false;
        }
        PipCoordinator coordinator = PipCoordinator.getInstance();
        if (coordinator.hasSystemPipSession() || coordinator.findFloating() != null) {
            return false;
        }
        final Context ctx = ApplicationLoader.applicationContext;
        try {
            windowManager = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            displayManager = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (windowManager == null || displayManager == null) {
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
                return false;
            }
            barHeightPx = AndroidUtilities.dp(36);

            // Remember which screen to reopen (chat list, or the open chat).
            openDialogId = 0;
            openDialogIsUser = false;
            try {
                BaseFragment top = LaunchActivity.getLastFragment();
                if (top instanceof ChatActivity) {
                    long did = ((ChatActivity) top).getDialogId();
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

            // Surface + virtual display FIRST (synchronous): the TextureView
            // is handed this texture, so no async surface race with Home.
            surfaceTexture = new SurfaceTexture(0);
            surfaceTexture.setDefaultBufferSize(fullWidth, fullHeight);
            surface = new Surface(surfaceTexture);
            virtualDisplay = displayManager.createVirtualDisplay(
                    "SilentGram Floating", fullWidth, fullHeight, densityDpi, surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC);
            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                teardown();
                return false;
            }

            buildOverlay(ctx);
            windowParams.x = clampX(startX);
            windowParams.y = clampY(startY);
            windowManager.addView(rootView, windowParams);
            rootView.requestFocus();
            active = true;

            launchFloatingActivity(ctx);

            // Watchdog: if the activity never appears (e.g. the launch was
            // blocked), do not leave a dark empty window — go back to the
            // fullscreen app instead.
            launchWatchdog = () -> {
                launchWatchdog = null;
                if (floatingActivity == null && active) {
                    FileLog.e("SilentFloatingWindow: floating activity never started, recovering");
                    teardown();
                    relaunchMain(0);
                }
            };
            AndroidUtilities.runOnUIThread(launchWatchdog, 5000);
            return true;
        } catch (Exception e) {
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
        active = false;
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
        try {
            if (surfaceTexture != null) {
                surfaceTexture.release();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
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
        textureView.setSurfaceTexture(surfaceTexture);
        textureView.setOnTouchListener((v, event) -> forwardTouch(event));
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

    /** Forward a touch from the TextureView into the floating activity. */
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
        // The TextureView shows the whole virtual display scaled down; the
        // transform maps window coordinates back to full-display coordinates.
        float scaleX = fullWidth / (float) textureView.getWidth();
        float scaleY = fullHeight / (float) textureView.getHeight();
        MotionEvent copy = MotionEvent.obtain(event);
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY);
        copy.transform(matrix);
        try {
            decor.dispatchTouchEvent(copy);
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            copy.recycle();
        }
        return true;
    }

    // ---------- drag / resize ----------

    private void updateLayout() {
        if (rootView == null || windowManager == null) {
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
        int min = AndroidUtilities.dp(240);
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
