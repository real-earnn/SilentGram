package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.SilentQuickActions;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

/**
 * SilentGram G7: Telegram-style channel/group picker.
 *
 * The same avatar + name rows as Telegram's own chat lists (UserCell in a
 * RecyclerListView, Telegram theme). Used by SilentQuickActions for
 * "Add Admin to..." and "Share..." — tap a chat and the EXACT Telegram
 * admin page opens for the target user (ChatRightsEditActivity), or the
 * channel/group link is shared, exactly as before.
 */
public class SilentChatPickerActivity extends BaseFragment {

    private final ArrayList<TLRPC.Chat> chats;
    private final String title;
    private final boolean share;
    private final long targetUserId;
    private final long dialogId;
    private final BaseFragment opener;

    private RecyclerListView listView;

    public SilentChatPickerActivity(BaseFragment opener, ArrayList<TLRPC.Chat> chats, String title, boolean share, long targetUserId, long dialogId) {
        this.opener = opener;
        this.chats = chats;
        this.title = title;
        this.share = share;
        this.targetUserId = targetUserId;
        this.dialogId = dialogId;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setTitle(title);
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

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return chats.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            UserCell cell = new UserCell(context, 6, 0, false);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TLRPC.Chat chat = chats.get(position);
            UserCell cell = (UserCell) holder.itemView;
            String status = chat.username != null && !chat.username.isEmpty() ? "@" + chat.username : "";
            cell.setData(chat, null, chat.title, status, 0, position != chats.size() - 1);
            cell.setOnClickListener(v -> {
                // Pop the picker first, then run the action from the chat
                // that opened it, so the admin screen lands on top.
                BaseFragment target = opener != null ? opener : SilentChatPickerActivity.this;
                finishFragment();
                if (share) {
                    SilentQuickActions.shareChat(target, currentAccount, chat, dialogId);
                } else {
                    SilentQuickActions.openAdminScreen(target, currentAccount, chat, targetUserId, dialogId);
                }
            });
        }
    }
}
