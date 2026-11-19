package org.telegram.ui;

import android.os.Bundle;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.SilentFloatingWindow;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * SilentGram: floating-window host. A second LaunchActivity instance
 * (standard launch mode — LaunchActivity itself is singleTask, so it can
 * never be the second instance) that runs on the floating window's virtual
 * display, created by {@link SilentFloatingWindow}. It behaves exactly like
 * the app: same fragment stack, same dialogs, same connection.
 *
 * While it is alive it IS the UI: LaunchActivity.instance points at it and
 * the main LaunchActivity is finished (Telegram's own
 * closeOtherAppActivities rule). Expanding or closing finishes it and the
 * manager tears the overlay + virtual display down.
 *
 * It never enters system PiP itself: onUserLeaveHint is a no-op, because
 * Home leaves the floating window in place.
 */
public class SilentFloatingActivity extends LaunchActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SilentFloatingWindow.getInstance().setFloatingActivity(this);
    }

    @Override
    protected void onUserLeaveHint() {
        // No-op: this activity IS the floating window. Home leaves the
        // window in place; never enter system PiP or a second floating
        // window from here.
    }

    @Override
    public void finish() {
        SilentFloatingWindow.getInstance().onFloatingActivityFinished();
        super.finish();
    }

    @Override
    protected void onDestroy() {
        if (LaunchActivity.instance == this) {
            LaunchActivity.instance = null;
        }
        SilentFloatingWindow.getInstance().onFloatingActivityDestroyed(this);
        super.onDestroy();
    }

    /** Fragment stack depth, for BACK handling by the floating manager. */
    public int silentGetFragmentCount() {
        try {
            return actionBarLayout == null ? 0 : actionBarLayout.getFragmentStack().size();
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }

    /** dialogId of the top chat, or 0 for the chat list — used on expand. */
    public long silentGetTopDialogId() {
        try {
            BaseFragment fragment = LaunchActivity.getLastFragment();
            if (fragment instanceof ChatActivity) {
                return ((ChatActivity) fragment).getDialogId();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }
}
