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
 * switches to that account -> pick the chat there -> the post is forwarded
 * from the new account.
 *
 * Link-based (Batch V): each post keeps its exact t.me link (Telegram's
 * own Copy Link). In the target account the live post is resolved from
 * that link and forwarded with Telegram's exact forward and the sender
 * hidden — the rich post keeps its design (no re-made copy, no lost
 * premium emoji, no wrong-size file). A post from a private channel the
 * target account has not joined cannot be fetched there: the user is
 * told so and nothing is sent. Posts with no link (a normal user's DM)
 * fall back to Telegram's own copy: text with full entities (premium
 * emoji included) and, when already downloaded, the local file sent
 * as-is — the other account cannot fetch a DM post by link.
 */
public class SilentMultiAccountForward {

    public static class PendingItem {
        /** Exact t.me post link ("" when the post has none, e.g. a DM). */
        public String link;
        public long sourceDialogId;
        public int sourceMessageId;
        /** Plain text / caption (fallback + preview). */
        public String text;
        /** Base64 entities for the fallback text (premium emoji, ...). */
        public String entities;
        /** Local cached file for the fallback, if already downloaded. */
        public String filePath;
        public boolean isPhoto;
        /** Server file references (Telegram's own send-by-reference) for the
         * DM fallback — the other account cannot fetch a DM post by link, so
         * media must travel as the original document/photo, not as text. */
        public TLRPC.TL_document document;
        public TLRPC.TL_photo photo;

        public PendingItem(String link, long sourceDialogId, int sourceMessageId, String text, String entities, String filePath, boolean isPhoto, TLRPC.TL_document document, TLRPC.TL_photo photo) {
            this.link = link == null ? "" : link;
            this.sourceDialogId = sourceDialogId;
            this.sourceMessageId = sourceMessageId;
            this.text = text;
            this.entities = entities;
            this.filePath = filePath;
            this.isPhoto = isPhoto;
            this.document = document;
            this.photo = photo;
        }
    }

    private static final ArrayList<PendingItem> pending = new ArrayList<>();

    public static void startForward(BaseFragment fragment, int currentAccount, ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        pending.clear();
        // Build the items (sync parts) and export each post's exact link
        // (Telegram's own Copy Link). The export is async, so the account
        // picker opens once every link has settled.
        final PendingItem[] items = new PendingItem[messages.size()];
        final int[] remaining = new int[]{messages.size()};
        for (int i = 0; i < messages.size(); i++) {
            MessageObject msg = messages.get(i);
            String text = msg.messageText != null ? msg.messageText.toString() : "";
            String caption = msg.caption != null ? msg.caption.toString() : "";
            String body = !text.isEmpty() ? text : caption;
            String entities = SilentTemplates.entitiesToBase64(msg.messageOwner.entities);
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
            final int index = i;
            final long sourceDialogId = msg.getDialogId();
            final int sourceMessageId = msg.getId();
            final String bodyFinal = body;
            final String entitiesFinal = entities;
            final String filePathFinal = filePath;
            final boolean isPhotoFinal = isPhoto;
            TLRPC.Document doc = msg.getDocument();
            final TLRPC.TL_document documentFinal = doc instanceof TLRPC.TL_document ? (TLRPC.TL_document) doc : null;
            TLRPC.TL_photo photoFinal = null;
            if (msg.isPhoto() && msg.messageOwner.media != null && msg.messageOwner.media.photo instanceof TLRPC.TL_photo) {
                photoFinal = (TLRPC.TL_photo) msg.messageOwner.media.photo;
            }
            final TLRPC.TL_photo photoFinalRef = photoFinal;
            SilentTemplates.exportMessageLink(currentAccount, msg, link -> {
                items[index] = new PendingItem(link, sourceDialogId, sourceMessageId, bodyFinal, entitiesFinal, filePathFinal, isPhotoFinal, documentFinal, photoFinalRef);
                if (--remaining[0] == 0) {
                    if (fragment.getParentActivity() == null) {
                        return;
                    }
                    for (PendingItem item : items) {
                        pending.add(item);
                    }
                    showAccountPicker(fragment, currentAccount);
                }
            });
        }
    }

    private static void showAccountPicker(BaseFragment fragment, int currentAccount) {
        // Pick the target account.
        ArrayList<Integer> accounts = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && a != currentAccount) {
                accounts.add(a);
            }
        }
        if (accounts.isEmpty()) {
            SilentToast.show(fragment.getParentActivity(), LocaleController.getString(R.string.SilentForwardNoOtherAccount), android.widget.Toast.LENGTH_SHORT);
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

        // Open the chat picker for the new account; on pick, forward the pending items.
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
                sendNextItem(0, peer, dlg, launchActivity, targetAccount);
                return true;
            }
        });
        launchActivity.presentFragment(dialogsActivity, false, true);
    }

    /** Send pending items one by one (link resolves are async). */
    private static void sendNextItem(int index, long peer, DialogsActivity dlg, LaunchActivity launchActivity, int targetAccount) {
        if (index >= pending.size()) {
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
            return;
        }
        PendingItem item = pending.get(index);
        if (item.link != null && !item.link.isEmpty()) {
            SilentTemplates.forwardPostFromLink(targetAccount, item.link, peer, new SilentTemplates.PostResolveCallback() {
                @Override
                public void onPost(MessageObject post) {
                    sendNextItem(index + 1, peer, dlg, launchActivity, targetAccount);
                }

                @Override
                public void onError(int reason) {
                    if (reason == SilentTemplates.RESOLVE_PRIVATE) {
                        SilentToast.show(launchActivity, "Private channel posts cannot be forwarded to the other account", android.widget.Toast.LENGTH_SHORT);
                    } else {
                        SilentToast.show(launchActivity, "Post is not available", android.widget.Toast.LENGTH_SHORT);
                    }
                    sendNextItem(index + 1, peer, dlg, launchActivity, targetAccount);
                }
            });
            return;
        }
        // No link (a normal user's message): Telegram's own copy — full
        // entities (premium emoji included) and the media sent as the
        // ORIGINAL document/photo (server file reference; the local file
        // is used when already downloaded). The other account cannot
        // fetch a DM post by link. NOTE: a bare params.path on a text
        // message is IGNORED by sendMessage — the media branches only
        // trigger when document/photo is set, so the file must travel
        // through the document/photo overloads below.
        SendMessagesHelper helper = SendMessagesHelper.getInstance(targetAccount);
        ArrayList<TLRPC.MessageEntity> entities = SilentTemplates.entitiesFromBase64(item.entities);
        String caption = item.text != null ? item.text : "";
        if (item.document != null) {
            helper.sendMessage(SendMessagesHelper.SendMessageParams.of(
                    item.document, null, item.filePath, peer, null, null,
                    caption, entities, null, null, true, 0, 0, 0, null, null, false, false));
        } else if (item.photo != null) {
            helper.sendMessage(SendMessagesHelper.SendMessageParams.of(
                    item.photo, item.filePath, peer, null, null,
                    caption, entities, null, null, true, 0, 0, 0, null, false, false));
        } else {
            helper.sendMessage(SendMessagesHelper.SendMessageParams.of(
                    caption, peer, null, null, null, false, entities, null, null, true, 0, 0, null, false));
        }
        sendNextItem(index + 1, peer, dlg, launchActivity, targetAccount);
    }
}
