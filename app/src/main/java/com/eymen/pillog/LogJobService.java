package com.eymen.pillog;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class LogJobService extends JobService {
    private volatile Thread worker;

    @Override
    public boolean onStartJob(final JobParameters params) {
        worker = new Thread(() -> {
            try {
                Collector.collectAndWrite(getApplicationContext());
            } catch (Throwable t) {
                try {
                    LogWriter.append(getApplicationContext(), "\n[HATA] " + android.util.Log.getStackTraceString(t) + "\n");
                } catch (Throwable ignored) {
                }
            } finally {
                jobFinished(params, false);
            }
        }, "pillog-worker");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Thread w = worker;
        if (w != null) w.interrupt();
        return false; // periyodik iş zaten bir sonraki turda tekrar çalışır
    }
}
