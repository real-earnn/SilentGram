package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentMessageHistory;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
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
 * SilentGram E6: Download Manager.
 * Lists every file currently downloading (from DownloadController), with a
 * "Boost" action per file — tapping it raises that file to the highest
 * download priority so it finishes first (IDM-style boost).
 */
public class SilentDownloadManagerActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ArrayList<MessageObject> files = new ArrayList<>();
    private TextView emptyView;

    @Override
    public boolean onFragmentCreate() {
        // SilentGram K4: refresh live while open — a finished/failed
        // download disappears and a newly started one appears, instead
        // of the list only refreshing when the screen is re-opened.
        getNotificationCenter().addObserver(this, NotificationCenter.onDownloadingFilesChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.onDownloadingFilesChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.onDownloadingFilesChanged) {
            reload();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentDownloadManagerTitle));
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
        listView.setAdapter(adapter = new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.SilentDownloadManagerEmpty));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        reload();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        files.clear();
        files.addAll(DownloadController.getInstance(currentAccount).downloadingFiles);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmpty();
    }

    private void updateEmpty() {
        if (emptyView == null) {
            return;
        }
        boolean empty = files.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (listView != null) {
            listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    /** Boost one file: highest priority + force it to the front of the queue. */
    public static void boostFile(int account, MessageObject msg) {
        if (msg == null || msg.getDocument() == null) {
            return;
        }
        FileLoader.getInstance(account).changePriority(FileLoader.PRIORITY_HIGH, msg.getDocument(), null, null, null, null, null);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            // Telegram-native row: channel/user avatar + name on top, file
            // name below — so each download shows which chat it came from.
            UserCell cell = new UserCell(context, 6, 0, false);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            MessageObject msg = files.get(position);
            UserCell cell = (UserCell) holder.itemView;
            String name = msg.getFileName();
            if (name == null || name.isEmpty()) {
                name = LocaleController.getString(R.string.SilentDownloadUnknown);
            }
            long did = msg.getDialogId();
            TLObject obj = did != 0 ? MessagesController.getInstance(currentAccount).getUserOrChat(did) : null;
            String chatName;
            if (obj instanceof TLRPC.User) {
                chatName = UserObject.getUserName((TLRPC.User) obj);
            } else if (obj instanceof TLRPC.Chat) {
                chatName = ((TLRPC.Chat) obj).title;
            } else if (did != 0) {
                chatName = SilentMessageHistory.getDialogName(currentAccount, did);
            } else {
                chatName = LocaleController.getString(R.string.SilentDownloadUnknown);
            }
            cell.setData(obj, null, chatName, name + (msg.getDocument() != null ? "  •  " + AndroidUtilities.formatFileSize(msg.getDocument().size) : ""), 0, position != files.size() - 1);
            cell.setOnClickListener(v -> {
                boostFile(currentAccount, msg);
                android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.SilentDownloadBoosted), android.widget.Toast.LENGTH_SHORT).show();
                reload();
            });
        }
    }
}
