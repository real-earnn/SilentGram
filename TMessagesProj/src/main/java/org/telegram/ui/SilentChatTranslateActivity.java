package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentTranslate;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
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
 * SilentGram F1: Whole chat translate.
 * Loads the recent messages of a chat, translates them all into the chosen
 * language (free Google endpoint, same as single-message Translate), and
 * shows original + translation side by side.
 */
public class SilentChatTranslateActivity extends BaseFragment {

    private static class TRow {
        String sender;
        String original;
        String translated;
        boolean translating;
    }

    private final long dialogId;
    private final String chatName;
    private RecyclerListView listView;
    private ListAdapter adapter;
    private ArrayList<TRow> rows = new ArrayList<>();
    private TextView emptyView;
    private String targetLang = "en";
    private String targetLangName = "English";
    private boolean started = false;

    public SilentChatTranslateActivity(long dialogId, String chatName) {
        this.dialogId = dialogId;
        this.chatName = chatName;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentTranslateChatTitle));
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
        emptyView.setText(LocaleController.getString(R.string.SilentTranslateChatEmpty));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        pickLanguageThenLoad();
        return fragmentView;
    }

    private void pickLanguageThenLoad() {
        String[][] langs = SilentTranslate.getLanguages();
        CharSequence[] names = new CharSequence[langs.length];
        for (int i = 0; i < langs.length; i++) {
            names[i] = langs[i][1];
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.SilentTranslateTo));
        builder.setItems(names, (d, which) -> {
            targetLang = langs[which][0];
            targetLangName = langs[which][1];
            loadMessages();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> finishFragment());
        builder.show();
    }

    private void loadMessages() {
        final int account = currentAccount;
        MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
            ArrayList<TRow> result = new ArrayList<>();
            try {
                SQLiteCursor cursor = MessagesStorage.getInstance(account).getDatabase()
                        .queryFinalized("SELECT data, out FROM messages_v2 WHERE uid = " + dialogId + " ORDER BY date DESC, mid DESC LIMIT 100");
                while (cursor.next()) {
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    int out = cursor.intValue(1);
                    String text = "";
                    String sender = "";
                    if (data != null) {
                        try {
                            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                            if (message != null) {
                                message.readAttachPath(data, UserConfig.getInstance(account).clientUserId);
                                MessageObject obj = new MessageObject(account, message, false, false);
                                if (obj.messageText != null) {
                                    text = obj.messageText.toString();
                                }
                                if (obj.isOutOwner()) {
                                    sender = "You";
                                } else {
                                    long fromId = DialogObject.getPeerDialogId(message.from_id);
                                    if (fromId > 0) {
                                        TLRPC.User u = MessagesController.getInstance(account).getUser(fromId);
                                        sender = u != null ? UserObject.getUserName(u) : "";
                                    }
                                }
                                if (sender.isEmpty()) {
                                    sender = out == 1 ? "You" : chatName;
                                }
                            }
                        } catch (Exception e) {
                            // Skip unreadable rows.
                        } finally {
                            data.reuse();
                        }
                    }
                    if (!text.isEmpty()) {
                        TRow row = new TRow();
                        row.sender = sender;
                        row.original = text;
                        result.add(row);
                    }
                }
                cursor.dispose();
            } catch (Exception e) {
                // Empty on failure.
            }
            // Oldest first (chronological).
            java.util.Collections.reverse(result);
            final ArrayList<TRow> finalRows = result;
            AndroidUtilities.runOnUIThread(() -> {
                rows.clear();
                rows.addAll(finalRows);
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
                updateEmpty();
                if (!started && !rows.isEmpty()) {
                    started = true;
                    translateNext(0);
                }
            });
        });
    }

    /** Translate one message at a time (sequential, to be gentle on the free endpoint). */
    private void translateNext(int index) {
        if (index >= rows.size()) {
            return;
        }
        TRow row = rows.get(index);
        row.translating = true;
        if (adapter != null) {
            adapter.notifyItemChanged(index);
        }
        SilentTranslate.translateText(row.original, "auto", targetLang, translated -> {
            AndroidUtilities.runOnUIThread(() -> {
                row.translating = false;
                row.translated = translated;
                if (adapter != null) {
                    adapter.notifyItemChanged(index);
                }
                translateNext(index + 1);
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
            TextCell cell = new TextCell(context);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TRow row = rows.get(position);
            TextCell cell = (TextCell) holder.itemView;
            String value;
            if (row.translated != null && !row.translated.isEmpty()) {
                value = row.translated;
            } else if (row.translating) {
                value = "…";
            } else {
                value = LocaleController.getString(R.string.SilentTranslatePending);
            }
            cell.setTextAndValue(row.sender + ": " + row.original, value, true);
        }
    }
}
