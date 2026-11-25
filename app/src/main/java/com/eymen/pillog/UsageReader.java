package com.eymen.pillog;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.util.HashMap;
import java.util.Map;

/** UsageStats olaylarından aralık içindeki ön plan / ön plan servisi sürelerini ve olay sayılarını çıkarır. */
final class UsageReader {

    static final class AppUsage {
        long fgMs;        // ekranda (activity resumed) geçen süre
        long fgsMs;       // ön plan servisi (müzik, navigasyon, indirme vb.) süresi
        int resumes;      // kaç kez açıldı
        int fgsStarts;    // kaç kez ön plan servisi başlattı
        int events;       // toplam olay sayısı (bildirim, etkileşim, kova değişimi vb.)
        // iç durum
        int fgActive;
        long fgSince;
        int fgsActive;
        long fgsSince;
        boolean seenFg;
        boolean seenFgs;
    }

    static final class Result {
        final Map<String, AppUsage> apps = new HashMap<>();
        /** Aralıktaki tüm olaylar, zaman sırasıyla (ham kayıt) */
        final java.util.List<String> events = new java.util.ArrayList<>();
        long screenOnMs;
        int screenOnCount;
        boolean ok;
    }

    private UsageReader() {}

    static boolean hasAccess(Context ctx) {
        AppOpsManager aom = ctx.getSystemService(AppOpsManager.class);
        if (aom == null) return false;
        int mode;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mode = aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
        } else {
            //noinspection deprecation
            mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private static String describe(java.text.SimpleDateFormat tf, UsageEvents.Event e, int type) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(tf.format(new java.util.Date(e.getTimeStamp()))).append("  ").append(typeName(type));
        String pkg = e.getPackageName();
        if (pkg != null) {
            sb.append("  ").append(pkg);
            String cls = e.getClassName();
            if (cls != null) {
                if (cls.startsWith(pkg)) cls = cls.substring(pkg.length());
                sb.append('/').append(cls);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (type == 11) sb.append("  kova=").append(bucketName(e.getAppStandbyBucket()));
        }
        return sb.toString();
    }

    static String typeName(int t) {
        switch (t) {
            case 1: return "AÇILDI(ekranda)";
            case 2: return "duraklatıldı";
            case 5: return "yapılandırma değişti";
            case 7: return "kullanıcı etkileşimi";
            case 8: return "kısayol kullanıldı";
            case 10: return "bildirim görüldü";
            case 11: return "bekleme kovası değişti";
            case 12: return "BİLDİRİM geldi";
            case 13: case 14: return "dilim sabitlendi";
            case 15: return "EKRAN AÇILDI";
            case 16: return "EKRAN KAPANDI";
            case 17: return "kilit ekranı gösterildi";
            case 18: return "kilit açıldı";
            case 19: return "ÖN PLAN SERVİSİ başladı";
            case 20: return "ön plan servisi durdu";
            case 21: return "ön plan servisi sürüyor";
            case 22: return "ön plan servisi (gün devri)";
            case 23: return "arka plana geçti";
            case 24: return "kapatıldı";
            case 26: return "CİHAZ KAPANIYOR";
            case 27: return "CİHAZ AÇILDI";
            case 28: return "kullanıcı kilidi açıldı";
            case 29: return "kullanıcı durduruldu";
            case 30: return "locus ayarlandı";
            case 31: return "bileşen kullanıldı(arka plan)";
            default: return "olay#" + t;
        }
    }

    private static String bucketName(int b) {
        switch (b) {
            case 5: return "muaf";
            case 10: return "aktif";
            case 20: return "çalışma seti";
            case 30: return "sık";
            case 40: return "nadir";
            case 45: return "KISITLI";
            case 50: return "hiç";
            default: return String.valueOf(b);
        }
    }

    static Result read(Context ctx, long from, long to) {
        Result r = new Result();
        if (!hasAccess(ctx)) return r;
        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        if (usm == null) return r;
        UsageEvents ev = usm.queryEvents(from, to);
        if (ev == null) return r;
        r.ok = true;

        java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US);
        UsageEvents.Event e = new UsageEvents.Event();
        long screenSince = -1;
        boolean screenSeen = false;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            long t = e.getTimeStamp();
            int type = e.getEventType();
            if (type != 25 /* FLUSH_TO_DISK */) r.events.add(describe(tf, e, type));

            if (type == 15 /* SCREEN_INTERACTIVE */) {
                screenSeen = true;
                if (screenSince < 0) screenSince = t;
                r.screenOnCount++;
                continue;
            } else if (type == 16 /* SCREEN_NON_INTERACTIVE */) {
                if (screenSince >= 0) r.screenOnMs += t - screenSince;
                else if (!screenSeen) r.screenOnMs += t - from; // aralık başında ekran açıktı
                screenSince = -1;
                screenSeen = true;
                continue;
            }

            String pkg = e.getPackageName();
            if (pkg == null) continue;
            AppUsage a = r.apps.get(pkg);
            if (a == null) {
                a = new AppUsage();
                r.apps.put(pkg, a);
            }
            a.events++;

            switch (type) {
                case 1: // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
                    if (a.fgActive == 0) a.fgSince = t;
                    a.fgActive++;
                    a.resumes++;
                    a.seenFg = true;
                    break;
                case 2: // ACTIVITY_PAUSED / MOVE_TO_BACKGROUND
                    if (a.fgActive > 0) {
                        a.fgActive--;
                        if (a.fgActive == 0) a.fgMs += t - a.fgSince;
                    } else if (!a.seenFg) {
                        a.fgMs += t - from; // aralık başlamadan önce açılmıştı
                    }
                    a.seenFg = true;
                    break;
                case 19: // FOREGROUND_SERVICE_START
                    if (a.fgsActive == 0) a.fgsSince = t;
                    a.fgsActive++;
                    a.fgsStarts++;
                    a.seenFgs = true;
                    break;
                case 20: // FOREGROUND_SERVICE_STOP
                    if (a.fgsActive > 0) {
                        a.fgsActive--;
                        if (a.fgsActive == 0) a.fgsMs += t - a.fgsSince;
                    } else if (!a.seenFgs) {
                        a.fgsMs += t - from;
                    }
                    a.seenFgs = true;
                    break;
                default:
                    break;
            }
        }
        // Aralık sonunda hâlâ açık olanlar
        for (AppUsage a : r.apps.values()) {
            if (a.fgActive > 0) a.fgMs += to - a.fgSince;
            if (a.fgsActive > 0) a.fgsMs += to - a.fgsSince;
        }
        if (screenSince >= 0) r.screenOnMs += to - screenSince;
        return r;
    }
}
