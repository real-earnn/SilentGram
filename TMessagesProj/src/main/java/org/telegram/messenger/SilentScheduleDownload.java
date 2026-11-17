package org.telegram.messenger;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.DatePickerDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * SilentGram scheduled download.
 *
 * Long-press a file or picture → "Schedule Download" → pick a time. At that
 * time the file starts downloading by itself. The document is serialised
 * (with its file reference) into SharedPreferences, an AlarmManager alarm is
 * set, and SilentDownloadReceiver triggers FileLoader.loadFile when it fires.
 * If the app is opened after the time passed, due downloads are started from
 * DialogsActivity as well, so nothing is lost.
 */
public class SilentScheduleDownload {

    public static final String ACTION = "org.telegram.messenger.ACTION_SILENT_SCHEDULED_DOWNLOAD";
    private static final String PREFS = "silentgram_downloads";
    private static final String KEY_LIST = "scheduled";

    /** Show the "when?" picker for this message's file. Must be called on the UI thread. */
    public static void showPicker(Context context, int account, MessageObject message) {
        if (context == null || message == null) {
            return;
        }
        TLRPC.Document document = message.getDocument();
        if (document == null && !message.isVideo() && !message.isPhoto()) {
            return;
        }
        Activity activity = AndroidUtilities.findActivity(context);
        if (activity == null) {
            return;
        }
        String fileName = message.getFileName();
        CharSequence[] options = new CharSequence[]{
                "In 1 hour",
                "In 3 hours",
                "Tonight at 9 PM",
                "Tomorrow at 9 AM",
                "Pick a date and time…"
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Schedule download" + (fileName != null && !fileName.isEmpty() ? " — " + fileName : ""));
        builder.setItems(options, (d, which) -> {
            Calendar when = Calendar.getInstance();
            switch (which) {
                case 0:
                    when.add(Calendar.HOUR_OF_DAY, 1);
                    schedule(context, account, message, when.getTimeInMillis());
                    break;
                case 1:
                    when.add(Calendar.HOUR_OF_DAY, 3);
                    schedule(context, account, message, when.getTimeInMillis());
                    break;
                case 2:
                    when.set(Calendar.HOUR_OF_DAY, 21);
                    when.set(Calendar.MINUTE, 0);
                    when.set(Calendar.SECOND, 0);
                    if (when.getTimeInMillis() <= System.currentTimeMillis()) {
                        when.add(Calendar.DAY_OF_YEAR, 1);
                    }
                    schedule(context, account, message, when.getTimeInMillis());
                    break;
                case 3:
                    when.add(Calendar.DAY_OF_YEAR, 1);
                    when.set(Calendar.HOUR_OF_DAY, 9);
                    when.set(Calendar.MINUTE, 0);
                    when.set(Calendar.SECOND, 0);
                    schedule(context, account, message, when.getTimeInMillis());
                    break;
                default:
                    pickCustomTime(activity, account, message);
                    break;
            }
        });
        builder.show();
    }

    private static void pickCustomTime(Activity activity, int account, MessageObject message) {
        Calendar now = Calendar.getInstance();
        DatePickerDialog datePicker = new DatePickerDialog(activity, (view, year, month, day) -> {
            TimePickerDialog timePicker = new TimePickerDialog(activity, (v, hour, minute) -> {
                Calendar when = Calendar.getInstance();
                when.set(year, month, day, hour, minute, 0);
                when.set(Calendar.MILLISECOND, 0);
                if (when.getTimeInMillis() <= System.currentTimeMillis()) {
                    when.add(Calendar.DAY_OF_YEAR, 1);
                }
                schedule(activity, account, message, when.getTimeInMillis());
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true);
            timePicker.show();
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
        datePicker.show();
    }

    /** Store the schedule and set the alarm. */
    public static void schedule(Context context, int account, MessageObject message, long whenMillis) {
        TLRPC.Document document = message.getDocument();
        if (document == null) {
            return;
        }
        String docBase64 = serializeDocument(document);
        if (docBase64 == null) {
            return;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray list = new JSONArray(prefs.getString(KEY_LIST, "[]"));
            JSONObject item = new JSONObject();
            item.put("account", account);
            item.put("dialog_id", message.getDialogId());
            item.put("message_id", message.getId());
            item.put("when", whenMillis);
            item.put("file_name", message.getFileName());
            item.put("document", docBase64);
            list.put(item);
            prefs.edit().putString(KEY_LIST, list.toString()).apply();

            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                Intent intent = new Intent(context, SilentDownloadReceiver.class);
                intent.setAction(ACTION);
                intent.putExtra("when", whenMillis);
                intent.putExtra("message_id", message.getId());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
                PendingIntent pi = PendingIntent.getBroadcast(context, message.getId(), intent, flags);
                if (Build.VERSION.SDK_INT >= 23) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pi);
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, whenMillis, pi);
                }
            }
            SilentToast.show(context, "Download scheduled", android.widget.Toast.LENGTH_SHORT);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Start every scheduled download whose time has passed. Called from the receiver and on app start. */
    public static void triggerDueDownloads(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray list = new JSONArray(prefs.getString(KEY_LIST, "[]"));
            JSONArray remaining = new JSONArray();
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                if (item.optLong("when", 0) <= now) {
                    changed = true;
                    startDownload(context, item);
                } else {
                    remaining.put(item);
                }
            }
            if (changed) {
                prefs.edit().putString(KEY_LIST, remaining.toString()).apply();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Trigger one scheduled download now. */
    public static void startDownload(Context context, JSONObject item) {
        try {
            int account = item.optInt("account", 0);
            if (!UserConfig.getInstance(account).isClientActivated()) {
                return;
            }
            TLRPC.Document document = deserializeDocument(item.optString("document", ""));
            if (document == null) {
                return;
            }
            FileLoader.getInstance(account).loadFile(document, null, FileLoader.PRIORITY_HIGH, 0);
            FileLog.d("SilentScheduleDownload: started download for message " + item.optInt("message_id", 0));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static String serializeDocument(TLRPC.Document document) {
        try {
            SerializedData data = new SerializedData();
            document.serializeToStream(data);
            return Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static TLRPC.Document deserializeDocument(String base64) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
            SerializedData data = new SerializedData(bytes);
            int constructor = data.readInt32(false);
            return TLRPC.Document.TLdeserialize(data, constructor, false);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }
}
