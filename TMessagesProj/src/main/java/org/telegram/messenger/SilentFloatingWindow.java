package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.PopupWindow;

import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.utils.pip.PipCoordinator;
import org.telegram.utils.settings.SharedSettings;

/**
 * SilentGram: the floating window is Telegram's OWN pop-up view — the
 * Android Bubbles API with Telegram's own BubbleActivity (v2.0.36).
 *
 * The virtual-display floating window (v2.0.18–v2.0.35) is REMOVED: it is
 * proven impossible on Android 10+ for a regular Play app (AOSP
 * ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay gate 4b — a
 * first launch onto an own virtual display is denied; Android 9 exempted
 * owners, Android 10 commit 1cba31c3 removed that). The virtual display,
 * the overlay window, the TextureView, touch forwarding and the proxy IME
 * are all gone with it.
 *
 * How the pop-up works now (all Telegram's own machinery):
 * - Home press (LaunchActivity.onUserLeaveHint, only for a real Home press)
 *   calls start() here. start() posts a bubble notification for the
 *   CURRENT chat via NotificationsController.showBubbleForDialog(), which
 *   reuses Telegram's own createNotificationShortcut() (dynamic shortcut +
 *   NotificationCompat.BubbleMetadata) pointing at Telegram's own
 *   BubbleActivity (the chat in bubble mode).
 * - The notification is posted while the app is in the foreground, with
 *   auto-expand ON, so the system opens the bubble immediately (Android
 *   only auto-expands foreground posts). If the system processes it after
 *   the app is backgrounded, the bubble still appears as its floating
 *   icon — one tap opens the chat in the pop-up window.
 * - The bubble is the system's own floating window: draggable, resizable
 *   by the system chrome, with the real chat inside (typing works — it is
 *   a real activity on the default display, not a virtual display).
 * - Needs Android 11+ (SharedConfig.chatBubbles defaults to SDK >= 30)
 *   and bubbles enabled for the app. Below that, or when bubbles are
 *   disabled, start() returns false and the app just closes to the home
 *   screen — never forced open again (the user's fallback rule).
 * - The setting (Settings, below Push Notifications) is the same ON/OFF
 *   toggle as before, renamed to "Pop-up view". OFF dismisses a live bubble.
 * - A content PiP (group call / live stream) owns the screen: start()
 *   refuses while one is active, as before.
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
    private long bubbleDialogId;
    private int bubbleAccount = -1;
    private long bubblePostedAtMs;
    private String lastStartError;

    /**
     * Post a bubble for the current chat. Returns false when the pop-up
     * could not be started — the caller then just backgrounds the app
     * normally (no system PiP fallback, no reopen). lastStartError is null
     * on a silent skip (unsupported device / no chat open / toggle off),
     * so no toast is shown; it is set only when a supported device failed
     * to post the bubble.
     */
    public boolean start(Activity activity) {
        lastStartError = null;
        if (active && bubbleDialogId != 0) {
            return true;
        }
        if (activity == null || activity.isFinishing()) {
            return false;
        }
        if (!SharedSettings.silentPipEnabled.get()) {
            return false;
        }
        PipCoordinator coordinator = PipCoordinator.getInstance();
        if (coordinator.hasSystemPipSession() || coordinator.findFloating() != null) {
            lastStartError = "another PiP window is active";
            return false;
        }
        long dialogId = 0;
        try {
            ChatActivity top = LaunchActivity.getTopChatActivity();
            if (top != null) {
                dialogId = top.getDialogId();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (dialogId == 0) {
            // Chat list (or nothing) open — there is no chat to pop up.
            return false;
        }
        int account = UserConfig.selectedAccount;
        NotificationsController nc = NotificationsController.getInstance(account);
        if (!nc.isBubbleSupported()) {
            // Silent skip: this device cannot show a bubble at all.
            return false;
        }
        if (!nc.showBubbleForDialog(dialogId, true)) {
            lastStartError = nc.getLastBubbleError();
            return false;
        }
        active = true;
        bubbleDialogId = dialogId;
        bubbleAccount = account;
        bubblePostedAtMs = SystemClock.uptimeMillis();
        return true;
    }

    /** True while a bubble posted by start() is open (or still opening). */
    public boolean isActive() {
        if (!active || bubbleDialogId == 0 || bubbleAccount < 0) {
            return false;
        }
        // Grace: the bubble activity reports in shortly after posting.
        if (SystemClock.uptimeMillis() - bubblePostedAtMs < 10000) {
            return true;
        }
        try {
            return NotificationsController.getInstance(bubbleAccount).isDialogOpenedInBubble(bubbleDialogId);
        } catch (Exception e) {
            return false;
        }
    }

    /** dialogId currently in a bubble, or 0 (debug report). */
    public long getBubbleDialogId() {
        return isActive() ? bubbleDialogId : 0;
    }

    /** Why the last start() failed, or null (silent skip / no error). */
    public String getLastStartError() {
        return lastStartError;
    }

    /** Dismiss a live bubble (Settings toggle OFF). No relaunch. */
    public void dismiss() {
        try {
            if (bubbleAccount >= 0) {
                NotificationsController.getInstance(bubbleAccount).dismissBubble();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        active = false;
        bubbleDialogId = 0;
        bubbleAccount = -1;
    }

    /** Alias for dismiss() — stop the pop-up. */
    public void stop() {
        dismiss();
    }

    /** Open the bubbled chat fullscreen in the main activity. */
    public void expand() {
        long dialogId = bubbleDialogId;
        dismiss();
        relaunchMain(dialogId);
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

    // --- Vestigial (virtual-display era) — kept as no-ops so existing call
    // sites compile unchanged. The bubble needs no touch forwarding (popups
    // are normal windows in the bubble activity) and no pause-dismissal
    // suppression (there is no proxy IME or overlay).
    /** Register a raw PopupWindow shown inside floating (no-op since v2.0.36). */
    public static void trackRawPopup(PopupWindow popup) {
    }

    /** Forget a tracked raw popup (no-op since v2.0.36). */
    public static void untrackRawPopup(PopupWindow popup) {
    }

    /** Whether popup dismissals are suppressed during a floating pause (always false since v2.0.36). */
    public boolean isPauseDismissalsSuppressed() {
        return false;
    }
}
