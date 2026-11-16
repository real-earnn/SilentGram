package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * SilentGram F9: Private notes & reminders per person.
 * For any user, keep a private note only you can see ("VIP buyer",
 * "owes 5000", "call on Friday") plus an optional reminder time.
 * When the time comes, the same reminder notification fires.
 */
public class SilentNotes {

    public static class Note {
        public long userId;
        public String chatName;
        public String text;
        public long remindAt; // millis, 0 = no reminder

        public Note(long userId, String chatName, String text, long remindAt) {
            this.userId = userId;
            this.chatName = chatName;
            this.text = text;
            this.remindAt = remindAt;
        }
    }

    private static final String KEY = "silent_notes";

    private static android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("silentgram", android.content.Context.MODE_PRIVATE);
    }

    public static List<Note> getNotes() {
        ArrayList<Note> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Note(o.optLong("uid"), o.optString("chat"), o.optString("text"), o.optLong("remind")));
            }
        } catch (Exception e) {
            // Return what we have.
        }
        // Reminders first (soonest first), then the rest.
        out.sort((a, b) -> {
            long ra = a.remindAt == 0 ? Long.MAX_VALUE : a.remindAt;
            long rb = b.remindAt == 0 ? Long.MAX_VALUE : b.remindAt;
            return Long.compare(ra, rb);
        });
        return out;
    }

    public static Note getNote(long userId) {
        for (Note n : getNotes()) {
            if (n.userId == userId) {
                return n;
            }
        }
        return null;
    }

    private static void saveNotes(List<Note> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Note n : list) {
                JSONObject o = new JSONObject();
                o.put("uid", n.userId);
                o.put("chat", n.chatName);
                o.put("text", n.text);
                o.put("remind", n.remindAt);
                arr.put(o);
            }
            prefs().edit().putString(KEY, arr.toString()).apply();
        } catch (Exception e) {
            // Never crash on a save failure.
        }
    }

    public static void saveNote(android.content.Context context, long userId, String chatName, String text, long remindAt) {
        List<Note> list = getNotes();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).userId == userId) {
                list.remove(i);
                break;
            }
        }
        if (text != null && !text.trim().isEmpty()) {
            list.add(new Note(userId, chatName, text.trim(), remindAt));
        }
        saveNotes(list);
        // Arm the reminder notification if a time was set.
        if (remindAt > 0 && text != null && !text.trim().isEmpty() && context != null) {
            SilentReminders.addReminder(context, userId, 0, text.trim(), chatName, remindAt);
        }
    }

    public static void deleteNote(long userId) {
        List<Note> list = getNotes();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).userId == userId) {
                list.remove(i);
                break;
            }
        }
        saveNotes(list);
    }
}
