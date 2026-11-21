package org.telegram.messenger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ProxyListActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * SilentGram v2.0.21: in-app Debug Report.
 *
 * Trigger: a 5-second hold on the Chat Lock row of the three-dots menu
 * of an open chat (see ChatActivity.attachSilentChatLockDebugHold). The
 * report is built on demand, at hold time, from live state — nothing is
 * trimmed except the log tail (see MAX_LOG_LINES). The dialog shows the
 * full JSON, scrollable, with a COPY button that copies the ENTIRE
 * report to the clipboard, plus Save to file as an extra fallback.
 *
 * Never included: tokens, passwords, API keys, session data, phone
 * numbers, or any other credential. Proxy rows carry address:port only
 * — the MTProto secrets are never read here. The call relay is reported
 * as host:port only (its username/password are never read).
 */
public class SilentDebugReport {

    /** How long the finger must stay down on the Chat Lock row. */
    public static final long HOLD_MS = 5000;

    /** Last N relevant FileLog lines kept in the report (the other sections are never trimmed). */
    private static final int MAX_LOG_LINES = 200;

    /** Hard cap on how many log lines are scanned (a session log can be long). */
    private static final int MAX_LOG_SCAN_LINES = 200000;

    /** Budget for the Groq relay root check at report time (never blocks the dialog past this). */
    private static final int RELAY_ROOT_TIMEOUT_MS = 2000;

    private static final String[] LOG_KEYWORDS = {
            "silent", "sg-call", "voip", "call", "proxy", "transcri", "groq", "floating"
    };

    /**
     * Attach the 5-second hold to a menu row view. A normal tap (finger
     * released before HOLD_MS) is untouched: the listener returns false
     * for it, so the row's own click runs as usual. Only when the finger
     * is still down at HOLD_MS does the hold fire — and then the
     * following ACTION_UP is consumed, so the normal tap action is
     * suppressed and the report opens instead. Sliding the finger off
     * the row, or a second finger going down, cancels the hold.
     */
    public static void attachHoldToChatLockRow(View row, final Activity activity, final int account, final Runnable closeMenu) {
        if (row == null) {
            return;
        }
        row.setOnTouchListener(new View.OnTouchListener() {
            private boolean holdFired;
            private final Runnable holdRunnable = () -> {
                holdFired = true;
                try {
                    row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } catch (Exception ignored) {
                }
                if (closeMenu != null) {
                    try {
                        closeMenu.run();
                    } catch (Exception ignored) {
                    }
                }
                showDebugReport(activity, account);
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    holdFired = false;
                    AndroidUtilities.runOnUIThread(holdRunnable, HOLD_MS);
                    return false;
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    int slop = AndroidUtilities.dp(8);
                    if (event.getX() < -slop || event.getX() > v.getWidth() + slop
                            || event.getY() < -slop || event.getY() > v.getHeight() + slop) {
                        AndroidUtilities.cancelRunOnUIThread(holdRunnable);
                    }
                    return false;
                }
                if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    AndroidUtilities.cancelRunOnUIThread(holdRunnable);
                    return false;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    AndroidUtilities.cancelRunOnUIThread(holdRunnable);
                    if (holdFired) {
                        holdFired = false;
                        return true;
                    }
                    return false;
                }
                return false;
            }
        });
    }

    /**
     * Build the report off the UI thread (it reads the log file and does
     * one short relay check) and show the dialog on the UI thread.
     */
    public static void showDebugReport(final Activity activity, final int account) {
        if (activity == null) {
            return;
        }
        new Thread(() -> {
            final String report;
            try {
                report = buildReport(account);
            } catch (Throwable e) {
                FileLog.e(e);
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (activity.isFinishing()) {
                        return;
                    }
                    showDialog(activity, report);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        }, "silent-debug-report").start();
    }

    /** The whole report as one pretty-printed JSON object. */
    public static String buildReport(int account) {
        JSONObject root = new JSONObject();
        putSection(root, "app", buildApp());
        putSection(root, "device", buildDevice());
        putSection(root, "account", buildAccount(account));
        putSection(root, "proxy", buildProxy());
        putSection(root, "calls", buildCalls());
        putSection(root, "floating", buildFloating());
        putSection(root, "vtt", buildVtt(account));
        putSection(root, "logs", buildLogs());
        try {
            return root.toString(2);
        } catch (Throwable e) {
            return root.toString();
        }
    }

    private static void putSection(JSONObject root, String key, JSONObject section) {
        try {
            root.put(key, section);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static JSONObject sectionError(String what, Throwable e) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", "could not build " + what + ": " + String.valueOf(e.getMessage()));
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject buildApp() {
        try {
            JSONObject app = new JSONObject();
            app.put("version", BuildVars.BUILD_VERSION_STRING);
            app.put("versionCode", getVersionCode());
            app.put("build", BuildConfig.BUILD_TYPE);
            return app;
        } catch (Throwable e) {
            return sectionError("app", e);
        }
    }

    // BuildConfig has no VERSION_CODE field in this AGP setup (the
    // workflow passes the version code to Gradle for the manifest only),
    // so read it from the installed package like ApplicationLoader does.
    private static int getVersionCode() {
        try {
            PackageInfo info = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            return info.versionCode;
        } catch (Throwable e) {
            return -1;
        }
    }

    private static JSONObject buildDevice() {
        try {
            JSONObject device = new JSONObject();
            device.put("model", Build.MODEL);
            device.put("manufacturer", Build.MANUFACTURER);
            device.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            String[] abis = Build.SUPPORTED_ABIS;
            device.put("abi", abis == null ? "unknown" : TextUtils.join(", ", abis));
            return device;
        } catch (Throwable e) {
            return sectionError("device", e);
        }
    }

    private static JSONObject buildAccount(int account) {
        try {
            JSONObject acc = new JSONObject();
            UserConfig userConfig = UserConfig.getInstance(account);
            acc.put("account", account);
            acc.put("premium", userConfig.isPremium());
            acc.put("activated", userConfig.isClientActivated());
            return acc;
        } catch (Throwable e) {
            return sectionError("account", e);
        }
    }

    private static JSONObject buildProxy() {
        try {
            JSONObject proxy = new JSONObject();
            proxy.put("enabled", SharedConfig.isProxyEnabled());
            JSONObject current = new JSONObject();
            SharedConfig.ProxyInfo cur = SharedConfig.currentProxy;
            if (cur == null || cur.settings == null) {
                current.put("key", "none");
            } else {
                current.put("key", cur.settings.getAddress() + ":" + cur.settings.getPort());
                current.put("own", SilentProxyManager.isCurrentProxyOwn());
                long ms = SilentProxyManager.getCurrentProxyMs();
                current.put("ms", ms < 0 ? JSONObject.NULL : ms);
                current.put("ping", cur.ping);
                current.put("available", cur.available);
                current.put("checking", cur.checking);
            }
            proxy.put("current", current);
            JSONArray kept = new JSONArray();
            for (SilentProxyManager.KeptProxySnapshot s : SilentProxyManager.getKeptProxySnapshots()) {
                JSONObject o = new JSONObject();
                o.put("key", s.key);
                o.put("ms", s.ms < 0 ? JSONObject.NULL : s.ms);
                o.put("own", s.own);
                o.put("ping", s.ping);
                o.put("available", s.available);
                o.put("checking", s.checking);
                kept.put(o);
            }
            proxy.put("kept", kept);
            proxy.put("keptCount", kept.length());
            proxy.put("keptSource", SilentProxyManager.getKeptProxySource());
            String diag = SilentProxyManager.getLastRoundDiagnostic();
            proxy.put("lastRoundDiagnostic", TextUtils.isEmpty(diag) ? "no round yet" : diag);
            proxy.put("lastShedOutcome", SilentProxyManager.getLastShedOutcome());
            JSONObject lastSwitch = new JSONObject();
            String from = SilentProxyManager.getLastSwitchFromKey();
            String to = SilentProxyManager.getLastSwitchToKey();
            long at = SilentProxyManager.getLastSwitchMs();
            if (to == null) {
                lastSwitch.put("state", "no switch yet");
            } else {
                lastSwitch.put("from", from == null ? "none" : from);
                lastSwitch.put("to", to);
                lastSwitch.put("at", formatTime(at));
            }
            proxy.put("lastSwitch", lastSwitch);
            proxy.put("watcherUnstableRounds", SilentProxyManager.getWatcherUnstableRounds());
            return proxy;
        } catch (Throwable e) {
            return sectionError("proxy", e);
        }
    }

    private static JSONObject buildCalls() {
        try {
            JSONObject calls = new JSONObject();
            String facts = ProxyListActivity.getLastCallFactsText();
            calls.put("lastCallFacts", facts != null ? facts : "no call yet");
            calls.put("relayHost", VoIPService.SILENT_CALL_RELAY_HOST);
            calls.put("relayPort", VoIPService.SILENT_CALL_RELAY_PORT);
            String relayStatus = ProxyListActivity.getLastCallRelayStatusText();
            calls.put("relayStatus", relayStatus != null ? relayStatus : "not probed yet");
            long relayOkMs = ProxyListActivity.getLastCallRelayOkMs();
            calls.put("relayLastOk", relayOkMs > 0 ? formatTime(relayOkMs) : "never");
            // VoIPService.makeInstance forces TCP (forceTcp = true, endpoint
            // type TCP_RELAY); the relay argument itself is diagnostics-only
            // until the tgcalls-fork patch lands.
            calls.put("forcedTcp", true);
            calls.put("endpointType", "TCP_RELAY");
            calls.put("note", "Per-endpoint TCP flags and the full endpoint list are in the logs section (SG-CALL-RELAY line).");
            return calls;
        } catch (Throwable e) {
            return sectionError("calls", e);
        }
    }

    private static JSONObject buildFloating() {
        try {
            SilentFloatingWindow fw = SilentFloatingWindow.getInstance();
            JSONObject floating = new JSONObject();
            floating.put("active", fw.isActive());
            floating.put("virtualDisplayId", fw.getVirtualDisplayId());
            floating.put("activityDisplayId", fw.getActivityDisplayId());
            String startError = fw.getLastStartError();
            floating.put("lastStartError", startError == null ? "none" : startError);
            String frameCheck = fw.getLastFrameCheck();
            if (frameCheck == null) {
                floating.put("frameCheck", "not run yet");
            } else {
                floating.put("frameCheck", frameCheck);
                floating.put("frameCheckAt", formatTime(fw.getLastFrameCheckAtMs()));
            }
            JSONObject window = new JSONObject();
            window.put("widthPx", fw.getWindowWidthPx());
            window.put("heightPx", fw.getWindowHeightPx());
            window.put("x", fw.getWindowX());
            window.put("y", fw.getWindowY());
            floating.put("window", window);
            JSONObject display = new JSONObject();
            display.put("widthPx", fw.getDisplayWidthPx());
            display.put("heightPx", fw.getDisplayHeightPx());
            floating.put("display", display);
            JSONObject screen = new JSONObject();
            screen.put("widthPx", fw.getScreenWidthPx());
            screen.put("heightPx", fw.getScreenHeightPx());
            floating.put("screen", screen);
            return floating;
        } catch (Throwable e) {
            return sectionError("floating", e);
        }
    }

    private static JSONObject buildVtt(int account) {
        try {
            JSONObject vtt = new JSONObject();
            JSONObject gate = new JSONObject();
            gate.put("premium", UserConfig.getInstance(account).isPremium());
            MessagesController mc = MessagesController.getInstance(account);
            gate.put("trialWeekly", mc.transcribeAudioTrialWeeklyNumber);
            gate.put("trialCurrent", mc.transcribeAudioTrialCurrentNumber);
            long cooldownUntil = mc.transcribeAudioTrialCooldownUntil;
            gate.put("trialCooldownUntil", cooldownUntil);
            gate.put("trialCooldownUntilText", cooldownUntil > 0 ? formatUnixSeconds(cooldownUntil) : "none");
            gate.put("freeInChat", "per message, not a global state");
            vtt.put("gate", gate);
            JSONObject groq = new JSONObject();
            groq.put("relayUrl", SilentVoiceTranscribe.getGroqRelayUrl());
            groq.put("relayRoot", probeGroqRelayRoot());
            vtt.put("groq", groq);
            JSONObject last = new JSONObject();
            String source = SilentVoiceTranscribe.getLastVttSource();
            if (source == null) {
                last.put("state", "no transcription yet");
            } else {
                last.put("source", source);
                String text = SilentVoiceTranscribe.getLastVttText();
                last.put("text", text == null ? JSONObject.NULL : text);
                String error = SilentVoiceTranscribe.getLastVttError();
                last.put("error", error == null ? JSONObject.NULL : error);
                last.put("at", formatTime(SilentVoiceTranscribe.getLastVttAtMs()));
            }
            vtt.put("last", last);
            return vtt;
        } catch (Throwable e) {
            return sectionError("vtt", e);
        }
    }

    /**
     * Lightweight GET of the Groq relay root (returns key_configured).
     * Bounded by RELAY_ROOT_TIMEOUT_MS; any failure is reported as an
     * error string, never thrown, and never blocks the dialog past the
     * timeout. Runs on the report thread, not the UI thread.
     */
    private static JSONObject probeGroqRelayRoot() {
        JSONObject result = new JSONObject();
        HttpURLConnection conn = null;
        try {
            String relayUrl = SilentVoiceTranscribe.getGroqRelayUrl();
            int cut = relayUrl.lastIndexOf('/');
            String root = cut > 0 ? relayUrl.substring(0, cut) : relayUrl;
            String rootUrl = root + "/";
            result.put("url", rootUrl);
            conn = (HttpURLConnection) new URL(rootUrl).openConnection();
            conn.setConnectTimeout(RELAY_ROOT_TIMEOUT_MS);
            conn.setReadTimeout(RELAY_ROOT_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "SilentGram");
            int code = conn.getResponseCode();
            result.put("http", code);
            String body = readResponseBody(conn, code);
            if (!TextUtils.isEmpty(body)) {
                try {
                    JSONObject json = new JSONObject(body);
                    result.put("keyConfigured", json.optBoolean("key_configured", false));
                } catch (Throwable e) {
                    result.put("body", body);
                }
            }
        } catch (Throwable e) {
            try {
                result.put("error", String.valueOf(e.getMessage()));
            } catch (Throwable ignored) {
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return result;
    }

    private static String readResponseBody(HttpURLConnection conn, int code) {
        try {
            java.io.InputStream stream = code >= HttpURLConnection.HTTP_BAD_REQUEST ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Tail of the current FileLog file: <logsDir>/<dd_MM_yyyy_HH_mm_ss>.txt
     * (see FileLog.init; the _mtproto.txt file is the TL dump, not the app
     * log). Only lines matching LOG_KEYWORDS are kept, newest MAX_LOG_LINES
     * of them, in chronological order.
     */
    private static JSONObject buildLogs() {
        JSONObject logs = new JSONObject();
        try {
            File dir = AndroidUtilities.getLogsDir();
            if (dir == null) {
                logs.put("error", "logs dir unavailable");
                return logs;
            }
            File[] files = dir.listFiles((d, name) -> name.endsWith(".txt") && !name.endsWith("_mtproto.txt"));
            File newest = null;
            if (files != null) {
                for (File f : files) {
                    if (newest == null || f.lastModified() > newest.lastModified()) {
                        newest = f;
                    }
                }
            }
            if (newest == null) {
                logs.put("error", "no FileLog file in " + dir.getAbsolutePath());
                return logs;
            }
            logs.put("file", newest.getName());
            ArrayDeque<String> tail = new ArrayDeque<>();
            int totalLines = 0;
            boolean scanTruncated = false;
            try (BufferedReader reader = new BufferedReader(new FileReader(newest))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    totalLines++;
                    if (totalLines > MAX_LOG_SCAN_LINES) {
                        scanTruncated = true;
                        break;
                    }
                    if (isRelevantLogLine(line)) {
                        tail.addLast(line);
                        if (tail.size() > MAX_LOG_LINES) {
                            tail.removeFirst();
                        }
                    }
                }
            }
            logs.put("totalLines", totalLines);
            logs.put("scanTruncated", scanTruncated);
            JSONArray lines = new JSONArray();
            for (String l : tail) {
                lines.put(l);
            }
            logs.put("lines", lines);
            logs.put("linesKept", tail.size());
            logs.put("note", "Last " + MAX_LOG_LINES + " relevant lines, chronological. Filter: Silent / SG-CALL / VoIP / call / proxy / transcri / groq / floating.");
            return logs;
        } catch (Throwable e) {
            return sectionError("logs", e);
        }
    }

    private static boolean isRelevantLogLine(String line) {
        String lower = line.toLowerCase(Locale.US);
        for (String keyword : LOG_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static void showDialog(Activity activity, final String report) {
        Context context = activity;
        TextView text = new TextView(context);
        text.setText(report);
        text.setTextSize(12);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        text.setTextIsSelectable(true);
        text.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(text);
        String size = formatSize(report.getBytes(StandardCharsets.UTF_8).length);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Debug Report (" + size + ")");
        builder.setView(scroll);
        builder.setPositiveButton("Copy", (d, w) -> {
            try {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("SilentGram debug report", report));
                Toast.makeText(context, "Debug report copied (" + size + ")", Toast.LENGTH_SHORT).show();
            } catch (Throwable e) {
                FileLog.e(e);
                Toast.makeText(context, "Could not copy report", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNeutralButton("Save to file", (d, w) -> saveToFile(activity, report));
        builder.setNegativeButton("Close", null);
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /** Extra fallback only — Copy is the way the report is shared. */
    private static void saveToFile(Activity activity, String report) {
        try {
            File dir = AndroidUtilities.getLogsDir();
            if (dir == null) {
                Toast.makeText(activity, "Could not save: no logs folder", Toast.LENGTH_SHORT).show();
                return;
            }
            String name = "silentgram-debug-report-" + new SimpleDateFormat("dd_MM_yyyy_HH_mm_ss", Locale.US).format(new Date()) + ".json";
            File file = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(report.getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(activity, "Saved to " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            FileLog.e(e);
            Toast.makeText(activity, "Could not save report", Toast.LENGTH_SHORT).show();
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private static String formatTime(long ms) {
        if (ms <= 0) {
            return "never";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }

    private static String formatUnixSeconds(long seconds) {
        if (seconds <= 0) {
            return "none";
        }
        return formatTime(seconds * 1000L);
    }
}
