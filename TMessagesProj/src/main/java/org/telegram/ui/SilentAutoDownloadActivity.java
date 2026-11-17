package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentAutoDownload;
import org.telegram.messenger.SilentMessageHistory;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.SilentToast;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

/**
 * SilentGram F2: Auto-download rules.
 * Lists your recent chats. Tap one for a popup to pick its rule:
 * Normal (Telegram's own settings) -> Always (media always auto-downloads)
 * -> Never (media never auto-downloads, saves data & storage).
 *
 * I11: folder tabs (All Chats + one tab per chat folder, via
 * DialogFilter.includesDialog) and a search icon in the action bar —
 * search matches the display name AND the username, across all chats.
 */
public class SilentAutoDownloadActivity extends BaseFragment {

    private static final int MENU_SEARCH = 1;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
    private ArrayList<TLRPC.Dialog> allDialogs = new ArrayList<>();
    private ArrayList<MessagesController.DialogFilter> filters = new ArrayList<>();
    private int selectedTab = -1; // -1 = All Chats, otherwise index into filters
    private TextView emptyView;
    private LinearLayout tabsRow;
    private HorizontalScrollView tabsScroll;
    private ActionBarMenuItem searchItem;
    private boolean searching;
    private String searchQuery;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentAutoDownloadTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        searchItem = actionBar.createMenu().addItem(MENU_SEARCH, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                searching = true;
                if (tabsScroll != null) {
                    tabsScroll.setVisibility(View.GONE);
                }
                applyFilter();
            }

            @Override
            public void onSearchCollapse() {
                searching = false;
                searchQuery = null;
                if (tabsScroll != null && !filters.isEmpty()) {
                    tabsScroll.setVisibility(View.VISIBLE);
                }
                applyFilter();
            }

            @Override
            public void onTextChanged(EditText editText) {
                searchQuery = editText.getText().toString();
                applyFilter();
            }
        });
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        frameLayout.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        tabsScroll = new HorizontalScrollView(context);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsRow = new LinearLayout(context);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
        tabsScroll.addView(tabsRow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        tabsScroll.setVisibility(View.GONE);
        column.addView(tabsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(44)));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter(context));
        column.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1.0f));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.SilentAutoDownloadEmpty));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        reload();
        return fragmentView;
    }

    private void reload() {
        allDialogs.clear();
        filters.clear();
        MessagesController mc = MessagesController.getInstance(currentAccount);
        // dialogs_dict holds every dialog by id (there is no plain `dialogs` list).
        if (mc.dialogs_dict != null) {
            for (int i = 0; i < mc.dialogs_dict.size(); i++) {
                TLRPC.Dialog d = mc.dialogs_dict.valueAt(i);
                if (d != null && d.id != 0 && !d.isFolder && !DialogObject.isFolderDialogId(d.id)) {
                    allDialogs.add(d);
                }
            }
        }
        if (mc.dialogFilters != null) {
            for (int i = 0; i < mc.dialogFilters.size(); i++) {
                MessagesController.DialogFilter filter = mc.dialogFilters.get(i);
                if (filter != null && !filter.isDefault()) {
                    filters.add(filter);
                }
            }
        }
        buildTabs();
        applyFilter();
    }

    /** Rebuild the folder tab strip; hidden entirely when there are no folders. */
    private void buildTabs() {
        if (tabsRow == null) {
            return;
        }
        tabsRow.removeAllViews();
        if (filters.isEmpty()) {
            if (tabsScroll != null) {
                tabsScroll.setVisibility(View.GONE);
            }
            return;
        }
        if (tabsScroll != null && !searching) {
            tabsScroll.setVisibility(View.VISIBLE);
        }
        Context context = tabsRow.getContext();
        addTab(context, LocaleController.getString(R.string.FilterAllChats), -1);
        for (int i = 0; i < filters.size(); i++) {
            addTab(context, filters.get(i).name, i);
        }
        updateTabStyles();
    }

    private void addTab(Context context, String title, int tabIndex) {
        TextView tab = new TextView(context);
        tab.setText(title);
        tab.setTextSize(15);
        tab.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        tab.setGravity(Gravity.CENTER);
        final int index = tabIndex;
        tab.setOnClickListener(v -> {
            selectedTab = index;
            updateTabStyles();
            applyFilter();
        });
        tabsRow.addView(tab, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
    }

    private void updateTabStyles() {
        if (tabsRow == null) {
            return;
        }
        for (int i = 0; i < tabsRow.getChildCount(); i++) {
            View child = tabsRow.getChildAt(i);
            if (!(child instanceof TextView)) {
                continue;
            }
            TextView tab = (TextView) child;
            int tabIndex = i - 1; // first tab is "All Chats" (-1)
            boolean selected = tabIndex == selectedTab;
            tab.setTypeface(selected ? AndroidUtilities.bold() : android.graphics.Typeface.DEFAULT);
            tab.setTextColor(Theme.getColor(selected ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteGrayText2));
        }
    }

    /** Apply the selected folder tab and, while searching, the search query. */
    private void applyFilter() {
        dialogs.clear();
        String query = searchQuery != null ? searchQuery.trim().toLowerCase() : null;
        boolean useSearch = searching && query != null && !query.isEmpty();
        MessagesController.DialogFilter filter = selectedTab >= 0 ? filters.get(selectedTab) : null;
        AccountInstance accountInstance = AccountInstance.getInstance(currentAccount);
        for (int i = 0; i < allDialogs.size(); i++) {
            TLRPC.Dialog d = allDialogs.get(i);
            if (useSearch) {
                // Search runs across ALL chats, not just the selected tab.
                if (!matchesQuery(d, query)) {
                    continue;
                }
            } else if (filter != null && !filter.includesDialog(accountInstance, d.id, d)) {
                continue;
            }
            dialogs.add(d);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmpty();
    }

    /** True when the display name OR the username contains the query. */
    private boolean matchesQuery(TLRPC.Dialog d, String query) {
        String displayName = dialogDisplayName(d);
        if (displayName != null && displayName.toLowerCase().contains(query)) {
            return true;
        }
        String username = dialogUsername(d);
        return username != null && username.toLowerCase().contains(query);
    }

    private String dialogDisplayName(TLRPC.Dialog d) {
        long did = d.id;
        String displayName = SilentMessageHistory.getDialogName(currentAccount, did);
        TLObject obj = MessagesController.getInstance(currentAccount).getUserOrChat(did);
        if (obj instanceof TLRPC.User) {
            displayName = UserObject.getUserName((TLRPC.User) obj);
        } else if (obj instanceof TLRPC.Chat) {
            displayName = ((TLRPC.Chat) obj).title;
        }
        return displayName;
    }

    private String dialogUsername(TLRPC.Dialog d) {
        TLObject obj = MessagesController.getInstance(currentAccount).getUserOrChat(d.id);
        if (obj instanceof TLRPC.User) {
            return ((TLRPC.User) obj).username;
        } else if (obj instanceof TLRPC.Chat) {
            return ((TLRPC.Chat) obj).username;
        }
        return null;
    }

    private void updateEmpty() {
        if (emptyView == null) {
            return;
        }
        boolean empty = dialogs.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (listView != null) {
            listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private String ruleLabel(int rule) {
        switch (rule) {
            case SilentAutoDownload.RULE_ALWAYS:
                return LocaleController.getString(R.string.SilentAutoDownloadAlways);
            case SilentAutoDownload.RULE_NEVER:
                return LocaleController.getString(R.string.SilentAutoDownloadNever);
            default:
                return LocaleController.getString(R.string.SilentAutoDownloadNormal);
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return dialogs.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            // Telegram-native row: channel/user avatar + name on top, the
            // current rule (Normal / Always / Never) below it.
            UserCell cell = new UserCell(context, 6, 0, false);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TLRPC.Dialog d = dialogs.get(position);
            final long did = d.id;
            final String dialogName = dialogDisplayName(d);
            final int rule = SilentAutoDownload.getRule(did);
            TLObject obj = MessagesController.getInstance(currentAccount).getUserOrChat(did);
            UserCell cell = (UserCell) holder.itemView;
            cell.setData(obj, null, dialogName, ruleLabel(rule), 0, position != dialogs.size() - 1);
            // `did`, `dialogName` and `rule` are final / effectively final,
            // so the lambdas below may capture them (javac: "local variables
            // referenced from a lambda expression must be final or
            // effectively final" — this killed the H3 build at lines 168/171
            // when `name` was reassigned before the lambda).
            cell.setOnClickListener(v -> {
                // Popup with the three rules; the current one is marked
                // with a check icon. Tapping one applies it.
                CharSequence[] items = new CharSequence[]{
                        LocaleController.getString(R.string.SilentAutoDownloadNormal),
                        LocaleController.getString(R.string.SilentAutoDownloadAlways),
                        LocaleController.getString(R.string.SilentAutoDownloadNever)
                };
                int[] icons = new int[3];
                icons[rule] = R.drawable.msg_check_s;
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle(dialogName);
                builder.setItems(items, icons, (dialog, which) -> {
                    SilentAutoDownload.setRule(did, which);
                    SilentToast.show(getParentActivity(), dialogName + ": " + ruleLabel(which), android.widget.Toast.LENGTH_SHORT);
                    notifyDataSetChanged();
                });
                builder.show();
            });
        }
    }
}
