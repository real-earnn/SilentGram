package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentBranding;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * SilentGram Developer page (opened from Settings → Developer).
 *
 * A VIP in-app page — it does NOT open a website. It shows:
 *   • About SilentGram (what the app is, version)
 *   • The official channel @silent_gram
 *   • The developer's channel @only_possible_worlds0
 *   • Owned by Fatima Qazmi (plain text — not clickable)
 *   • Developed by Nothing is impossible (clickable — opens @only_possible)
 *   • A "Silent Hackers Team" button opening the full team page.
 * Tapping a channel or a username opens it right here inside the app.
 */
public class SilentDeveloperActivity extends BaseFragment {

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentDeveloperTitle));
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
        title.setText("SilentGram");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFFFFFF);
        header.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView version = new TextView(context);
        version.setText(LocaleController.formatString(R.string.TelegramVersion, BuildVars.BUILD_VERSION_STRING));
        version.setTextSize(14);
        version.setTextColor(0xCCFFFFFF);
        version.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(8));
        header.addView(version, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView tagline = new TextView(context);
        tagline.setText(LocaleController.getString(R.string.SilentTagline));
        tagline.setTextSize(14);
        tagline.setTextColor(0xFFFFFFFF);
        header.addView(tagline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // About.
        addSectionHeader(layout, context, LocaleController.getString(R.string.SilentAboutTitle));
        TextView about = new TextView(context);
        about.setText(LocaleController.getString(R.string.SilentAboutApp));
        about.setTextSize(15);
        about.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        about.setPadding(0, 0, 0, AndroidUtilities.dp(12));
        layout.addView(about, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Links section.
        addSectionHeader(layout, context, LocaleController.getString(R.string.SilentDeveloperLinks));
        addLinkRow(layout, context, LocaleController.getString(R.string.SilentOfficialChannel), "@" + SilentBranding.OFFICIAL_CHANNEL_USERNAME, SilentBranding.OFFICIAL_CHANNEL_USERNAME);
        addLinkRow(layout, context, LocaleController.getString(R.string.SilentDevChannel), "@" + SilentBranding.DEV_CHANNEL_USERNAME, SilentBranding.DEV_CHANNEL_USERNAME);
        addLinkRow(layout, context, LocaleController.getString(R.string.SilentOwner), "@" + SilentBranding.OWNER_USERNAME, SilentBranding.OWNER_USERNAME);

        // Credits section.
        addSectionHeader(layout, context, LocaleController.getString(R.string.SilentCredits));
        // Owned by Fatima Qazmi — plain text, NOT clickable.
        addPlainRow(layout, context, LocaleController.getString(R.string.SilentOwnedBy), SilentBranding.OWNER_NAME);
        // Developed by Nothing is impossible — clickable, opens @only_possible.
        addLinkRow(layout, context, LocaleController.getString(R.string.SilentDevelopedBy), SilentBranding.DEVELOPER_ALIAS, SilentBranding.DEVELOPER_USERNAME);

        // Silent Hackers Team button.
        addSectionHeader(layout, context, LocaleController.getString(R.string.SilentTeamTitle));
        TextView teamBtn = new TextView(context);
        teamBtn.setText(LocaleController.getString(R.string.SilentHackersTeam));
        teamBtn.setTextSize(16);
        teamBtn.setTypeface(Typeface.DEFAULT_BOLD);
        teamBtn.setGravity(Gravity.CENTER);
        teamBtn.setTextColor(0xFFFFFFFF);
        teamBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), 0xFF6D28D9));
        teamBtn.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        teamBtn.setOnClickListener(v -> presentFragment(new SilentTeamActivity()));
        layout.addView(teamBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, AndroidUtilities.dp(4), 0, 0));

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

    /** A tappable row: label on the left, value on the right. Tapping opens it in-app. */
    private void addLinkRow(LinearLayout layout, Context context, String label, String value, String username) {
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
        valueView.setText(value);
        valueView.setTextSize(15);
        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        row.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        layout.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        addDivider(layout, context);
    }

    /** A plain row: label on the left, value on the right. NOT clickable. */
    private void addPlainRow(LinearLayout layout, Context context, String label, String value) {
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

        layout.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        addDivider(layout, context);
    }

    private void addDivider(LinearLayout layout, Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        layout.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
    }
}
