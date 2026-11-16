package org.telegram.messenger;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * SilentGram F4: Chat Lock (vault).
 *
 * Lock any chat behind a 6-digit PIN and/or fingerprint. A locked chat shows
 * a lock screen when opened — the messages stay hidden until unlocked.
 * The PIN is stored only as a SHA-256 hash. Unlock state is per-session
 * (in memory only — locking again after the app restarts).
 */
public class SilentChatLock {

    private static final String KEY_LOCKED = "chatlock_locked";
    private static final String KEY_PIN_HASH = "chatlock_pin_hash";
    private static final String KEY_FINGERPRINT = "chatlock_fingerprint";

    // Dialogs unlocked in this session (in-memory only).
    private static final Set<Long> unlockedThisSession = new HashSet<>();

    private static android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("silentgram", android.content.Context.MODE_PRIVATE);
    }

    public static Set<Long> getLockedChats() {
        HashSet<Long> out = new HashSet<>();
        for (String s : prefs().getStringSet(KEY_LOCKED, new HashSet<>()).toArray(new String[0])) {
            try {
                out.add(Long.parseLong(s));
            } catch (Exception e) {
                // Skip malformed entries.
            }
        }
        return out;
    }

    public static boolean isLocked(long dialogId) {
        return getLockedChats().contains(dialogId);
    }

    public static void setLocked(long dialogId, boolean locked) {
        Set<Long> set = getLockedChats();
        if (locked) {
            set.add(dialogId);
        } else {
            set.remove(dialogId);
            unlockedThisSession.remove(dialogId);
        }
        HashSet<String> raw = new HashSet<>();
        for (long id : set) {
            raw.add(String.valueOf(id));
        }
        prefs().edit().putStringSet(KEY_LOCKED, raw).apply();
    }

    public static boolean isUnlockedThisSession(long dialogId) {
        return unlockedThisSession.contains(dialogId);
    }

    public static void markUnlocked(long dialogId) {
        unlockedThisSession.add(dialogId);
    }

    public static boolean hasPin() {
        return prefs().getString(KEY_PIN_HASH, null) != null;
    }

    public static void setPin(String pin6) {
        prefs().edit().putString(KEY_PIN_HASH, sha256(pin6)).apply();
    }

    public static boolean checkPin(String pin6) {
        String hash = prefs().getString(KEY_PIN_HASH, null);
        return hash != null && hash.equals(sha256(pin6));
    }

    public static boolean isFingerprintEnabled() {
        return prefs().getBoolean(KEY_FINGERPRINT, false);
    }

    public static void setFingerprintEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_FINGERPRINT, enabled).apply();
    }

    private static String sha256(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] bytes = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }
}
