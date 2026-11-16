package org.telegram.messenger;

import android.content.Context;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatRightsEditActivity;
import org.telegram.ui.Components.AlertsCreator;

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
 *
 * The menu is Telegram's own bottom-sheet menu (BottomSheet.Builder with
 * icon rows), opened from the floating button on the right edge of a
 * 1-1 chat, or from the header arrow in MY channel/group chats.
 */
public class SilentQuickActions {

    /**
     * VIP panel for a 1-1 chat: Telegram's own bottom-sheet menu, icon on
     * the left + label per row. Each row calls the same handler the old
     * plain list used (refactored into the methods below).
     */
    public static void showVipPanel(BaseFragment fragment, int account, long dialogId, long targetUserId) {
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
                LocaleController.getString(R.string.SilentQuickPrivateNote),
                LocaleController.getString(R.string.SilentRemindMe)
        };
        int[] icons = new int[]{
                R.drawable.msg_bot,
                R.drawable.msg_bots,
                R.drawable.msg_forward,
                R.drawable.msg_forward,
                R.drawable.msg_expand,
                R.drawable.msg_delete_auto,
                R.drawable.msg_saved,
                R.drawable.msg_recent
        };
        BottomSheet.Builder builder = new BottomSheet.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentQuickActions));
        builder.setItems(items, icons, (dialog, which) -> {
            switch (which) {
                case 0:
                    addAdminToChannels(fragment, account, targetUserId, dialogId);
                    break;
                case 1:
                    addAdminToGroups(fragment, account, targetUserId, dialogId);
                    break;
                case 2:
                    shareChannel(fragment, account, targetUserId, dialogId);
                    break;
                case 3:
                    shareGroup(fragment, account, targetUserId, dialogId);
                    break;
                case 4:
                    showMemberChats(fragment, targetUserId);
                    break;
                case 5:
                    showAutoDeletePicker(fragment, account, dialogId);
                    break;
                case 6:
                    showPrivateNote(fragment, targetUserId);
                    break;
                case 7: {
                    TLRPC.User user = MessagesController.getInstance(account).getUser(targetUserId);
                    String name = user != null ? UserObject.getUserName(user) : "";
                    remindMeChat(fragment, account, dialogId, name);
                    break;
                }
            }
        });
        builder.show();
    }

    /** Old entry point — now opens the VIP panel (same actions). */
    public static void showMenu(BaseFragment fragment, int account, long dialogId, long targetUserId) {
        showVipPanel(fragment, account, dialogId, targetUserId);
    }

    /** "Add Admin to Your Channels" — pick one of MY channels, open its admin page. */
    public static void addAdminToChannels(BaseFragment fragment, int account, long targetUserId, long dialogId) {
        pickChat(fragment, account, true, false, targetUserId, dialogId);
    }

    /** "Add Admin to Your Groups" — pick one of MY groups, open its admin page. */
    public static void addAdminToGroups(BaseFragment fragment, int account, long targetUserId, long dialogId) {
        pickChat(fragment, account, false, false, targetUserId, dialogId);
    }

    /** "Share Your Channel" — pick one of MY channels and send its link here. */
    public static void shareChannel(BaseFragment fragment, int account, long targetUserId, long dialogId) {
        pickChat(fragment, account, true, true, targetUserId, dialogId);
    }

    /** "Share Your Group" — pick one of MY groups and send its link here. */
    public static void shareGroup(BaseFragment fragment, int account, long targetUserId, long dialogId) {
        pickChat(fragment, account, false, true, targetUserId, dialogId);
    }

    /** "Show Member in My Channels & Groups" — which of MY chats is this person in? */
    public static void showMemberChats(BaseFragment fragment, long targetUserId) {
        if (fragment == null) {
            return;
        }
        TLRPC.User user = MessagesController.getInstance(fragment.getCurrentAccount()).getUser(targetUserId);
        String name = user != null ? UserObject.getUserName(user) : "";
        fragment.presentFragment(new org.telegram.ui.SilentMemberChatsActivity(targetUserId, name));
    }

    /** "Private Notes" — private note for this person. */
    public static void showPrivateNote(BaseFragment fragment, long targetUserId) {
        if (fragment == null) {
            return;
        }
        TLRPC.User user = MessagesController.getInstance(fragment.getCurrentAccount()).getUser(targetUserId);
        String name = user != null ? UserObject.getUserName(user) : "";
        fragment.presentFragment(new org.telegram.ui.SilentNoteEditorActivity(targetUserId, name));
    }

    /**
     * "Remind Me" for this chat (no message involved): pick ANY date and
     * time in Telegram's own date/time picker, then a reminder fires for
     * this chat at that time (same AlarmManager + SilentReminderReceiver
     * mechanism as message reminders).
     */
    public static void remindMeChat(BaseFragment fragment, int account, long dialogId, String chatName) {
        Context context = fragment == null ? null : fragment.getParentActivity();
        if (context == null || dialogId == 0) {
            return;
        }
        AlertsCreator.createScheduleDatePickerDialog(context,
                LocaleController.getString(R.string.SilentRemindMe), dialogId, -1, true,
                (notify, scheduleDate, scheduleRepeatPeriod) -> {
                    long when = (long) scheduleDate * 1000L;
                    SilentReminders.addReminder(context, dialogId, 0, chatName, chatName, when);
                    String whenText = android.text.format.DateFormat.format("d MMM, HH:mm", when).toString();
                    android.widget.Toast.makeText(context, whenText, android.widget.Toast.LENGTH_SHORT).show();
                }, null);
    }

    /** Auto-delete timer quick switch for a chat. */
    public static void showAutoDeletePicker(BaseFragment fragment, int account, long dialogId) {
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
            android.widget.Toast.makeText(context, items[which], android.widget.Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    /**
     * SilentGram E3: Administration menu for MY channel/group chats.
     * Admin list, Member list, Invite link (copy public link, or copy /
     * create a new private link).
     *
     * NOTE: chatId is the POSITIVE chat id (MessagesController.getChat and
     * ChatUsersActivity both key chats by the positive id). Callers must
     * pass chat.id, not the (negative) dialog id.
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
                LocaleController.getString(R.string.SilentCreateNewLink)
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
            }
        });
        builder.show();
    }

    private static void copyLink(Context context, String link) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", link));
        android.widget.Toast.makeText(context, LocaleController.getString(R.string.TextCopied), android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Export the invite link; revoke=true creates a brand-new private link. */
    private static void exportInviteLink(BaseFragment fragment, int account, long chatId, boolean revoke) {
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.TL_messages_exportChatInvite req = new TLRPC.TL_messages_exportChatInvite();
        // getInputPeer takes a DIALOG id (negative for chats).
        req.peer = mc.getInputPeer(-chatId);
        req.legacy_revoke_permanent = revoke;
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            AndroidUtilities.runOnUIThread(() -> {
                if (err != null) {
                    android.widget.Toast.makeText(fragment.getParentActivity(), err.text, android.widget.Toast.LENGTH_SHORT).show();
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
            // Basic group = not a channel at all; megagroup = supergroup.
            boolean isGroup = chat.megagroup || !ChatObject.isChannel(chat);
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
            android.widget.Toast.makeText(fragment.getParentActivity(), LocaleController.getString(channels ? R.string.SilentQuickNoChannels : R.string.SilentQuickNoGroups), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Telegram-style picker: avatar + name rows, Telegram theme.
        String title = LocaleController.getString(channels ? R.string.SilentQuickPickChannel : R.string.SilentQuickPickGroup);
        fragment.presentFragment(new org.telegram.ui.SilentChatPickerActivity(fragment, mine, title, share, targetUserId, dialogId));
    }

    /** Open the permissions screen to make targetUserId an admin of chat. */
    public static void openAdminScreen(BaseFragment fragment, int account, TLRPC.Chat chat, long targetUserId) {
        MessagesController mc = MessagesController.getInstance(account);
        // NOTE: getChatFull / loadFullChat / ChatRightsEditActivity all take
        // the POSITIVE chat id (fullChats is keyed by it; every upstream
        // caller passes chat.id or -dialogId). Passing -chat.id here used
        // to make getChatFull always return null, so the rights screen
        // never opened.
        TLRPC.ChatFull full = mc.getChatFull(chat.id);
        if (full == null) {
            // Full info not cached yet — load it, then the user taps again.
            mc.loadFullChat(chat.id, 0, true);
            android.widget.Toast.makeText(fragment.getParentActivity(), LocaleController.getString(R.string.SilentQuickLoading), android.widget.Toast.LENGTH_SHORT).show();
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
        // default_banned_rights lives on the Chat itself (base TLRPC.Chat),
        // not on ChatFull — ChatObject reads it the same way.
        TLRPC.TL_chatBannedRights defaultBanned = new TLRPC.TL_chatBannedRights();
        if (chat.default_banned_rights != null) {
            defaultBanned = chat.default_banned_rights;
        }
        ChatRightsEditActivity rightsFragment = new ChatRightsEditActivity(
                targetUserId, chat.id, adminRights, defaultBanned, null, "",
                ChatRightsEditActivity.TYPE_ADMIN, true, true, null);
        fragment.presentFragment(rightsFragment);
    }

    /** Send this channel/group's link to the current chat. */
    public static void shareChat(BaseFragment fragment, int account, TLRPC.Chat chat, long dialogId) {
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
                AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(fragment.getParentActivity(), LocaleController.getString(R.string.SilentQuickShareFailed), android.widget.Toast.LENGTH_SHORT).show());
            }
        });
    }
}
