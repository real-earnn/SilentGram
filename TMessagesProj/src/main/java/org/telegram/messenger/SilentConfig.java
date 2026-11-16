package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * SilentGram app config, served by our own API (silentgram-proxy.silenthost.site).
 *
 * The user edits a single config.json on the server — no app rebuild needed:
 *   { "verified": {
 *       "users": ["only_possible0"],
 *       "groups": [],
 *       "channels": ["silent_gram", "only_possible_worlds0", ...],
 *       "bots": ["real_earnn_bot", ...] },
 *     "sponsored": null }
 *
 * - "verified": split by type (users / groups / channels / bots). Every
 *   username in any of the four lists gets the blue tick. An old file with
 *   verified as a flat array ["a", "b"] is still accepted.
 * - "sponsored": null (or missing / empty username) = NO sponsored promo
 *   row. The Official @silent_gram row is hardcoded in the app and is NOT
 *   a sponsor. A sponsored object {username, title} shows that channel.
 *
 * Fetched once per app start (and cached in SharedPreferences, so the app
 * still knows the list when offline). The built-in defaults in
 * SilentBranding always apply as well.
 */
public class SilentConfig {

    private static final String CONFIG_URL = "https://silentgram-proxy.silenthost.site/api/config";
    private static volatile boolean fetching;

    public static void refreshAsync() {
        if (fetching) {
            return;
        }
        fetching = true;
        new Thread(() -> {
            try {
                fetchAndApply();
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                fetching = false;
            }
        }, "silent-config-fetch").start();
    }

    /** Load the cached config (offline) into SilentBranding. Call on start. */
    public static void loadCached() {
        SharedPreferences prefs = prefs();
        String verified = prefs.getString("api_verified", "");
        List<String> names = new ArrayList<>();
        if (verified != null && !verified.isEmpty()) {
            for (String n : verified.split(",")) {
                if (!n.isEmpty()) names.add(n);
            }
        }
        SilentBranding.setApiVerifiedUsernames(names);
        SilentBranding.setApiSponsoredUsername(prefs.getString("api_sponsored", null));
        SilentBranding.setApiSponsoredTitle(prefs.getString("api_sponsored_title", null));
    }

    private static void fetchAndApply() throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(CONFIG_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "SilentGram");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            JSONObject root = new JSONObject(sb.toString());
            List<String> names = new ArrayList<>();
            // New format: verified is an object {users, groups, channels, bots}.
            // Old format: verified is a flat array — still accepted.
            JSONObject verifiedObj = root.optJSONObject("verified");
            if (verifiedObj != null) {
                addVerifiedNames(names, verifiedObj.optJSONArray("users"));
                addVerifiedNames(names, verifiedObj.optJSONArray("groups"));
                addVerifiedNames(names, verifiedObj.optJSONArray("channels"));
                addVerifiedNames(names, verifiedObj.optJSONArray("bots"));
            } else {
                addVerifiedNames(names, root.optJSONArray("verified"));
            }
            String sponsored = null;
            String sponsoredTitle = null;
            JSONObject sp = root.optJSONObject("sponsored");
            if (sp != null) {
                sponsored = sp.optString("username", null);
                sponsoredTitle = sp.optString("title", null);
                // Empty username = no sponsored promo (same as sponsored: null).
                if (sponsored != null && sponsored.trim().isEmpty()) {
                    sponsored = null;
                    sponsoredTitle = null;
                }
            }
            // Apply + cache.
            SilentBranding.setApiVerifiedUsernames(names);
            SilentBranding.setApiSponsoredUsername(sponsored);
            SilentBranding.setApiSponsoredTitle(sponsoredTitle);
            SharedPreferences prefs = prefs();
            StringBuilder joined = new StringBuilder();
            HashSet<String> seen = new HashSet<>();
            for (String n : names) {
                String lower = n.toLowerCase();
                if (seen.add(lower)) {
                    if (joined.length() > 0) joined.append(',');
                    joined.append(lower);
                }
            }
            SharedPreferences.Editor e = prefs.edit();
            e.putString("api_verified", joined.toString());
            if (sponsored != null) {
                e.putString("api_sponsored", sponsored);
            } else {
                e.remove("api_sponsored");
            }
            if (sponsoredTitle != null) {
                e.putString("api_sponsored_title", sponsoredTitle);
            } else {
                e.remove("api_sponsored_title");
            }
            e.apply();
            FileLog.d("SilentConfig: " + names.size() + " verified, sponsored=" + sponsored);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Append the non-empty strings of one verified list into names. */
    private static void addVerifiedNames(List<String> names, JSONArray arr) {
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            String n = arr.optString(i, "");
            if (!n.isEmpty()) names.add(n);
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("silentgram", Context.MODE_PRIVATE);
    }
}
