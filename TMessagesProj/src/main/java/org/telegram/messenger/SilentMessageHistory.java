package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SilentGram E7: Deleted & Edited History viewer.
 *
 * Every message text you see is cached locally (in memory + SharedPreferences,
 * capped at 1000 entries). When a message is later edited, the old text is
 * saved as "Edited History". When a message is deleted, the cached text is
 * saved as "Deleted". A per-chat viewer shows them — tap a message to read
 * the old text.
 *
 * This only ever stores text you already received and saw. Nothing is sent
 * anywhere.
 */
public class SilentMessageHistory {

    public static class Entry {
        public long dialogId;
        public int msgId;
        public String text;
        public String sender;
        // SilentGram Batch Q: Telegram user id of the sender. 0 = unknown
        // / legacy entry recorded before the id was stored (name string
        // only) — such entries still render the name as plain text.
        public long senderId;
        public long time;
        public String kind; // "deleted" or "edited"

        public Entry(long dialogId, int msgId, String text, String sender, long time, String kind) {
            this(dialogId, msgId, text, sender, 0, time, kind);
        }

        public Entry(long dialogId, int msgId, String text, String sender, long senderId, long time, String kind) {
            this.dialogId = dialogId;
            this.msgId = msgId;
            this.text = text;
            this.sender = sender;
            this.senderId = senderId;
            this.time = time;
            this.kind = kind;
        }
    }

    private static final int MAX_CACHE = 1000;
    private static final int MAX_HISTORY_PER_CHAT = 200;

    // In-memory LRU cache: key "dialogId:msgId" -> {text, sender, time, senderId}.
    // SilentGram Batch Q: the 4th element is new — entries cached before this
    // change have only 3 elements and read back with senderId 0 (see
    // silentCacheSenderId); loadCache always builds 4-element arrays.
    private static final LinkedHashMap<String, String[]> cache = new LinkedHashMap<String, String[]>(MAX_CACHE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
            return size() > MAX_CACHE;
        }
    };
    private static boolean cacheLoaded = false;

    private static android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("silentgram", android.content.Context.MODE_PRIVATE);
    }

    private static synchronized void loadCache() {
        if (cacheLoaded) {
            return;
        }
        cacheLoaded = true;
        try {
            JSONObject obj = new JSONObject(prefs().getString("msg_cache", "{}"));
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject v = obj.getJSONObject(key);
                cache.put(key, new String[]{v.optString("t"), v.optString("s"), String.valueOf(v.optLong("time")), String.valueOf(v.optLong("sid"))});
            }
        } catch (Exception e) {
            // Start fresh on corrupt data.
        }
    }

    private static void saveCache() {
        try {
            JSONObject obj = new JSONObject();
            synchronized (cache) {
                for (Map.Entry<String, String[]> e : cache.entrySet()) {
                    JSONObject v = new JSONObject();
                    v.put("t", e.getValue()[0]);
                    v.put("s", e.getValue()[1]);
                    v.put("time", Long.parseLong(e.getValue()[2]));
                    // SilentGram Batch Q: sender id as the 4th element.
                    // Length-guarded for any 3-element entry that predates it.
                    v.put("sid", e.getValue().length > 3 ? Long.parseLong(e.getValue()[3]) : 0);
                    obj.put(e.getKey(), v);
                }
            }
            prefs().edit().putString("msg_cache", obj.toString()).apply();
        } catch (Exception e) {
            // Never crash on a save failure.
        }
    }

    // SilentGram Batch Q: sender id stored as the cache array's 4th
    // element. Entries cached before this change have only 3 elements
    // (or a corrupt 4th) and read back as 0 = unknown / legacy.
    private static long silentCacheSenderId(String[] cached) {
        if (cached == null || cached.length <= 3 || cached[3] == null) {
            return 0;
        }
        try {
            return Long.parseLong(cached[3]);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * SilentGram v2.0.34: Deleted & Edited History is PERSONAL-ONLY.
     * A dialog counts as personal only when it is a 1:1 chat with a real
     * user — not a group, channel, bot, Saved Messages (self) or secret
     * chat. Groups/channels/bots are excluded because a group bot's
     * auto-delete of spam would otherwise stay visible in history, which
     * channel/group owners will not promote.
     */
    public static boolean isPersonalChat(int account, long dialogId) {
        if (!DialogObject.isUserDialog(dialogId)) {
            return false;
        }
        try {
            TLRPC.User u = MessagesController.getInstance(account).getUser(dialogId);
            if (u == null) {
                return false;
            }
            return !UserObject.isUserSelf(u) && !UserObject.isBot(u);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Called whenever a message is shown. If the text changed since we last
     * saw it, the OLD text is saved as edit history.
     */
    public static void onMessageShown(int account, long dialogId, int msgId, String text, String sender, long senderId, long time) {
        if (text == null || text.isEmpty() || !isPersonalChat(account, dialogId)) {
            return;
        }
        loadCache();
        String key = dialogId + ":" + msgId;
        String[] old;
        synchronized (cache) {
            old = cache.get(key);
        }
        if (old != null && !text.equals(old[0])) {
            // Edited — keep the old text (and its sender, id included).
            addHistory(account, new Entry(dialogId, msgId, old[0], old[1], silentCacheSenderId(old), Long.parseLong(old[2]), "edited"));
        }
        synchronized (cache) {
            cache.put(key, new String[]{text, sender == null ? "" : sender, String.valueOf(time), String.valueOf(senderId)});
        }
        saveCache();
    }

    /** Called when messages are deleted in a chat — recover their cached text. */
    public static void onMessagesDeleted(int account, long dialogId, List<Integer> ids) {
        if (ids == null || ids.isEmpty() || !isPersonalChat(account, dialogId)) {
            return;
        }
        loadCache();
        for (int id : ids) {
            String key = dialogId + ":" + id;
            String[] old;
            synchronized (cache) {
                old = cache.remove(key);
            }
            if (old != null && old[0] != null && !old[0].isEmpty()) {
                addHistory(account, new Entry(dialogId, id, old[0], old[1], silentCacheSenderId(old), Long.parseLong(old[2]), "deleted"));
            }
        }
        saveCache();
    }

    private static void addHistory(int account, Entry e) {
        if (!isPersonalChat(account, e.dialogId)) {
            return;
        }
        List<Entry> list = getHistoryRaw(e.dialogId);
        // Don't duplicate the same message+kind twice.
        for (Entry x : list) {
            if (x.msgId == e.msgId && x.kind.equals(e.kind) && x.text.equals(e.text)) {
                return;
            }
        }
        list.add(0, e);
        while (list.size() > MAX_HISTORY_PER_CHAT) {
            list.remove(list.size() - 1);
        }
        saveHistory(e.dialogId, list);
    }

    /**
     * Personal-only read: history of a group, channel, bot or Saved
     * Messages never surfaces, even if it was stored by an older build.
     */
    public static List<Entry> getHistory(int account, long dialogId) {
        if (!isPersonalChat(account, dialogId)) {
            return new ArrayList<>();
        }
        return getHistoryRaw(dialogId);
    }

    /** Ungated read — only for internal capture/purge bookkeeping. */
    private static List<Entry> getHistoryRaw(long dialogId) {
        ArrayList<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString("msg_history_" + dialogId, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(dialogId, o.optInt("id"), o.optString("t"), o.optString("s"), o.optLong("sid"), o.optLong("time"), o.optString("k")));
            }
        } catch (Exception e) {
            // Return what we have.
        }
        return out;
    }

    /**
     * Edit history of ONE message (kind "edited"), oldest first — the
     * versions a message had before its current text. Used by the inline
     * "View edit history" line under edited messages in the chat.
     */
    public static List<Entry> getHistoryForMessage(int account, long dialogId, int msgId) {
        ArrayList<Entry> out = new ArrayList<>();
        for (Entry e : getHistory(account, dialogId)) {
            if (e.msgId == msgId && "edited".equals(e.kind)) {
                out.add(e);
            }
        }
        // getHistory() is newest-first; the inline expansion reads oldest→newest.
        java.util.Collections.reverse(out);
        return out;
    }

    private static void saveHistory(long dialogId, List<Entry> list) {        try {
            JSONArray arr = new JSONArray();
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("id", e.msgId);
                o.put("t", e.text);
                o.put("s", e.sender);
                o.put("sid", e.senderId);
                o.put("time", e.time);
                o.put("k", e.kind);
                arr.put(o);
            }
            prefs().edit().putString("msg_history_" + dialogId, arr.toString()).apply();
        } catch (Exception e) {
            // Never crash on a save failure.
        }
    }

    public static void clearHistory(long dialogId) {
        prefs().edit().remove("msg_history_" + dialogId).apply();
    }

    /**
     * SilentGram Q-B: purge ONE message completely — remove every history
     * entry for it (kind "deleted" or "edited") AND its cached text, so
     * "Delete for me" leaves no backup behind. Called after a delete-for-me
     * is issued; the E7 hook may already have moved the cached text into
     * history during the delete, so both stores must be cleared. Never
     * throws.
     */
    public static void removeEntry(long dialogId, int msgId) {
        try {
            List<Entry> list = getHistoryRaw(dialogId);
            boolean changed = false;
            Iterator<Entry> it = list.iterator();
            while (it.hasNext()) {
                if (it.next().msgId == msgId) {
                    it.remove();
                    changed = true;
                }
            }
            if (changed) {
                saveHistory(dialogId, list);
            }
        } catch (Exception e) {
            // Never crash on a purge failure.
        }
        loadCache();
        String key = dialogId + ":" + msgId;
        boolean removedFromCache;
        synchronized (cache) {
            removedFromCache = cache.remove(key) != null;
        }
        if (removedFromCache) {
            saveCache();
        }
    }

    /** All PERSONAL dialogIds that have any deleted/edited history. */
    public static List<Long> getDialogsWithHistory(int account) {
        ArrayList<Long> out = new ArrayList<>();
        try {
            java.util.Map<String, ?> all = prefs().getAll();
            for (String key : all.keySet()) {
                if (key.startsWith("msg_history_")) {
                    try {
                        long did = Long.parseLong(key.substring("msg_history_".length()));
                        if (!isPersonalChat(account, did)) {
                            continue;
                        }
                        if (!getHistoryRaw(did).isEmpty()) {
                            out.add(did);
                        }
                    } catch (Exception e) {
                        // Skip malformed keys.
                    }
                }
            }
        } catch (Exception e) {
            // Return what we have.
        }
        // Newest first (by latest entry time).
        out.sort((a, b) -> {
            List<Entry> la = getHistoryRaw(a);
            List<Entry> lb = getHistoryRaw(b);
            long ta = la.isEmpty() ? 0 : la.get(0).time;
            long tb = lb.isEmpty() ? 0 : lb.get(0).time;
            return Long.compare(tb, ta);
        });
        return out;
    }

    /** Resolve a display name for a dialog (works even after the dialog is deleted, from cached data). */
    public static String getDialogName(int account, long dialogId) {
        try {
            MessagesController mc = MessagesController.getInstance(account);
            if (dialogId > 0) {
                TLRPC.User u = mc.getUser(dialogId);
                if (u != null) {
                    return UserObject.getUserName(u);
                }
            } else {
                TLRPC.Chat c = mc.getChat(-dialogId);
                if (c != null && c.title != null) {
                    return c.title;
                }
            }
        } catch (Exception e) {
            // Fall through to sender names.
        }
        // Fall back: the most frequent sender name in the history.
        List<Entry> entries = getHistory(dialogId);
        String best = "";
        int bestCount = 0;
        java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
        for (Entry e : entries) {
            if (e.sender == null || e.sender.isEmpty() || e.sender.equals("You")) {
                continue;
            }
            int n = counts.getOrDefault(e.sender, 0) + 1;
            counts.put(e.sender, n);
            if (n > bestCount) {
                bestCount = n;
                best = e.sender;
            }
        }
        return best.isEmpty() ? ("Chat " + Math.abs(dialogId)) : best;
    }
}
