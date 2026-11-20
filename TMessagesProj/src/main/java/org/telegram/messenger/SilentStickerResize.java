package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.LinearLayout;
import org.telegram.ui.Components.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Locale;

/**
 * SilentGram sticker resize (1–10), like Plus Messenger.
 *
 * Long-press a sticker → "Resize" → a number picker (1 = tiny, emoji-size;
 * 5 = normal; 10 = biggest). The size is ONE value per account: changing it
 * from any sticker resizes every sticker shown anywhere in that account.
 * ChatMessageCell scales the sticker by it, capped so a sticker NEVER takes
 * the full screen — dp(16) margins stay on the sides, so tapping beside a
 * sticker does not open the sticker view.
 */
public class SilentStickerResize {

    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 10;
    /** Level 5 = normal size (1.0x). */
    private static final int NORMAL_LEVEL = 5;

    /** Max scale: level 10 = 2.0x. With the base 0.5x-parent-width sizing
     * that reaches the margined cap for a standard sticker. */
    private static final float MAX_SCALE = 2.0f;

    /** Scale for a level: 1 -> 0.2x, 5 -> 1.0x, 10 -> 2.0x. */
    public static float scaleForLevel(int level) {
        return Math.max(0.2f, Math.min(MAX_SCALE, level / (float) NORMAL_LEVEL));
    }

    /** One scale per account — shared by every sticker in that account. */
    private static String key(int account) {
        return "sticker_scale_account_" + account;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("silentgram", Context.MODE_PRIVATE);
    }

    /** Stored scale for this account (1.0 = normal), clamped to the 1–10 range.
     * Older builds stored up to 4.0x (level 20) — that clamps down to 2.0x here,
     * so a sticker saved huge before can never exceed the new max. */
    public static float getScale(int account) {
        float scale = prefs().getFloat(key(account), 1.0f);
        if (scale <= 0) {
            return 1.0f;
        }
        return Math.max(0.2f, Math.min(MAX_SCALE, scale));
    }

    /** Current level for this account (5 = normal). */
    public static int getLevel(int account) {
        float scale = getScale(account);
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, Math.round(scale * NORMAL_LEVEL)));
    }

    public static void setLevel(int account, int level) {
        prefs().edit().putFloat(key(account), scaleForLevel(level)).apply();
    }

    /** Max rendered sticker width: the chat width minus dp(16) on each side,
     * so a sticker never takes the full screen and tapping beside it misses
     * the sticker. */
    public static int maxStickerWidth(int parentWidth) {
        return Math.max(1, parentWidth - AndroidUtilities.dp(32));
    }

    /** Max rendered sticker height: 80% of the screen height. */
    public static int maxStickerHeight() {
        return Math.max(1, (int) (AndroidUtilities.displaySize.y * 0.8f));
    }

    /** Approximate sticker size in dp at a level (base dp(72) at 1.0x), for the
     * confirmation shown after picking. */
    public static int sizeDpForLevel(int level) {
        return Math.round(72 * scaleForLevel(level));
    }

    /** Show the 1–10 number picker for this account. onChanged runs after picking. */
    public static void showPicker(Context context, int account, MessageObject messageObject, Runnable onChanged) {
        if (context == null || messageObject == null) {
            return;
        }
        int current = getLevel(account);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(8));

        TextView label = new TextView(context);
        label.setTextSize(15);
        label.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        label.setGravity(Gravity.CENTER);
        layout.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        NumberPicker picker = new NumberPicker(context);
        picker.setMinValue(MIN_LEVEL);
        picker.setMaxValue(MAX_LEVEL);
        picker.setValue(current);
        picker.setWrapSelectorWheel(false);
        // 5-row wheel: the selected level sits in the middle, with two
        // values above and two below it — so you always see exactly where
        // you are (e.g. 6 in the middle, 5 and 4 above, 7 and 8 below).
        picker.setItemCount(5);
        layout.addView(picker, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, AndroidUtilities.dp(12), 0, 0));

        Runnable updateLabel = () -> {
            int level = picker.getValue();
            String scaleText = String.format(Locale.US, "%.1fx", scaleForLevel(level));
            label.setText(LocaleController.getString(R.string.SilentStickerResize) + ": " + level + (level == NORMAL_LEVEL ? " (normal, " + scaleText + ")" : " (" + scaleText + ")"));
        };
        updateLabel.run();
        picker.setOnValueChangedListener((pickerView, oldVal, newVal) -> updateLabel.run());

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentStickerResize));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
            int level = picker.getValue();
            setLevel(account, level);
            confirmSize(context, level);
            if (onChanged != null) {
                onChanged.run();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setNeutralButton(LocaleController.getString(R.string.SilentStickerResizeReset), (dialog, which) -> {
            setLevel(account, NORMAL_LEVEL);
            confirmSize(context, NORMAL_LEVEL);
            if (onChanged != null) {
                onChanged.run();
            }
        });
        builder.show();
    }

    /** Confirmation after picking: the exact number and the resulting size. */
    private static void confirmSize(Context context, int level) {
        String scaleText = String.format(Locale.US, "%.1fx", scaleForLevel(level));
        String text = LocaleController.getString(R.string.SilentStickerResize) + ": " + level + " (" + scaleText + ", ~" + sizeDpForLevel(level) + " dp)";
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }

    /** Backwards-compatible picker without a refresh callback. */
    public static void showPicker(Context context, int account, MessageObject messageObject) {
        showPicker(context, account, messageObject, null);
    }
}
