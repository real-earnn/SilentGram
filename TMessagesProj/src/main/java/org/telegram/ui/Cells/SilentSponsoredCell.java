package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ConnectionsManager;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentBranding;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

/**
 * SilentGram sponsored suggestion row — the official @silent_gram channel
 * (or a sponsored channel from the API config), shown right below Archived
 * Chats at the top of the All list and the Channels folder when the user
 * has not joined it yet. Tapping the row opens the channel in-app; the
 * Join pill actually joins it. The blue tick shows ONLY for our own
 * official channel — a sponsored channel never gets one.
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
        // Weight 1 so the text column never runs under the Join pill.
        row.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        // Name + verified tick (official channel only — never for a sponsor).
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

        boolean isOfficial = SilentBranding.OFFICIAL_CHANNEL_USERNAME.equalsIgnoreCase(SilentBranding.getSponsoredUsername());
        if (isOfficial) {
            // The REAL blue verified badge (circle + check), not a text tick.
            FrameLayout badge = new FrameLayout(context);
            ImageView badgeBg = new ImageView(context);
            badgeBg.setImageDrawable(Theme.dialogs_verifiedDrawable);
            badge.addView(badgeBg, LayoutHelper.createFrame(16, 16, Gravity.CENTER));
            ImageView badgeCheck = new ImageView(context);
            badgeCheck.setImageDrawable(Theme.dialogs_verifiedCheckDrawable);
            badge.addView(badgeCheck, LayoutHelper.createFrame(16, 16, Gravity.CENTER));
            badge.setPadding(AndroidUtilities.dp(4), 0, 0, 0);
            nameRow.addView(badge, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL));
        }

        // Subtitle: Official + username (or "Sponsored" + username).
        TextView sub = new TextView(context);
        String label = isOfficial
                ? LocaleController.getString(R.string.SilentSponsored)
                : LocaleController.getString(R.string.SilentSponsoredPromo);
        sub.setText(label + "  •  @" + SilentBranding.getSponsoredUsername());
        sub.setTextSize(14);
        sub.setTextColor(Theme.getColor(Theme.key_chats_message));
        sub.setSingleLine(true);
        text.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // "Join" pill on the right, aligned to the TOP (with the name row)
        // so it never sits over the username line below.
        TextView join = new TextView(context);
        join.setText(LocaleController.getString(R.string.SilentJoin));
        join.setTextSize(13);
        join.setTypeface(Typeface.DEFAULT_BOLD);
        join.setTextColor(0xFFFFFFFF);
        join.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16), 0xFF3E9BFF));
        join.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(6), AndroidUtilities.dp(12), AndroidUtilities.dp(6));
        join.setClickable(true);
        join.setOnClickListener(v -> joinSponsoredChannel(context));
        row.addView(join, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
    }

    /** Resolve the sponsored username and actually join the channel. */
    private void joinSponsoredChannel(Context context) {
        int account = UserConfig.selectedAccount;
        String username = SilentBranding.getSponsoredUsername();
        if (username == null || username.isEmpty()) {
            return;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            if (err != null || !(res instanceof TLRPC.TL_contacts_resolvedPeer)) {
                AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(context, err != null ? err.text : LocaleController.getString(R.string.SilentJoinFailed), android.widget.Toast.LENGTH_SHORT).show());
                return;
            }
            TLRPC.TL_contacts_resolvedPeer peer = (TLRPC.TL_contacts_resolvedPeer) res;
            if (peer.chats == null || peer.chats.isEmpty()) {
                return;
            }
            TLRPC.Chat chat = peer.chats.get(0);
            // Join: addUserToChat with the current user on a channel id.
            // The join retry (proxy-off) in MessagesController covers the
            // CHANNELS_TOO_MUCH false limit from a bad proxy.
            MessagesController.getInstance(account).addUserToChat(
                    -chat.id,
                    UserConfig.getInstance(account).getCurrentUser(),
                    0, null, null, false,
                    () -> AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(context, LocaleController.getString(R.string.SilentJoined), android.widget.Toast.LENGTH_SHORT).show()),
                    e -> AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(context, e != null ? e.text : LocaleController.getString(R.string.SilentJoinFailed), android.widget.Toast.LENGTH_SHORT).show()));
        });
    }
}
