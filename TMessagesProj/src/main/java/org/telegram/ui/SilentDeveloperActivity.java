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
 *   • The owner @only_possible0
 *   • Developed by / Owned by M Arslan
 * Tapping a channel or a username opens it right here inside the app.
 */
public class SilentDeveloperActivity extends BaseFragment {

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentDeveloperTitle));
        actionBar.setActionBarMenuOnItemClick(view -> {
            if (view.getId() == -1) {
                finishFragment();
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

        // App title.
        TextView title = new TextView(context);
        title.setText("SilentGram");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView version = new TextView(context);
        version.setText(LocaleController.formatString(R.string.TelegramVersion, BuildVars.VERSION_NAME));
        version.setTextSize(14);
        version.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        version.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(16));
        layout.addView(version, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // About.
        TextView about = new TextView(context);
        about.setText(LocaleController.getString(R.string.SilentAboutApp));
        about.setTextSize(15);
        about.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        about.setPadding(0, 0, 0, AndroidUtilities.dp(20));
        layout.addView(about, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Links section.
        addSectionHeader(layout, context, LocaleController.getString(R.string.SilentDeveloperLinks));
        addLinkRow(layout, context, LocaleController.getString(R.string.SilentOfficialChannel), "@" + SilentBranding.OFFICIAL_CHANNEL_USERNAME, SilentBranding.OFFICIAL_CHANNEL_USERNAME);
        addLinkRow(layout, context, LocaleController.getString(R.string.SilentDevChannel), "@" + SilentBranding.DEV_CHANNEL_USERNAME, SilentBranding.DEV_CHANNEL_USERNAME);
        addLinkRow(layout, context, LocaleController.getString(R.string.SilentOwner), "@" + SilentBranding.OWNER_USERNAME, SilentBranding.OWNER_USERNAME);

        // Credits section.
        addSectionHeader(layout, context, LocaleController.getString(R.string.SilentCredits));
        addInfoRow(layout, context, LocaleController.getString(R.string.SilentDevelopedBy), SilentBranding.OWNER_NAME, SilentBranding.OWNER_USERNAME);
        addInfoRow(layout, context, LocaleController.getString(R.string.SilentOwnedBy), SilentBranding.OWNER_NAME, SilentBranding.OWNER_USERNAME);

        return fragmentView;
    }

    private void addSectionHeader(LinearLayout layout, Context context, String text) {
        TextView header = new TextView(context);
        header.setText(text);
        header.setTextSize(13);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        header.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    /** A tappable row: label on the left, @username on the right. Tapping opens it in-app. */
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

    /** A row showing "Developed by / Owned by" + name. Tapping the name opens the owner's profile. */
    private void addInfoRow(LinearLayout layout, Context context, String label, String name, String username) {
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
        valueView.setText(name);
        valueView.setTextSize(15);
        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
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
