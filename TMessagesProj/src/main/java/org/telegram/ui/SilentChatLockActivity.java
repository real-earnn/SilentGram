package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentChatLock;
import org.telegram.messenger.SilentMessageHistory;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Set;

/**
 * SilentGram F4 / H16: Chat Lock manager.
 *
 * The manager NEVER opens without the password:
 *  - no PIN stored yet -> first open is a set-password-twice flow;
 *  - PIN stored -> a PIN entry screen (fingerprint if enabled) every time.
 * After unlocking, the list starts from the stored locked set (empty when
 * nothing is locked yet) with a + button to add any chat. Tapping a locked
 * chat opens it in place (usable without taking it out of the lock);
 * long-press removes it from the lock. A settings gear at the top changes
 * the 6-digit PIN and toggles unlock-with-fingerprint.
 */
public class SilentChatLockActivity extends BaseFragment {

    private static final int MENU_SETTINGS = 1;

    private static final int MODE_SET_PIN = 0;
    private static final int MODE_VERIFY = 1;
    private static final int MODE_LIST = 2;

    private int mode = MODE_VERIFY;
    private int pinStep = 1;
    private String firstPin;
    private boolean fingerprintTried;

    private FrameLayout rootView;
    private ActionBarMenuItem gearItem;
    private RecyclerListView listView;
    private ListAdapter adapter;
    private ArrayList<Long> locked = new ArrayList<>();
    private TextView emptyView;
    private FragmentFloatingButton fab;
    private AlertDialog settingsDialog;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentChatLockTitle));
        gearItem = actionBar.createMenu().addItem(MENU_SETTINGS, R.drawable.msg_settings_old);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SETTINGS && mode == MODE_LIST) {
                    showSettingsDialog();
                }
            }
        });

        rootView = new FrameLayout(context);
        rootView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = rootView;

        mode = SilentChatLock.hasPin() ? MODE_VERIFY : MODE_SET_PIN;
        showCurrentScreen();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // A chat may have been taken out of the lock from its own 3-dot
        // menu while it was open — refresh the list when coming back.
        if (mode == MODE_LIST) {
            reload();
        }
    }

    private void showCurrentScreen() {
        if (rootView == null) {
            return;
        }
        rootView.removeAllViews();
        listView = null;
        emptyView = null;
        fab = null;
        if (gearItem != null) {
            gearItem.setVisibility(mode == MODE_LIST ? View.VISIBLE : View.GONE);
        }
        if (mode == MODE_LIST) {
            buildListScreen();
        } else {
            buildAuthScreen();
            // Try fingerprint right away if enabled (same as ChatActivity).
            if (mode == MODE_VERIFY && SilentChatLock.isFingerprintEnabled() && !fingerprintTried) {
                fingerprintTried = true;
                AndroidUtilities.runOnUIThread(this::tryFingerprint, 500);
            }
        }
    }

    // ---------- auth screens (set PIN twice / enter PIN) ----------

    private void buildAuthScreen() {
        Context context = rootView.getContext();
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        rootView.addView(layout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        TextView title = new TextView(context);
        if (mode == MODE_SET_PIN) {
            title.setText(LocaleController.getString(pinStep == 1 ? R.string.SilentChatLockCreatePin : R.string.SilentChatLockConfirmPin));
        } else {
            title.setText(LocaleController.getString(R.string.SilentChatLockEnterPin));
        }
        title.setTextSize(20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER);
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        TextView hint = new TextView(context);
        if (mode == MODE_SET_PIN) {
            hint.setText(LocaleController.getString(R.string.SilentChatLockSetPinHint));
        } else {
            hint.setText(LocaleController.getString(R.string.SilentChatLockVerifyHint));
        }
        hint.setTextSize(15);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        hint.setGravity(Gravity.CENTER);
        layout.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        final EditText pinField = new EditText(context);
        pinField.setHint(LocaleController.getString(R.string.SilentChatLockPinHint));
        pinField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinField.setSingleLine(true);
        pinField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        pinField.setTextSize(20);
        pinField.setGravity(Gravity.CENTER);
        pinField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        pinField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        layout.addView(pinField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));
        pinField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() == 6) {
                    submitPin(s.toString());
                }
            }
        });

        TextView actionBtn = new TextView(context);
        if (mode == MODE_SET_PIN) {
            actionBtn.setText(LocaleController.getString(pinStep == 1 ? R.string.Continue : R.string.Save));
        } else {
            actionBtn.setText(LocaleController.getString(R.string.SilentChatLockUnlock));
        }
        actionBtn.setTextSize(16);
        actionBtn.setTypeface(AndroidUtilities.bold());
        actionBtn.setGravity(Gravity.CENTER);
        actionBtn.setTextColor(0xFFFFFFFF);
        actionBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), 0xFF6D28D9));
        actionBtn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        actionBtn.setOnClickListener(v -> submitPin(pinField.getText().toString().trim()));
        layout.addView(actionBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        if (mode == MODE_VERIFY && SilentChatLock.isFingerprintEnabled()) {
            TextView fpBtn = new TextView(context);
            fpBtn.setText(LocaleController.getString(R.string.SilentChatLockUseFingerprint));
            fpBtn.setTextSize(15);
            fpBtn.setGravity(Gravity.CENTER);
            fpBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            fpBtn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
            fpBtn.setOnClickListener(v -> tryFingerprint());
            layout.addView(fpBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
    }

    private void submitPin(String pin) {
        if (mode == MODE_SET_PIN) {
            if (pin.length() != 6) {
                toast(LocaleController.getString(R.string.SilentChatLockPinMustBe6));
                return;
            }
            if (pinStep == 1) {
                firstPin = pin;
                pinStep = 2;
                showCurrentScreen();
            } else if (pin.equals(firstPin)) {
                SilentChatLock.setPin(pin);
                toast(LocaleController.getString(R.string.SilentChatLockPinSaved));
                firstPin = null;
                pinStep = 1;
                mode = MODE_LIST;
                showCurrentScreen();
            } else {
                toast(LocaleController.getString(R.string.SilentChatLockPinsDontMatch));
                firstPin = null;
                pinStep = 1;
                showCurrentScreen();
            }
        } else if (mode == MODE_VERIFY) {
            if (SilentChatLock.checkPin(pin)) {
                mode = MODE_LIST;
                showCurrentScreen();
            } else {
                toast(LocaleController.getString(R.string.SilentChatLockWrongPin));
                showCurrentScreen();
            }
        }
    }

    private void onAuthenticated() {
        if (mode == MODE_VERIFY) {
            mode = MODE_LIST;
            showCurrentScreen();
        }
    }

    private void tryFingerprint() {
        try {
            android.app.Activity activity = AndroidUtilities.findActivity(getParentActivity());
            if (activity == null) {
                return;
            }
            if (androidx.biometric.BiometricManager.from(activity).canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
                return;
            }
            java.util.concurrent.Executor executor = androidx.core.content.ContextCompat.getMainExecutor(activity);
            androidx.biometric.BiometricPrompt prompt = new androidx.biometric.BiometricPrompt((androidx.fragment.app.FragmentActivity) activity, executor,
                    new androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(androidx.biometric.BiometricPrompt.AuthenticationResult result) {
                            onAuthenticated();
                        }
                    });
            androidx.biometric.BiometricPrompt.PromptInfo info = new androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                    .setTitle(LocaleController.getString(R.string.SilentChatLockTitle))
                    .setNegativeButtonText(LocaleController.getString(R.string.SilentChatLockUsePin))
                    .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build();
            prompt.authenticate(info);
        } catch (Exception e) {
            // Fingerprint unavailable — PIN still works.
        }
    }

    // ---------- locked-chat list (shown only after unlocking) ----------

    private void buildListScreen() {
        Context context = rootView.getContext();

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemLongClickListener((view, position) -> {
            if (position >= 0 && position < locked.size()) {
                confirmRemove(locked.get(position));
                return true;
            }
            return false;
        });
        rootView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.SilentChatLockEmpty));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        rootView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        fab = new FragmentFloatingButton(context, getResourceProvider());
        fab.setImageResource(R.drawable.iv_media_add);
        fab.setOnClickListener(v -> showAddChatPicker());
        rootView.addView(fab, FragmentFloatingButton.createDefaultLayoutParams());

        reload();
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
        emptyView.setVisibility(locked.isEmpty() ? View.VISIBLE : View.GONE);
        if (listView != null) {
            listView.setVisibility(View.VISIBLE);
        }
    }

    private String getDialogName(long did) {
        return SilentMessageHistory.getDialogName(currentAccount, did);
    }

    /** Open the locked chat in place — usable without taking it out of the lock. */
    private void openLockedChat(long did) {
        Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(did)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(did));
        } else {
            if (DialogObject.isUserDialog(did)) {
                args.putLong("user_id", did);
            } else {
                args.putLong("chat_id", -did);
            }
            if (!getMessagesController().checkCanOpenChat(args, this)) {
                return;
            }
        }
        SilentChatLock.markUnlocked(did);
        presentFragment(new ChatActivity(args));
    }

    private void confirmRemove(long did) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getDialogName(did));
        builder.setMessage(LocaleController.getString(R.string.SilentChatLockRemoveConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.Remove), (d, w) -> {
            SilentChatLock.setLocked(did, false);
            toast(LocaleController.getString(R.string.SilentChatLockRemoved));
            reload();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    /** + button: pick any unlocked chat and add it to the lock. */
    private void showAddChatPicker() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        Set<Long> lockedSet = SilentChatLock.getLockedChats();
        ArrayList<Long> ids = new ArrayList<>();
        androidx.collection.LongSparseArray<TLRPC.Dialog> dict = MessagesController.getInstance(currentAccount).dialogs_dict;
        for (int i = 0; i < dict.size(); i++) {
            TLRPC.Dialog dialog = dict.valueAt(i);
            if (dialog == null || dialog.isFolder) {
                continue;
            }
            long id = dialog.id;
            if (id == 0 || DialogObject.isFolderDialogId(id) || lockedSet.contains(id)) {
                continue;
            }
            ids.add(id);
        }
        if (ids.isEmpty()) {
            toast(LocaleController.getString(R.string.SilentChatLockNoChatsToAdd));
            return;
        }
        ids.sort((a, b) -> getDialogName(a).compareToIgnoreCase(getDialogName(b)));
        CharSequence[] names = new CharSequence[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            names[i] = getDialogName(ids.get(i));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentChatLockAddChat));
        builder.setItems(names, (d, which) -> {
            SilentChatLock.setLocked(ids.get(which), true);
            toast(LocaleController.getString(R.string.SilentChatLockAdded));
            reload();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    /** Gear at the top of the list: change PIN + fingerprint toggle. */
    private void showSettingsDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextCell pinCell = new TextCell(context);
        pinCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        pinCell.setTextAndValue(LocaleController.getString(R.string.SilentChatLockChangePin), "••••••", true);
        pinCell.setOnClickListener(v -> {
            if (settingsDialog != null) {
                settingsDialog.dismiss();
            }
            showSetPinDialog();
        });
        layout.addView(pinCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextCheckCell fpCell = new TextCheckCell(context);
        fpCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fpCell.setTextAndCheck(LocaleController.getString(R.string.SilentChatLockFingerprint), SilentChatLock.isFingerprintEnabled(), true);
        fpCell.setOnClickListener(v -> {
            boolean next = !SilentChatLock.isFingerprintEnabled();
            SilentChatLock.setFingerprintEnabled(next);
            fpCell.setChecked(next);
        });
        layout.addView(fpCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentChatLockSettings));
        builder.setView(layout);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        settingsDialog = builder.show();
    }

    /** Change PIN: enter the new 6-digit PIN twice (already authenticated). */
    private void showSetPinDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        final EditText pinField1 = new EditText(context);
        pinField1.setHint(LocaleController.getString(R.string.SilentChatLockPinHint));
        pinField1.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinField1.setSingleLine(true);
        pinField1.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        layout.addView(pinField1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final EditText pinField2 = new EditText(context);
        pinField2.setHint(LocaleController.getString(R.string.SilentChatLockConfirmPin));
        pinField2.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinField2.setSingleLine(true);
        pinField2.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        layout.addView(pinField2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentChatLockChangePin));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
            String a = pinField1.getText().toString().trim();
            String b = pinField2.getText().toString().trim();
            if (a.length() != 6 || b.length() != 6) {
                toast(LocaleController.getString(R.string.SilentChatLockPinMustBe6));
            } else if (!a.equals(b)) {
                toast(LocaleController.getString(R.string.SilentChatLockPinsDontMatch));
            } else {
                SilentChatLock.setPin(a);
                toast(LocaleController.getString(R.string.SilentChatLockPinSaved));
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private void toast(String text) {
        Context context = getParentActivity();
        if (context == null && rootView != null) {
            context = rootView.getContext();
        }
        if (context != null) {
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return locked.size();
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
            long did = locked.get(position);
            TextCell cell = (TextCell) holder.itemView;
            cell.setTextAndValue(getDialogName(did), LocaleController.getString(R.string.SilentChatLockTapToOpen), true);
            cell.setOnClickListener(v -> openLockedChat(did));
        }
    }
}
