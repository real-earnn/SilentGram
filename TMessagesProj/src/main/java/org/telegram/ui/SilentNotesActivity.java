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
import org.telegram.messenger.SilentNotes;
import org.telegram.messenger.UserObject;
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * SilentGram F9: Notes & Reminders — every private note, reminders first
 * (soonest on top). Tap a note to edit it; long-press to delete.
 */
public class SilentNotesActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;
    private List<SilentNotes.Note> notes;
    private TextView emptyView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentNotesTitle));
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
        emptyView.setText(LocaleController.getString(R.string.SilentNotesEmpty));
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
        notes = SilentNotes.getNotes();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmpty();
    }

    private void updateEmpty() {
        if (emptyView == null) {
            return;
        }
        boolean empty = notes == null || notes.isEmpty();
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
            return notes == null ? 0 : notes.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            // Telegram-native row: the chat's avatar + name on top, reminder
            // time + note preview below it.
            UserCell cell = new UserCell(context, 6, 0, false);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            SilentNotes.Note n = notes.get(position);
            UserCell cell = (UserCell) holder.itemView;
            // Notes are stored per person (user id); resolve the user so the
            // row shows their real avatar. Falls back to the stored name
            // (UserCell draws a letter avatar when the object is null).
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(n.userId);
            String name = user != null ? UserObject.getUserName(user) : n.chatName;
            String sub = n.text.length() > 70 ? n.text.substring(0, 70) + "…" : n.text;
            if (n.remindAt > 0) {
                String when = new SimpleDateFormat("dd MMM, HH:mm", Locale.US).format(new Date(n.remindAt));
                sub = when + " • " + sub;
            }
            cell.setData(user, null, name, sub, 0, position != notes.size() - 1);
            cell.setOnClickListener(v -> {
                presentFragment(new SilentNoteEditorActivity(n.userId, n.chatName));
            });
            cell.setOnLongClickListener(v -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle(n.chatName);
                builder.setMessage(LocaleController.getString(R.string.SilentNoteDeleteConfirm));
                builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                    SilentNotes.deleteNote(n.userId);
                    reload();
                });
                builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
                builder.show();
                return true;
            });
        }
    }
}
