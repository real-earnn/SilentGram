package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;

/**
 * SilentGram F8: Voice message to text.
 *
 * On-device transcription: the voice message plays through the speaker
 * while Android's speech recognizer listens and writes the text.
 * Works best in a quiet room. (A server-side Whisper transcription would
 * be more accurate — this is the free on-device version, no key needed.)
 */
public class SilentVoiceTranscribe {

    public static void transcribe(Context context, int account, MessageObject msg) {
        if (context == null || msg == null || !msg.isVoice()) {
            return;
        }
        File audioFile = FileLoader.getInstance(account).getPathToAttach(msg.getDocument(), true);
        if (audioFile == null || !audioFile.exists()) {
            // Download it first, then transcribe.
            FileLoader.getInstance(account).loadFile(msg.getDocument(), msg, FileLoader.PRIORITY_HIGH, 0);
            SilentToast.show(context, LocaleController.getString(R.string.SilentTranscribeDownloading), android.widget.Toast.LENGTH_SHORT);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            SilentToast.show(context, LocaleController.getString(R.string.SilentTranscribeUnavailable), android.widget.Toast.LENGTH_SHORT);
            return;
        }

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        final TextView resultView = new TextView(context);
        resultView.setTextSize(15);
        resultView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        resultView.setText(LocaleController.getString(R.string.SilentTranscribeListening));
        layout.addView(resultView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextView hint = new TextView(context);
        hint.setText(LocaleController.getString(R.string.SilentTranscribeHint));
        hint.setTextSize(12);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        hint.setPadding(0, AndroidUtilities.dp(12), 0, 0);
        layout.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentTranscribeTitle));
        builder.setView(layout);
        builder.setNegativeButton(LocaleController.getString(R.string.Close), null);
        final AlertDialog dialog = builder.create();
        dialog.show();

        final StringBuilder transcript = new StringBuilder();
        final SpeechRecognizer recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        final MediaPlayer player = new MediaPlayer();
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}

            @Override
            public void onError(int error) {
                stopAll();
                AndroidUtilities.runOnUIThread(() -> {
                    if (transcript.length() == 0) {
                        resultView.setText(LocaleController.getString(R.string.SilentTranscribeFailed));
                    }
                });
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) {
                    transcript.append(list.get(0)).append(" ");
                    final String text = transcript.toString().trim();
                    AndroidUtilities.runOnUIThread(() -> resultView.setText(text));
                }
                stopAll();
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) {
                    final String text = (transcript.toString() + list.get(0)).trim();
                    AndroidUtilities.runOnUIThread(() -> resultView.setText(text));
                }
            }

            @Override public void onEvent(int eventType, Bundle params) {}

            private void stopAll() {
                try {
                    if (player.isPlaying()) {
                        player.stop();
                    }
                    player.release();
                } catch (Exception e) {
                    // Ignore.
                }
                try {
                    recognizer.destroy();
                } catch (Exception e) {
                    // Ignore.
                }
            }
        });

        try {
            player.setDataSource(audioFile.getAbsolutePath());
            player.prepare();
            player.setOnCompletionListener(mp -> {
                // Give the recognizer a moment to finish, then stop.
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        recognizer.stopListening();
                    } catch (Exception e) {
                        // Ignore.
                    }
                }, 800);
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizer.startListening(intent);
            player.start();
        } catch (Exception e) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            SilentToast.show(context, LocaleController.getString(R.string.SilentTranscribeFailed), android.widget.Toast.LENGTH_SHORT);
            dialog.dismiss();
        }
    }
}
