package org.telegram.messenger;

import android.content.Context;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatRightsEditActivity;

import java.util.ArrayList;

/**
 * SilentGram quick actions — from inside any user's chat, one tap to:
 *   • Make this person an admin of one of YOUR channels / groups
 *     (opens the permissions screen for them in that channel/group).
 *   • Share one of YOUR channels / groups with this person.
 *
 * Making someone an admin normally means hunting through the channel,
 * finding the person and opening the rights screen — this skips all of it.
 * Only channels/groups where you are creator or admin are listed.
 */
public class SilentQuickActions {

    public static void showMenu(BaseFragment fragment, int account, long dialogId, long targetUserId) {
        if (fragment == null || fragment.getParentActivity() == null || targetUserId == 0) {
            return;
        }
        Context context = fragment.getParentActivity();
        CharSequence[] items = new CharSequence[]{
                LocaleController.getString(R.string.SilentQuickAdminChannels),
                LocaleController.getString(R.string.SilentQuickAdminGroups),
                LocaleController.getString(R.string.SilentQuickShareChannel),
                LocaleController.getString(R.string.SilentQuickShareGroup),
                LocaleController.getString(R.string.SilentQuickMemberChats),
                LocaleController.getString(R.string.SilentQuickAutoDelete),
                LocaleController.getString(R.string.SilentQuickHistory)
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentQuickActions));
        builder.setItems(items, (dialog, which) -> {
            switch (which) {
                case 0:
                    pickChat(fragment, account, true, false, targetUserId, dialogId);
                    break;
                case 1:
                    pickChat(fragment, account, false, false, targetUserId, dialogId);
                    break;
                case 2:
                    pickChat(fragment, account, true, true, targetUserId, dialogId);
                    break;
                case 3:
                    pickChat(fragment, account, false, true, targetUserId, dialogId);
                    break;
                case 4: {
                    // SilentGram: which of MY channels/groups is this person in?
                    TLRPC.User user = MessagesController.getInstance(account).getUser(targetUserId);
                    String name = user != null ? UserObject.getUserName(user) : "";
                    fragment.presentFragment(new org.telegram.ui.SilentMemberChatsActivity(targetUserId, name));
                    break;
                }
                case 5: {
                    // SilentGram: auto-delete timer for this chat (1m / 1h / 24h / Off).
                    showAutoDeletePicker(fragment, account, dialogId);
                    break;
                }
                case 6: {
                    // SilentGram E7: Deleted & Edited History for this chat.
                    TLRPC.User user = MessagesController.getInstance(account).getUser(targetUserId);
                    String name = user != null ? UserObject.getUserName(user) : "";
                    fragment.presentFragment(new org.telegram.ui.SilentHistoryActivity(dialogId, name));
                    break;
                }
            }
        });
        builder.show();
    }

    /** Auto-delete timer quick switch for a chat. */
    private static void showAutoDeletePicker(BaseFragment fragment, int account, long dialogId) {
        Context context = fragment.getParentActivity();
        CharSequence[] items = new CharSequence[]{
                LocaleController.getString(R.string.SilentAutoDelete1m),
                LocaleController.getString(R.string.SilentAutoDelete1h),
                LocaleController.getString(R.string.SilentAutoDelete24h),
                LocaleController.getString(R.string.SilentAutoDeleteOff)
        };
        int[] ttls = new int[]{60, 3600, 86400, 0};
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentQuickAutoDelete));
        builder.setItems(items, (dialog, which) -> {
            MessagesController.getInstance(account).setDialogHistoryTTL(dialogId, ttls[which]);
            AndroidUtilities.showToast(context, items[which]);
        });
        builder.show();
    }

    /**
     * SilentGram E3: Administration menu for MY channel/group chats.
     * Admin list, Member list, Invite link (copy public link, or copy /
     * create a new private link).
     */
    public static void showChannelAdminMenu(BaseFragment fragment, int account, long chatId) {
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.Chat chat = mc.getChat(chatId);
        if (chat == null) {
            return;
        }
        boolean isPublic = chat.username != null && !chat.username.isEmpty();
        Context context = fragment.getParentActivity();
        CharSequence[] items = new CharSequence[]{
                LocaleController.getString(R.string.SilentAdminList),
                LocaleController.getString(R.string.SilentMemberList),
                isPublic ? LocaleController.getString(R.string.SilentCopyPublicLink)
                        : LocaleController.getString(R.string.SilentCopyPrivateLink),
                LocaleController.getString(R.string.SilentCreateNewLink),
                LocaleController.getString(R.string.SilentQuickHistory)
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentAdministration));
        builder.setItems(items, (dialog, which) -> {
            android.os.Bundle args = new android.os.Bundle();
            args.putLong("chat_id", chatId);
            switch (which) {
                case 0:
                    args.putInt("type", org.telegram.ui.ChatUsersActivity.TYPE_ADMIN);
                    fragment.presentFragment(new org.telegram.ui.ChatUsersActivity(args));
                    break;
                case 1:
                    args.putInt("type", org.telegram.ui.ChatUsersActivity.TYPE_USERS);
                    fragment.presentFragment(new org.telegram.ui.ChatUsersActivity(args));
                    break;
                case 2:
                    if (isPublic) {
                        copyLink(context, "https://t.me/" + chat.username);
                    } else {
                        exportInviteLink(fragment, account, chatId, false);
                    }
                    break;
                case 3:
                    exportInviteLink(fragment, account, chatId, true);
                    break;
                case 4: {
                    // SilentGram E7: Deleted & Edited History for this chat.
                    String name = chat.title == null ? "" : chat.title;
                    fragment.presentFragment(new org.telegram.ui.SilentHistoryActivity(chatId, name));
                    break;
                }
            }
        });
        builder.show();
    }

    private static void copyLink(Context context, String link) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", link));
        AndroidUtilities.showToast(context, LocaleController.getString(R.string.Copied));
    }

    /** Export the invite link; revoke=true creates a brand-new private link. */
    private static void exportInviteLink(BaseFragment fragment, int account, long chatId, boolean revoke) {
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.TL_messages_exportChatInvite req = new TLRPC.TL_messages_exportChatInvite();
        req.peer = mc.getInputPeer(chatId);
        req.legacy_revoke_permanent = revoke;
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            AndroidUtilities.runOnUIThread(() -> {
                if (err != null) {
                    AndroidUtilities.showToast(fragment.getParentActivity(), err.text);
                    return;
                }
                if (res instanceof TLRPC.TL_chatInviteExported) {
                    copyLink(fragment.getParentActivity(), ((TLRPC.TL_chatInviteExported) res).link);
                }
            });
        });
    }

    /** List my channels (or groups) where I can add admins, then act on it. */
    private static void pickChat(BaseFragment fragment, int account, boolean channels, boolean share, long targetUserId, long dialogId) {
        MessagesController mc = MessagesController.getInstance(account);
        ArrayList<TLRPC.Chat> mine = new ArrayList<>();
        for (int i = 0; i < mc.dialogs_dict.size(); i++) {
            TLRPC.Dialog d = mc.dialogs_dict.valueAt(i);
            if (d == null || d.id >= 0) {
                continue;
            }
            TLRPC.Chat chat = mc.getChat(-d.id);
            if (chat == null || chat.deactivated) {
                continue;
            }
            boolean isChannel = ChatObject.isChannel(chat) && !chat.megagroup;
            boolean isGroup = chat.megagroup || ChatObject.isChat(chat);
            if (channels && !isChannel) {
                continue;
            }
            if (!channels && !isGroup) {
                continue;
            }
            // Only chats where I am creator or admin (so I can add admins).
            if (!chat.creator && chat.admin_rights == null) {
                continue;
            }
            mine.add(chat);
        }
        if (mine.isEmpty()) {
            AndroidUtilities.showToast(fragment.getParentActivity(), LocaleController.getString(channels ? R.string.SilentQuickNoChannels : R.string.SilentQuickNoGroups));
            return;
        }
        CharSequence[] names = new CharSequence[mine.size()];
        for (int i = 0; i < mine.size(); i++) {
            names[i] = mine.get(i).title;
        }
        Context context = fragment.getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(channels ? R.string.SilentQuickPickChannel : R.string.SilentQuickPickGroup));
        builder.setItems(names, (dialog, which) -> {
            TLRPC.Chat chat = mine.get(which);
            if (share) {
                shareChat(fragment, account, chat, dialogId);
            } else {
                openAdminScreen(fragment, account, chat, targetUserId);
            }
        });
        builder.show();
    }

    /** Open the permissions screen to make targetUserId an admin of chat. */
    private static void openAdminScreen(BaseFragment fragment, int account, TLRPC.Chat chat, long targetUserId) {
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.ChatFull full = mc.getChatFull(-chat.id);
        if (full == null) {
            // Full info not cached yet — load it, then the user taps again.
            mc.loadFullChat(-chat.id, 0, true);
            AndroidUtilities.showToast(fragment.getParentActivity(), LocaleController.getString(R.string.SilentQuickLoading));
            return;
        }
        TLRPC.TL_chatAdminRights adminRights = new TLRPC.TL_chatAdminRights();
        adminRights.change_info = true;
        adminRights.post_messages = true;
        adminRights.edit_messages = true;
        adminRights.delete_messages = true;
        adminRights.ban_users = true;
        adminRights.invite_users = true;
        adminRights.pin_messages = true;
        adminRights.add_admins = true;
        adminRights.anonymous = false;
        adminRights.manage_call = true;
        adminRights.other = true;
        adminRights.manage_topics = true;
        adminRights.post_stories = true;
        adminRights.edit_stories = true;
        adminRights.delete_stories = true;
        TLRPC.TL_chatBannedRights defaultBanned = full.default_banned_rights != null
                ? full.default_banned_rights : new TLRPC.TL_chatBannedRights();
        ChatRightsEditActivity rightsFragment = new ChatRightsEditActivity(
                targetUserId, -chat.id, adminRights, defaultBanned, null, "",
                ChatRightsEditActivity.TYPE_ADMIN, true, true, null);
        fragment.presentFragment(rightsFragment);
    }

    /** Send this channel/group's link to the current chat. */
    private static void shareChat(BaseFragment fragment, int account, TLRPC.Chat chat, long dialogId) {
        String username = ChatObject.getPublicUsername(chat);
        if (username != null && !username.isEmpty()) {
            String link = "https://t.me/" + username;
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(link, dialogId, null, null, null, true, null, null, null, true, 0, 0, null, false);
            SendMessagesHelper.getInstance(account).sendMessage(params);
            return;
        }
        // Private chat — export an invite link first, then send it.
        TLRPC.TL_messages_exportChatInvite req = new TLRPC.TL_messages_exportChatInvite();
        req.peer = MessagesController.getInstance(account).getInputPeer(-chat.id);
        MessagesController.getInstance(account).getConnectionsManager().sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.TL_chatInviteExported) {
                String link = ((TLRPC.TL_chatInviteExported) response).link;
                AndroidUtilities.runOnUIThread(() -> {
                    SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(link, dialogId, null, null, null, true, null, null, null, true, 0, 0, null, false);
                    SendMessagesHelper.getInstance(account).sendMessage(params);
                });
            } else {
                AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showToast(fragment.getParentActivity(), LocaleController.getString(R.string.SilentQuickShareFailed)));
            }
        });
    }
}
