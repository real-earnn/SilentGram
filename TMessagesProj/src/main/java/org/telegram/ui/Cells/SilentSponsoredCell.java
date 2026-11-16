package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentBranding;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

/**
 * SilentGram sponsored suggestion row — the official @silent_gram channel,
 * shown at the very top of the All list and the Channels folder when the
 * user has not joined it yet. Tapping it opens the channel in-app.
 */
public class SilentSponsoredCell extends FrameLayout {

    public SilentSponsoredCell(Context context) {
        super(context);
        setBackground(Theme.getSelectorDrawable(false));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Avatar — SilentGram "S" monogram.
        BackupImageView avatar = new BackupImageView(context);
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(0, "SilentGram", null);
        avatar.setImageDrawable(avatarDrawable);
        row.addView(avatar, LayoutHelper.createLinear(52, 52));

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(AndroidUtilities.dp(10), 0, 0, 0);
        row.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        // Name + verified tick.
        LinearLayout nameRow = new LinearLayout(context);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        text.addView(nameRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        TextView name = new TextView(context);
        name.setText("SilentGram");
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(Theme.getColor(Theme.key_chats_name));
        name.setSingleLine(true);
        nameRow.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        TextView tick = new TextView(context);
        tick.setText("  ✓");
        tick.setTextSize(15);
        tick.setTypeface(Typeface.DEFAULT_BOLD);
        tick.setTextColor(0xFF3E9BFF);
        nameRow.addView(tick, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        // Subtitle: Sponsored + username.
        TextView sub = new TextView(context);
        sub.setText(LocaleController.getString(R.string.SilentSponsored) + "  •  @" + SilentBranding.OFFICIAL_CHANNEL_USERNAME);
        sub.setTextSize(14);
        sub.setTextColor(Theme.getColor(Theme.key_chats_message));
        sub.setSingleLine(true);
        text.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // "Join" pill on the right.
        TextView join = new TextView(context);
        join.setText(LocaleController.getString(R.string.SilentJoin));
        join.setTextSize(14);
        join.setTypeface(Typeface.DEFAULT_BOLD);
        join.setTextColor(0xFFFFFFFF);
        join.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16), 0xFF3E9BFF));
        join.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(7), AndroidUtilities.dp(14), AndroidUtilities.dp(7));
        row.addView(join, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
    }
}
