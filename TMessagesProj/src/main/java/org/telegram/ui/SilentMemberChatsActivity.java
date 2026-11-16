package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * SilentGram: "Show member in my channels & groups".
 *
 * From any user's chat, see which of YOUR channels/groups (where you are
 * creator or admin) this person is a member of — two tabs (Groups /
 * Channels). Tap one chat for Ban / Mute / Remove, or select several and
 * apply the action to all of them at once.
 *
 * Membership comes from Telegram's own common-chats list
 * (messages.getCommonChats), filtered to chats where you can manage members.
 */
public class SilentMemberChatsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private final long userId;
    private final String userName;
    private MessagesController.CommonChatsList commonChats;
    private final ArrayList<TLRPC.Chat> groups = new ArrayList<>();
    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    private final HashSet<Long> selected = new HashSet<>();
    private boolean showChannels = false;
    private boolean selectionMode = false;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextView groupsTab;
    private TextView channelsTab;
    private LinearLayout bottomBar;
    private TextView emptyView;

    public SilentMemberChatsActivity(long userId, String userName) {
        this.userId = userId;
        this.userName = userName;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentMemberChatsTitle));
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

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        frameLayout.addView(layout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Tabs: Groups | Channels.
        LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        layout.addView(tabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        groupsTab = makeTab(context, LocaleController.getString(R.string.SilentMemberChatsGroups), true);
        channelsTab = makeTab(context, LocaleController.getString(R.string.SilentMemberChatsChannels), false);
        groupsTab.setOnClickListener(v -> setTab(false));
        channelsTab.setOnClickListener(v -> setTab(true));
        tabs.addView(groupsTab, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        tabs.addView(channelsTab, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter(context));
        layout.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.SilentMemberChatsEmpty));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        emptyView.setVisibility(View.GONE);
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        // Bottom bar: Ban / Mute / Remove (multi-select actions).
        bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        bottomBar.setVisibility(View.GONE);
        bottomBar.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        layout.addView(bottomBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        addBottomButton(context, LocaleController.getString(R.string.SilentMemberBan), 0xFFD32F2F, () -> applyAction("ban"));
        addBottomButton(context, LocaleController.getString(R.string.SilentMemberMute), 0xFFEF6C00, () -> applyAction("mute"));
        addBottomButton(context, LocaleController.getString(R.string.SilentMemberRemove), 0xFF616161, () -> applyAction("remove"));

        commonChats = MessagesController.getInstance(currentAccount).getCommonChats(userId);
        if (commonChats != null) {
            commonChats.shown = true;
            if (commonChats.chats.isEmpty() && !commonChats.endReached) {
                commonChats.load();
            }
            rebuildLists();
        }
        updateTabs();
        return fragmentView;
    }

    private TextView makeTab(Context context, String text, boolean active) {
        TextView tab = new TextView(context);
        tab.setText(text);
        tab.setTextSize(15);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
        updateTabStyle(tab, active);
        return tab;
    }

    private void updateTabStyle(TextView tab, boolean active) {
        tab.setTextColor(active ? 0xFFFFFFFF : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        tab.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(20), active ? 0xFF6D28D9 : Theme.getColor(Theme.key_windowBackgroundGray)));
    }

    private void setTab(boolean channels) {
        showChannels = channels;
        selectionMode = false;
        selected.clear();
        updateTabs();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateBottomBar();
    }

    private void updateTabs() {
        updateTabStyle(groupsTab, !showChannels);
        updateTabStyle(channelsTab, showChannels);
    }

    private void rebuildLists() {
        groups.clear();
        channels.clear();
        if (commonChats == null) {
            return;
        }
        MessagesController mc = MessagesController.getInstance(currentAccount);
        for (TLRPC.Chat chat : commonChats.chats) {
            if (chat == null || chat.deactivated) {
                continue;
            }
            // Only chats where I am creator or admin (so I can manage members).
            if (!chat.creator && chat.admin_rights == null) {
                continue;
            }
            boolean isChannel = ChatObject.isChannel(chat) && !chat.megagroup;
            if (isChannel) {
                channels.add(chat);
            } else {
                groups.add(chat);
            }
        }
        updateEmpty();
    }

    private void updateEmpty() {
        if (emptyView == null) {
            return;
        }
        boolean empty = (showChannels ? channels : groups).isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (listView != null) {
            listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private void updateBottomBar() {
        if (bottomBar == null) {
            return;
        }
        bottomBar.setVisibility(selectionMode && !selected.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void addBottomButton(Context context, String text, int color, Runnable onClick) {
        TextView btn = new TextView(context);
        btn.setText(text);
        btn.setTextSize(15);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(0xFFFFFFFF);
        btn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), color));
        btn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        btn.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f);
        lp.leftMargin = AndroidUtilities.dp(4);
        lp.rightMargin = AndroidUtilities.dp(4);
        bottomBar.addView(btn, lp);
    }

    /** Apply Ban / Mute / Remove to all selected chats. */
    private void applyAction(String action) {
        if (selected.isEmpty()) {
            return;
        }
        ArrayList<TLRPC.Chat> targets = new ArrayList<>();
        for (TLRPC.Chat c : showChannels ? channels : groups) {
            if (selected.contains(-c.id)) {
                targets.add(c);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        String title = action.equals("ban") ? LocaleController.getString(R.string.SilentMemberBan)
                : action.equals("mute") ? LocaleController.getString(R.string.SilentMemberMute)
                : LocaleController.getString(R.string.SilentMemberRemove);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setMessage(LocaleController.formatString(R.string.SilentMemberConfirm, title, targets.size(), userName));
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
            for (TLRPC.Chat chat : targets) {
                applyToChat(chat, action);
            }
            selected.clear();
            selectionMode = false;
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            updateBottomBar();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    /** Ban / Mute / Remove this user from one chat. */
    private void applyToChat(TLRPC.Chat chat, String action) {
        MessagesController mc = MessagesController.getInstance(currentAccount);
        boolean isChannel = ChatObject.isChannel(chat);
        if (isChannel || chat.megagroup) {
            TLRPC.TL_channels_editBanned req = new TLRPC.TL_channels_editBanned();
            req.channel = mc.getInputChannel(-chat.id);
            req.participant = mc.getInputUser(userId);
            TLRPC.TL_chatBannedRights rights = new TLRPC.TL_chatBannedRights();
            if (action.equals("ban")) {
                rights.view_messages = true;
                req.until_date = 0; // permanent ban
            } else if (action.equals("mute")) {
                rights.send_messages = true;
                req.until_date = 0; // permanent mute
            } else { // remove (kick): 1-minute ban = removed, can rejoin after
                rights.view_messages = true;
                req.until_date = (int) (System.currentTimeMillis() / 1000) + 60;
            }
            req.banned_rights = rights;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
                if (err != null) {
                    AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showToast(getParentActivity(), err.text));
                }
            });
        } else {
            // Basic group: only remove is possible.
            TLRPC.TL_messages_deleteChatUser req = new TLRPC.TL_messages_deleteChatUser();
            req.chat_id = chat.id;
            req.user_id = mc.getInputUser(userId);
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
                if (err != null) {
                    AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showToast(getParentActivity(), err.text));
                }
            });
        }
    }

    /** Single-chat action sheet (tap a row without selection). */
    private void showSingleActions(TLRPC.Chat chat) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(chat.title);
        CharSequence[] items = new CharSequence[]{
                LocaleController.getString(R.string.SilentMemberBan),
                LocaleController.getString(R.string.SilentMemberMute),
                LocaleController.getString(R.string.SilentMemberRemove)
        };
        builder.setItems(items, (d, which) -> {
            String action = which == 0 ? "ban" : which == 1 ? "mute" : "remove";
            applyToChat(chat, action);
        });
        builder.show();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (commonChats != null) {
            commonChats.shown = false;
        }
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.commonChatsLoaded);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.commonChatsLoaded) {
            long dialogId = (Long) args[0];
            if (dialogId == userId) {
                rebuildLists();
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
                updateEmpty();
            }
        }
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.commonChatsLoaded);
        return true;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return (showChannels ? channels : groups).size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextCell cell = new TextCell(context);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TLRPC.Chat chat = (showChannels ? channels : groups).get(position);
            TextCell cell = (TextCell) holder.itemView;
            cell.setTextAndValue(chat.title, selected.contains(-chat.id) ? "✓" : "", false);
            cell.setOnClickListener(v -> {
                if (selectionMode) {
                    toggleSelect(chat);
                } else {
                    showSingleActions(chat);
                }
            });
            cell.setOnLongClickListener(v -> {
                if (!selectionMode) {
                    selectionMode = true;
                }
                toggleSelect(chat);
                return true;
            });
        }

        private void toggleSelect(TLRPC.Chat chat) {
            long key = -chat.id;
            if (selected.contains(key)) {
                selected.remove(key);
            } else {
                selected.add(key);
            }
            if (selected.isEmpty()) {
                selectionMode = false;
            }
            notifyDataSetChanged();
            updateBottomBar();
        }
    }
}
