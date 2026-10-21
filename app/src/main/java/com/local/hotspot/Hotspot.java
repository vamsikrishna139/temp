package com.local.hotspot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.telephony.TelephonyManager;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * Schedule + radio control. Window: hourly turn-on 07:00-17:00, hotspot off at 18:00.
 * Wi-Fi, mobile data and hotspot are switched through Shizuku (ADB shell identity, no root).
 */
final class Hotspot {
    static final int START_HOUR = 7;
    static final int OFF_HOUR = 18;
    static final int MIN_BATTERY = 20;
    private static final int TETHERING_WIFI = 0;
    private static final int MAX_LOG_LINES = 40;
    private static final String SHELL_PKG = "com.android.shell";

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
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
        }
        prefs(c).edit().putString("next", fmt(t.getTime())).apply();
    }

    /** Runs whatever the current time calls for: turn on inside the window, hotspot off outside it. */
    static void tick(Context c) {
        if (inWindow()) {
            turnOn(c);
        } else if (waitForShizuku()) {
            setHotspot(c, false);
        } else {
            log(c, "Hotspot off skipped: " + shizukuProblem());
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
        if (!waitForShizuku()) {
            log(c, "Skipped: " + shizukuProblem());
            return;
        }

        boolean dataOn;
        try {
            dataOn = c.getSystemService(TelephonyManager.class).isDataEnabled();
        } catch (SecurityException e) {
            dataOn = false;
        }
        if (!dataOn) {
            log(c, sh("svc data enable") ? "Mobile data: turned on" : "Mobile data: failed");
        } else {
            log(c, "Mobile data: already on");
        }

        if (isHotspotOn(c)) {
            log(c, "Hotspot: already on");
        } else {
            setHotspot(c, true);
            // The hotspot comes up asynchronously, and the phone switches Wi-Fi off while it does.
            for (int i = 0; i < 20 && !isHotspotOn(c); i++) sleep(500);
            sleep(3000);
        }

        // Wi-Fi goes last: the phone runs Wi-Fi and hotspot together once the hotspot is up.
        WifiManager wm = c.getSystemService(WifiManager.class);
        if (!wm.isWifiEnabled()) {
            boolean apBefore = isHotspotOn(c);
            log(c, sh("svc wifi enable") ? "Wi-Fi: turned on" : "Wi-Fi: failed");
            sleep(3000);
            if (apBefore && !isHotspotOn(c)) log(c, "Hotspot: dropped when Wi-Fi turned on");
        } else {
            log(c, "Wi-Fi: already on");
        }
    }

    // ---- Shizuku ----

    static boolean shizukuRunning() {
        return Shizuku.pingBinder() && !Shizuku.isPreV11();
    }

    static boolean shizukuReady() {
        return shizukuRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }

    static String shizukuProblem() {
        return shizukuRunning() ? "Shizuku permission not granted (open this app)"
                : "Shizuku not running (open Shizuku, tap Start)";
    }

    /** Shizuku hands its binder to a freshly started process asynchronously, so give it a few seconds. */
    static boolean waitForShizuku() {
        for (int i = 0; i < 20 && !Shizuku.pingBinder(); i++) sleep(250);
        return shizukuReady();
    }

    /** Runs a command as the ADB shell user through Shizuku. */
    private static boolean sh(String cmd) {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
            return p.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- Hotspot ----

    static boolean isHotspotOn(Context c) {
        try {
            HiddenApiBypass.addHiddenApiExemptions("");
            WifiManager wm = c.getSystemService(WifiManager.class);
            return (boolean) WifiManager.class.getMethod("isWifiApEnabled").invoke(wm);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Context that reports the shell package, matching the uid Shizuku makes our binder calls with. */
    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) {
            super(base);
        }

        // Overrides the hidden Context#getOpPackageName() used by TetheringManager.
        public String getOpPackageName() {
            return SHELL_PKG;
        }
    }

    /** A TetheringManager whose calls reach the tethering service as the shell user (holds TETHER_PRIVILEGED). */
    private static Object shellTetheringManager(Context c) throws Exception {
        HiddenApiBypass.addHiddenApiExemptions("");
        IBinder raw = SystemServiceHelper.getSystemService("tethering");
        if (raw == null) throw new IllegalStateException("tethering service not found");
        IBinder binder = new ShizukuBinderWrapper(raw);
        Supplier<IBinder> supplier = () -> binder;
        return Class.forName("android.net.TetheringManager")
                .getConstructor(Context.class, Supplier.class)
                .newInstance(new ShellContext(c), supplier);
    }

    static void setHotspot(Context c, boolean on) {
        try {
            Object tm = shellTetheringManager(c);
            if (on) {
                Class<?> cbClass = Class.forName("android.net.TetheringManager$StartTetheringCallback");
                Object cb = Proxy.newProxyInstance(cbClass.getClassLoader(), new Class<?>[]{cbClass},
                        (proxy, m, args) -> {
                            switch (m.getName()) {
                                case "onTetheringStarted":
                                    log(c, "Hotspot: turned on");
                                    return null;
                                case "onTetheringFailed":
                                    log(c, "Hotspot: failed, error " + args[0]);
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

    // ---- Log ----

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
