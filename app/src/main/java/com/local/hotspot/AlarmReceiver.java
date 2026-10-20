package com.local.hotspot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the hourly alarm, boot and app update: act for the current hour, then arm the next alarm. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Context c = context.getApplicationContext();
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                if (Hotspot.isEnabled(c)) Hotspot.tick(c);
            } catch (Throwable t) {
                Hotspot.log(c, "Error: " + t);
            } finally {
                Hotspot.schedule(c);
                result.finish();
            }
        }).start();
    }
}
