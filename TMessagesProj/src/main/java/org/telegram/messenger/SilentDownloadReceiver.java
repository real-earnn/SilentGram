package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fires when a scheduled download's time arrives and starts every download
 * whose time has passed.
 */
public class SilentDownloadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) {
            return;
        }
        try {
            SilentScheduleDownload.triggerDueDownloads(context.getApplicationContext());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
