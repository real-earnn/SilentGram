package org.telegram.messenger;

import android.content.Context;
import android.os.Bundle;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

/**
 * SilentGram E9: Multi-account forward.
 *
 * From account A's chat, "Forward to account" -> pick an account -> the app
 * switches to that account -> pick the chat there -> the message is sent
 * from the new account. Text is sent as-is; media is re-sent from the local
 * file if it was already downloaded on this phone, otherwise the caption /
 * text is sent.
 */
public class SilentMultiAccountForward {

    public static class PendingItem {
        public String text;
        public String filePath; // local cached file, if any
        public boolean isPhoto;

        public PendingItem(String text, String filePath, boolean isPhoto) {
            this.text = text;
            this.filePath = filePath;
            this.isPhoto = isPhoto;
        }
    }

    private static final ArrayList<PendingItem> pending = new ArrayList<>();

    public static void startForward(BaseFragment fragment, int currentAccount, ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        pending.clear();
        for (MessageObject msg : messages) {
            String text = msg.messageText != null ? msg.messageText.toString() : "";
            String caption = msg.caption != null ? msg.caption.toString() : "";
            String body = !text.isEmpty() ? text : caption;
            String filePath = null;
            boolean isPhoto = false;
            if (msg.getDocument() != null) {
                java.io.File f = FileLoader.getInstance(currentAccount).getPathToAttach(msg.getDocument(), true);
                if (f != null && f.exists()) {
                    filePath = f.getAbsolutePath();
                }
            } else if (msg.isPhoto()) {
                isPhoto = true;
                TLRPC.Photo photo = msg.messageOwner.media != null ? msg.messageOwner.media.photo : null;
                if (photo != null) {
                    java.io.File f = FileLoader.getInstance(currentAccount).getPathToAttach(msg.getPhoto(), true);
                    if (f != null && f.exists()) {
                        filePath = f.getAbsolutePath();
                    }
                }
            }
            pending.add(new PendingItem(body, filePath, isPhoto));
        }

        // Pick the target account.
        ArrayList<Integer> accounts = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && a != currentAccount) {
                accounts.add(a);
            }
        }
        if (accounts.isEmpty()) {
            android.widget.Toast.makeText(fragment.getParentActivity(), LocaleController.getString(R.string.SilentForwardNoOtherAccount), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Context context = fragment.getParentActivity();
        CharSequence[] names = new CharSequence[accounts.size()];
        for (int i = 0; i < accounts.size(); i++) {
            TLRPC.User u = UserConfig.getInstance(accounts.get(i)).getCurrentUser();
            names[i] = u != null ? UserObject.getUserName(u) : ("Account " + (accounts.get(i) + 1));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentForwardToAccount));
        builder.setItems(names, (d, which) -> {
            int targetAccount = accounts.get(which);
            switchToAccountAndPickChat(fragment, targetAccount);
        });
        builder.show();
    }

    private static void switchToAccountAndPickChat(BaseFragment fragment, int targetAccount) {
        LaunchActivity launchActivity = (LaunchActivity) fragment.getParentActivity();
        if (launchActivity == null) {
            return;
        }
        launchActivity.switchToAccount(targetAccount, true);

        // Open the chat picker for the new account; on pick, send the pending items.
        // SilentGram H19 fix: the picker MUST be a forward picker (onlySelect +
        // DIALOGS_TYPE_FORWARD, exactly like upstream's forward flow). With a plain
        // DialogsActivity, onlySelect is false, so tapping a chat just OPENS it and
        // the delegate is never consulted — the forward never lands anywhere.
        Bundle pickerArgs = new Bundle();
        pickerArgs.putBoolean("onlySelect", true);
        pickerArgs.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        pickerArgs.putBoolean("canSelectTopics", true);
        DialogsActivity dialogsActivity = new DialogsActivity(pickerArgs);
        dialogsActivity.setDelegate(new DialogsActivity.DialogsActivityDelegate() {
            @Override
            public boolean didSelectDialogs(DialogsActivity dlg, ArrayList<MessagesStorage.TopicKey> dids, CharSequence message, boolean param, boolean notify, int scheduleDate, int scheduleRepeatPeriod, org.telegram.ui.TopicsFragment topicsFragment) {
                if (dids == null || dids.isEmpty()) {
                    return false;
                }
                long peer = dids.get(0).dialogId;
                SendMessagesHelper helper = SendMessagesHelper.getInstance(targetAccount);
                for (PendingItem item : pending) {
                    if (item.filePath != null) {
                        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(item.text, peer);
                        params.path = item.filePath;
                        helper.sendMessage(params);
                    } else if (item.text != null && !item.text.isEmpty()) {
                        helper.sendMessage(SendMessagesHelper.SendMessageParams.of(item.text, peer));
                    }
                }
                pending.clear();
                dlg.finishFragment();
                // Open the chat we just sent to.
                Bundle args = new Bundle();
                if (peer > 0) {
                    args.putLong("user_id", peer);
                } else {
                    args.putLong("chat_id", -peer);
                }
                launchActivity.presentFragment(new org.telegram.ui.ChatActivity(args), false, true);
                return true;
            }
        });
        launchActivity.presentFragment(dialogsActivity, false, true);
    }
}
