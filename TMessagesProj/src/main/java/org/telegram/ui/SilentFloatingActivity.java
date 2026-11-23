package org.telegram.ui;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
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

    // v2.0.24: set when this activity is paused/stopped (Home), cleared
    // on resume after the re-kick — the floating manager uses it to know
    // a tap must wake the activity before the touch is forwarded.
    private boolean wasPausedOrStopped;

    /** Whether this activity was paused/stopped (e.g. by Home) and has
     * not resumed since — the floating manager re-kicks it on the next
     * tap instead of forwarding the touch into a stopped window. */
    public boolean silentWasPausedOrStopped() {
        return wasPausedOrStopped;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SilentFloatingWindow.getInstance().setFloatingActivity(this);
        // v2.0.24: the virtual display has no status bar and no
        // navigation bar, so nothing may reserve space for them.
        // AndroidUtilities.statusBarHeight is a GLOBAL static written
        // only by DrawerLayoutContainer from the last insets dispatch
        // — the main activity's phone value would otherwise leak in
        // here, and ActionBar adds it on top of its own height, so the
        // content starts below a big empty strip and loses that height.
        // Zero it, and strip the system-bars top/bottom from the
        // insets this window delivers, so no later dispatch can
        // re-introduce the gap. Scoped to this activity/window only:
        // on expand the re-launched main activity re-writes both
        // globals from the real display's insets on its first dispatch.
        AndroidUtilities.statusBarHeight = 0;
        AndroidUtilities.navigationBarHeight = 0;
        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets strippedBars = Insets.of(bars.left, 0, bars.right, 0);
            WindowInsetsCompat stripped = new WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.systemBars(), strippedBars)
                    .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars(), strippedBars)
                    .build();
            return stripped;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        SilentFloatingWindow.getInstance().setFloatingResumed(true);
        // v2.0.24: after Home this activity is stopped and nothing
        // resumes it — the first resize was the only thing that
        // re-kicked drawing. Re-kick on resume instead, so the picture
        // comes back without the user having to resize. The flag is
        // false on the initial launch, so this fires only after a real
        // pause/stop.
        if (wasPausedOrStopped) {
            wasPausedOrStopped = false;
            SilentFloatingWindow.getInstance().onFloatingActivityResumed();
        }
    }

    @Override
    protected void onStop() {
        wasPausedOrStopped = true;
        super.onStop();
    }

    @Override
    protected void onUserLeaveHint() {
        // No-op: this activity IS the floating window. Home leaves the
        // window in place; never enter system PiP or a second floating
        // window from here.
    }

    @Override
    protected void onPause() {
        // v2.0.24: remember the pause so onResume() can re-kick the
        // activity (see onResume above).
        wasPausedOrStopped = true;
        SilentFloatingWindow.getInstance().setFloatingResumed(false);
        // v2.0.25: start the frame watchdog now (see
        // SilentFloatingWindow.onFloatingActivityBackgrounded) — after
        // Home the system never delivers onResume(), so waiting for it
        // leaves a black window until the first tap or resize. The
        // watchdog samples the picture and re-polls while it is black,
        // so the picture is back before the user taps.
        SilentFloatingWindow.getInstance().onFloatingActivityBackgrounded();
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
        // v2.0.23: the system photo picker (API 33+) is a media picker too.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && MediaStore.ACTION_PICK_IMAGES.equals(action)) {
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
        // v2.0.26: a destroy for a configuration change is NOT a real
        // close — the system recreates this activity on the SAME virtual
        // display, so the overlay + display must survive it. Before this
        // guard, any unhandled config change (a resize changes the
        // virtual display's density, and `density` was not in
        // configChanges) tore the whole floating window down here, and
        // the recreated activity landed on the phone display instead:
        // the user saw the FULL app after resizing. Only a genuine
        // destroy (expand / close / finish) tears the window down.
        if (!isChangingConfigurations()) {
            SilentFloatingWindow.getInstance().onFloatingActivityDestroyed(this);
        }
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
