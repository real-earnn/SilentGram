package org.telegram.ui;

import android.content.Context;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Iterator;

/**
 * SilentGram E8: Bot API Tester.
 * A lightweight Telegram Bot API tester: paste a bot token, pick a method
 * (getMe / sendMessage / getUpdates / ...), add JSON params, and see the
 * raw JSON response. No server of ours is involved — the request goes
 * straight from the phone to api.telegram.org.
 */
public class SilentBotTesterActivity extends BaseFragment {

    private EditText tokenField;
    private EditText methodField;
    private EditText paramsField;
    private TextView resultView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentBotTesterTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        fragmentView = scrollView;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        scrollView.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        tokenField = addField(context, layout, LocaleController.getString(R.string.SilentBotTokenHint));
        methodField = addField(context, layout, LocaleController.getString(R.string.SilentBotMethodHint));
        methodField.setText("getMe");
        paramsField = addField(context, layout, LocaleController.getString(R.string.SilentBotParamsHint));
        paramsField.setText("{}");
        paramsField.setMinLines(3);
        paramsField.setGravity(Gravity.TOP);

        TextView sendBtn = new TextView(context);
        sendBtn.setText(LocaleController.getString(R.string.SilentBotSend));
        sendBtn.setTextSize(16);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setTextColor(0xFFFFFFFF);
        sendBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), 0xFF6D28D9));
        sendBtn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        sendBtn.setOnClickListener(v -> sendRequest());
        layout.addView(sendBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16));

        resultView = new TextView(context);
        resultView.setTextSize(13);
        resultView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        resultView.setMovementMethod(new ScrollingMovementMethod());
        resultView.setText(LocaleController.getString(R.string.SilentBotResultHint));
        layout.addView(resultView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return fragmentView;
    }

    private EditText addField(Context context, LinearLayout layout, String hint) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setTextSize(15);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        layout.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
        return field;
    }

    private void sendRequest() {
        final String token = tokenField.getText().toString().trim();
        final String method = methodField.getText().toString().trim();
        final String paramsJson = paramsField.getText().toString().trim();
        if (token.isEmpty() || method.isEmpty()) {
            resultView.setText(LocaleController.getString(R.string.SilentBotNeedToken));
            return;
        }
        resultView.setText(LocaleController.getString(R.string.SilentBotSending));
        new Thread(() -> {
            String result;
            try {
                URL url = new URL("https://api.telegram.org/bot" + token + "/" + method);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                StringBuilder body = new StringBuilder();
                try {
                    JSONObject params = new JSONObject(paramsJson.isEmpty() ? "{}" : paramsJson);
                    Iterator<String> keys = params.keys();
                    boolean first = true;
                    while (keys.hasNext()) {
                        String k = keys.next();
                        if (!first) {
                            body.append("&");
                        }
                        first = false;
                        body.append(URLEncoder.encode(k, "UTF-8")).append("=")
                                .append(URLEncoder.encode(params.optString(k), "UTF-8"));
                    }
                } catch (Exception e) {
                    body.append("");
                }
                byte[] bytes = body.toString().getBytes("UTF-8");
                conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                OutputStream os = conn.getOutputStream();
                os.write(bytes);
                os.flush();
                os.close();
                int code = conn.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                result = "HTTP " + code + "\n\n" + prettyJson(sb.toString());
            } catch (Exception e) {
                result = LocaleController.getString(R.string.SilentBotError) + ": " + e.getMessage();
            }
            final String out = result;
            AndroidUtilities.runOnUIThread(() -> resultView.setText(out));
        }).start();
    }

    private String prettyJson(String raw) {
        try {
            JSONObject obj = new JSONObject(raw);
            return obj.toString(2);
        } catch (Exception e) {
            return raw;
        }
    }
}
