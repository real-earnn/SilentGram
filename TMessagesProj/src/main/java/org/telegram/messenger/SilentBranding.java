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
    /** Owner of SilentGram / Silent Hackers Team (plain text, not a username). */
    public static final String OWNER_NAME = "Fatima Qazmi";
    /** The developer — alias "Nothing is impossible". */
    public static final String DEVELOPER_ALIAS = "Nothing is impossible";
    /** NOTE: @only_possible was stolen — the developer link is @only_possible0. */
    public static final String DEVELOPER_USERNAME = "only_possible0";
    public static final String DEVELOPER_REAL_NAME = "Muhammad Arslan";
    /** Extra verified identities (channels + bots), also settable via the API config. */
    public static final String EXCHANGE_CHANNEL_USERNAME = "Silent_Exchange";
    public static final String REAL_EARN_CHANNEL_USERNAME = "imp_tst";
    public static final String REAL_EARN_BOT_USERNAME = "real_earnn_bot";
    public static final String EXCHANGE_BOT_USERNAME = "Silenr_Exchange_Bot";

    /** Usernames that came from the API config (see SilentConfig) — the user
     * adds new channels/bots there and the app picks them up on next start. */
    private static java.util.Set<String> apiVerifiedUsernames = new java.util.HashSet<>();
    private static String apiSponsoredUsername = null;

    public static void setApiVerifiedUsernames(java.util.Collection<String> names) {
        apiVerifiedUsernames.clear();
        if (names != null) {
            for (String n : names) {
                if (n != null && !n.isEmpty()) {
                    apiVerifiedUsernames.add(n.toLowerCase());
                }
            }
        }
    }

    public static void setApiSponsoredUsername(String username) {
        apiSponsoredUsername = username;
    }

    /** The channel shown in the Official row (API config wins, else @silent_gram). */
    public static String getSponsoredUsername() {
        return apiSponsoredUsername != null && !apiSponsoredUsername.isEmpty()
                ? apiSponsoredUsername : OFFICIAL_CHANNEL_USERNAME;
    }

    private static String apiSponsoredTitle = null;

    public static void setApiSponsoredTitle(String title) {
        apiSponsoredTitle = title;
    }

    /** Display name for the Official row (API config wins, else "SilentGram"). */
    public static String getSponsoredTitle() {
        return apiSponsoredTitle != null && !apiSponsoredTitle.isEmpty()
                ? apiSponsoredTitle : "SilentGram";
    }

    public static boolean isOfficialChannel(String username) {
        return username != null && OFFICIAL_CHANNEL_USERNAME.equalsIgnoreCase(username);
    }

    public static boolean isOwner(String username) {
        return username != null && OWNER_USERNAME.equalsIgnoreCase(username);
    }

    public static boolean isDevChannel(String username) {
        return username != null && DEV_CHANNEL_USERNAME.equalsIgnoreCase(username);
    }

    /** A username that should carry the blue verified tick.
     * NOTE: the OWNER (@only_possible0) is intentionally NOT here — his
     * profile shows the "👑 Owner" label only, no blue tick. */
    public static boolean shouldBeVerified(String username) {
        if (username == null) {
            return false;
        }
        if (isOfficialChannel(username) || isDevChannel(username)
                || EXCHANGE_CHANNEL_USERNAME.equalsIgnoreCase(username)
                || REAL_EARN_CHANNEL_USERNAME.equalsIgnoreCase(username)
                || REAL_EARN_BOT_USERNAME.equalsIgnoreCase(username)
                || EXCHANGE_BOT_USERNAME.equalsIgnoreCase(username)) {
            return true;
        }
        return apiVerifiedUsernames.contains(username.toLowerCase());
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

    /** Has the user joined the official / sponsored channel? */
    public static boolean hasJoinedOfficialChannel(int account) {
        MessagesController mc = MessagesController.getInstance(account);
        if (mc == null) {
            return false;
        }
        String sponsored = getSponsoredUsername();
        // Walk the dialog list (public API) and match the channel by username.
        for (int i = 0; i < mc.dialogs_dict.size(); i++) {
            TLRPC.Dialog dialog = mc.dialogs_dict.valueAt(i);
            if (dialog == null || dialog.id >= 0) {
                continue;
            }
            TLRPC.Chat chat = mc.getChat(-dialog.id);
            if (chat != null && sponsored.equalsIgnoreCase(chat.username)) {
                return true;
            }
        }
        return false;
    }

    /** Text shown when someone taps the blue tick on the official channel. */
    public static String officialChannelInfo() {
        return "This is a verified channel.\n\nThis is the official channel of SilentGram.";
    }
}
