package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentMessageHistory;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * SilentGram F7: Recent Deleted Chats.
 *
 * When someone deletes a chat (or its messages) to destroy the evidence,
 * the messages we already saw are still here. This lists every chat with
 * deleted messages — tap one to read the whole captured chat again.
 */
public class SilentDeletedChatsActivity extends BaseFragment {

    private static class ChatRow {
        long dialogId;
        String name;
        int deletedCount;
        int editedCount;
    }

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ArrayList<ChatRow> rows = new ArrayList<>();
    private TextView emptyView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentDeletedChatsTitle));
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
        emptyView.setText(LocaleController.getString(R.string.SilentDeletedChatsEmpty));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        reload();
        return fragmentView;
    }

    private void reload() {
        rows.clear();
        List<Long> dialogs = SilentMessageHistory.getDialogsWithHistory(currentAccount);
        for (long did : dialogs) {
            List<SilentMessageHistory.Entry> entries = SilentMessageHistory.getHistory(currentAccount, did);
            int del = 0, ed = 0;
            for (SilentMessageHistory.Entry e : entries) {
                if (e.kind.equals("deleted")) {
                    del++;
                } else {
                    ed++;
                }
            }
            if (del == 0 && ed == 0) {
                continue;
            }
            ChatRow row = new ChatRow();
            row.dialogId = did;
            row.name = SilentMessageHistory.getDialogName(currentAccount, did);
            row.deletedCount = del;
            row.editedCount = ed;
            rows.add(row);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmpty();
    }

    private void updateEmpty() {
        if (emptyView == null) {
            return;
        }
        boolean empty = rows.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (listView != null) {
            listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
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
            ChatRow row = rows.get(position);
            TextCell cell = (TextCell) holder.itemView;
            String sub = row.deletedCount + " " + LocaleController.getString(R.string.SilentHistoryDeleted)
                    + (row.editedCount > 0 ? " • " + row.editedCount + " " + LocaleController.getString(R.string.SilentHistoryEdited) : "");
            cell.setTextAndValue(row.name, sub, true);
            cell.setOnClickListener(v -> {
                // Open the full captured chat for this dialog.
                presentFragment(new SilentHistoryActivity(row.dialogId, row.name));
            });
        }
    }
}
