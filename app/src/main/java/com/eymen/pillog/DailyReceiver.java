package com.eymen.pillog;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Her sabah 10:00: biten dönemin dosyasını kapatır ve bildirim gönderir. Sonraki kayıtlar yeni dosyaya gider.
 * Ayrıca açılışta / güncellemede alarmı yeniden kurar (alarmlar yeniden başlatmada silinir).
 */
public class DailyReceiver extends BroadcastReceiver {
    static final String ACTION_ROLLOVER = "com.eymen.pillog.ROLLOVER";
    private static final String CHANNEL = "pillog_daily";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        String a = intent.getAction();
        if (!ACTION_ROLLOVER.equals(a)) {
            // BOOT_COMPLETED / MY_PACKAGE_REPLACED
            schedule(app);
            try { Scheduler.schedule(app); } catch (Exception ignored) { }
            return;
        }
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                long now = System.currentTimeMillis();
                String oldName = LogWriter.fileNameFor(now - 24L * 3600_000L);
                String newName = LogWriter.fileNameFor(now);
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(now));
                try {
                    LogWriter.appendTo(app, oldName, "\n######## KAYIT SONU " + ts + " — sonraki kayıtlar: " + newName + " ########\n");
                    LogWriter.appendTo(app, newName, "######## YENİ KAYIT DÖNEMİ " + ts + " (önceki: " + oldName + ") ########\n");
                } catch (Exception ignored) {
                }
                notify(app, oldName, newName);
            } finally {
                schedule(app);
                pr.finish();
            }
        }, "pillog-daily").start();
    }

    /** Bir sonraki 10:00 için alarm kurar (aynı PendingIntent ile tekrar çağrılması güvenlidir). */
    static void schedule(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, LogWriter.ROLLOVER_HOUR);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 1000) c.add(Calendar.DAY_OF_MONTH, 1);
        Intent i = new Intent(ctx, DailyReceiver.class).setAction(ACTION_ROLLOVER);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
        }
    }

    static boolean canNotify(Context ctx) {
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        return nm != null && nm.areNotificationsEnabled();
    }

    static boolean exactAllowed(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        return am != null && am.canScheduleExactAlarms();
    }

    private static void notify(Context ctx, String oldName, String newName) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Günlük kayıt", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(ctx, 2, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = "Kayıt alma bitti: " + oldName + ". Yeni kayda geçiliyor: " + newName;
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Pil Log: kayıt tamamlandı")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text + "\nDosya: İndirilenler/" + LogWriter.FOLDER + "/" + oldName))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(10, n);
        } catch (SecurityException ignored) {
        }
    }
}
