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

import java.util.ArrayList;

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
 * transcribed shows its stored text without a new request; anyone else sees
 * the lock message in the popup instead of a request. Every check and
 * request uses the message's own account (messageObject.currentAccount),
 * never a passed-in account. No trial/cooldown logic is bypassed, and no
 * Premium status is faked — the server still enforces Premium.
 */
public class SilentVoiceTranscribe {

    private static final int MAX_TRIES = 3;
    private static final long RETRY_DELAY_MS = 5000;

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
        // above). Anyone else sees the lock message in the popup.
        if (!UserConfig.getInstance(acc).isPremium()
                && !TranscribeButton.isFreeTranscribeInChat(msg)
                && !TranscribeButton.canTranscribeTrial(msg)) {
            resultView.setText(LocaleController.getString(R.string.SilentTranscribePremium));
            return;
        }
        requestTranscription(context, acc, msg, dialog, resultView, transcript, 1);
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
