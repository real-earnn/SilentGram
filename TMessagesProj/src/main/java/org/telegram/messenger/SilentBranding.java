package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

/**
 * SilentGram branding — the identities that make this app ours.
 *
 * Official channel:  @silent_gram            (the app's own channel)
 * Owner:             @only_possible0         (M Arslan — blue tick only,
 *                                             no extra label next to his name)
 * Developer channel: @only_possible_worlds0 (the developer's channel,
 *                                             shown as "Impossible - World")
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
    /** Display name of the Developer Channel rows ("Impossible - World").
     * The person/Owner rows keep DEVELOPER_ALIAS. */
    public static final String DEV_CHANNEL_DISPLAY_NAME = "Impossible - World";
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
     * NOTE: the OWNER (@only_possible0) is intentionally NOT here — the
     * "👑 Owner" label next to his name was removed (batch G); only the
     * blue tick (when the account is verified) shows next to his name. */
    public static boolean shouldBeVerified(String username) {
        if (username == null) {
            return false;
        }
        if (isOfficialChannel(username) || isDevChannel(username)
                || isOwner(username)
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

    /** The "Owner" / "Founder" label. Kept for compatibility — it is no
     * longer appended to the owner's name in profiles (removed, batch G). */
    public static String ownerLabel() {
        return "Owner";
    }

    /** SilentGram K10: locally-persisted joined/left state for the
     * Official / sponsored channel row.
     *
     * The row used to be decided by scanning the in-memory dialog list
     * (see hasJoinedOfficialChannel below) — that list only fills once the
     * dialogs have synced from the server, so on a fresh start, with no
     * internet, or while the proxy is still connecting, the scan found
     * nothing and the row kept showing even though the user had joined.
     * The joined/left state is therefore remembered in the "silentgram"
     * SharedPreferences (same file the other Silent* helpers use), keyed
     * per account AND per channel username — so the official channel
     * (@silent_gram) and a sponsored channel from the API config (when
     * one is set) each keep their own state, with the same mechanism.
     * Local memory wins: once a value is stored it is trusted without
     * any network check. It is updated on the real server events in
     * MessagesController (TL_updateChannel join/leave) and seeded from
     * the dialog list the first time it is asked (positive hits only —
     * an empty / not-yet-loaded dialog list must never overwrite a
     * stored "joined"). */
    private static SharedPreferences silentPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("silentgram", Context.MODE_PRIVATE);
    }

    private static String joinedKey(int account, String username) {
        return "official_joined_" + account + "_" + (username == null ? "" : username.toLowerCase());
    }

    /** Is there a locally stored joined/left value for this channel? */
    public static boolean hasStoredJoinedState(int account, String username) {
        if (username == null || username.isEmpty()) {
            return false;
        }
        return silentPrefs().contains(joinedKey(account, username));
    }

    /** The locally remembered joined state (false when nothing stored). */
    public static boolean isJoinedStored(int account, String username) {
        if (username == null || username.isEmpty()) {
            return false;
        }
        return silentPrefs().getBoolean(joinedKey(account, username), false);
    }

    /** Remember that the user joined (true) or left (false) this channel.
     * Called from MessagesController when the server reports the change
     * (TL_updateChannel), never from a guess. */
    public static void setJoinedChannelLocally(int account, String username, boolean joined) {
        if (username == null || username.isEmpty()) {
            return;
        }
        silentPrefs().edit().putBoolean(joinedKey(account, username), joined).apply();
    }

    /** Is this one of the channels the Official row can show — the
     * official @silent_gram, or the sponsored channel from the API config
     * (which falls back to @silent_gram when the config has none)? */
    public static boolean isOfficialOrSponsoredChannel(String username) {
        if (username == null || username.isEmpty()) {
            return false;
        }
        return OFFICIAL_CHANNEL_USERNAME.equalsIgnoreCase(username)
                || getSponsoredUsername().equalsIgnoreCase(username);
    }

    /** Has the user joined the official / sponsored channel?
     * K10: local memory is consulted FIRST — a stored value is returned
     * as-is, even with no internet / no proxy, so the row's visibility
     * on app open never depends on a network check. Only when nothing
     * is stored yet (first run after this build) is the dialog list
     * scanned, and only a positive hit is persisted — a miss means
     * "not loaded yet or not joined", which must not be remembered. */
    public static boolean hasJoinedOfficialChannel(int account) {
        String sponsored = getSponsoredUsername();
        if (hasStoredJoinedState(account, sponsored)) {
            return isJoinedStored(account, sponsored);
        }
        MessagesController mc = MessagesController.getInstance(account);
        if (mc == null) {
            return false;
        }
        // Walk the dialog list (public API) and match the channel by username.
        for (int i = 0; i < mc.dialogs_dict.size(); i++) {
            TLRPC.Dialog dialog = mc.dialogs_dict.valueAt(i);
            if (dialog == null || dialog.id >= 0) {
                continue;
            }
            TLRPC.Chat chat = mc.getChat(-dialog.id);
            if (chat != null && sponsored.equalsIgnoreCase(chat.username)) {
                setJoinedChannelLocally(account, sponsored, true);
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
