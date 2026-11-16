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
        public long time;
        public String kind; // "deleted" or "edited"

        public Entry(long dialogId, int msgId, String text, String sender, long time, String kind) {
            this.dialogId = dialogId;
            this.msgId = msgId;
            this.text = text;
            this.sender = sender;
            this.time = time;
            this.kind = kind;
        }
    }

    private static final int MAX_CACHE = 1000;
    private static final int MAX_HISTORY_PER_CHAT = 200;

    // In-memory LRU cache: key "dialogId:msgId" -> {text, sender, time}.
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
                cache.put(key, new String[]{v.optString("t"), v.optString("s"), String.valueOf(v.optLong("time"))});
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
                    obj.put(e.getKey(), v);
                }
            }
            prefs().edit().putString("msg_cache", obj.toString()).apply();
        } catch (Exception e) {
            // Never crash on a save failure.
        }
    }

    /**
     * Called whenever a message is shown. If the text changed since we last
     * saw it, the OLD text is saved as edit history.
     */
    public static void onMessageShown(long dialogId, int msgId, String text, String sender, long time) {
        if (text == null || text.isEmpty()) {
            return;
        }
        loadCache();
        String key = dialogId + ":" + msgId;
        String[] old;
        synchronized (cache) {
            old = cache.get(key);
        }
        if (old != null && !text.equals(old[0])) {
            // Edited — keep the old text.
            addHistory(new Entry(dialogId, msgId, old[0], old[1], Long.parseLong(old[2]), "edited"));
        }
        synchronized (cache) {
            cache.put(key, new String[]{text, sender == null ? "" : sender, String.valueOf(time)});
        }
        saveCache();
    }

    /** Called when messages are deleted in a chat — recover their cached text. */
    public static void onMessagesDeleted(long dialogId, List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
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
                addHistory(new Entry(dialogId, id, old[0], old[1], Long.parseLong(old[2]), "deleted"));
            }
        }
        saveCache();
    }

    private static void addHistory(Entry e) {
        List<Entry> list = getHistory(e.dialogId);
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

    public static List<Entry> getHistory(long dialogId) {
        ArrayList<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString("msg_history_" + dialogId, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(dialogId, o.optInt("id"), o.optString("t"), o.optString("s"), o.optLong("time"), o.optString("k")));
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
    public static List<Entry> getHistoryForMessage(long dialogId, int msgId) {
        ArrayList<Entry> out = new ArrayList<>();
        for (Entry e : getHistory(dialogId)) {
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

    /** All dialogIds that have any deleted/edited history. */
    public static List<Long> getDialogsWithHistory() {
        ArrayList<Long> out = new ArrayList<>();
        try {
            java.util.Map<String, ?> all = prefs().getAll();
            for (String key : all.keySet()) {
                if (key.startsWith("msg_history_")) {
                    try {
                        long did = Long.parseLong(key.substring("msg_history_".length()));
                        if (!getHistory(did).isEmpty()) {
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
            List<Entry> la = getHistory(a);
            List<Entry> lb = getHistory(b);
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
