package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;

/**
 * SilentGram translate.
 *
 * Translates a message (or the selected part of it) with the free Google
 * Translate endpoint — no key, no account. The dialog shows the source text,
 * lets the user pick the source language (auto-detect by default) and the
 * target language, and shows the full translation when they tap Translate.
 *
 * Endpoint: https://translate.googleapis.com/translate_a/single
 *   ?client=gtx&sl=<source>&tl=<target>&dt=t&q=<text>
 *
 * The Google endpoint is called over plain HTTPS — NOT through MTProto —
 * so it works even if MTProto is down, but it fails on any network that
 * blocks that host (some WiFi does, while mobile data works). If the
 * Google call fails, translateText retries ONCE via Telegram's own
 * server-side translate over MTProto (the exact TL_messages_translateText
 * call TranslateController makes for stories), which works wherever
 * Telegram works. The retry runs only after the primary has already
 * failed, so the working path is untouched; if both fail, callers show
 * the same "Translation failed" message as before.
 */
public class SilentTranslate {

    private static final String ENDPOINT = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=%s&tl=%s&dt=t&q=%s";

    // A small, useful language list: code + display name.
    private static final String[][] LANGUAGES = {
            {"auto", "Auto-detect"},
            {"en", "English"},
            {"ur", "Urdu"},
            {"ar", "Arabic"},
            {"hi", "Hindi"},
            {"es", "Spanish"},
            {"fr", "French"},
            {"de", "German"},
            {"ru", "Russian"},
            {"tr", "Turkish"},
            {"fa", "Persian"},
            {"ps", "Pashto"},
            {"bn", "Bengali"},
            {"zh-CN", "Chinese"},
            {"ja", "Japanese"},
            {"pt", "Portuguese"},
            {"it", "Italian"},
            {"nl", "Dutch"}
    };

    private static int targetIndex = 1; // English by default

    /** Show the translate dialog for the given text. Must be called on the UI thread. */
    public static void show(Context context, String text) {
        if (context == null || TextUtils.isEmpty(text)) {
            return;
        }
        Activity activity = AndroidUtilities.findActivity(context);
        if (activity == null) {
            return;
        }
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));

        TextView sourceLabel = new TextView(context);
        sourceLabel.setText(LocaleController.getString(R.string.SilentTranslateFrom));
        sourceLabel.setTextSize(13);
        sourceLabel.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        layout.addView(sourceLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView sourceLangView = new TextView(context);
        sourceLangView.setText(LANGUAGES[0][1]);
        sourceLangView.setTextSize(16);
        sourceLangView.setTextColor(Theme.getColor(Theme.key_dialogTextLink));
        sourceLangView.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(8));
        final int[] sourceIndex = {0};
        sourceLangView.setOnClickListener(v -> pickLanguage(context, sourceIndex, true, () -> sourceLangView.setText(LANGUAGES[sourceIndex[0]][1])));
        layout.addView(sourceLangView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView sourceText = new TextView(context);
        sourceText.setText(text);
        sourceText.setTextSize(15);
        sourceText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        sourceText.setPadding(0, 0, 0, AndroidUtilities.dp(12));
        layout.addView(sourceText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView targetLabel = new TextView(context);
        targetLabel.setText(LocaleController.getString(R.string.SilentTranslateTo));
        targetLabel.setTextSize(13);
        targetLabel.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        layout.addView(targetLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView targetLangView = new TextView(context);
        targetLangView.setText(LANGUAGES[targetIndex][1]);
        targetLangView.setTextSize(16);
        targetLangView.setTextColor(Theme.getColor(Theme.key_dialogTextLink));
        targetLangView.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(8));
        final int[] targetIdx = {targetIndex};
        targetLangView.setOnClickListener(v -> pickLanguage(context, targetIdx, false, () -> {
            targetIndex = targetIdx[0];
            targetLangView.setText(LANGUAGES[targetIndex][1]);
        }));
        layout.addView(targetLangView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView resultView = new TextView(context);
        resultView.setTextSize(15);
        resultView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        resultView.setPadding(0, AndroidUtilities.dp(8), 0, 0);
        resultView.setVisibility(android.view.View.GONE);
        layout.addView(resultView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(layout);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.TranslateMessage));
        builder.setView(scrollView);
        builder.setNegativeButton(LocaleController.getString(R.string.Close), null);
        builder.setPositiveButton(LocaleController.getString(R.string.SilentTranslateButton), null);
        AlertDialog dialog = builder.create();
        dialog.show();
        // Override the positive button so the dialog stays open while translating.
        if (dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE) != null) {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                resultView.setVisibility(android.view.View.VISIBLE);
                resultView.setText(LocaleController.getString(R.string.SilentTranslating));
                String sl = LANGUAGES[sourceIndex[0]][0];
                String tl = LANGUAGES[targetIndex][0];
                translateInBackground(text, sl, tl, translated -> AndroidUtilities.runOnUIThread(() -> {
                    if (translated == null) {
                        resultView.setText(LocaleController.getString(R.string.SilentTranslateFailed));
                    } else {
                        resultView.setText(translated);
                    }
                }));
            });
        }
    }

    private static void pickLanguage(Context context, int[] current, boolean allowAuto, Runnable onPick) {
        ArrayList<String> names = new ArrayList<>();
        ArrayList<Integer> idx = new ArrayList<>();
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (!allowAuto && i == 0) {
                continue; // target language cannot be "auto"
            }
            names.add(LANGUAGES[i][1]);
            idx.add(i);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentChooseLanguage));
        builder.setItems(names.toArray(new CharSequence[0]), (d, which) -> {
            current[0] = idx.get(which);
            if (onPick != null) {
                onPick.run();
            }
        });
        builder.show();
    }

    /** Public wrapper for whole-chat translation (F1). `done` runs with the translated text (null on failure). */
    public static void translateText(String text, String sourceLang, String targetLang, java.util.function.Consumer<String> done) {
        translateText(text, sourceLang, targetLang, UserConfig.selectedAccount, done);
    }

    /** Same, with an explicit account (used by the Voice-to-Text popup, which resolves the message's own account). */
    public static void translateText(String text, String sourceLang, String targetLang, int account, java.util.function.Consumer<String> done) {
        translateInBackground(text, sourceLang, targetLang, account, done);
    }

    /** The language codes/names table (for pickers). */
    public static String[][] getLanguages() {
        return LANGUAGES;
    }

    private static void translateInBackground(String text, String sl, String tl, java.util.function.Consumer<String> done) {
        translateInBackground(text, sl, tl, UserConfig.selectedAccount, done);
    }

    private static void translateInBackground(String text, String sl, String tl, int account, java.util.function.Consumer<String> done) {
        new Thread(() -> {
            String result = null;
            HttpURLConnection conn = null;
            try {
                String url = String.format(ENDPOINT, sl, tl, URLEncoder.encode(text, "UTF-8"));
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "SilentGram");
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                    }
                    result = parseTranslation(sb.toString());
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            // Primary (Google) unchanged above. Second try ONLY when it
            // failed: Telegram's own server-side translate over MTProto,
            // which works wherever Telegram works. If this also fails,
            // done gets null and callers show the existing failure text.
            if (result == null) {
                translateViaTelegram(text, tl, account, done);
                return;
            }
            done.accept(result);
        }, "silent-translate").start();
    }

    /** Second try, ONLY after the Google endpoint failed: Telegram's own
     * server-side translate (TL_messages_translateText with raw text —
     * the exact call TranslateController makes for stories). Runs over
     * MTProto, so it works wherever Telegram itself works. Best-effort:
     * any failure just yields null, like the primary failing. */
    private static void translateViaTelegram(String text, String toLang, int account, java.util.function.Consumer<String> done) {
        try {
            TLRPC.TL_messages_translateText req = new TLRPC.TL_messages_translateText();
            req.flags |= 2; // raw text (no peer/id)
            TLRPC.TL_textWithEntities part = new TLRPC.TL_textWithEntities();
            part.text = text;
            req.text.add(part);
            req.to_lang = TranslateController.normalizeLanguage(toLang);
            ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
                String out = null;
                if (res instanceof TLRPC.TL_messages_translateResult) {
                    ArrayList<TLRPC.TL_textWithEntities> r = ((TLRPC.TL_messages_translateResult) res).result;
                    if (r != null && !r.isEmpty() && r.get(0) != null && !TextUtils.isEmpty(r.get(0).text)) {
                        out = r.get(0).text;
                    }
                }
                done.accept(out);
            });
        } catch (Throwable e) {
            FileLog.e(e);
            done.accept(null);
        }
    }

    /** The endpoint returns [[["translated","original",...],...],...] — join the translated parts. */
    private static String parseTranslation(String json) {
        try {
            JSONArray root = new JSONArray(json);
            JSONArray sentences = root.optJSONArray(0);
            if (sentences == null) {
                return null;
            }
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < sentences.length(); i++) {
                JSONArray part = sentences.optJSONArray(i);
                if (part != null && part.length() > 0) {
                    out.append(part.optString(0, ""));
                }
            }
            return out.length() > 0 ? out.toString() : null;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }
}
