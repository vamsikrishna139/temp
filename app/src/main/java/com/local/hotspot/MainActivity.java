package com.local.hotspot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Shizuku.OnBinderReceivedListener binderListener = () -> ui.post(this::onShizukuChanged);
    private final Shizuku.OnRequestPermissionResultListener permListener = (code, result) -> ui.post(this::refresh);

    private TextView status;
    private TextView log;
    private Button toggle;
    private boolean askedShizuku;
    private boolean askedBattery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("Hotspot Scheduler");
        title.setTextSize(24);
        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, pad / 2, 0, pad / 2);
        toggle = new Button(this);
        toggle.setOnClickListener(v -> onToggle());
        log = new TextView(this);
        log.setTextSize(13);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);

        root.addView(title);
        root.addView(status);
        root.addView(toggle);
        root.addView(scroll);
        setContentView(root);

        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addRequestPermissionResultListener(permListener);
        Hotspot.schedule(this);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener);
        Shizuku.removeRequestPermissionResultListener(permListener);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        askPermissions();
    }

    private void onShizukuChanged() {
        refresh();
        askPermissions();
    }

    private void onToggle() {
        boolean on = !Hotspot.isEnabled(this);
        Hotspot.setEnabled(this, on);
        if (on && Hotspot.inWindow()) {
            Context c = getApplicationContext();
            new Thread(() -> {
                try {
                    Hotspot.turnOn(c);
                } catch (Throwable t) {
                    Hotspot.log(c, "Error: " + t);
                }
            }).start();
            ui.postDelayed(this::refresh, 8000);
        }
        refresh();
    }

    private void askPermissions() {
        if (Hotspot.shizukuRunning() && !askedShizuku
                && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            askedShizuku = true;
            Shizuku.requestPermission(1);
            return;
        }
        String pkg = getPackageName();
        PowerManager pm = getSystemService(PowerManager.class);
        if (!pm.isIgnoringBatteryOptimizations(pkg) && !askedBattery) {
            askedBattery = true;
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + pkg)));
        }
    }

    private void refresh() {
        boolean on = Hotspot.isEnabled(this);
        StringBuilder s = new StringBuilder();
        s.append("Schedule: ").append(on ? "ON" : "OFF").append('\n');
        if (on) s.append("Next run: ").append(Hotspot.prefs(this).getString("next", "-")).append('\n');
        s.append("Hourly 07:00-17:00, hotspot off at 18:00\n");
        s.append(Hotspot.shizukuReady() ? "Shizuku: ready\n" : "⚠ " + Hotspot.shizukuProblem() + "\n");
        if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
            s.append("⚠ Battery optimisation is still on\n");
        }
        status.setText(s);
        toggle.setText(on ? "Turn schedule OFF" : "Turn schedule ON");
        log.setText(Hotspot.prefs(this).getString("log", ""));
    }
}
