package com.eymen.pillog;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Tek seferlik kurulum ekranı. Uygulama bir kez açılınca arka planda 15 dakikada bir
 * kayıt almaya başlar; bu ekranı tekrar açmanız gerekmez.
 */
public class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(15);
        status.setTextIsSelectable(true);
        root.addView(status);

        Button usage = new Button(this);
        usage.setText("Kullanım erişimi iznini aç");
        usage.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                i.setData(Uri.parse("package:" + getPackageName()));
            }
            try {
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            }
        });
        root.addView(usage);

        Button batt = new Button(this);
        batt.setText("Pil optimizasyonundan muaf tut (düzenli kayıt için)");
        batt.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
        root.addView(batt);

        Button now = new Button(this);
        now.setText("Şimdi bir kayıt al");
        now.setOnClickListener(v -> {
            Scheduler.runNow(this);
            status.postDelayed(this::refresh, 3000);
        });
        root.addView(now);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
        Scheduler.schedule(this);
        Scheduler.runNow(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean usageOk = UsageReader.hasAccess(this);
        boolean dumpOk = BatteryStatsReader.hasPermission(this);
        StringBuilder sb = new StringBuilder();
        sb.append("Pil Log arka planda çalışıyor.\n\n");
        sb.append("Her ~15 dakikada bir şu dosyaya kayıt eklenir:\n")
                .append("İndirilenler/").append(LogWriter.FOLDER).append("/").append(LogWriter.todayFileName())
                .append("\n(her gün yeni dosya)\n\n");
        sb.append(usageOk ? "✔" : "✘").append(" Kullanım erişimi (hangi uygulama ne kadar ekranda / ön plan servisinde)\n");
        sb.append(dumpOk ? "✔" : "✘").append(" DUMP izni (tüm uygulama ve sistem süreçlerinin mAh ve CPU tüketimi)\n");
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        boolean battOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        sb.append(battOk ? "✔" : "✘").append(" Pil optimizasyonu muafiyeti (uygulamayı hiç açmasanız da Android kayıtları günde bire düşürmesin diye; kendi tüketimi artmaz)\n");
        if (!dumpOk) {
            sb.append("\nDUMP iznini bilgisayardan bir kez verin:\n")
                    .append("adb shell pm grant ").append(getPackageName()).append(" android.permission.DUMP\n");
        }
        long last = getSharedPreferences("state", MODE_PRIVATE).getLong("lastRun", 0);
        if (last > 0) {
            sb.append("\nSon kayıt: ").append(new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date(last))).append('\n');
        }
        sb.append("\nBu ekranı kapatabilirsiniz; kayıt devam eder.\n");
        status.setText(sb);
    }
}
