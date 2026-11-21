package org.telegram.ui;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.SilentFloatingWindow;

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
    protected void onPause() {
        // v2.0.22: Home pauses this activity — suppress fragment
        // pause-dismissals only for the duration of super.onPause()
        // (LaunchActivity.onPause runs the fragment onPause chain
        // synchronously), so a popup/dialog on top survives Home.
        // Fragment-cover pauses run outside this window and still
        // dismiss normally.
        SilentFloatingWindow.getInstance().setPauseDismissalsSuppressed(true);
        try {
            super.onPause();
        } finally {
            SilentFloatingWindow.getInstance().setPauseDismissalsSuppressed(false);
        }
    }

    /** v2.0.22: pin launches that belong inside the floating window to
     * the virtual display. The picture-upload picker is a SYSTEM picker
     * launched with no display pinning at all, so the system sometimes
     * puts it on the default display (background main app) and
     * sometimes on the virtual display — it must ALWAYS open inside
     * floating. The 2-arg startActivityForResult delegates to this
     * 3-arg form, so this one override covers both. */
    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        if (shouldPinToFloatingDisplay(intent)) {
            try {
                super.startActivityForResult(intent, requestCode, pickerDisplayOptions(options));
                return;
            } catch (Exception e) {
                // The system refused the virtual display (an external app
                // cannot render on an OWN_CONTENT_ONLY display): fall
                // back to the unpinned launch instead of breaking the
                // picker entirely.
                FileLog.e(e);
            }
        }
        super.startActivityForResult(intent, requestCode, options);
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        if (shouldPinToFloatingDisplay(intent)) {
            try {
                super.startActivity(intent, pickerDisplayOptions(options));
                return;
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        super.startActivity(intent, options);
    }

    /** Pin only what belongs inside floating: media-picker intents, and
     * our own activities (explicit component, or an implicit intent that
     * resolves to our package). Everything else — browser, share, view
     * intents for other apps — passes through UNPINNED: other apps
     * cannot render on this OWN_CONTENT_ONLY virtual display, so
     * forcing them onto it would fail or show nothing. */
    private boolean shouldPinToFloatingDisplay(Intent intent) {
        if (intent == null) {
            return false;
        }
        if (isMediaPickerIntent(intent)) {
            return true;
        }
        try {
            ComponentName component = intent.getComponent();
            if (component != null) {
                return getPackageName().equals(component.getPackageName());
            }
            ComponentName resolved = intent.resolveActivity(getPackageManager());
            if (resolved != null) {
                return getPackageName().equals(resolved.getPackageName());
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    private boolean isMediaPickerIntent(Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_PICK.equals(action) || Intent.ACTION_GET_CONTENT.equals(action)) {
            return true;
        }
        if (Intent.ACTION_CHOOSER.equals(action)) {
            try {
                Intent inner = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (inner != null) {
                    String innerAction = inner.getAction();
                    return Intent.ACTION_PICK.equals(innerAction) || Intent.ACTION_GET_CONTENT.equals(innerAction);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            return false;
        }
        return MediaStore.ACTION_IMAGE_CAPTURE.equals(action)
                || MediaStore.ACTION_VIDEO_CAPTURE.equals(action);
    }

    /** Display options for a pinned launch. A caller-supplied options
     * bundle is replaced (picker launches carry none in practice) —
     * there is no API to add a display id to an existing bundle. */
    private Bundle pickerDisplayOptions(Bundle options) {
        int vdId = SilentFloatingWindow.getInstance().getVirtualDisplayId();
        if (vdId < 0) {
            return options;
        }
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(vdId);
        return opts.toBundle();
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

    /** dialogId of the top chat, or 0 for the chat list — used on expand.
     * v2.0.22: looks beneath any sheet fragment, so a sheet over a chat
     * still expands back to that chat. */
    public long silentGetTopDialogId() {
        try {
            ChatActivity chat = LaunchActivity.getTopChatActivity();
            if (chat != null) {
                return chat.getDialogId();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }
}
