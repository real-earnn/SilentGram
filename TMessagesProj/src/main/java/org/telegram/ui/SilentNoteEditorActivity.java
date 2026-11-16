package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentNotes;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * SilentGram F9: edit a private note for one person, with an optional
 * reminder time. Only you can see it.
 */
public class SilentNoteEditorActivity extends BaseFragment {

    private final long userId;
    private final String chatName;
    private EditText noteField;
    private long remindAt = 0;
    private TextView remindLabel;

    public SilentNoteEditorActivity(long userId, String chatName) {
        this.userId = userId;
        this.chatName = chatName;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentNoteTitle) + " — " + chatName);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    save();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(1, R.drawable.msg_check_s);

        SilentNotes.Note existing = SilentNotes.getNote(userId);
        remindAt = existing != null ? existing.remindAt : 0;

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        frameLayout.addView(layout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        noteField = new EditText(context);
        noteField.setHint(LocaleController.getString(R.string.SilentNoteHint));
        noteField.setTextSize(16);
        noteField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        noteField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        noteField.setMinLines(5);
        noteField.setGravity(Gravity.TOP);
        if (existing != null) {
            noteField.setText(existing.text);
        }
        layout.addView(noteField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        remindLabel = new TextView(context);
        remindLabel.setTextSize(15);
        remindLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        remindLabel.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(12), AndroidUtilities.dp(4), AndroidUtilities.dp(12));
        updateRemindLabel();
        remindLabel.setOnClickListener(v -> pickReminder());
        layout.addView(remindLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView hint = new TextView(context);
        hint.setText(LocaleController.getString(R.string.SilentNotePrivateHint));
        hint.setTextSize(12);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        hint.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4), 0);
        layout.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return fragmentView;
    }

    private void updateRemindLabel() {
        if (remindAt > 0) {
            String when = new java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.US).format(new java.util.Date(remindAt));
            remindLabel.setText("⏰ " + when + "  (" + LocaleController.getString(R.string.SilentNoteChangeReminder) + ")");
        } else {
            remindLabel.setText("⏰ " + LocaleController.getString(R.string.SilentNoteSetReminder));
        }
    }

    private void pickReminder() {
        CharSequence[] items = new CharSequence[]{
                LocaleController.getString(R.string.SilentRemind1h),
                LocaleController.getString(R.string.SilentRemind3h),
                LocaleController.getString(R.string.SilentRemindTomorrow9),
                LocaleController.getString(R.string.SilentNoteNoReminder)
        };
        long now = System.currentTimeMillis();
        long[] times = new long[]{now + 3600_000L, now + 3 * 3600_000L, nextMorning9(), 0};
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.SilentNoteSetReminder));
        builder.setItems(items, (d, which) -> {
            remindAt = times[which];
            updateRemindLabel();
        });
        builder.show();
    }

    private long nextMorning9() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 9);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private void save() {
        SilentNotes.saveNote(getParentActivity(), userId, chatName, noteField.getText().toString(), remindAt);
        AndroidUtilities.showToast(getParentActivity(), LocaleController.getString(R.string.SilentNoteSaved));
        finishFragment();
    }
}
