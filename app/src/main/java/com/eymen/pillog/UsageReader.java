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

    static Result read(Context ctx, long from, long to) {
        Result r = new Result();
        if (!hasAccess(ctx)) return r;
        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        if (usm == null) return r;
        UsageEvents ev = usm.queryEvents(from, to);
        if (ev == null) return r;
        r.ok = true;

        UsageEvents.Event e = new UsageEvents.Event();
        long screenSince = -1;
        boolean screenSeen = false;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            long t = e.getTimeStamp();
            int type = e.getEventType();

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
