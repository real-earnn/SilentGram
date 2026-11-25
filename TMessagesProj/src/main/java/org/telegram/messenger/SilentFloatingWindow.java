package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.PopupWindow;

import org.telegram.ui.LaunchActivity;
import org.telegram.utils.pip.PipCoordinator;

/**
 * SilentGram: the pop-up view is Telegram's OWN bubble — the Android
 * Bubbles API with Telegram's own BubbleActivity (v2.0.36).
 *
 * The virtual-display floating window (v2.0.18–v2.0.35) is REMOVED: it is
 * proven impossible on Android 10+ for a regular Play app (AOSP
 * ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay gate 4b — a
 * first launch onto an own virtual display is denied; Android 9 exempted
 * owners, Android 10 commit 1cba31c3 removed that). The virtual display,
 * the overlay window, the TextureView, touch forwarding and the proxy IME
 * are all gone with it.
 *
 * How the pop-up works now (v2.0.37, all Telegram's own machinery):
 * - The user taps "Open in Bubble Chat" in the quick-action panel
 *   (SilentQuickActions — below Auto-Delete Timer for personal/bot
 *   chats, at the end for channels/groups). openBubble() here posts a
 *   bubble notification for THAT dialog via
 *   NotificationsController.showBubbleForDialog(), which reuses
 *   Telegram's own createNotificationShortcut() (dynamic shortcut +
 *   NotificationCompat.BubbleMetadata) pointing at Telegram's own
 *   BubbleActivity (the chat in bubble mode). The notification is posted
 *   on the dialog's own notification channel, so future messages in that
 *   chat become bubbles through Telegram's own message-notification path.
 * - autoExpand is OFF: the bubble icon appears on the side of the
 *   screen; tapping it opens the chat in the pop-up, tapping again
 *   collapses it. That is the system's own bubble behaviour — nothing
 *   of ours sits in between.
 * - The caller (SilentQuickActions) finishes the activity after a
 *   successful post, so SilentGram closes and only the bubble icon
 *   remains.
 * - There is NO Settings row and NO Home-press trigger (both removed in
 *   v2.0.37): Home just closes the app normally.
 * - Needs Android 11+ (SharedConfig.chatBubbles defaults to SDK >= 30)
 *   and bubbles enabled for the app. Below that, or when bubbles are
 *   disabled, openBubble() returns false and the app stays open — the
 *   caller shows the error.
 * - A content PiP (group call / live stream) owns the screen: openBubble()
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
     * Post a bubble for the given dialog (menu-triggered, v2.0.37).
     * Returns false when the bubble could not be posted — the caller then
     * shows the error and stays in the chat. lastStartError is null on a
     * silent skip (unsupported device / no activity / no dialog), so no
     * toast is shown; it is set only when a supported device failed to
     * post the bubble. If another chat is already bubbled, its bubble is
     * dismissed first (one bubble notification exists at a time).
     */
    public boolean openBubble(Activity activity, long dialogId) {
        lastStartError = null;
        if (activity == null || activity.isFinishing() || dialogId == 0) {
            return false;
        }
        if (active && bubbleDialogId == dialogId) {
            // Already bubbled — nothing more to post.
            return true;
        }
        PipCoordinator coordinator = PipCoordinator.getInstance();
        if (coordinator.hasSystemPipSession() || coordinator.findFloating() != null) {
            lastStartError = "another PiP window is active";
            return false;
        }
        int account = UserConfig.selectedAccount;
        NotificationsController nc = NotificationsController.getInstance(account);
        if (!nc.isBubbleSupported()) {
            // Silent skip: this device cannot show a bubble at all.
            return false;
        }
        if (active && bubbleDialogId != 0) {
            // A different chat is bubbled — close it first; one bubble
            // notification exists at a time (same notification id).
            dismiss();
        }
        if (!nc.showBubbleForDialog(dialogId, false)) {
            lastStartError = nc.getLastBubbleError();
            return false;
        }
        active = true;
        bubbleDialogId = dialogId;
        bubbleAccount = account;
        bubblePostedAtMs = SystemClock.uptimeMillis();
        return true;
    }

    /** True while a bubble posted by openBubble() is open (or still opening). */
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

    /** Why the last openBubble() failed, or null (silent skip / no error). */
    public String getLastStartError() {
        return lastStartError;
    }

    /** Dismiss a live bubble. No relaunch. */
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
