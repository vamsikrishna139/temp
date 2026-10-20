package com.local.hotspot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.telephony.TelephonyManager;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;

/** Schedule + Wi-Fi/hotspot control. Window: hourly turn-on 07:00-17:00, hotspot off at 18:00. */
final class Hotspot {
    static final int START_HOUR = 7;
    static final int OFF_HOUR = 18;
    static final int MIN_BATTERY = 20;
    private static final int TETHERING_WIFI = 0;
    private static final int MAX_LOG_LINES = 40;

    private Hotspot() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("prefs", Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean("enabled", true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("enabled", on).apply();
        log(c, on ? "Schedule turned ON" : "Schedule turned OFF");
        schedule(c);
    }

    static boolean inWindow() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= START_HOUR && h < OFF_HOUR;
    }

    /** Arms the alarm for the next full hour inside 07:00-18:00, or cancels it if the schedule is off. */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        Intent i = new Intent(c, AlarmReceiver.class).setAction("com.local.hotspot.TICK");
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        if (!isEnabled(c)) {
            prefs(c).edit().remove("next").apply();
            return;
        }

        Calendar t = Calendar.getInstance();
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        t.add(Calendar.HOUR_OF_DAY, 1);
        int h = t.get(Calendar.HOUR_OF_DAY);
        if (h < START_HOUR) {
            t.set(Calendar.HOUR_OF_DAY, START_HOUR);
        } else if (h > OFF_HOUR) {
            t.add(Calendar.DAY_OF_YEAR, 1);
            t.set(Calendar.HOUR_OF_DAY, START_HOUR);
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
        prefs(c).edit().putString("next", fmt(t.getTime())).apply();
    }

    /** Runs whatever the current time calls for: turn on inside the window, hotspot off outside it. */
    static void tick(Context c) {
        if (inWindow()) {
            turnOn(c);
        } else {
            setHotspot(c, false);
        }
    }

    static void turnOn(Context c) {
        int battery = c.getSystemService(BatteryManager.class)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (battery < MIN_BATTERY) {
            log(c, "Skipped: battery " + battery + "%");
            return;
        }
        if (c.getSystemService(PowerManager.class).isPowerSaveMode()) {
            log(c, "Skipped: Power Saver is on");
            return;
        }

        WifiManager wm = c.getSystemService(WifiManager.class);
        boolean apOn = isHotspotOn(c);
        if (!wm.isWifiEnabled()) {
            // Android refuses Wi-Fi toggles from normal apps while the hotspot is up.
            if (apOn) {
                log(c, "Wi-Fi: off, can't enable while hotspot is on");
            } else {
                log(c, wm.setWifiEnabled(true) ? "Wi-Fi: turned on" : "Wi-Fi: blocked by system");
                sleep(3000);
            }
        }

        try {
            if (!c.getSystemService(TelephonyManager.class).isDataEnabled()) {
                log(c, "Mobile data: OFF (cannot be switched on without root)");
            }
        } catch (SecurityException ignored) {
        }

        if (apOn) {
            log(c, "Hotspot: already on");
        } else {
            setHotspot(c, true);
        }
    }

    static boolean isHotspotOn(Context c) {
        try {
            HiddenApiBypass.addHiddenApiExemptions("");
            WifiManager wm = c.getSystemService(WifiManager.class);
            return (boolean) WifiManager.class.getMethod("isWifiApEnabled").invoke(wm);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Uses TetheringManager (system API, allowed with "Modify system settings"). */
    static void setHotspot(Context c, boolean on) {
        try {
            HiddenApiBypass.addHiddenApiExemptions("");
            Object tm = c.getSystemService("tethering");
            if (on) {
                Class<?> cbClass = Class.forName("android.net.TetheringManager$StartTetheringCallback");
                Object cb = Proxy.newProxyInstance(cbClass.getClassLoader(), new Class<?>[]{cbClass},
                        (proxy, m, args) -> {
                            switch (m.getName()) {
                                case "onTetheringStarted":
                                    log(c, "Hotspot: turned on");
                                    return null;
                                case "onTetheringFailed":
                                    log(c, "Hotspot: failed, error " + args[0]
                                            + ((int) args[0] == 14 ? " (no permission)" : ""));
                                    return null;
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "equals":
                                    return proxy == args[0];
                                case "toString":
                                    return "StartTetheringCallback";
                                default:
                                    return null;
                            }
                        });
                Executor direct = Runnable::run;
                tm.getClass().getMethod("startTethering", int.class, Executor.class, cbClass)
                        .invoke(tm, TETHERING_WIFI, direct, cb);
            } else {
                tm.getClass().getMethod("stopTethering", int.class).invoke(tm, TETHERING_WIFI);
                log(c, "Hotspot: turned off");
            }
        } catch (InvocationTargetException e) {
            log(c, "Hotspot error: " + e.getCause());
        } catch (Throwable t) {
            log(c, "Hotspot error: " + t);
        }
    }

    static synchronized void log(Context c, String msg) {
        String old = prefs(c).getString("log", "");
        String all = fmt(new Date()) + "  " + msg + "\n" + old;
        String[] lines = all.split("\n");
        if (lines.length > MAX_LOG_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < MAX_LOG_LINES; i++) sb.append(lines[i]).append('\n');
            all = sb.toString();
        }
        prefs(c).edit().putString("log", all).apply();
    }

    private static String fmt(Date d) {
        return new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(d);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
