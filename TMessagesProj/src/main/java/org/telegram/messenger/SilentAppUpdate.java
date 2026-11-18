package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * SilentGram Batch U: in-app update prompt.
 *
 * On app open (wired from DialogsActivity.onFragmentCreate), check the
 * latest release of the GitHub repo real-earnn/SilentGram at most once a
 * day, compare its tag with this build's version
 * (BuildVars.BUILD_VERSION_STRING, e.g. "2.0.10" — the tag is "v2.0.10"),
 * and if a newer release exists show a non-blocking Bulletin at the top
 * with an Update button that opens the release page. No version is
 * hardcoded; the check runs on a background thread with short timeouts
 * and never blocks startup, never toasts, and never throws.
 */
public class SilentAppUpdate {

    private static final String REPO = "real-earnn/SilentGram";
    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases/latest";

    private static final String PREFS = "silentgram";
    private static final String KEY_LAST_CHECK_MS = "silent_update_last_check_ms";
    private static final String KEY_DISMISSED_TAG = "silent_update_dismissed_tag";
    /** At most one network check per day. */
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private static final String UPDATE_BUTTON = "Update";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Check for a newer release and, if there is one, show the update
     * bulletin. Safe to call on every app open: the network check runs at
     * most once a day and entirely off the UI thread. A null or detached
     * fragment is a silent no-op.
     */
    public static void checkAndPrompt(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - prefs().getLong(KEY_LAST_CHECK_MS, 0) < CHECK_INTERVAL_MS) {
            return;
        }
        prefs().edit().putLong(KEY_LAST_CHECK_MS, now).apply();
        new Thread(() -> {
            String latestTag = fetchLatestTag();
            if (latestTag == null) {
                return;
            }
            String current = BuildVars.BUILD_VERSION_STRING;
            if (!isNewerVersion(latestTag, current)) {
                return;
            }
            if (latestTag.equals(prefs().getString(KEY_DISMISSED_TAG, ""))) {
                return;
            }
            String latest = normalizeVersion(latestTag);
            AndroidUtilities.runOnUIThread(() -> {
                if (fragment.getParentActivity() == null) {
                    return;
                }
                Context context = fragment.getParentActivity();
                BulletinFactory.of(fragment).createSimpleBulletin(
                        R.drawable.msg_recent,
                        "SilentGram " + latest + " is available",
                        UPDATE_BUTTON,
                        () -> {
                            prefs().edit().putString(KEY_DISMISSED_TAG, latestTag).apply();
                            org.telegram.messenger.browser.Browser.openUrl(context, RELEASES_PAGE);
                        }).show();
            });
        }, "silent-update-check").start();
    }

    /** Latest release tag (e.g. "v2.0.11") or null on any failure. */
    private static String fetchLatestTag() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            // GitHub's API refuses requests without a User-Agent (403).
            conn.setRequestProperty("User-Agent", "SilentGram");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            String tag = new JSONObject(sb.toString()).optString("tag_name", "");
            return tag.isEmpty() ? null : tag;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** "v2.0.11" -> "2.0.11"; null/blank -> "". */
    public static String normalizeVersion(String version) {
        if (version == null) {
            return "";
        }
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        return v;
    }

    /** True when latest is a strictly newer numeric version than current. */
    public static boolean isNewerVersion(String latest, String current) {
        String l = normalizeVersion(latest);
        String c = normalizeVersion(current);
        if (l.isEmpty() || c.isEmpty() || l.equals(c)) {
            return false;
        }
        String[] lp = l.split("\\.");
        String[] cp = c.split("\\.");
        int n = Math.max(lp.length, cp.length);
        for (int i = 0; i < n; i++) {
            int lv = i < lp.length ? parsePart(lp[i]) : 0;
            int cv = i < cp.length ? parsePart(cp[i]) : 0;
            if (lv != cv) {
                return lv > cv;
            }
        }
        return false;
    }

    /** Leading numeric run of a version part ("11-beta" -> 11); 0 if none. */
    private static int parsePart(String part) {
        if (part == null || part.isEmpty()) {
            return 0;
        }
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(part.substring(0, end));
        } catch (Exception e) {
            return 0;
        }
    }
}
