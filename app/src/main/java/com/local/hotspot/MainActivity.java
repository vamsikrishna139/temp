package com.local.hotspot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView status;
    private TextView log;
    private Button toggle;
    private boolean askedWrite;
    private boolean askedBattery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(18);
        toggle = new Button(this);
        toggle.setOnClickListener(v -> onToggle());
        log = new TextView(this);
        log.setTextSize(13);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);

        root.addView(status);
        root.addView(toggle);
        root.addView(scroll);
        setContentView(root);

        Hotspot.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        askPermissions();
    }

    private void onToggle() {
        boolean on = !Hotspot.isEnabled(this);
        Hotspot.setEnabled(this, on);
        if (on && Hotspot.inWindow()) {
            Context c = getApplicationContext();
            new Thread(() -> Hotspot.turnOn(c)).start();
            new Handler(Looper.getMainLooper()).postDelayed(this::refresh, 6000);
        }
        refresh();
    }

    private void askPermissions() {
        String pkg = getPackageName();
        if (!Settings.System.canWrite(this) && !askedWrite) {
            askedWrite = true;
            Toast.makeText(this, "Allow \"Modify system settings\" so the app can switch the hotspot",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + pkg)));
            return;
        }
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
        if (!Settings.System.canWrite(this)) s.append("⚠ \"Modify system settings\" not allowed\n");
        if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
            s.append("⚠ Battery optimisation is still on\n");
        }
        status.setText(s);
        toggle.setText(on ? "Turn schedule OFF" : "Turn schedule ON");
        log.setText(Hotspot.prefs(this).getString("log", ""));
    }
}
