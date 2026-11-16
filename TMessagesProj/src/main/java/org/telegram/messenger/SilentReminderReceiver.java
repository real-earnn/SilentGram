package org.telegram.messenger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.util.List;

/**
 * SilentGram F3: fires a message reminder at its time.
 * Shows a notification with the message text; tapping it opens SilentGram.
 */
public class SilentReminderReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "silent_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !SilentReminders.ACTION_REMIND.equals(intent.getAction())) {
            return;
        }
        int id = intent.getIntExtra("reminder_id", 0);
        SilentReminders.Reminder found = null;
        for (SilentReminders.Reminder r : SilentReminders.getReminders()) {
            if (r.id == id) {
                found = r;
                break;
            }
        }
        if (found == null) {
            return;
        }
        SilentReminders.consumeReminder(id);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    LocaleController.getString(R.string.SilentRemindersChannel), NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(LocaleController.getString(R.string.SilentRemindersChannelDesc));
            nm.createNotificationChannel(channel);
        }
        Intent openIntent = new Intent(context, org.telegram.ui.LaunchActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, id, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = found.text;
        if (text != null && text.length() > 200) {
            text = text.substring(0, 200) + "…";
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.msg_recent)
                .setContentTitle(LocaleController.getString(R.string.SilentReminderTitle) + " • " + found.chatName)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        nm.notify(id, builder.build());
    }
}
