package org.telegram.messenger;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.ui.ActionBar.AlertDialog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * SilentGram G24: whole-chat translation, in place, Telegram-native look.
 *
 * Telegram's own TranslateController is Premium-gated
 * (isFeatureAvailable() requires Premium), so the engine here is
 * SilentTranslate (free Google endpoint, same as the per-message translate).
 * The presentation, however, is Telegram's own: the translated text is
 * swapped into the message with MessageObject.applyNewText — the exact call
 * Telegram's MessageObject.updateTranslation() uses — so the translation
 * renders in the SAME bubble, with the SAME text layout, as if the message
 * had been sent in that language. Turning it off restores the originals
 * with applyNewText() (no-arg), again the same call Telegram uses.
 *
 * Translations are stored per chat in the "silentgram" prefs
 * (key chat_translate_<dialogId>): target language + msgId -> text.
 * Only plain text messages are translated in this first pass; media
 * captions are left in the original language.
 */
public class SilentChatTranslator {

    private static final String KEY_PREFIX = "chat_translate_";
    private static final int MAX_STORED_PER_CHAT = 200;

    private static class ChatState {
        String lang;
        // Insertion-ordered so the oldest translation can be dropped first.
        LinkedHashMap<Integer, String> msgs = new LinkedHashMap<>();
    }

    private static final Map<Long, ChatState> cache = new HashMap<>();
    // "dialogId:msgId" of translations currently in flight (dedupe).
    private static final Set<String> pending = new HashSet<>();

    private static android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("silentgram", android.content.Context.MODE_PRIVATE);
    }

    private static synchronized ChatState state(long dialogId) {
        ChatState s = cache.get(dialogId);
        if (s != null) {
            return s;
        }
        s = new ChatState();
        try {
            JSONObject obj = new JSONObject(prefs().getString(KEY_PREFIX + dialogId, "{}"));
            s.lang = obj.optString("lang", null);
            if (s.lang != null && s.lang.isEmpty()) {
                s.lang = null;
            }
            JSONObject msgs = obj.optJSONObject("msgs");
            if (msgs != null) {
                Iterator<String> keys = msgs.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    try {
                        s.msgs.put(Integer.parseInt(k), msgs.optString(k));
                    } catch (Exception e) {
                        // Skip malformed entries.
                    }
                }
            }
        } catch (Exception e) {
            // Start fresh on corrupt data.
        }
        cache.put(dialogId, s);
        return s;
    }

    private static synchronized void save(long dialogId) {
        try {
            ChatState s = cache.get(dialogId);
            if (s == null) {
                return;
            }
            JSONObject obj = new JSONObject();
            if (s.lang != null) {
                obj.put("lang", s.lang);
            }
            JSONObject msgs = new JSONObject();
            for (Map.Entry<Integer, String> e : s.msgs.entrySet()) {
                msgs.put(String.valueOf(e.getKey()), e.getValue());
            }
            obj.put("msgs", msgs);
            prefs().edit().putString(KEY_PREFIX + dialogId, obj.toString()).apply();
        } catch (Exception e) {
            // Never crash on a save failure.
        }
    }

    /** True when this chat is currently translated in place. */
    public static boolean isTranslating(long dialogId) {
        return state(dialogId).lang != null;
    }

    public static String getTargetLang(long dialogId) {
        return state(dialogId).lang;
    }

    /** Enable in-place translation for this chat (target language code). */
    public static synchronized void setTargetLang(long dialogId, String lang) {
        if (lang == null || lang.isEmpty() || "auto".equals(lang)) {
            return;
        }
        ChatState s = state(dialogId);
        // A new language invalidates translations stored for the old one.
        if (s.lang != null && !s.lang.equals(lang)) {
            s.msgs.clear();
        }
        s.lang = lang;
        save(dialogId);
    }

    /** Turn translation off and forget this chat's stored translations. */
    public static synchronized void disable(long dialogId) {
        cache.remove(dialogId);
        prefs().edit().remove(KEY_PREFIX + dialogId).apply();
    }

    public static String getTranslation(long dialogId, int msgId) {
        return state(dialogId).msgs.get(msgId);
    }

    /** Ids of messages that have a stored translation for this chat. */
    public static Set<Integer> getStoredIds(long dialogId) {
        return new HashSet<>(state(dialogId).msgs.keySet());
    }

    public static synchronized void putTranslation(long dialogId, int msgId, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ChatState s = state(dialogId);
        s.msgs.put(msgId, text);
        while (s.msgs.size() > MAX_STORED_PER_CHAT) {
            Integer first = s.msgs.keySet().iterator().next();
            s.msgs.remove(first);
        }
        save(dialogId);
    }

    /** Only plain text messages are translated in this first pass. */
    public static boolean shouldTranslate(MessageObject msg) {
        return msg != null
                && msg.type == MessageObject.TYPE_TEXT
                && msg.messageText != null
                && msg.messageText.length() > 0;
    }

    /**
     * Apply the stored translation to a message, if this chat is translating
     * and one is stored. If none is stored yet, start translating it in the
     * background; onTranslated runs on the UI thread when it lands.
     * Called from ChatMessageCell before the text layout is built.
     */
    public static void applyToMessage(MessageObject msg, Runnable onTranslated) {
        if (msg == null || !shouldTranslate(msg)) {
            return;
        }
        long dialogId = msg.getDialogId();
        if (!isTranslating(dialogId)) {
            return;
        }
        int msgId = msg.getId();
        String tr = getTranslation(dialogId, msgId);
        if (tr != null) {
            // Same call Telegram's own updateTranslation() uses to swap text.
            if (!TextUtils.equals(msg.messageText, tr)) {
                msg.applyNewText(tr);
            }
            return;
        }
        ensureTranslated(dialogId, msgId, msg.messageText.toString(), onTranslated);
    }

    /** Start translating one message (deduped); onReady runs on the UI thread. */
    public static void ensureTranslated(long dialogId, int msgId, String original, Runnable onReady) {
        if (original == null || original.isEmpty()) {
            return;
        }
        String key = dialogId + ":" + msgId;
        synchronized (SilentChatTranslator.class) {
            if (pending.contains(key) || getTranslation(dialogId, msgId) != null) {
                return;
            }
            pending.add(key);
        }
        String lang = getTargetLang(dialogId);
        if (lang == null) {
            synchronized (SilentChatTranslator.class) {
                pending.remove(key);
            }
            return;
        }
        SilentTranslate.translateText(original, "auto", lang, translated -> {
            synchronized (SilentChatTranslator.class) {
                pending.remove(key);
            }
            if (translated != null) {
                putTranslation(dialogId, msgId, translated);
            }
            if (onReady != null) {
                AndroidUtilities.runOnUIThread(onReady);
            }
        });
    }

    /**
     * Translate every given message that has no stored translation yet.
     * onEachDone runs on the UI thread after EACH message lands, so the
     * caller can rebind the visible cells as translations arrive.
     */
    public static void translateMessages(long dialogId, List<MessageObject> messages, Runnable onEachDone) {
        if (messages == null || !isTranslating(dialogId)) {
            return;
        }
        for (MessageObject msg : messages) {
            if (!shouldTranslate(msg)) {
                continue;
            }
            if (getTranslation(dialogId, msg.getId()) == null) {
                ensureTranslated(dialogId, msg.getId(), msg.messageText.toString(), onEachDone);
            }
        }
    }

    /**
     * Target-language picker — the same language list as SilentTranslate
     * (getLanguages()), shown as Telegram's own AlertDialog list.
     * "Auto-detect" is not offered: a target language must be concrete.
     */
    public static void pickTargetLanguage(Context context, Consumer<String> onPick) {
        if (context == null || onPick == null) {
            return;
        }
        String[][] langs = SilentTranslate.getLanguages();
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> codes = new ArrayList<>();
        for (int i = 0; i < langs.length; i++) {
            if ("auto".equals(langs[i][0])) {
                continue;
            }
            names.add(langs[i][1]);
            codes.add(langs[i][0]);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentChooseLanguage));
        builder.setItems(names.toArray(new CharSequence[0]), (d, which) -> onPick.accept(codes.get(which)));
        builder.show();
    }
}
