package com.eymen.pillog;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Bir sistem servisinin dump çıktısını satır satır okur (dumpsys ile aynı veri).
 * Önce binder üzerinden doğrudan, olmazsa "dumpsys" komutuyla dener. DUMP izni gerekir.
 */
final class Dumper {
    interface LineSink {
        void line(String l) throws Exception;
    }

    private Dumper() {}

    /** @return kullanılan yol ("binder"/"dumpsys"); hata olursa istisna fırlatır */
    static String dump(String service, String[] args, LineSink sink) throws Exception {
        Exception first;
        try {
            int n = viaBinder(service, args, sink);
            if (n > 0) return "binder";
            first = new IllegalStateException("binder boş döndü");
        } catch (Exception e) {
            first = e;
        }
        String[] cmd = new String[args.length + 2];
        cmd[0] = "dumpsys";
        cmd[1] = service;
        System.arraycopy(args, 0, cmd, 2, args.length);
        java.lang.Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        int n;
        try (InputStream in = p.getInputStream()) {
            n = read(in, sink);
        } finally {
            p.destroy();
        }
        if (n == 0) throw new IllegalStateException(service + " okunamadı: " + first);
        return "dumpsys";
    }

    private static int viaBinder(String service, String[] args, LineSink sink) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method get = sm.getMethod("getService", String.class);
        IBinder b = (IBinder) get.invoke(null, service);
        if (b == null) throw new IllegalStateException(service + " servisi bulunamadı");
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor readSide = pipe[0];
        try {
            // Asenkron: servis boruya yazarken biz okuruz, kilitlenme olmaz
            b.dumpAsync(pipe[1].getFileDescriptor(), args);
        } finally {
            pipe[1].close();
        }
        Thread guard = new Thread(() -> {
            try {
                Thread.sleep(60_000);
                readSide.close();
            } catch (Exception ignored) {
            }
        });
        guard.setDaemon(true);
        guard.start();
        try (InputStream in = new FileInputStream(readSide.getFileDescriptor())) {
            return read(in, sink);
        } finally {
            guard.interrupt();
            try { readSide.close(); } catch (Exception ignored) { }
        }
    }

    private static int read(InputStream in, LineSink sink) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 16 * 1024);
        String l;
        int n = 0;
        while ((l = br.readLine()) != null) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            n++;
            sink.line(l);
        }
        return n;
    }
}
