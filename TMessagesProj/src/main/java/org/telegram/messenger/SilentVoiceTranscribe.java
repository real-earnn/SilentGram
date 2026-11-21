package org.telegram.messenger;

import android.content.Context;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TranscribeButton;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.UUID;

/**
 * SilentGram F8: Voice message to text (server-side, like Telegram's own button).
 *
 * The old version played the voice aloud and let Android's SpeechRecognizer
 * listen through the microphone — it failed silently whenever anything went
 * wrong (file missing, recognizer unavailable, startListening error), so a
 * tap could do nothing at all. This version sends the message to Telegram's
 * own transcription service (TL_messages_transcribeAudio — the exact request
 * TranscribeButton sends) and shows the result in a popup that ALWAYS opens:
 * a loading state first, then the text, or the error inside the popup.
 *
 * Popup (user spec): top = transcribed text (Telegram default = English);
 * below it a "Translate to other language" row with a language selector;
 * tap Translate and the translation appears right below, in that language.
 * Translation reuses SilentTranslate (Google Translate endpoint, no key).
 *
 * Premium gating mirrors TranscribeButton EXACTLY (onTap + transcribePressed):
 * Premium users and free-in-chat transcriptions go straight through; trial
 * users go through while they have trials left; a message that was already
 * transcribed shows its stored text without a new request; anyone else is
 * transcribed by Groq Whisper through our own relay (see below) instead of
 * seeing the lock message. Every check and request uses the message's own
 * account (messageObject.currentAccount), never a passed-in account. No
 * trial/cooldown logic is bypassed, and no Premium status is faked — the
 * server still enforces Premium on the Telegram path.
 *
 * Groq fallback (non-Premium only): the voice file is downloaded if needed,
 * then POSTed to our own Groq Whisper relay, which calls Groq's
 * whisper-large-v3-turbo and returns {text}. The result is shown in the
 * SAME popup, with the same Translate row below it. The relay holds the
 * Groq key server-side (groq_key.txt); the app never sees or stores any
 * key, and the relay URL is our own service address, not a secret. If the
 * relay or key is missing, or Groq fails, the error is shown INSIDE the
 * popup — never the Premium lock message, and the popup never closes
 * silently. A Groq rate limit shows "busy, try again in a bit".
 *
 * Quotas (the relay enforces them; the app only identifies the user):
 * every request also sends a "user_hash" multipart field — the SHA-256
 * hex of "silentgram-vtt:" + this account's Telegram user id, so the
 * raw id is never sent — and a "duration_seconds" field with the voice
 * message's duration in seconds (MessageObject.getDuration(), from the
 * audio attribute on the document; 0 when unavailable). The relay caps
 * each user at 20 transcriptions per UTC day and guards the shared Groq
 * org quota (2,000 transcriptions / 8 audio hours per day); both quota
 * errors are shown INSIDE the popup as plain text, like every other
 * Groq error. The hash is never logged in full (never logged at all).
 */
public class SilentVoiceTranscribe {

    private static final int MAX_TRIES = 3;
    private static final long RETRY_DELAY_MS = 5000;

    // Groq Whisper relay (own Silent Hosting project 629). Hardcoded on
    // purpose: it is our own service URL, not a secret — the Groq key
    // lives only in groq_key.txt on the server, never in the app.
    private static final String GROQ_RELAY_URL = "https://groq-whisper-relay-629.silenthost.site/transcribe";
    private static final long GROQ_MAX_FILE_BYTES = 25L * 1024L * 1024L; // Groq's 25 MB file limit
    private static final long GROQ_DOWNLOAD_TIMEOUT_MS = 60000;
    private static final long GROQ_DOWNLOAD_POLL_MS = 500;
    private static final int GROQ_CONNECT_TIMEOUT_MS = 15000;
    private static final int GROQ_READ_TIMEOUT_MS = 120000;

    // Plain literals (not R.string): new string resources would need a
    // strings.xml edit, which is outside this change. If these messages
    // ever need translating, move them to strings.xml as
    // SilentTranscribeGroqNotSetup / SilentTranscribeGroqBusy.
    private static final String GROQ_NOT_SETUP_TEXT = "Transcription service is not set up yet.";
    private static final String GROQ_BUSY_TEXT = "Transcription is busy — try again in a bit.";
    private static final String GROQ_DAILY_QUOTA_TEXT = "Today's free transcription limit is used up — try again tomorrow";
    private static final String GROQ_SHARED_QUOTA_TEXT = "Transcription service is busy today";

    // Prefix hashed together with the account's Telegram user id to form
    // the per-account identifier sent to the relay ("user_hash"). The raw
    // user id is never sent — only this SHA-256 hex, so the relay can cap
    // each user at 20 transcriptions/day without learning who they are.
    private static final String GROQ_USER_HASH_PREFIX = "silentgram-vtt:";

    // Last transcription outcome, for the debug report (in-memory only,
    // never persisted). The text is the user's own voice message content.
    private static volatile String lastVttSource; // "telegram", "groq" or "cached"
    private static volatile String lastVttText;
    private static volatile String lastVttError; // null on success
    private static volatile long lastVttAtMs;

    /** The Groq relay URL (our own service address, not a secret). */
    public static String getGroqRelayUrl() {
        return GROQ_RELAY_URL;
    }

    public static String getLastVttSource() {
        return lastVttSource;
    }

    public static String getLastVttText() {
        return lastVttText;
    }

    public static String getLastVttError() {
        return lastVttError;
    }

    public static long getLastVttAtMs() {
        return lastVttAtMs;
    }

    private static void recordVttResult(String source, String text, String error) {
        lastVttSource = source;
        lastVttText = text;
        lastVttError = error;
        lastVttAtMs = System.currentTimeMillis();
    }

    public static void transcribe(Context context, int account, MessageObject msg) {
        if (context == null) {
            return;
        }
        // The message's own account (TranscribeButton uses
        // messageObject.currentAccount for every check and request).
        final int acc = msg != null ? msg.currentAccount : account;

        // The popup opens FIRST, before any check or request, so a tap can
        // never silently do nothing. Every failure below is shown in it.
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));

        final TextView resultView = new TextView(context);
        resultView.setTextSize(15);
        resultView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        resultView.setText(LocaleController.getString(R.string.SilentTranscribeTranscribing));
        layout.addView(resultView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // "Translate to other language" row: label + language selector.
        TextView translateLabel = new TextView(context);
        translateLabel.setText(LocaleController.getString(R.string.SilentTranscribeTranslateTo));
        translateLabel.setTextSize(13);
        translateLabel.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        translateLabel.setPadding(0, AndroidUtilities.dp(16), 0, 0);
        layout.addView(translateLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final String[][] languages = SilentTranslate.getLanguages();
        final int[] targetIdx = {2}; // Urdu by default (transcription is English).
        final TextView targetLangView = new TextView(context);
        targetLangView.setText(languages[targetIdx[0]][1]);
        targetLangView.setTextSize(16);
        targetLangView.setTextColor(Theme.getColor(Theme.key_dialogTextLink));
        targetLangView.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
        targetLangView.setOnClickListener(v -> pickLanguage(context, targetIdx, () -> targetLangView.setText(languages[targetIdx[0]][1])));
        layout.addView(targetLangView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView translationLabel = new TextView(context);
        translationLabel.setText(LocaleController.getString(R.string.SilentTranscribeTranslation));
        translationLabel.setTextSize(13);
        translationLabel.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        translationLabel.setPadding(0, AndroidUtilities.dp(8), 0, 0);
        translationLabel.setVisibility(View.GONE);
        layout.addView(translationLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView translationView = new TextView(context);
        translationView.setTextSize(15);
        translationView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        translationView.setVisibility(View.GONE);
        layout.addView(translationView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(layout);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentTranscribeTitle));
        builder.setView(scrollView);
        builder.setNegativeButton(LocaleController.getString(R.string.Close), null);
        builder.setPositiveButton(LocaleController.getString(R.string.SilentTranslateButton), null);
        final AlertDialog dialog = builder.create();
        dialog.show();

        final String[] transcript = new String[1];

        // Translate button: stays disabled until there is text to translate,
        // and never dismisses the dialog (same pattern as SilentTranslate).
        if (dialog.getButton(DialogInterface.BUTTON_POSITIVE) != null) {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (transcript[0] == null || TextUtils.isEmpty(transcript[0])) {
                    return;
                }
                translationLabel.setVisibility(View.VISIBLE);
                translationView.setVisibility(View.VISIBLE);
                translationView.setText(LocaleController.getString(R.string.SilentTranslating));
                String tl = languages[targetIdx[0]][0];
                SilentTranslate.translateText(transcript[0], "auto", tl, acc, translated -> AndroidUtilities.runOnUIThread(() -> {
                    if (!dialog.isShowing()) {
                        return;
                    }
                    if (translated == null) {
                        translationView.setText(LocaleController.getString(R.string.SilentTranslateFailed));
                    } else {
                        translationView.setText(translated);
                    }
                }));
            });
        }

        if (msg == null || msg.messageOwner == null || !msg.isVoice()) {
            resultView.setText(LocaleController.getString(R.string.SilentTranscribeFailed));
            return;
        }
        if (!msg.isSent()) {
            resultView.setText(LocaleController.getString(R.string.SilentTranscribeNotSent));
            return;
        }
        // Telegram's own button shows a cached transcription without any
        // server request (TranscribeButton.onTap allows the tap when
        // voiceTranscription is non-empty; transcribePressed shows the
        // stored final text). Without this, a message the user already
        // transcribed shows our lock instead of its text.
        if (!TextUtils.isEmpty(msg.messageOwner.voiceTranscription)) {
            transcript[0] = msg.messageOwner.voiceTranscription;
            recordVttResult("cached", msg.messageOwner.voiceTranscription, null);
            resultView.setText(msg.messageOwner.voiceTranscription);
            if (dialog.getButton(DialogInterface.BUTTON_POSITIVE) != null) {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
            }
            if (msg.messageOwner.voiceTranscriptionFinal) {
                TranscribeButton.openVideoTranscription(msg);
                msg.messageOwner.voiceTranscriptionOpen = true;
                try {
                    TLRPC.InputPeer peer = MessagesController.getInstance(acc).getInputPeer(msg.messageOwner.peer_id);
                    if (peer != null) {
                        long dialogId = DialogObject.getPeerDialogId(peer);
                        MessagesStorage.getInstance(acc).updateMessageVoiceTranscriptionOpen(dialogId, msg.messageOwner.id, msg.messageOwner);
                        NotificationCenter.getInstance(acc).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, msg, null, null, (Boolean) true, (Boolean) true);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                return;
            }
            // Non-final cached text: shown above, but still re-request so it
            // can finish — the native button does the same.
            requestTranscription(context, acc, msg, dialog, resultView, transcript, 1);
            return;
        }
        // Eligibility is TranscribeButton.onTap exactly: Premium, free in
        // this chat, or a trial left (a cached transcription is handled
        // above). Anyone else is transcribed by Groq Whisper through our
        // own relay instead of seeing the Premium lock message — the
        // Telegram path is untouched, and no trial logic is bypassed.
        if (!UserConfig.getInstance(acc).isPremium()
                && !TranscribeButton.isFreeTranscribeInChat(msg)
                && !TranscribeButton.canTranscribeTrial(msg)) {
            transcribeViaGroq(acc, msg, dialog, resultView, transcript);
            return;
        }
        requestTranscription(context, acc, msg, dialog, resultView, transcript, 1);
    }

    /** Result of one Groq relay call: either transcribed text, or an error kind. */
    private static final class GroqResult {
        final String text;
        final String errorKind; // null on success; "not_setup", "busy", "daily_quota", "shared_quota" or "failed"

        private GroqResult(String text, String errorKind) {
            this.text = text;
            this.errorKind = errorKind;
        }

        static GroqResult ok(String text) {
            return new GroqResult(text, null);
        }

        static GroqResult error(String errorKind) {
            return new GroqResult(null, errorKind);
        }
    }

    /**
     * Groq Whisper path, for non-Premium users only. Runs on a background
     * thread: makes sure the voice file is downloaded, POSTs it to our own
     * relay, and shows the returned text in the SAME popup (so the Translate
     * button works on it exactly like on Telegram's text). Every failure is
     * shown inside the popup; the Premium lock message is never shown here.
     */
    private static void transcribeViaGroq(int account, MessageObject msg,
            AlertDialog dialog, TextView resultView, String[] transcript) {
        new Thread(() -> {
            GroqResult result = GroqResult.error("failed");
            TLRPC.Document document = msg.getDocument();
            if (document != null) {
                File file = FileLoader.getInstance(account).getPathToMessage(msg.messageOwner);
                if (file != null && !TextUtils.isEmpty(file.getAbsolutePath())) {
                    if (!file.exists() || file.length() == 0) {
                        // Not downloaded yet: start the download and wait for
                        // the file to appear (voice notes are small, so this
                        // is normally a second or two; 60s is the hard cap).
                        FileLoader.getInstance(account).loadFile(document, msg, 1, 0);
                        long waited = 0;
                        while (waited < GROQ_DOWNLOAD_TIMEOUT_MS && (!file.exists() || file.length() == 0)) {
                            try {
                                Thread.sleep(GROQ_DOWNLOAD_POLL_MS);
                            } catch (InterruptedException e) {
                                break;
                            }
                            waited += GROQ_DOWNLOAD_POLL_MS;
                        }
                    }
                    if (file.exists() && file.length() > 0 && file.length() <= GROQ_MAX_FILE_BYTES) {
                        String filename = FileLoader.getDocumentFileName(document);
                        if (TextUtils.isEmpty(filename)) {
                            filename = "voice.ogg";
                        }
                        String mimeType = document.mime_type;
                        if (TextUtils.isEmpty(mimeType)) {
                            mimeType = "audio/ogg";
                        }
                        // Voice duration in whole seconds, from the audio
                        // attribute on the document (MessageObject
                        // .getDuration()); 0 when the metadata has none —
                        // the relay treats 0 as "unknown" and clamps any
                        // absurd value itself, so this can never crash.
                        int durationSeconds = 0;
                        try {
                            durationSeconds = (int) Math.round(msg.getDuration());
                            if (durationSeconds < 0) {
                                durationSeconds = 0;
                            }
                        } catch (Throwable e) {
                            durationSeconds = 0;
                        }
                        result = uploadVoiceToGroq(file, filename, mimeType, computeGroqUserHash(account), durationSeconds);
                    }
                }
            }
            final GroqResult done = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (!dialog.isShowing()) {
                    return;
                }
                if (done.errorKind == null && !TextUtils.isEmpty(done.text)) {
                    transcript[0] = done.text;
                    recordVttResult("groq", done.text, null);
                    resultView.setText(done.text);
                    if (dialog.getButton(DialogInterface.BUTTON_POSITIVE) != null) {
                        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
                    }
                } else if ("not_setup".equals(done.errorKind)) {
                    recordVttResult("groq", null, "not_setup");
                    resultView.setText(GROQ_NOT_SETUP_TEXT);
                } else if ("busy".equals(done.errorKind)) {
                    recordVttResult("groq", null, "busy");
                    resultView.setText(GROQ_BUSY_TEXT);
                } else if ("daily_quota".equals(done.errorKind)) {
                    recordVttResult("groq", null, "daily_quota");
                    resultView.setText(GROQ_DAILY_QUOTA_TEXT);
                } else if ("shared_quota".equals(done.errorKind)) {
                    recordVttResult("groq", null, "shared_quota");
                    resultView.setText(GROQ_SHARED_QUOTA_TEXT);
                } else {
                    recordVttResult("groq", null, "failed");
                    resultView.setText(LocaleController.getString(R.string.SilentTranscribeFailed));
                }
            });
        }, "silent-groq-transcribe").start();
    }

    /**
     * Per-account identifier for the Groq relay's daily cap: the SHA-256
     * hex of "silentgram-vtt:" + this account's Telegram user id. The raw
     * user id is never sent, and the hash itself is never logged (not even
     * in part). Returns null when there is no signed-in user id, in which
     * case the relay puts the request in its "anonymous" bucket.
     */
    private static String computeGroqUserHash(int account) {
        try {
            long userId = UserConfig.getInstance(account).getClientUserId();
            if (userId <= 0) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((GROQ_USER_HASH_PREFIX + userId).getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * POSTs one voice file to the Groq relay as multipart/form-data and
     * returns the transcribed text. Besides the file it sends two text
     * fields: "user_hash" (SHA-256 of "silentgram-vtt:" + user id, or
     * omitted when there is none) and "duration_seconds" (voice duration
     * in whole seconds, 0 when unknown). Maps the relay's errors: 503 /
     * groq_key_missing means the server key is not set up yet; 429 /
     * groq_rate_limited means Groq is busy; 429 / daily_quota_used_up
     * means this user already used today's 20 free transcriptions; 429 /
     * shared_quota_exhausted means the shared Groq org quota for today is
     * used up; anything else is a plain failure. Never throws — every
     * problem comes back as a GroqResult.
     */
    private static GroqResult uploadVoiceToGroq(File file, String filename, String mimeType, String userHash, int durationSeconds) {
        String boundary = "----SilentGramGroq" + UUID.randomUUID().toString();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(GROQ_RELAY_URL).openConnection();
            conn.setConnectTimeout(GROQ_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(GROQ_READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", "SilentGram");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setChunkedStreamingMode(0);
            try (OutputStream out = conn.getOutputStream()) {
                String partHeader = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                        + "Content-Type: " + mimeType + "\r\n\r\n";
                out.write(partHeader.getBytes("UTF-8"));
                try (FileInputStream in = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                out.write("\r\n".getBytes("UTF-8"));
                if (!TextUtils.isEmpty(userHash)) {
                    String hashPart = "--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"user_hash\"\r\n\r\n"
                            + userHash + "\r\n";
                    out.write(hashPart.getBytes("UTF-8"));
                }
                String durationPart = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"duration_seconds\"\r\n\r\n"
                        + durationSeconds + "\r\n";
                out.write(durationPart.getBytes("UTF-8"));
                out.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
                out.flush();
            }
            int code = conn.getResponseCode();
            String body = readGroqResponse(conn, code);
            String error = null;
            String text = null;
            if (!TextUtils.isEmpty(body)) {
                try {
                    JSONObject json = new JSONObject(body);
                    text = json.optString("text", null);
                    error = json.optString("error", null);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            if (code == HttpURLConnection.HTTP_OK && !TextUtils.isEmpty(text)) {
                return GroqResult.ok(text);
            }
            if ("daily_quota_used_up".equals(error)) {
                return GroqResult.error("daily_quota");
            }
            if ("shared_quota_exhausted".equals(error)) {
                return GroqResult.error("shared_quota");
            }
            if (code == 503 || "groq_key_missing".equals(error)) {
                return GroqResult.error("not_setup");
            }
            if (code == 429 || "groq_rate_limited".equals(error)) {
                return GroqResult.error("busy");
            }
            return GroqResult.error("failed");
        } catch (Throwable e) {
            FileLog.e(e);
            return GroqResult.error("failed");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Reads the relay response body (error stream for HTTP >= 400). */
    private static String readGroqResponse(HttpURLConnection conn, int code) {
        InputStream stream = null;
        try {
            stream = code >= HttpURLConnection.HTTP_BAD_REQUEST ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }
    }

    private static void requestTranscription(Context context, int account, MessageObject msg,
            AlertDialog dialog, TextView resultView, String[] transcript, int attempt) {
        TLRPC.InputPeer peer = MessagesController.getInstance(account).getInputPeer(msg.messageOwner.peer_id);
        if (peer == null) {
            AndroidUtilities.runOnUIThread(() -> {
                if (dialog.isShowing()) {
                    resultView.setText(LocaleController.getString(R.string.SilentTranscribeFailed));
                }
            });
            return;
        }
        long dialogId = DialogObject.getPeerDialogId(peer);
        int messageId = msg.messageOwner.id;
        TLRPC.TL_messages_transcribeAudio req = new TLRPC.TL_messages_transcribeAudio();
        req.peer = peer;
        req.msg_id = messageId;
        int flags = 0;
        if (!UserConfig.getInstance(account).isPremium()) {
            flags |= ConnectionsManager.RequestFlagDoNotWaitFloodWait;
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            if (res instanceof TLRPC.TL_messages_transcribedAudio) {
                TLRPC.TL_messages_transcribedAudio r = (TLRPC.TL_messages_transcribedAudio) res;
                if ((r.flags & 2) != 0) {
                    MessagesController.getInstance(account).updateTranscribeAudioTrialCurrentNumber(r.trial_remains_num);
                    MessagesController.getInstance(account).updateTranscribeAudioTrialCooldownUntil(r.trial_remains_until_date);
                }
                final String text = r.text;
                final boolean isFinal = !r.pending;
                final long transcriptionId = r.transcription_id;
                AndroidUtilities.runOnUIThread(() -> {
                    if (!dialog.isShowing()) {
                        return;
                    }
                    if (!TextUtils.isEmpty(text)) {
                        transcript[0] = text;
                        recordVttResult("telegram", text, null);
                        if (isFinal) {
                            resultView.setText(text);
                            if (dialog.getButton(DialogInterface.BUTTON_POSITIVE) != null) {
                                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
                            }
                            TranscribeButton.finishTranscription(msg, transcriptionId, text);
                        } else {
                            resultView.setText(text + "\n\n" + LocaleController.getString(R.string.SilentTranscribeTranscribing));
                            MessagesStorage.getInstance(account).updateMessageVoiceTranscription(dialogId, messageId, text, msg.messageOwner);
                            if (attempt < MAX_TRIES) {
                                scheduleRetry(context, account, msg, dialog, resultView, transcript, attempt + 1);
                            } else {
                                resultView.setText(text + "\n\n" + LocaleController.getString(R.string.SilentTranscribeTakingLong));
                                if (dialog.getButton(DialogInterface.BUTTON_POSITIVE) != null) {
                                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
                                }
                            }
                        }
                    } else if (!isFinal) {
                        if (attempt < MAX_TRIES) {
                            scheduleRetry(context, account, msg, dialog, resultView, transcript, attempt + 1);
                        } else {
                            resultView.setText(LocaleController.getString(R.string.SilentTranscribeTakingLong));
                        }
                    } else {
                        resultView.setText(LocaleController.getString(R.string.SilentTranscribeFailed));
                    }
                });
            } else {
                if (err != null && err.text != null && err.text.startsWith("FLOOD_WAIT_")) {
                    recordVttResult("telegram", null, "flood_wait");
                    MessagesController.getInstance(account).updateTranscribeAudioTrialCurrentNumber(0);
                    MessagesController.getInstance(account).updateTranscribeAudioTrialCooldownUntil(
                            ConnectionsManager.getInstance(account).getCurrentTime() + Utilities.parseInt(err.text));
                    AndroidUtilities.runOnUIThread(() -> {
                        if (dialog.isShowing()) {
                            resultView.setText(LocaleController.getString(R.string.SilentTranscribePremium));
                        }
                        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, msg);
                        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateTranscriptionLock);
                    });
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (dialog.isShowing()) {
                        recordVttResult("telegram", null, "failed");
                        resultView.setText(LocaleController.getString(R.string.SilentTranscribeFailed));
                    }
                });
            }
        }, flags);
    }

    private static void scheduleRetry(Context context, int account, MessageObject msg,
            AlertDialog dialog, TextView resultView, String[] transcript, int nextAttempt) {
        AndroidUtilities.runOnUIThread(() -> {
            if (dialog.isShowing()) {
                requestTranscription(context, account, msg, dialog, resultView, transcript, nextAttempt);
            }
        }, RETRY_DELAY_MS);
    }

    private static void pickLanguage(Context context, int[] current, Runnable onPick) {
        String[][] languages = SilentTranslate.getLanguages();
        ArrayList<String> names = new ArrayList<>();
        ArrayList<Integer> idx = new ArrayList<>();
        for (int i = 0; i < languages.length; i++) {
            if (i == 0) {
                continue; // target language cannot be "auto"
            }
            names.add(languages[i][1]);
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
}
