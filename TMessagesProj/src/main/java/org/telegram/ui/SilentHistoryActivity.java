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
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * SilentGram E7: Deleted & Edited History viewer for one chat.
 * Lists every deleted / edited message we captured, newest first.
 * Tap a row to read the full old text.
 */
public class SilentHistoryActivity extends BaseFragment {

    private final long dialogId;
    private final String chatName;
    private RecyclerListView listView;
    private ListAdapter adapter;
    private List<SilentMessageHistory.Entry> entries;
    private TextView emptyView;

    public SilentHistoryActivity(long dialogId, String chatName) {
        this.dialogId = dialogId;
        this.chatName = chatName;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentHistoryTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                    builder.setTitle(LocaleController.getString(R.string.SilentHistoryClear));
                    builder.setMessage(LocaleController.getString(R.string.SilentHistoryClearConfirm));
                    builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                        SilentMessageHistory.clearHistory(dialogId);
                        reload();
                    });
                    builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
                    builder.show();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(1, R.drawable.msg_delete);

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.SilentHistoryEmpty));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        reload();
        return fragmentView;
    }

    private void reload() {
        entries = SilentMessageHistory.getHistory(dialogId);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmpty();
    }

    private void updateEmpty() {
        if (emptyView == null) {
            return;
        }
        boolean empty = entries == null || entries.isEmpty();
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
            return entries == null ? 0 : entries.size();
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
            SilentMessageHistory.Entry e = entries.get(position);
            TextCell cell = (TextCell) holder.itemView;
            String tag = e.kind.equals("deleted")
                    ? LocaleController.getString(R.string.SilentHistoryDeleted)
                    : LocaleController.getString(R.string.SilentHistoryEdited);
            String sender = (e.sender == null || e.sender.isEmpty()) ? "" : e.sender + ": ";
            String preview = e.text.length() > 80 ? e.text.substring(0, 80) + "…" : e.text;
            cell.setTextAndValue("[" + tag + "] " + sender + preview, "", true);
            cell.setOnClickListener(v -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle("[" + tag + "] " + (e.sender == null ? "" : e.sender));
                builder.setMessage(e.text);
                builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
                builder.show();
            });
        }
    }
}
