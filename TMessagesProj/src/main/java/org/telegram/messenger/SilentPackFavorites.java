package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;

/**
 * SilentGram pack favorites — emoji, sticker and gift packs.
 *
 * Long-press (or open) a pack → "Add to Favorites" and it jumps to the
 * front of the pack list, so the packs you actually use are always one
 * tap away instead of buried among dozens. Stored by pack short_name.
 */
public class SilentPackFavorites {

    private static final String PREFS = "silentgram";
    private static final String KEY = "fav_packs";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static HashSet<String> getFavorites() {
        HashSet<String> out = new HashSet<>();
        String saved = prefs().getString(KEY, "");
        if (saved != null && !saved.isEmpty()) {
            for (String n : saved.split(",")) {
                if (!n.isEmpty()) out.add(n);
            }
        }
        return out;
    }

    public static boolean isFavorite(String shortName) {
        return shortName != null && getFavorites().contains(shortName);
    }

    public static void setFavorite(String shortName, boolean favorite) {
        if (shortName == null || shortName.isEmpty()) {
            return;
        }
        HashSet<String> favs = getFavorites();
        if (favorite) {
            favs.add(shortName);
        } else {
            favs.remove(shortName);
        }
        StringBuilder sb = new StringBuilder();
        for (String n : favs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(n);
        }
        prefs().edit().putString(KEY, sb.toString()).apply();
    }

    public static void toggleFavorite(String shortName) {
        setFavorite(shortName, !isFavorite(shortName));
    }
}
