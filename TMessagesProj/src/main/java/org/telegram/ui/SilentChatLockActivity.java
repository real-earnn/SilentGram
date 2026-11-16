package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentChatLock;
import org.telegram.messenger.SilentMessageHistory;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Set;

/**
 * SilentGram F4: Chat Lock manager.
 * Lists locked chats (tap to unlock/remove), sets the 6-digit PIN, and
 * toggles fingerprint unlock.
 */
public class SilentChatLockActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ArrayList<Long> locked = new ArrayList<>();
    private TextView emptyView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentChatLockTitle));
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
        emptyView.setText(LocaleController.getString(R.string.SilentChatLockEmpty));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        reload();
        return fragmentView;
    }

    private void reload() {
        Set<Long> set = SilentChatLock.getLockedChats();
        locked.clear();
        locked.addAll(set);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmpty();
    }

    private void updateEmpty() {
        if (emptyView == null) {
            return;
        }
        // Rows: PIN row + fingerprint row + locked chats.
        boolean empty = locked.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (listView != null) {
            listView.setVisibility(View.VISIBLE);
        }
    }

    private void showPinDialog() {
        Context context = getParentActivity();
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        final EditText pinField = new EditText(context);
        pinField.setHint(LocaleController.getString(R.string.SilentChatLockPinHint));
        pinField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinField.setSingleLine(true);
        layout.addView(pinField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(SilentChatLock.hasPin() ? LocaleController.getString(R.string.SilentChatLockChangePin) : LocaleController.getString(R.string.SilentChatLockSetPin));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
            String pin = pinField.getText().toString().trim();
            if (pin.length() == 6) {
                SilentChatLock.setPin(pin);
                AndroidUtilities.showToast(context, LocaleController.getString(R.string.SilentChatLockPinSaved));
                reload();
            } else {
                AndroidUtilities.showToast(context, LocaleController.getString(R.string.SilentChatLockPinMustBe6));
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return 2 + locked.size(); // PIN row + fingerprint row + chats
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemViewType(int position) {
            return position == 1 ? 1 : 0;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == 1) {
                TextCheckCell cell = new TextCheckCell(context);
                cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                return new RecyclerListView.Holder(cell);
            }
            TextCell cell = new TextCell(context);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position == 0) {
                TextCell cell = (TextCell) holder.itemView;
                cell.setTextAndValue(
                        SilentChatLock.hasPin() ? LocaleController.getString(R.string.SilentChatLockChangePin) : LocaleController.getString(R.string.SilentChatLockSetPin),
                        SilentChatLock.hasPin() ? "••••••" : LocaleController.getString(R.string.SilentChatLockNoPin),
                        true);
                cell.setOnClickListener(v -> showPinDialog());
            } else if (position == 1) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setTextAndCheck(LocaleController.getString(R.string.SilentChatLockFingerprint), SilentChatLock.isFingerprintEnabled(), true);
                cell.setOnClickListener(v -> {
                    boolean next = !SilentChatLock.isFingerprintEnabled();
                    SilentChatLock.setFingerprintEnabled(next);
                    cell.setChecked(next);
                });
            } else {
                long did = locked.get(position - 2);
                TextCell cell = (TextCell) holder.itemView;
                cell.setTextAndValue(SilentMessageHistory.getDialogName(currentAccount, did), LocaleController.getString(R.string.SilentChatLockTapToUnlock), true);
                cell.setOnClickListener(v -> {
                    SilentChatLock.setLocked(did, false);
                    reload();
                });
            }
        }
    }
}
