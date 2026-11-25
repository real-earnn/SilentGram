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
 * 1-1 chat, or — in MY channel/group chats — from the same floating
 * button, which opens the Administration menu instead.
 */
public class SilentQuickActions {

    // SilentGram Batch U: "Removed Members" label for the quick-actions
    // pill. Literal (not R.string) until a matching entry lands in
    // strings.xml — see the Batch U report; the behaviour is identical
    // either way.
    private static final String REMOVED_MEMBERS_TITLE = "Removed Members";

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
                LocaleController.getString(R.string.SilentQuickOpenBubble),
                LocaleController.getString(R.string.SilentQuickPrivateNote),
                LocaleController.getString(R.string.SilentRemindMe)
        };
        int[] icons = new int[]{
                R.drawable.msg_bot,
                R.drawable.msg_bots,
                R.drawable.msg_link,
                R.drawable.msg_link,
                R.drawable.msg_expand,
                R.drawable.msg_delete_auto,
                R.drawable.menu_stream_pip,
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
                    openInBubbleChat(fragment, account, dialogId);
                    break;
                case 7:
                    showPrivateNote(fragment, targetUserId);
                    break;
                case 8: {
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

    /**
     * "Open in Bubble Chat" (v2.0.37) — Telegram's OWN bubble, nothing of
     * ours in between. Posts the bubble for this dialog (autoExpand OFF:
     * the bubble icon appears on the side of the screen; tapping it opens
     * the chat in the pop-up, tapping again collapses it — the system's
     * own bubble behaviour) and then finishes the activity, so SilentGram
     * closes and only the bubble icon remains. On failure the error is
     * shown and the app stays in the chat. Broadcast channels are refused
     * by Telegram's own code (showBubbleForDialog: "channels cannot
     * bubble") — the row still appears, the tap just shows the error.
     * Future messages in this chat become bubbles through Telegram's own
     * message-notification path (same dialog channel + same shortcut id).
     */
    public static void openInBubbleChat(BaseFragment fragment, int account, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null || dialogId == 0) {
            return;
        }
        SilentFloatingWindow fw = SilentFloatingWindow.getInstance();
        if (!fw.openBubble(fragment.getParentActivity(), dialogId)) {
            String error = fw.getLastStartError();
            if (error != null) {
                SilentToast.show(fragment.getParentActivity(), error, android.widget.Toast.LENGTH_LONG);
            }
            return;
        }
        // Bubble posted — close the app so only the bubble icon remains.
        fragment.getParentActivity().finish();
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

    /** "Show Member in My Groups" — which of MY groups is this person in? */
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
                    SilentToast.show(context, whenText, android.widget.Toast.LENGTH_SHORT);
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
            SilentToast.show(context, items[which], android.widget.Toast.LENGTH_SHORT);
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
        // K16: the row list is built conditionally, so the click handler
        // switches on an action code carried alongside each row — never
        // on the row index, which shifts when a row is hidden.
        ArrayList<CharSequence> itemList = new ArrayList<>();
        ArrayList<Integer> actionList = new ArrayList<>();
        itemList.add(LocaleController.getString(R.string.SilentAdminList));
        actionList.add(0);
        itemList.add(LocaleController.getString(R.string.SilentMemberList));
        actionList.add(1);
        itemList.add(isPublic ? LocaleController.getString(R.string.SilentCopyPublicLink)
                : LocaleController.getString(R.string.SilentCopyPrivateLink));
        actionList.add(2);
        itemList.add(LocaleController.getString(R.string.SilentCreateNewLink));
        actionList.add(3);
        // K16 "Recent Actions": Telegram's admin log
        // (ChannelAdminLogActivity). It only exists for channels — broadcast
        // channels and megagroups — and gigagroups; basic groups have no
        // event log. Same type gate as ChatEditActivity, which only creates
        // its log row under `isChannel || gigagroup` (ChatEditActivity:1291).
        // The pill itself already limits this menu to creator/admin
        // (ChatActivity:7338), so no extra rights gate is needed here.
        if (ChatObject.isChannel(chat) || chat.gigagroup) {
            itemList.add(LocaleController.getString(R.string.EventLog));
            actionList.add(4);
            // "Removed Members" — Telegram's own Removed Users page
            // (ChatUsersActivity TYPE_BANNED). Basic groups have no banned
            // list, so this row only exists for channels / megagroups /
            // gigagroups (same gate as Recent Actions above).
            itemList.add(REMOVED_MEMBERS_TITLE);
            actionList.add(6);
        }
        // K16 "Reactions": the screen where the allowed reactions are
        // chosen. Shown only when the user may change chat info — exactly
        // the gate ChatEditActivity uses for its own reactions row
        // (ChatEditActivity:2477: reactionsCell visible only if
        // ChatObject.canChangeChatInfo).
        if (ChatObject.canChangeChatInfo(chat)) {
            itemList.add(LocaleController.getString(R.string.Reactions));
            actionList.add(5);
        }
        // "Open in Bubble Chat" (v2.0.37) — always last, in every chat
        // type. Telegram's own bubble (SilentFloatingWindow.openBubble);
        // the menu item is ours, the bubble is Telegram's own.
        itemList.add(LocaleController.getString(R.string.SilentQuickOpenBubble));
        actionList.add(7);
        CharSequence[] items = itemList.toArray(new CharSequence[0]);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentAdministration));
        builder.setItems(items, (dialog, which) -> {
            android.os.Bundle args = new android.os.Bundle();
            args.putLong("chat_id", chatId);
            switch (actionList.get(which)) {
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
                case 4:
                    // Same screen as the "Recent Actions" row in the
                    // channel/group edit settings (ChatEditActivity:1295).
                    // (ChatUsersActivity also has a recentActionsRow tap
                    // at :1062, but its row assignment is commented out
                    // at :536 in this codebase, so ChatEditActivity is
                    // the live precedent.)
                    fragment.presentFragment(new org.telegram.ui.ChannelAdminLogActivity(chat));
                    break;
                case 5:
                    openReactionsScreen(fragment, account, chat);
                    break;
                case 6:
                    // Telegram's own Removed Users page — the same screen
                    // as ChatEditActivity's blocked-users row (ChatEditActivity:1210):
                    // ChatUsersActivity with TYPE_BANNED. Never a custom
                    // rebuild of this page.
                    android.os.Bundle bannedArgs = new android.os.Bundle();
                    bannedArgs.putLong("chat_id", chatId);
                    bannedArgs.putInt("type", org.telegram.ui.ChatUsersActivity.TYPE_BANNED);
                    org.telegram.ui.ChatUsersActivity bannedFragment = new org.telegram.ui.ChatUsersActivity(bannedArgs);
                    TLRPC.ChatFull bannedInfo = mc.getChatFull(chatId);
                    if (bannedInfo != null) {
                        bannedFragment.setInfo(bannedInfo);
                    }
                    // If the full info is not cached yet, ChatUsersActivity
                    // loads it itself (onFragmentCreate: loadChatParticipants
                    // + chatInfoDidLoad observer, ChatUsersActivity:623-628)
                    // and every info read there is null-guarded, so opening
                    // without setInfo is safe.
                    fragment.presentFragment(bannedFragment);
                    break;
                case 7:
                    // "Open in Bubble Chat" — Telegram's own bubble for
                    // this channel/group (dialog id is -chatId).
                    openInBubbleChat(fragment, account, -chatId);
                    break;
            }
        });
        builder.show();
    }


    /**
     * K16 "Reactions": open the screen where the allowed reactions for
     * this chat are chosen — the same screens the channel/group edit
     * settings use (ChatEditActivity:1226-1235):
     *   • broadcast channel -> ChatCustomReactionsEditActivity(chatId, info)
     *   • group / megagroup -> ChatReactionsEditActivity (Bundle with
     *     KEY_CHAT_ID) + setInfo(info)
     *
     * Both screens need the full chat info (ChatFull): the broadcast
     * screen refuses to create itself when info is null
     * (ChatCustomReactionsEditActivity.onFragmentCreate returns false),
     * and the group screen derives its initial selection from it
     * (ChatReactionsEditActivity.setInfo). So the info is taken from the
     * cache when available, otherwise loaded first and the screen is
     * opened when chatInfoDidLoad arrives — the same waiter pattern
     * openAdminScreen uses above.
     */
    private static void openReactionsScreen(BaseFragment fragment, int account, TLRPC.Chat chat) {
        openReactionsScreen(fragment, account, chat, false);
    }

    private static void openReactionsScreen(BaseFragment fragment, int account, TLRPC.Chat chat, boolean fullLoadAttempted) {
        if (fragment == null || fragment.getParentActivity() == null || chat == null) {
            return;
        }
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.ChatFull full = mc.getChatFull(chat.id);
        if (full != null) {
            presentReactionsScreen(fragment, chat, full);
            return;
        }
        if (!fullLoadAttempted) {
            // Register the waiter FIRST, then load, so the notification
            // cannot slip past us.
            NotificationCenter.getInstance(account).addObserver(new ReactionsFullChatWaiter(account, fragment, chat), NotificationCenter.chatInfoDidLoad);
            mc.loadFullChat(chat.id, 0, true);
            return;
        }
        // The load finished (or failed) without usable full info: open
        // anyway — the group screen tolerates a null info
        // (ChatReactionsEditActivity.setInfo is null-safe); the broadcast
        // screen declines to create itself in that case, so nothing is
        // shown rather than crashing.
        presentReactionsScreen(fragment, chat, null);
    }

    private static void presentReactionsScreen(BaseFragment fragment, TLRPC.Chat chat, TLRPC.ChatFull info) {
        if (fragment == null || fragment.getParentActivity() == null || chat == null) {
            return;
        }
        if (ChatObject.isChannelAndNotMegaGroup(chat)) {
            fragment.presentFragment(new org.telegram.ui.Components.Reactions.ChatCustomReactionsEditActivity(chat.id, info));
        } else {
            android.os.Bundle args = new android.os.Bundle();
            args.putLong(org.telegram.ui.ChatReactionsEditActivity.KEY_CHAT_ID, chat.id);
            org.telegram.ui.ChatReactionsEditActivity reactionsFragment = new org.telegram.ui.ChatReactionsEditActivity(args);
            reactionsFragment.setInfo(info);
            fragment.presentFragment(reactionsFragment);
        }
    }

    /** One-shot waiter: when the full chat we asked for arrives, open the reactions screen. */
    private static class ReactionsFullChatWaiter implements NotificationCenter.NotificationCenterDelegate {
        private final int account;
        private final java.lang.ref.WeakReference<BaseFragment> fragmentRef;
        private final TLRPC.Chat chat;

        ReactionsFullChatWaiter(int account, BaseFragment fragment, TLRPC.Chat chat) {
            this.account = account;
            this.fragmentRef = new java.lang.ref.WeakReference<>(fragment);
            this.chat = chat;
        }

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (id != NotificationCenter.chatInfoDidLoad || args == null || args.length == 0 || !(args[0] instanceof TLRPC.ChatFull)) {
                return;
            }
            TLRPC.ChatFull full = (TLRPC.ChatFull) args[0];
            if (full.id != chat.id) {
                return;
            }
            NotificationCenter.getInstance(this.account).removeObserver(this, NotificationCenter.chatInfoDidLoad);
            BaseFragment fragment = fragmentRef.get();
            if (fragment == null || fragment.getParentActivity() == null) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> openReactionsScreen(fragment, this.account, chat, true));
        }
    }

    private static void copyLink(Context context, String link) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", link));
        SilentToast.show(context, LocaleController.getString(R.string.TextCopied), android.widget.Toast.LENGTH_SHORT);
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
                    SilentToast.show(fragment.getParentActivity(), err.text, android.widget.Toast.LENGTH_SHORT);
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
            SilentToast.show(fragment.getParentActivity(), LocaleController.getString(channels ? R.string.SilentQuickNoChannels : R.string.SilentQuickNoGroups), android.widget.Toast.LENGTH_SHORT);
            return;
        }
        // Telegram-style picker: avatar + name rows, Telegram theme.
        String title = LocaleController.getString(channels ? R.string.SilentQuickPickChannel : R.string.SilentQuickPickGroup);
        fragment.presentFragment(new org.telegram.ui.SilentChatPickerActivity(fragment, mine, title, share, targetUserId, dialogId));
    }

    /**
     * Open the permissions screen to make targetUserId an admin of chat.
     *
     * This is exactly the page Telegram shows in the manual flow (user
     * already chosen + channel/group already chosen): the PERMISSIONS LIST
     * (ChatRightsEditActivity) directly — never a "Loading channel info,
     * tap again" toast (H8). The sequence is:
     *   1. Check whether the user is a member of the chat. For channels
     *      and megagroups that is one server call, channels.getParticipant
     *      (TL_channels_getParticipant, TLRPC.java:57671); for basic groups
     *      it is the full chat's participant list (ChatFull.participants,
     *      TLRPC.java:11024), loaded first via loadFullChat if needed and
     *      continued automatically when chatInfoDidLoad arrives.
     *   2. Member -> open the rights screen. Not a member -> ask whether
     *      to share the channel/group link so they can join first.
     *
     * All ids here are POSITIVE chat ids: getChatFull / loadFullChat /
     * ChatRightsEditActivity all take chat.id (see the note that used to
     * live on the old -chat.id bug).
     */
    public static void openAdminScreen(BaseFragment fragment, int account, TLRPC.Chat chat, long targetUserId, long dialogId) {
        openAdminScreen(fragment, account, chat, targetUserId, dialogId, false);
    }

    private static void openAdminScreen(BaseFragment fragment, int account, TLRPC.Chat chat, long targetUserId, long dialogId, boolean fullLoadAttempted) {
        if (fragment == null || fragment.getParentActivity() == null || chat == null) {
            return;
        }
        MessagesController mc = MessagesController.getInstance(account);
        if (ChatObject.isChannel(chat)) {
            // Channels and megagroups: ask the server directly whether the
            // user is a participant. No full-chat info is needed for this.
            TLRPC.TL_channels_getParticipant req = new TLRPC.TL_channels_getParticipant();
            req.channel = mc.getInputChannel(chat.id);
            req.participant = mc.getInputPeer(targetUserId);
            ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (fragment.getParentActivity() == null) {
                    return;
                }
                if (res instanceof TLRPC.TL_channels_channelParticipant) {
                    TLRPC.ChannelParticipant participant = ((TLRPC.TL_channels_channelParticipant) res).participant;
                    openRightsScreen(fragment, account, chat, targetUserId, participant != null ? participant.admin_rights : null);
                } else if (err != null && "USER_NOT_PARTICIPANT".equals(err.text)) {
                    showNotMemberPrompt(fragment, account, chat, targetUserId, dialogId);
                } else {
                    // Any other outcome (e.g. the user has no access hash,
                    // so the server cannot resolve them): do not block —
                    // open the rights screen and let Telegram itself
                    // report the problem.
                    openRightsScreen(fragment, account, chat, targetUserId, null);
                }
            }));
            return;
        }
        // Basic group: membership lives in the full chat's participant list.
        TLRPC.ChatFull full = mc.getChatFull(chat.id);
        if (full != null && full.participants != null) {
            if (isBasicGroupMember(full, targetUserId)) {
                openRightsScreen(fragment, account, chat, targetUserId, null);
            } else {
                showNotMemberPrompt(fragment, account, chat, targetUserId, dialogId);
            }
            return;
        }
        if (!fullLoadAttempted) {
            // Full info not cached yet — register the waiter FIRST, then
            // load, so the notification cannot slip past us. The flow
            // continues automatically when chatInfoDidLoad arrives.
            NotificationCenter.getInstance(account).addObserver(new FullChatWaiter(account, fragment, chat, targetUserId, dialogId), NotificationCenter.chatInfoDidLoad);
            mc.loadFullChat(chat.id, 0, true);
            return;
        }
        // The load finished (or failed) without usable participants: open
        // the rights screen anyway — ChatRightsEditActivity tolerates a
        // null ChatFull and picks it up via its own chatInfoDidLoad
        // observer (ChatRightsEditActivity:1283,1307).
        openRightsScreen(fragment, account, chat, targetUserId, null);
    }

    private static boolean isBasicGroupMember(TLRPC.ChatFull full, long userId) {
        if (full.participants == null || full.participants.participants == null) {
            return false;
        }
        for (TLRPC.ChatParticipant p : full.participants.participants) {
            if (p != null && p.user_id == userId) {
                return true;
            }
        }
        return false;
    }

    /** One-shot waiter: when the full chat we asked for arrives, continue the admin flow. */
    private static class FullChatWaiter implements NotificationCenter.NotificationCenterDelegate {
        private final int account;
        private final java.lang.ref.WeakReference<BaseFragment> fragmentRef;
        private final TLRPC.Chat chat;
        private final long targetUserId;
        private final long dialogId;

        FullChatWaiter(int account, BaseFragment fragment, TLRPC.Chat chat, long targetUserId, long dialogId) {
            this.account = account;
            this.fragmentRef = new java.lang.ref.WeakReference<>(fragment);
            this.chat = chat;
            this.targetUserId = targetUserId;
            this.dialogId = dialogId;
        }

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (id != NotificationCenter.chatInfoDidLoad || args == null || args.length == 0 || !(args[0] instanceof TLRPC.ChatFull)) {
                return;
            }
            TLRPC.ChatFull full = (TLRPC.ChatFull) args[0];
            if (full.id != chat.id) {
                return;
            }
            NotificationCenter.getInstance(this.account).removeObserver(this, NotificationCenter.chatInfoDidLoad);
            BaseFragment fragment = fragmentRef.get();
            if (fragment == null || fragment.getParentActivity() == null) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> openAdminScreen(fragment, this.account, chat, targetUserId, dialogId, true));
        }
    }

    /** The user is not a member of this chat — ask whether to share its link so they can join first. */
    private static void showNotMemberPrompt(BaseFragment fragment, int account, TLRPC.Chat chat, long targetUserId, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(targetUserId);
        String userName = user != null ? UserObject.getUserName(user) : "";
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(chat.title);
        builder.setMessage(LocaleController.formatString(R.string.SilentQuickNotMember, userName, chat.title));
        builder.setPositiveButton(LocaleController.getString(R.string.SilentQuickShareLink), (d, w) -> shareChat(fragment, account, chat, dialogId));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    /**
     * Present Telegram's own permissions page (ChatRightsEditActivity) for
     * targetUserId in this chat — the exact page the manual flow shows.
     *
     * Mirrors the upstream promote-new-admin call (ChatUsersActivity:1055):
     * NO preset admin rights (existingAdminRights is null for a plain
     * member, so the screen derives the initial toggles from MY admin
     * rights) — the old code pre-ticked every right, which the manual flow
     * never does. An already-admin user keeps their current rights
     * (existingAdminRights passed through, addingNew=false). The chat's own
     * default banned rights are passed as the default (Chat.default_banned_rights,
     * as ProfileActivity:7366 does). No delegate is needed: a null delegate
     * is explicitly handled (ChatRightsEditActivity:1587 — the fragment
     * finishes itself after the rights are set).
     */
    private static void openRightsScreen(BaseFragment fragment, int account, TLRPC.Chat chat, long targetUserId, TLRPC.TL_chatAdminRights existingAdminRights) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        MessagesController mc = MessagesController.getInstance(account);
        // Make sure the rights screen's own chatInfoDidLoad observer gets
        // the full info even when it was not cached yet.
        if (mc.getChatFull(chat.id) == null) {
            mc.loadFullChat(chat.id, 0, true);
        }
        ChatRightsEditActivity rightsFragment = new ChatRightsEditActivity(
                targetUserId, chat.id, existingAdminRights, chat.default_banned_rights, null, "",
                ChatRightsEditActivity.TYPE_ADMIN, true, existingAdminRights == null, null);
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
                AndroidUtilities.runOnUIThread(() -> SilentToast.show(fragment.getParentActivity(), LocaleController.getString(R.string.SilentQuickShareFailed), android.widget.Toast.LENGTH_SHORT));
            }
        });
    }
}
