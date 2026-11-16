package org.telegram.messenger;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * SilentGram F3: Remind me.
 * Pick any message, set a time, and get a notification at that time with
 * the message text — tap it to jump back to the chat.
 */
public class SilentReminders {

    public static class Reminder {
        public long dialogId;
        public int msgId;
        public String text;
        public String chatName;
        public long time; // millis
        public int id;

        public Reminder(long dialogId, int msgId, String text, String chatName, long time, int id) {
            this.dialogId = dialogId;
            this.msgId = msgId;
            this.text = text;
            this.chatName = chatName;
            this.time = time;
            this.id = id;
        }
    }

    private static final String KEY = "silent_reminders";
    public static final String ACTION_REMIND = "org.telegram.messenger.ACTION_SILENT_REMIND";

    private static android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("silentgram", android.content.Context.MODE_PRIVATE);
    }

    public static List<Reminder> getReminders() {
        ArrayList<Reminder> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Reminder(o.optLong("did"), o.optInt("mid"), o.optString("text"),
                        o.optString("chat"), o.optLong("time"), o.optInt("id")));
            }
        } catch (Exception e) {
            // Return what we have.
        }
        out.sort((a, b) -> Long.compare(a.time, b.time));
        return out;
    }

    private static void saveReminders(List<Reminder> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Reminder r : list) {
                JSONObject o = new JSONObject();
                o.put("did", r.dialogId);
                o.put("mid", r.msgId);
                o.put("text", r.text);
                o.put("chat", r.chatName);
                o.put("time", r.time);
                o.put("id", r.id);
                arr.put(o);
            }
            prefs().edit().putString(KEY, arr.toString()).apply();
        } catch (Exception e) {
            // Never crash on a save failure.
        }
    }

    /** Schedule a reminder and arm the alarm. Returns the reminder id. */
    public static int addReminder(Context context, long dialogId, int msgId, String text, String chatName, long timeMillis) {
        List<Reminder> list = getReminders();
        int id = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
        list.add(new Reminder(dialogId, msgId, text, chatName, timeMillis, id));
        saveReminders(list);
        scheduleAlarm(context, id, timeMillis);
        return id;
    }

    public static void removeReminder(Context context, int id) {
        List<Reminder> list = getReminders();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == id) {
                list.remove(i);
                break;
            }
        }
        saveReminders(list);
        cancelAlarm(context, id);
    }

    /** Called by the receiver after firing — remove it from the list. */
    public static void consumeReminder(int id) {
        List<Reminder> list = getReminders();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == id) {
                list.remove(i);
                break;
            }
        }
        saveReminders(list);
    }

    private static void scheduleAlarm(Context context, int id, long timeMillis) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(context, SilentReminderReceiver.class);
            intent.setAction(ACTION_REMIND);
            intent.putExtra("reminder_id", id);
            PendingIntent pi = PendingIntent.getBroadcast(context, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMillis, pi);
        } catch (Exception e) {
            // Alarm not set — the reminder stays in the list.
        }
    }

    private static void cancelAlarm(Context context, int id) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(context, SilentReminderReceiver.class);
            intent.setAction(ACTION_REMIND);
            PendingIntent pi = PendingIntent.getBroadcast(context, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
        } catch (Exception e) {
            // Nothing to cancel.
        }
    }
}
