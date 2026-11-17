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
import org.telegram.messenger.SilentToast;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * SilentGram: "Show Member in My Groups".
 *
 * From any user's chat, see which of YOUR groups (where you are creator
 * or admin) this person is a member of. Tap one group for Ban / Mute /
 * Remove, or select several and apply the action to all of them at once.
 * Channels are intentionally NOT listed here (H7).
 *
 * Membership comes from Telegram's own common-chats list
 * (messages.getCommonChats), filtered to groups where you can manage members.
 */
public class SilentMemberChatsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private final long userId;
    private final String userName;
    private MessagesController.CommonChatsList commonChats;
    private final ArrayList<TLRPC.Chat> groups = new ArrayList<>();
    private final HashSet<Long> selected = new HashSet<>();
    private boolean selectionMode = false;

    private RecyclerListView listView;
    private ListAdapter adapter;
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
            // getCommonChats pages (first 15, then 30 per page) — keep
            // loading until every page is in, so groups on later pages
            // show up too and not just the first page's groups.
            if (!commonChats.endReached) {
                commonChats.load();
            }
        }
        return fragmentView;
    }

    private void rebuildLists() {
        groups.clear();
        if (commonChats == null) {
            return;
        }
        MessagesController mc = MessagesController.getInstance(currentAccount);
        for (TLRPC.Chat listChat : commonChats.chats) {
            if (listChat == null || listChat.deactivated) {
                continue;
            }
            // Resolve through the local cache: the list objects come straight
            // from the server and may not carry creator/admin_rights, while
            // the cached Chat (merged from dialogs/full syncs) does.
            TLRPC.Chat chat = mc.getChat(listChat.id);
            if (chat == null) {
                chat = listChat;
            }
            if (chat.deactivated) {
                continue;
            }
            // Groups only (H7): broadcast channels are never listed here.
            // Megagroups and basic groups both count as groups.
            if (ChatObject.isChannelAndNotMegaGroup(chat)) {
                continue;
            }
            // Only chats where I am creator or admin (so I can manage members).
            if (!chat.creator && chat.admin_rights == null) {
                continue;
            }
            groups.add(chat);
        }
        updateEmpty();
    }

    private void updateEmpty() {
        if (emptyView == null) {
            return;
        }
        boolean empty = groups.isEmpty();
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
        for (TLRPC.Chat c : groups) {
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
            // participant is an InputPeer (not InputUser).
            req.participant = MessagesController.getInputPeer(mc.getUser(userId));
            TLRPC.TL_chatBannedRights rights = new TLRPC.TL_chatBannedRights();
            if (action.equals("ban")) {
                rights.view_messages = true;
                rights.until_date = 0; // permanent ban
            } else if (action.equals("mute")) {
                rights.send_messages = true;
                rights.until_date = 0; // permanent mute
            } else { // remove (kick): 1-minute ban = removed, can rejoin after
                rights.view_messages = true;
                rights.until_date = (int) (System.currentTimeMillis() / 1000) + 60;
            }
            req.banned_rights = rights;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
                if (err != null) {
                    AndroidUtilities.runOnUIThread(() -> SilentToast.show(getParentActivity(), err.text, android.widget.Toast.LENGTH_SHORT).show());
                }
            });
        } else {
            // Basic group: only remove is possible.
            TLRPC.TL_messages_deleteChatUser req = new TLRPC.TL_messages_deleteChatUser();
            req.chat_id = chat.id;
            req.user_id = mc.getInputUser(userId);
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
                if (err != null) {
                    AndroidUtilities.runOnUIThread(() -> SilentToast.show(getParentActivity(), err.text, android.widget.Toast.LENGTH_SHORT).show());
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
                // Keep paging until the server says there is no more —
                // otherwise only the first page of common chats ever shows.
                if (commonChats != null && !commonChats.endReached) {
                    commonChats.load();
                }
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
            return groups.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            // Telegram-style row: avatar on the left + name (UserCell with
            // a round checkbox for the multi-select).
            UserCell cell = new UserCell(context, 6, 1, false);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TLRPC.Chat chat = groups.get(position);
            UserCell cell = (UserCell) holder.itemView;
            cell.setData(chat, null, chat.title, "", 0, position != groups.size() - 1);
            cell.setChecked(selected.contains(-chat.id), false);
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
