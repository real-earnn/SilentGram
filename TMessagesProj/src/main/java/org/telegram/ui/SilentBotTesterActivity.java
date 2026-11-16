package org.telegram.ui;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * SilentGram E8: Bot API Tester.
 * Two modes, switched by the top-right ActionBar icon:
 *
 * USER mode (default) — a visual message builder: bot token (with a
 * "Check your bot token" getMe check), target chat, message type
 * (Send Text / Send Text + Media / Send Reply / Reply Keyboard Button /
 * Send Inline Button), text, photo URL/file_id for media, reply-to
 * message id, and an inline/reply button editor (name + style +
 * add/remove/reorder). Send posts via api.telegram.org and shows the
 * result. No local-file picker: Bot API photo upload from a local file
 * needs multipart/form-data, which this tester does not implement — media
 * is sent by direct URL or file_id, which works with the form-encoded
 * POST below.
 *
 * DEVELOPER mode — the original raw view (method field + JSON params +
 * Send), kept working exactly as before.
 *
 * NOTE: Telegram's Bot API 9.4 supports a real "style" field on inline
 * keyboard buttons (primary/success/danger) — it is sent on every button.
 * The style is ALSO kept in callback_data ("primary:Name", "success:Name",
 * "danger:Name") so the bot's own code can tell buttons apart.
 *
 * No server of ours is involved — every request goes straight from the
 * phone to api.telegram.org.
 */
public class SilentBotTesterActivity extends BaseFragment {

    private static final int MENU_TOGGLE_MODE = 1;

    private static final int TYPE_TEXT = 0;
    private static final int TYPE_MEDIA = 1;
    private static final int TYPE_REPLY = 2;
    private static final int TYPE_REPLY_KEYBOARD = 3;
    private static final int TYPE_INLINE = 4;

    private static final int STYLE_PRIMARY = 0;
    private static final int STYLE_SUCCESS = 1;
    private static final int STYLE_DANGER = 2;

    private static final String[] STYLE_KEYS = new String[]{"primary", "success", "danger"};

    /** Callback for the raw Bot API POST, invoked on the UI thread. */
    private interface BotApiCallback {
        void onResult(int code, String body);
    }

    private static class UserButton {
        String name;
        int style;

        UserButton(String name, int style) {
            this.name = name;
            this.style = style;
        }
    }

    private boolean userMode = true;
    private ActionBarMenuItem modeItem;

    private LinearLayout userLayout;
    private LinearLayout devLayout;

    // ---- developer (raw) mode views: unchanged behaviour ----
    private EditText tokenField;
    private EditText methodField;
    private EditText paramsField;
    private TextView resultView;

    // ---- user mode views ----
    private EditText userTokenField;
    private TextView tokenStatusView;
    private EditText chatField;
    private Spinner typeSpinner;
    private EditText userTextField;
    private EditText mediaField;
    private TextView mediaNoteView;
    private EditText replyIdField;
    private LinearLayout buttonsSection;
    private LinearLayout buttonsContainer;
    private TextView userResultView;
    private int selectedType = TYPE_TEXT;
    private final ArrayList<UserButton> userButtons = new ArrayList<>();

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentBotTesterTitle));
        modeItem = actionBar.createMenu().addItem(MENU_TOGGLE_MODE, R.drawable.formatting_code);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_TOGGLE_MODE) {
                    toggleMode();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        fragmentView = scrollView;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        scrollView.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        buildUserMode(context, layout);
        buildDevMode(context, layout);
        applyMode();

        return fragmentView;
    }

    private void toggleMode() {
        userMode = !userMode;
        applyMode();
    }

    private void applyMode() {
        userLayout.setVisibility(userMode ? View.VISIBLE : View.GONE);
        devLayout.setVisibility(userMode ? View.GONE : View.VISIBLE);
        if (modeItem != null) {
            // Icon shows the mode you switch TO: code icon in user mode,
            // pencil icon in developer mode.
            modeItem.setIcon(userMode ? R.drawable.formatting_code : R.drawable.msg_edit);
        }
    }

    // ------------------------------------------------------------------
    // USER mode
    // ------------------------------------------------------------------

    private void buildUserMode(Context context, LinearLayout layout) {
        userLayout = new LinearLayout(context);
        userLayout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(userLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        userTokenField = addField(context, userLayout, LocaleController.getString(R.string.SilentBotTokenHint));

        TextView checkBtn = new TextView(context);
        checkBtn.setText(LocaleController.getString(R.string.SilentBotCheckToken));
        checkBtn.setTextSize(15);
        checkBtn.setGravity(Gravity.CENTER);
        checkBtn.setTextColor(0xFFFFFFFF);
        checkBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), 0xFF2563EB));
        checkBtn.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
        checkBtn.setOnClickListener(v -> checkToken());
        userLayout.addView(checkBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        tokenStatusView = new TextView(context);
        tokenStatusView.setTextSize(14);
        tokenStatusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        userLayout.addView(tokenStatusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        chatField = addField(context, userLayout, LocaleController.getString(R.string.SilentBotChatHint));

        typeSpinner = new Spinner(context);
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, new String[]{
                LocaleController.getString(R.string.SilentBotTypeText),
                LocaleController.getString(R.string.SilentBotTypeMedia),
                LocaleController.getString(R.string.SilentBotTypeReply),
                LocaleController.getString(R.string.SilentBotTypeReplyKeyboard),
                LocaleController.getString(R.string.SilentBotTypeInline)
        });
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(typeAdapter);
        typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedType = position;
                updateTypeVisibility();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        userLayout.addView(typeSpinner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        userTextField = addField(context, userLayout, LocaleController.getString(R.string.SilentBotTextHint));
        userTextField.setMinLines(2);
        userTextField.setGravity(Gravity.TOP);

        mediaField = addField(context, userLayout, LocaleController.getString(R.string.SilentBotMediaHint));
        mediaNoteView = new TextView(context);
        mediaNoteView.setText(LocaleController.getString(R.string.SilentBotMediaNote));
        mediaNoteView.setTextSize(13);
        mediaNoteView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        userLayout.addView(mediaNoteView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        replyIdField = addField(context, userLayout, LocaleController.getString(R.string.SilentBotReplyIdHint));
        replyIdField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);

        // Button editor (reply keyboard + inline buttons).
        buttonsSection = new LinearLayout(context);
        buttonsSection.setOrientation(LinearLayout.VERTICAL);
        userLayout.addView(buttonsSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView buttonsTitle = new TextView(context);
        buttonsTitle.setText(LocaleController.getString(R.string.SilentBotButtonsTitle));
        buttonsTitle.setTextSize(15);
        buttonsTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        buttonsSection.addView(buttonsTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        buttonsContainer = new LinearLayout(context);
        buttonsContainer.setOrientation(LinearLayout.VERTICAL);
        buttonsSection.addView(buttonsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView addBtn = new TextView(context);
        addBtn.setText(LocaleController.getString(R.string.SilentBotAddButton));
        addBtn.setTextSize(15);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setTextColor(0xFFFFFFFF);
        addBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), 0xFF6D28D9));
        addBtn.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
        addBtn.setOnClickListener(v -> {
            userButtons.add(new UserButton("", STYLE_PRIMARY));
            rebuildButtonsEditor(context);
        });
        buttonsSection.addView(addBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 12));
        rebuildButtonsEditor(context);

        TextView userSendBtn = new TextView(context);
        userSendBtn.setText(LocaleController.getString(R.string.SilentBotSendMessage));
        userSendBtn.setTextSize(16);
        userSendBtn.setGravity(Gravity.CENTER);
        userSendBtn.setTextColor(0xFFFFFFFF);
        userSendBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), 0xFF6D28D9));
        userSendBtn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        userSendBtn.setOnClickListener(v -> sendUserMessage());
        userLayout.addView(userSendBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 16));

        userResultView = new TextView(context);
        userResultView.setTextSize(13);
        userResultView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        userResultView.setMovementMethod(new ScrollingMovementMethod());
        userResultView.setText(LocaleController.getString(R.string.SilentBotResultHint));
        userLayout.addView(userResultView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        updateTypeVisibility();
    }

    private void updateTypeVisibility() {
        boolean needMedia = selectedType == TYPE_MEDIA;
        boolean needReplyId = selectedType == TYPE_REPLY;
        boolean needButtons = selectedType == TYPE_REPLY_KEYBOARD || selectedType == TYPE_INLINE;
        mediaField.setVisibility(needMedia ? View.VISIBLE : View.GONE);
        mediaNoteView.setVisibility(needMedia ? View.VISIBLE : View.GONE);
        replyIdField.setVisibility(needReplyId ? View.VISIBLE : View.GONE);
        buttonsSection.setVisibility(needButtons ? View.VISIBLE : View.GONE);
    }

    private void rebuildButtonsEditor(Context context) {
        buttonsContainer.removeAllViews();
        for (int i = 0; i < userButtons.size(); i++) {
            final int index = i;
            final UserButton button = userButtons.get(i);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);

            EditText nameField = new EditText(context);
            nameField.setHint(LocaleController.getString(R.string.SilentBotButtonNameHint));
            nameField.setTextSize(15);
            nameField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            nameField.setText(button.name);
            nameField.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    button.name = s.toString();
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
            row.addView(nameField, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

            Spinner styleSpinner = new Spinner(context);
            ArrayAdapter<String> styleAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, new String[]{
                    LocaleController.getString(R.string.SilentBotStylePrimary),
                    LocaleController.getString(R.string.SilentBotStyleSuccess),
                    LocaleController.getString(R.string.SilentBotStyleDanger)
            });
            styleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            styleSpinner.setAdapter(styleAdapter);
            styleSpinner.setSelection(button.style);
            styleSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    button.style = position;
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            row.addView(styleSpinner, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

            row.addView(makeMiniButton(context, "↑", v -> {
                if (index > 0) {
                    userButtons.remove(index);
                    userButtons.add(index - 1, button);
                    rebuildButtonsEditor(context);
                }
            }));
            row.addView(makeMiniButton(context, "↓", v -> {
                if (index < userButtons.size() - 1) {
                    userButtons.remove(index);
                    userButtons.add(index + 1, button);
                    rebuildButtonsEditor(context);
                }
            }));
            row.addView(makeMiniButton(context, "✕", v -> {
                userButtons.remove(index);
                rebuildButtonsEditor(context);
            }));

            buttonsContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
    }

    private TextView makeMiniButton(Context context, String label, View.OnClickListener listener) {
        TextView btn = new TextView(context);
        btn.setText(label);
        btn.setTextSize(16);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        btn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(8), 0xFFE5E7EB));
        btn.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(6));
        btn.setOnClickListener(listener);
        LinearLayout.LayoutParams params = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 4, 0);
        btn.setLayoutParams(params);
        return btn;
    }

    /** (a) "Check your bot token": getMe with the entered token. */
    private void checkToken() {
        final String token = userTokenField.getText().toString().trim();
        if (token.isEmpty()) {
            tokenStatusView.setText(LocaleController.getString(R.string.SilentBotNeedToken));
            return;
        }
        tokenStatusView.setText(LocaleController.getString(R.string.SilentBotSending));
        postBotApi(token, "getMe", "{}", (code, body) -> {
            try {
                JSONObject obj = new JSONObject(body);
                if (obj.optBoolean("ok", false)) {
                    JSONObject result = obj.optJSONObject("result");
                    String name = result.optString("first_name");
                    if (result.has("last_name")) {
                        name += " " + result.optString("last_name");
                    }
                    String username = result.optString("username");
                    long id = result.optLong("id");
                    tokenStatusView.setText(LocaleController.formatString(R.string.SilentBotTokenValid, name, username, String.valueOf(id)));
                    tokenStatusView.setTextColor(0xFF2E9E4F);
                } else {
                    tokenStatusView.setText(LocaleController.formatString(R.string.SilentBotTokenInvalid, obj.optString("description")));
                    tokenStatusView.setTextColor(0xFFD33A2C);
                }
            } catch (Exception e) {
                tokenStatusView.setText(LocaleController.formatString(R.string.SilentBotTokenInvalid, body == null ? "" : body));
                tokenStatusView.setTextColor(0xFFD33A2C);
            }
        });
    }

    private void sendUserMessage() {
        final String token = userTokenField.getText().toString().trim();
        final String chat = chatField.getText().toString().trim();
        final String text = userTextField.getText().toString();
        if (token.isEmpty()) {
            userResultView.setText(LocaleController.getString(R.string.SilentBotNeedToken));
            return;
        }
        if (chat.isEmpty()) {
            userResultView.setText(LocaleController.getString(R.string.SilentBotNeedChat));
            return;
        }
        boolean needText = selectedType != TYPE_MEDIA;
        if (needText && text.trim().isEmpty()) {
            userResultView.setText(LocaleController.getString(R.string.SilentBotNeedText));
            return;
        }
        if (selectedType == TYPE_MEDIA && mediaField.getText().toString().trim().isEmpty()) {
            userResultView.setText(LocaleController.getString(R.string.SilentBotNeedMedia));
            return;
        }
        if (selectedType == TYPE_REPLY && replyIdField.getText().toString().trim().isEmpty()) {
            userResultView.setText(LocaleController.getString(R.string.SilentBotNeedReplyId));
            return;
        }
        if ((selectedType == TYPE_REPLY_KEYBOARD || selectedType == TYPE_INLINE) && userButtons.isEmpty()) {
            userResultView.setText(LocaleController.getString(R.string.SilentBotNeedButtons));
            return;
        }

        try {
            JSONObject params = new JSONObject();
            putChatId(params, chat);
            String method;
            switch (selectedType) {
                case TYPE_MEDIA:
                    method = "sendPhoto";
                    params.put("photo", mediaField.getText().toString().trim());
                    params.put("caption", text);
                    break;
                case TYPE_REPLY:
                    method = "sendMessage";
                    params.put("text", text);
                    params.put("reply_to_message_id", Long.parseLong(replyIdField.getText().toString().trim()));
                    break;
                case TYPE_REPLY_KEYBOARD: {
                    method = "sendMessage";
                    params.put("text", text);
                    JSONObject markup = new JSONObject();
                    JSONArray keyboard = new JSONArray();
                    for (UserButton b : userButtons) {
                        JSONArray row = new JSONArray();
                        // Bot API 9.4 supports a real "style" field on
                        // KeyboardButton too (primary/success/danger).
                        row.put(new JSONObject().put("text", b.name).put("style", STYLE_KEYS[b.style]));
                        keyboard.put(row);
                    }
                    markup.put("keyboard", keyboard);
                    markup.put("resize_keyboard", true);
                    markup.put("one_time_keyboard", true);
                    params.put("reply_markup", markup);
                    break;
                }
                case TYPE_INLINE: {
                    method = "sendMessage";
                    params.put("text", text);
                    JSONObject markup = new JSONObject();
                    JSONArray keyboard = new JSONArray();
                    for (UserButton b : userButtons) {
                        JSONArray row = new JSONArray();
                        // Bot API 9.4 supports a real "style" field on
                        // InlineKeyboardButton (primary/success/danger); the
                        // style is also kept in callback_data so the bot's own
                        // code can distinguish buttons.
                        row.put(new JSONObject()
                                .put("text", b.name)
                                .put("style", STYLE_KEYS[b.style])
                                .put("callback_data", STYLE_KEYS[b.style] + ":" + b.name));
                        keyboard.put(row);
                    }
                    markup.put("inline_keyboard", keyboard);
                    params.put("reply_markup", markup);
                    break;
                }
                case TYPE_TEXT:
                default:
                    method = "sendMessage";
                    params.put("text", text);
                    break;
            }
            userResultView.setText(LocaleController.getString(R.string.SilentBotSending));
            postBotApi(token, method, params.toString(), (code, body) -> {
                if (code == -1) {
                    userResultView.setText(LocaleController.getString(R.string.SilentBotError) + ": " + body);
                } else {
                    userResultView.setText("HTTP " + code + "\n\n" + prettyJson(body));
                }
            });
        } catch (Exception e) {
            userResultView.setText(LocaleController.getString(R.string.SilentBotError) + ": " + e.getMessage());
        }
    }

    private void putChatId(JSONObject params, String chat) throws Exception {
        try {
            params.put("chat_id", Long.parseLong(chat));
        } catch (NumberFormatException e) {
            params.put("chat_id", chat);
        }
    }

    // ------------------------------------------------------------------
    // DEVELOPER (raw) mode — unchanged behaviour
    // ------------------------------------------------------------------

    private void buildDevMode(Context context, LinearLayout layout) {
        devLayout = new LinearLayout(context);
        devLayout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(devLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        tokenField = addField(context, devLayout, LocaleController.getString(R.string.SilentBotTokenHint));
        methodField = addField(context, devLayout, LocaleController.getString(R.string.SilentBotMethodHint));
        methodField.setText("getMe");
        paramsField = addField(context, devLayout, LocaleController.getString(R.string.SilentBotParamsHint));
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
        devLayout.addView(sendBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16));

        resultView = new TextView(context);
        resultView.setTextSize(13);
        resultView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        resultView.setMovementMethod(new ScrollingMovementMethod());
        resultView.setText(LocaleController.getString(R.string.SilentBotResultHint));
        devLayout.addView(resultView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
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
        postBotApi(token, method, paramsJson, (code, body) -> {
            if (code == -1) {
                resultView.setText(LocaleController.getString(R.string.SilentBotError) + ": " + body);
            } else {
                resultView.setText("HTTP " + code + "\n\n" + prettyJson(body));
            }
        });
    }

    /**
     * Raw POST to api.telegram.org, form-encoded (same as the original
     * sendRequest). Runs on a background thread; the callback runs on the
     * UI thread with the HTTP status code and raw response body (code -1
     * with the exception message on transport failure).
     */
    private void postBotApi(final String token, final String method, final String paramsJson, final BotApiCallback callback) {
        new Thread(() -> {
            int code = -1;
            String body = null;
            try {
                URL url = new URL("https://api.telegram.org/bot" + token + "/" + method);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                StringBuilder postBody = new StringBuilder();
                try {
                    JSONObject params = new JSONObject(paramsJson.isEmpty() ? "{}" : paramsJson);
                    Iterator<String> keys = params.keys();
                    boolean first = true;
                    while (keys.hasNext()) {
                        String k = keys.next();
                        if (!first) {
                            postBody.append("&");
                        }
                        first = false;
                        postBody.append(URLEncoder.encode(k, "UTF-8")).append("=")
                                .append(URLEncoder.encode(params.optString(k), "UTF-8"));
                    }
                } catch (Exception e) {
                    postBody.append("");
                }
                byte[] bytes = postBody.toString().getBytes("UTF-8");
                conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                OutputStream os = conn.getOutputStream();
                os.write(bytes);
                os.flush();
                os.close();
                code = conn.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                body = sb.toString();
            } catch (Exception e) {
                body = e.getMessage();
            }
            final int outCode = code;
            final String outBody = body;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(outCode, outBody));
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
