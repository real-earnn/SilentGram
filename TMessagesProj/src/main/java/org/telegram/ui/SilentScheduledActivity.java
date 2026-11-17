package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentMessageHistory;
import org.telegram.messenger.UserConfig;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
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
 * SilentGram F5: Scheduled Messages — every scheduled message from every
 * chat, in one place, ordered by send time. Tap one to open that chat's
 * scheduled view.
 */
public class SilentScheduledActivity extends BaseFragment {

    private static class SchedRow {
        long dialogId;
        String chatName;
        String text;
        long date; // seconds
    }

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ArrayList<SchedRow> rows = new ArrayList<>();
    private TextView emptyView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentScheduledTitle));
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
        emptyView.setText(LocaleController.getString(R.string.SilentScheduledEmpty));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        loadRows();
        return fragmentView;
    }

    private void loadRows() {
        final int account = currentAccount;
        MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
            ArrayList<SchedRow> result = new ArrayList<>();
            try {
                SQLiteCursor cursor = MessagesStorage.getInstance(account).getDatabase()
                        .queryFinalized("SELECT uid, mid, date, data FROM scheduled_messages_v2 ORDER BY date ASC");
                while (cursor.next()) {
                    long uid = cursor.longValue(0);
                    long date = cursor.longValue(2);
                    NativeByteBuffer data = cursor.byteBufferValue(3);
                    String text = "";
                    if (data != null) {
                        try {
                            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                            if (message != null) {
                                message.readAttachPath(data, UserConfig.getInstance(account).clientUserId);
                                MessageObject obj = new MessageObject(account, message, false, false);
                                if (obj.messageText != null) {
                                    text = obj.messageText.toString();
                                }
                            }
                        } catch (Exception e) {
                            // Skip unreadable rows.
                        } finally {
                            data.reuse();
                        }
                    }
                    SchedRow row = new SchedRow();
                    row.dialogId = uid;
                    row.date = date;
                    row.text = text;
                    result.add(row);
                }
                cursor.dispose();
            } catch (Exception e) {
                // Empty on failure.
            }
            final ArrayList<SchedRow> finalRows = result;
            AndroidUtilities.runOnUIThread(() -> {
                rows.clear();
                rows.addAll(finalRows);
                // Resolve chat names on the UI thread.
                for (SchedRow r : rows) {
                    r.chatName = SilentMessageHistory.getDialogName(account, r.dialogId);
                }
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
                updateEmpty();
            });
        });
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
            // Telegram-native row: channel/user avatar + name on top, send
            // time + message preview below it.
            UserCell cell = new UserCell(context, 6, 0, false);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            SchedRow row = rows.get(position);
            UserCell cell = (UserCell) holder.itemView;
            String time = LocaleController.getInstance().getFormatterDayMonth().format(new java.util.Date(row.date * 1000))
                    + " " + LocaleController.getInstance().getFormatterDay().format(new java.util.Date(row.date * 1000));
            String preview = row.text == null ? "" : (row.text.length() > 70 ? row.text.substring(0, 70) + "…" : row.text);
            TLObject obj = MessagesController.getInstance(currentAccount).getUserOrChat(row.dialogId);
            cell.setData(obj, null, row.chatName, time + " • " + preview, 0, position != rows.size() - 1);
            cell.setOnClickListener(v -> {
                android.os.Bundle args = new android.os.Bundle();
                if (row.dialogId > 0) {
                    args.putLong("user_id", row.dialogId);
                } else {
                    args.putLong("chat_id", -row.dialogId);
                }
                args.putInt("chatMode", ChatActivity.MODE_SCHEDULED);
                presentFragment(new ChatActivity(args));
            });
        }
    }
}
