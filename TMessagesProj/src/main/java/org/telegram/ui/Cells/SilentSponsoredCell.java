package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
        name.setText(SilentBranding.getSponsoredTitle());
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(Theme.getColor(Theme.key_chats_name));
        name.setSingleLine(true);
        nameRow.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        // Name + the REAL blue verified badge (circle + check), not a text tick.
        FrameLayout badge = new FrameLayout(context);
        ImageView badgeBg = new ImageView(context);
        badgeBg.setImageDrawable(Theme.dialogs_verifiedDrawable);
        badge.addView(badgeBg, LayoutHelper.createFrame(16, 16, Gravity.CENTER));
        ImageView badgeCheck = new ImageView(context);
        badgeCheck.setImageDrawable(Theme.dialogs_verifiedCheckDrawable);
        badge.addView(badgeCheck, LayoutHelper.createFrame(16, 16, Gravity.CENTER));
        badge.setPadding(AndroidUtilities.dp(4), 0, 0, 0);
        nameRow.addView(badge, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL));

        // Subtitle: Official + username.
        TextView sub = new TextView(context);
        sub.setText(LocaleController.getString(R.string.SilentSponsored) + "  •  @" + SilentBranding.getSponsoredUsername());
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
