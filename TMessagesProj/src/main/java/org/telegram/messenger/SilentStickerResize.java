package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * SilentGram sticker resize (1–20), like Plus Messenger.
 *
 * Long-press a sticker → "Resize" → a slider (1 = tiny, emoji-size;
 * 5 = normal; 20 = as big as the screen). The size is stored per message
 * (dialog + message id) and ChatMessageCell scales the sticker's
 * maxPhotoWidth by it, so only that sticker changes size.
 */
public class SilentStickerResize {

    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 20;
    /** Level 5 = normal size (1.0x). */
    private static final int NORMAL_LEVEL = 5;

    /** Scale for a level: 1 -> 0.2x, 5 -> 1.0x, 20 -> 4.0x. */
    public static float scaleForLevel(int level) {
        return Math.max(0.2f, Math.min(4.0f, level / (float) NORMAL_LEVEL));
    }

    private static String key(long dialogId, int messageId) {
        return "sticker_scale_" + dialogId + "_" + messageId;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("silentgram", Context.MODE_PRIVATE);
    }

    /** Stored scale for this sticker (1.0 = normal). */
    public static float getScale(int account, long dialogId, int messageId) {
        float scale = prefs().getFloat(key(dialogId, messageId), 1.0f);
        return scale > 0 ? scale : 1.0f;
    }

    /** Current level for this sticker (5 = normal). */
    public static int getLevel(int account, long dialogId, int messageId) {
        float scale = getScale(account, dialogId, messageId);
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, Math.round(scale * NORMAL_LEVEL)));
    }

    public static void setLevel(int account, long dialogId, int messageId, int level) {
        prefs().edit().putFloat(key(dialogId, messageId), scaleForLevel(level)).apply();
    }

    /** Show the 1–20 slider for this sticker. onChanged runs after picking. */
    public static void showPicker(Context context, int account, MessageObject messageObject, Runnable onChanged) {
        if (context == null || messageObject == null) {
            return;
        }
        long dialogId = messageObject.getDialogId();
        int messageId = messageObject.getId();
        int current = getLevel(account, dialogId, messageId);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(8));

        TextView label = new TextView(context);
        label.setTextSize(15);
        label.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        label.setGravity(Gravity.CENTER);
        layout.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        SeekBar seekBar = new SeekBar(context);
        seekBar.setMax(MAX_LEVEL - MIN_LEVEL);
        seekBar.setProgress(current - MIN_LEVEL);
        layout.addView(seekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, AndroidUtilities.dp(12), 0, 0));

        Runnable updateLabel = () -> {
            int level = seekBar.getProgress() + MIN_LEVEL;
            label.setText(LocaleController.getString(R.string.SilentStickerResize) + ": " + level + (level == NORMAL_LEVEL ? " (normal)" : ""));
        };
        updateLabel.run();
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateLabel.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentStickerResize));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
            int level = seekBar.getProgress() + MIN_LEVEL;
            setLevel(account, dialogId, messageId, level);
            if (onChanged != null) {
                onChanged.run();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setNeutralButton(LocaleController.getString(R.string.SilentStickerResizeReset), (dialog, which) -> {
            setLevel(account, dialogId, messageId, NORMAL_LEVEL);
            if (onChanged != null) {
                onChanged.run();
            }
        });
        builder.show();
    }

    /** Backwards-compatible picker without a refresh callback. */
    public static void showPicker(Context context, int account, MessageObject messageObject) {
        showPicker(context, account, messageObject, null);
    }
}
