package com.eymen.pillog;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

/** JobScheduler ile periyodik kayıt. Sistem işleri toplu çalıştırdığı için (Doze bakım pencereleri) pil yükü çok düşüktür. */
final class Scheduler {
    static final int PERIODIC_JOB_ID = 1001;
    static final int ONESHOT_JOB_ID = 1002;
    static final long INTERVAL_MS = 15L * 60L * 1000L; // Android'in izin verdiği en kısa periyot

    private Scheduler() {}

    static void schedule(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null) return;
        ComponentName cn = new ComponentName(ctx, LogJobService.class);
        if (js.getPendingJob(PERIODIC_JOB_ID) == null) {
            JobInfo periodic = new JobInfo.Builder(PERIODIC_JOB_ID, cn)
                    .setPeriodic(INTERVAL_MS, 5L * 60L * 1000L)
                    .setPersisted(true)
                    .build();
            js.schedule(periodic);
        }
    }

    /** Uygulama açıldığında hemen bir kayıt alınsın (dosya oluştuğunu görmek için). */
    static void runNow(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null) return;
        JobInfo once = new JobInfo.Builder(ONESHOT_JOB_ID, new ComponentName(ctx, LogJobService.class))
                .setOverrideDeadline(1000)
                .build();
        js.schedule(once);
    }
}
