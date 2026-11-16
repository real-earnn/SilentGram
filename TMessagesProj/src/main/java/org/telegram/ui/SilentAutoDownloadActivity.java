package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentAutoDownload;
import org.telegram.messenger.SilentMessageHistory;
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

/**
 * SilentGram F2: Auto-download rules.
 * Lists your recent chats. Tap one to cycle its rule:
 * Normal (Telegram's own settings) -> Always (media always auto-downloads)
 * -> Never (media never auto-downloads, saves data & storage).
 */
public class SilentAutoDownloadActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
    private TextView emptyView;

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

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

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
        dialogs.clear();
        MessagesController mc = MessagesController.getInstance(currentAccount);
        if (mc.dialogs != null) {
            for (TLRPC.Dialog d : mc.dialogs) {
                if (d != null && d.id != 0) {
                    dialogs.add(d);
                }
            }
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
            TextCell cell = new TextCell(context);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TLRPC.Dialog d = dialogs.get(position);
            long did = d.id;
            String name = SilentMessageHistory.getDialogName(currentAccount, did);
            int rule = SilentAutoDownload.getRule(did);
            TextCell cell = (TextCell) holder.itemView;
            cell.setTextAndValue(name, ruleLabel(rule), true);
            cell.setOnClickListener(v -> {
                int next = (rule + 1) % 3;
                SilentAutoDownload.setRule(did, next);
                AndroidUtilities.showToast(getParentActivity(), name + ": " + ruleLabel(next));
                notifyDataSetChanged();
            });
        }
    }
}
