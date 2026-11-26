package com.eymen.pillog;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

/** JobScheduler ile periyodik kayıt. Sistem işleri toplu çalıştırdığı için (Doze bakım pencereleri) pil yükü çok düşüktür. */
final class Scheduler {
    static final int PERIODIC_JOB_ID = 1001;
    static final long INTERVAL_MS = 60L * 60L * 1000L; // saatte bir

    private Scheduler() {}

    static void schedule(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null) return;
        ComponentName cn = new ComponentName(ctx, LogJobService.class);
        JobInfo pending = js.getPendingJob(PERIODIC_JOB_ID);
        // Eski sürümden kalan farklı aralıklı iş varsa yenisiyle değiştir
        if (pending == null || pending.getIntervalMillis() != INTERVAL_MS) {
            JobInfo periodic = new JobInfo.Builder(PERIODIC_JOB_ID, cn)
                    .setPeriodic(INTERVAL_MS, 10L * 60L * 1000L)
                    .setPersisted(true)
                    .build();
            js.schedule(periodic);
        }
    }
}
