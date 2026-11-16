package org.telegram.messenger;

import java.util.HashSet;
import java.util.Set;

/**
 * SilentGram F2: Auto-download rules per chat.
 *
 * Two lists: chats where media ALWAYS auto-downloads (even on mobile data),
 * and chats where media NEVER auto-downloads (saves data/storage).
 * Everything else follows Telegram's normal auto-download settings.
 * The rule is enforced in DownloadController.canDownloadMedia.
 */
public class SilentAutoDownload {

    public static final int RULE_NORMAL = 0;
    public static final int RULE_ALWAYS = 1;
    public static final int RULE_NEVER = 2;

    private static final String KEY_ALWAYS = "autodl_always";
    private static final String KEY_NEVER = "autodl_never";

    private static android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("silentgram", android.content.Context.MODE_PRIVATE);
    }

    private static Set<Long> getSet(String key) {
        HashSet<Long> out = new HashSet<>();
        for (String s : prefs().getStringSet(key, new HashSet<>()).toArray(new String[0])) {
            try {
                out.add(Long.parseLong(s));
            } catch (Exception e) {
                // Skip malformed entries.
            }
        }
        return out;
    }

    private static void saveSet(String key, Set<Long> set) {
        HashSet<String> raw = new HashSet<>();
        for (long id : set) {
            raw.add(String.valueOf(id));
        }
        prefs().edit().putStringSet(key, raw).apply();
    }

    public static int getRule(long dialogId) {
        if (getSet(KEY_ALWAYS).contains(dialogId)) {
            return RULE_ALWAYS;
        }
        if (getSet(KEY_NEVER).contains(dialogId)) {
            return RULE_NEVER;
        }
        return RULE_NORMAL;
    }

    public static void setRule(long dialogId, int rule) {
        Set<Long> always = getSet(KEY_ALWAYS);
        Set<Long> never = getSet(KEY_NEVER);
        always.remove(dialogId);
        never.remove(dialogId);
        if (rule == RULE_ALWAYS) {
            always.add(dialogId);
        } else if (rule == RULE_NEVER) {
            never.add(dialogId);
        }
        saveSet(KEY_ALWAYS, always);
        saveSet(KEY_NEVER, never);
    }

    public static Set<Long> getAlwaysSet() {
        return getSet(KEY_ALWAYS);
    }

    public static Set<Long> getNeverSet() {
        return getSet(KEY_NEVER);
    }
}
