package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

/**
 * SilentGram branding — the identities that make this app ours.
 *
 * Official channel:  @silent_gram            (the app's own channel)
 * Owner:             @only_possible0         (M Arslan — shows Owner/Founder)
 * Developer channel: @only_possible_worlds0 (the developer's channel)
 *
 * The official channel and the owner are shown with Telegram's blue verified
 * tick, so everyone can tell they are genuine. The tick is applied locally
 * whenever the user/chat object is loaded (see MessagesController.putUser /
 * putChat), because verification is otherwise a server-side flag we cannot
 * set from here.
 */
public class SilentBranding {

    public static final String OFFICIAL_CHANNEL_USERNAME = "silent_gram";
    public static final String OWNER_USERNAME = "only_possible0";
    public static final String DEV_CHANNEL_USERNAME = "only_possible_worlds0";
    public static final String OWNER_NAME = "M Arslan";

    public static boolean isOfficialChannel(String username) {
        return username != null && OFFICIAL_CHANNEL_USERNAME.equalsIgnoreCase(username);
    }

    public static boolean isOwner(String username) {
        return username != null && OWNER_USERNAME.equalsIgnoreCase(username);
    }

    public static boolean isDevChannel(String username) {
        return username != null && DEV_CHANNEL_USERNAME.equalsIgnoreCase(username);
    }

    /** A username that should carry the blue verified tick. */
    public static boolean shouldBeVerified(String username) {
        return isOfficialChannel(username) || isOwner(username) || isDevChannel(username);
    }

    /** Force the blue verified tick on the owner (user). */
    public static void applyBranding(TLRPC.User user) {
        if (user == null) {
            return;
        }
        if (shouldBeVerified(user.username)) {
            user.verified = true;
        } else if (user.usernames != null) {
            for (int i = 0; i < user.usernames.size(); i++) {
                TLRPC.TL_username u = user.usernames.get(i);
                if (u != null && u.active && shouldBeVerified(u.username)) {
                    user.verified = true;
                    break;
                }
            }
        }
    }

    /** Force the blue verified tick on the official / developer channel. */
    public static void applyBranding(TLRPC.Chat chat) {
        if (chat == null) {
            return;
        }
        if (shouldBeVerified(chat.username)) {
            chat.verified = true;
        } else if (chat.usernames != null) {
            for (int i = 0; i < chat.usernames.size(); i++) {
                TLRPC.TL_username u = chat.usernames.get(i);
                if (u != null && u.active && shouldBeVerified(u.username)) {
                    chat.verified = true;
                    break;
                }
            }
        }
    }

    /** The "Owner" / "Founder" label shown next to the owner's name. */
    public static String ownerLabel() {
        return "Owner";
    }

    /** Has the user joined the official @silent_gram channel? */
    public static boolean hasJoinedOfficialChannel(int account) {
        MessagesController mc = MessagesController.getInstance(account);
        if (mc == null) {
            return false;
        }
        TLRPC.TLObject obj = mc.objectsByUsernames.get(OFFICIAL_CHANNEL_USERNAME);
        if (obj instanceof TLRPC.Chat) {
            TLRPC.Chat chat = (TLRPC.Chat) obj;
            // A channel's dialog id is -chat.id.
            return mc.dialogs_dict.containsKey(-chat.id);
        }
        return false;
    }

    /** Text shown when someone taps the blue tick on the official channel. */
    public static String officialChannelInfo() {
        return "This is a verified channel.\n\nThis is the official channel of SilentGram.";
    }
}
