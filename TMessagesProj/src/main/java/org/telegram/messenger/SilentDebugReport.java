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

import org.json.JSONObject;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ProxyListActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * SilentGram v2.0.21: in-app Debug Report.
 *
 * Trigger: a 5-second hold on the Chat Lock row of the three-dots menu
 * of an open chat (see ChatActivity.attachSilentChatLockDebugHold). The
 * report is built on demand, at hold time, from live in-memory state.
 * Lean by design (v2.0.22): only app, device, account, calls,
 * floating and vtt — the Groq, proxy and logs sections were removed.
 * The dialog shows the
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
     * Build the report off the UI thread (live in-memory state only)
     * and show the dialog on the UI thread.
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
        putSection(root, "calls", buildCalls());
        putSection(root, "floating", buildFloating());
        putSection(root, "vtt", buildVtt(account));
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


    private static JSONObject buildCalls() {
        try {
            JSONObject calls = new JSONObject();
            String facts = ProxyListActivity.getLastCallFactsText();
            calls.put("lastCallFacts", facts != null ? cap(facts, 2000) : "no call yet");
            // SilentGram v2.0.24: group-type attempts (group /
            // invite-link / conference / livestream) have their own
            // facts line — the 1:1 line above never recorded them.
            String groupFacts = ProxyListActivity.getLastGroupCallFactsText();
            calls.put("lastGroupCallFacts", groupFacts != null ? cap(groupFacts, 2000) : "no group call yet");
            calls.put("relayHost", VoIPService.SILENT_CALL_RELAY_HOST);
            calls.put("relayPort", VoIPService.SILENT_CALL_RELAY_PORT);
            String relayStatus = ProxyListActivity.getLastCallRelayStatusText();
            calls.put("relayStatus", relayStatus != null ? relayStatus : "not probed yet");
            long relayOkMs = ProxyListActivity.getLastCallRelayOkMs();
            calls.put("relayLastOk", relayOkMs > 0 ? formatTime(relayOkMs) : "never");
            // VoIPService.makeInstance no longer forces TCP (forceTcp =
            // false, endpoint type UDP_RELAY since v2.0.22): the tgcalls
            // fork routes reflector UDP media through the SOCKS5 relay.
            calls.put("forcedTcp", false);
            calls.put("endpointType", "UDP_RELAY");
            calls.put("note", "Per-endpoint TCP flags and the full endpoint list are in the FileLog (SG-CALL-RELAY line), not in this report.");
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
            floating.put("minimized", fw.isMinimized());
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
            display.put("densityDpi", fw.getDisplayDensityDpi());
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
            JSONObject last = new JSONObject();
            String source = SilentVoiceTranscribe.getLastVttSource();
            if (source == null) {
                last.put("state", "no transcription yet");
            } else {
                last.put("source", source);
                String text = SilentVoiceTranscribe.getLastVttText();
                last.put("text", text == null ? JSONObject.NULL : cap(text, 2000));
                String error = SilentVoiceTranscribe.getLastVttError();
                last.put("error", error == null ? JSONObject.NULL : cap(error, 500));
                last.put("at", formatTime(SilentVoiceTranscribe.getLastVttAtMs()));
            }
            vtt.put("last", last);
            return vtt;
        } catch (Throwable e) {
            return sectionError("vtt", e);
        }
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
                // v2.0.22: verify the copy with a read-back before
                // claiming success — from the floating activity the
                // system can silently drop setPrimaryClip (background
                // clipboard restriction), and the old code toasted
                // "copied" unconditionally. Application context: the
                // dialog's activity may be on the virtual display.
                Context appCtx = ApplicationLoader.applicationContext;
                ClipboardManager cm = (ClipboardManager) appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) {
                    Toast.makeText(appCtx, "Could not copy report (no clipboard)", Toast.LENGTH_SHORT).show();
                    return;
                }
                cm.setPrimaryClip(ClipData.newPlainText("SilentGram debug report", report));
                boolean copied = false;
                try {
                    ClipData clip = cm.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0) != null) {
                        CharSequence pasted = clip.getItemAt(0).getText();
                        copied = pasted != null && report.equals(pasted.toString());
                    }
                } catch (Throwable ignored) {
                }
                if (copied) {
                    Toast.makeText(appCtx, "Debug report copied (" + size + ")", Toast.LENGTH_SHORT).show();
                } else {
                    FileLog.e("SilentDebugReport: clipboard read-back mismatch, copy failed");
                    Toast.makeText(appCtx, "Copy failed — use Save to file", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable e) {
                FileLog.e(e);
                Toast.makeText(ApplicationLoader.applicationContext, "Could not copy report", Toast.LENGTH_SHORT).show();
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

    /** Cap an unbounded field so the report (and the clipboard clip)
     * can never grow large enough to break the binder transaction. */
    private static String cap(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "… (truncated)";
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
