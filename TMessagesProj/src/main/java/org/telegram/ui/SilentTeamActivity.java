package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentBranding;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Silent Hackers Team page (opened from Developer → "Silent Hackers Team").
 *
 * A VIP in-app page — it does NOT open a website. It tells the full story:
 *   • Who the team is — a Pakistani developers team, NOT an illegal hacking
 *     group. Ethical security research, bug bounty hunting, project research.
 *   • Owner: Fatima Qazmi. Developer / Co-founder / Co-owner: Muhammad Arslan
 *     ("Nothing is impossible").
 *   • The developer — real name, date of birth, city, contact.
 *   • All projects: Silent Hosting, Silent AI, SilentGram, Real Earn Team,
 *     Silent Exchange, Silent Studio, Silent VPN, Pak Sim Database.
 *   • Free community utilities.
 *
 * No Sigma, no Kami, no best friend — removed on the owner's instruction.
 */
public class SilentTeamActivity extends BaseFragment {

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentHackersTeam));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;

        ScrollView scrollView = new ScrollView(context);
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        scrollView.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // VIP header card.
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        header.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16), 0xFF6D28D9));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.SilentHackersTeam));
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFFFFFF);
        header.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView subtitle = new TextView(context);
        subtitle.setText(LocaleController.getString(R.string.SilentTeamTagline));
        subtitle.setTextSize(14);
        subtitle.setTextColor(0xCCFFFFFF);
        subtitle.setPadding(0, AndroidUtilities.dp(6), 0, 0);
        header.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Who we are.
        addSectionHeader(layout, context, LocaleController.getString(R.string.SilentTeamWhoWeAre));
        addBody(layout, context, LocaleController.getString(R.string.SilentTeamAbout));

        // People.
        addSectionHeader(layout, context, LocaleController.getString(R.string.SilentTeamPeople));
        addPlainRow(layout, context, LocaleController.getString(R.string.SilentOwnedBy), SilentBranding.OWNER_NAME, true);
        addLinkRow(layout, context, LocaleController.getString(R.string.SilentTeamDeveloper), SilentBranding.DEVELOPER_ALIAS + "  •  " + SilentBranding.DEVELOPER_REAL_NAME, SilentBranding.DEVELOPER_USERNAME, true);
        addPlainRow(layout, context, LocaleController.getString(R.string.SilentTeamRole), LocaleController.getString(R.string.SilentTeamRoleValue), false);
        addPlainRow(layout, context, LocaleController.getString(R.string.SilentTeamDob), "15-05-2004", false);
        addPlainRow(layout, context, LocaleController.getString(R.string.SilentTeamLocation), "Multan, Pakistan", false);
        addLinkRow(layout, context, LocaleController.getString(R.string.SilentTeamChannel), SilentBranding.DEV_CHANNEL_DISPLAY_NAME, SilentBranding.DEV_CHANNEL_USERNAME, true);
        addLinkRow(layout, context, LocaleController.getString(R.string.SilentTeamContact), SilentBranding.DEVELOPER_ALIAS, SilentBranding.DEVELOPER_USERNAME, true);

        // Projects.
        addSectionHeader(layout, context, LocaleController.getString(R.string.SilentTeamProjects));
        addProjectCard(layout, context,
                LocaleController.getString(R.string.SilentProjectHosting),
                LocaleController.getString(R.string.SilentProjectHostingDesc),
                "silenthost.site", null, null);
        addProjectCard(layout, context,
                LocaleController.getString(R.string.SilentProjectAI),
                LocaleController.getString(R.string.SilentProjectAIDesc),
                null, null, null);
        addProjectCard(layout, context,
                "SilentGram",
                LocaleController.getString(R.string.SilentProjectGramDesc),
                null, SilentBranding.OFFICIAL_CHANNEL_USERNAME, null);
        addProjectCard(layout, context,
                LocaleController.getString(R.string.SilentProjectRealEarn),
                LocaleController.getString(R.string.SilentProjectRealEarnDesc),
                null, "imp_tst", "real_earnn_bot");
        addProjectCard(layout, context,
                LocaleController.getString(R.string.SilentProjectExchange),
                LocaleController.getString(R.string.SilentProjectExchangeDesc),
                null, "Silent_Exchange", "Silenr_Exchange_Bot");
        addProjectCard(layout, context,
                LocaleController.getString(R.string.SilentProjectStudio),
                LocaleController.getString(R.string.SilentProjectComingSoon),
                null, null, null);
        addProjectCard(layout, context,
                LocaleController.getString(R.string.SilentProjectVPN),
                LocaleController.getString(R.string.SilentProjectComingSoon),
                null, null, null);
        addProjectCard(layout, context,
                LocaleController.getString(R.string.SilentProjectSimDb),
                LocaleController.getString(R.string.SilentProjectSimDbDesc),
                null, null, null);

        // Free community utilities.
        addSectionHeader(layout, context, LocaleController.getString(R.string.SilentTeamFreeUtils));
        addBody(layout, context, LocaleController.getString(R.string.SilentTeamFreeUtilsDesc));
        addLinkRow(layout, context, LocaleController.getString(R.string.SilentTeamChannel), SilentBranding.DEV_CHANNEL_DISPLAY_NAME, SilentBranding.DEV_CHANNEL_USERNAME, true);

        return fragmentView;
    }

    private void addSectionHeader(LinearLayout layout, Context context, String text) {
        TextView header = new TextView(context);
        header.setText(text);
        header.setTextSize(13);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        header.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(8));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void addBody(LinearLayout layout, Context context, String text) {
        TextView body = new TextView(context);
        body.setText(text);
        body.setTextSize(15);
        body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        body.setPadding(0, 0, 0, AndroidUtilities.dp(8));
        layout.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    /** A tappable row: label on the left, display name (+ optional blue tick)
     * on the right. Tapping opens the hidden username in-app. */
    private void addLinkRow(LinearLayout layout, Context context, String label, String displayName, String username, boolean showVerifiedBadge) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setOnClickListener(v -> {
            if (getParentActivity() == null) {
                return;
            }
            MessagesController.getInstance(currentAccount).openByUserName(username, this, 0);
        });

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(15);
        labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.addView(labelView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        TextView valueView = new TextView(context);
        valueView.setText(displayName);
        valueView.setTextSize(15);
        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        row.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        if (showVerifiedBadge) {
            row.addView(makeVerifiedBadge(context), LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL));
        }

        layout.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        addDivider(layout, context);
    }

    /** A plain row: label on the left, value on the right (+ optional blue tick). NOT clickable. */
    private void addPlainRow(LinearLayout layout, Context context, String label, String value, boolean showVerifiedBadge) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(15);
        labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.addView(labelView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        TextView valueView = new TextView(context);
        valueView.setText(value);
        valueView.setTextSize(15);
        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        if (showVerifiedBadge) {
            row.addView(makeVerifiedBadge(context), LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL));
        }

        layout.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        addDivider(layout, context);
    }

    /** The real blue verified badge (circle + check), as used in chat rows. */
    private FrameLayout makeVerifiedBadge(Context context) {
        FrameLayout badge = new FrameLayout(context);
        badge.setPadding(AndroidUtilities.dp(4), 0, 0, 0);
        android.widget.ImageView badgeBg = new android.widget.ImageView(context);
        badgeBg.setImageDrawable(Theme.dialogs_verifiedDrawable);
        badge.addView(badgeBg, LayoutHelper.createFrame(16, 16, Gravity.CENTER));
        android.widget.ImageView badgeCheck = new android.widget.ImageView(context);
        badgeCheck.setImageDrawable(Theme.dialogs_verifiedCheckDrawable);
        badge.addView(badgeCheck, LayoutHelper.createFrame(16, 16, Gravity.CENTER));
        return badge;
    }

    /** A project card: name + description + optional channel/bot links. */
    private void addProjectCard(LinearLayout layout, Context context, String name, String desc, String site, String channel, String bot) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), Theme.getColor(Theme.key_windowBackgroundGray)));
        layout.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, AndroidUtilities.dp(8)));

        TextView nameView = new TextView(context);
        nameView.setText(name);
        nameView.setTextSize(16);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        card.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView descView = new TextView(context);
        descView.setText(desc);
        descView.setTextSize(14);
        descView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        descView.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        card.addView(descView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (site != null) {
            TextView siteView = new TextView(context);
            siteView.setText(site);
            siteView.setTextSize(14);
            siteView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            siteView.setPadding(0, AndroidUtilities.dp(6), 0, 0);
            card.addView(siteView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        if (channel != null) {
            TextView chView = new TextView(context);
            chView.setText(LocaleController.getString(R.string.SilentTeamChannel) + ": @" + channel);
            chView.setTextSize(14);
            chView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            chView.setPadding(0, AndroidUtilities.dp(4), 0, 0);
            chView.setOnClickListener(v -> {
                if (getParentActivity() == null) return;
                MessagesController.getInstance(currentAccount).openByUserName(channel, this, 0);
            });
            card.addView(chView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        if (bot != null) {
            TextView botView = new TextView(context);
            botView.setText(LocaleController.getString(R.string.SilentTeamBot) + ": @" + bot);
            botView.setTextSize(14);
            botView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            botView.setPadding(0, AndroidUtilities.dp(4), 0, 0);
            botView.setOnClickListener(v -> {
                if (getParentActivity() == null) return;
                MessagesController.getInstance(currentAccount).openByUserName(bot, this, 0);
            });
            card.addView(botView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
    }

    private void addDivider(LinearLayout layout, Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        layout.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
    }
}
