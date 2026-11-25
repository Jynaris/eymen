package com.eymen.pillog;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sistemin kendi pil istatistiklerini (dumpsys batterystats, checkin/CSV biçimi) okur.
 * Bu, tüm uygulamalar ve sistem süreçleri için UID bazında CPU süresi, tahmini mAh,
 * wakelock, alarm, iş (job) ve ağ verisi sağlar. android.permission.DUMP gerektirir (ADB ile bir kez verilir).
 * Değerler "son tam şarjdan beri" toplamdır; aralık farkları Collector'da hesaplanır.
 */
final class BatteryStatsReader {

    static final class UidStats {
        long cpuUserMs, cpuSysMs;
        double mah;
        long wakelockMs;
        long wakeupAlarms;
        long jobs;
        long mobileRx, mobileTx, wifiRx, wifiTx;

        long cpuMs() { return cpuUserMs + cpuSysMs; }
    }

    static final class Result {
        final Map<Integer, UidStats> uids = new HashMap<>();
        final Map<Integer, String> uidNames = new HashMap<>();
        /** Uygulama dışı tüketim kalemleri: ekran, hücresel radyo, wifi, boşta vb. */
        final Map<String, Double> otherMah = new TreeMap<>();
        String error;
        String source;
    }

    private BatteryStatsReader() {}

    static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED;
    }

    static Result read(Context ctx) {
        Result r = new Result();
        if (!hasPermission(ctx)) {
            r.error = "DUMP izni yok (README'deki adb komutunu bir kez çalıştırın)";
            return r;
        }
        String[] args = {"-c", "--charged"};
        Exception first = null;
        try {
            parseFromBinder(args, r);
            r.source = "binder";
            if (!r.uids.isEmpty()) return r;
        } catch (Exception e) {
            first = e;
        }
        // Yedek yol: dumpsys komutunu uygulamanın kendi kimliğiyle çalıştır
        try {
            r.uids.clear();
            r.uidNames.clear();
            r.otherMah.clear();
            java.lang.Process p = new ProcessBuilder("dumpsys", "batterystats", "-c", "--charged")
                    .redirectErrorStream(true).start();
            try (InputStream in = p.getInputStream()) {
                parse(in, r);
            }
            p.waitFor();
            r.source = "dumpsys";
            if (r.uids.isEmpty()) r.error = "batterystats boş döndü" + (first != null ? " (" + first + ")" : "");
        } catch (Exception e) {
            r.error = "batterystats okunamadı: " + (first != null ? first + " / " : "") + e;
        }
        return r;
    }

    private static void parseFromBinder(String[] args, Result r) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method get = sm.getMethod("getService", String.class);
        IBinder b = (IBinder) get.invoke(null, "batterystats");
        if (b == null) throw new IllegalStateException("batterystats servisi bulunamadı");
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor readSide = pipe[0];
        ParcelFileDescriptor writeSide = pipe[1];
        try {
            // Asenkron: servis boruya yazarken biz okuyabilelim (kilitlenme olmasın)
            b.dumpAsync(writeSide.getFileDescriptor(), args);
        } finally {
            writeSide.close();
        }
        // Güvenlik: servis takılırsa 60 sn sonra okumayı kes
        final ParcelFileDescriptor rs = readSide;
        Thread guard = new Thread(() -> {
            try {
                Thread.sleep(60_000);
                rs.close();
            } catch (Exception ignored) {
            }
        });
        guard.setDaemon(true);
        guard.start();
        try (InputStream in = new FileInputStream(readSide.getFileDescriptor())) {
            parse(in, r);
        } finally {
            guard.interrupt();
            try { readSide.close(); } catch (Exception ignored) { }
        }
    }

    /** Satır biçimi: 9,<uid>,<kategori>,<tür>,alanlar... ("l" = son tam şarjdan beri) */
    static void parse(InputStream in, Result r) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 16 * 1024);
        String line;
        while ((line = br.readLine()) != null) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            // Geçmiş (history) satırları çok sayıdadır ve gerekmez: hızlıca atla
            int c1 = line.indexOf(',');
            if (c1 < 0) continue;
            int c2 = line.indexOf(',', c1 + 1);
            if (c2 < 0) continue;
            int c3 = line.indexOf(',', c2 + 1);
            if (c3 < 0) continue;
            String cat = line.substring(c2 + 1, c3);
            if (!cat.equals("l") && !cat.equals("i")) continue;

            String[] p = line.split(",", -1);
            if (p.length < 5) continue;
            int uid;
            try {
                uid = Integer.parseInt(p[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            String type = p[3];

            if (cat.equals("i")) {
                if (type.equals("uid") && p.length >= 6) {
                    try {
                        int u = Integer.parseInt(p[4]);
                        String old = r.uidNames.get(u);
                        r.uidNames.put(u, old == null ? p[5] : (old.contains(p[5]) ? old : old + "+" + p[5]));
                    } catch (NumberFormatException ignored) {
                    }
                }
                continue;
            }

            switch (type) {
                case "cpu":
                    if (p.length >= 6) {
                        UidStats s = stats(r, uid);
                        s.cpuUserMs = parseLong(p[4]);
                        s.cpuSysMs = parseLong(p[5]);
                    }
                    break;
                case "pwi":
                    if (p.length >= 6) {
                        double mah = parseDouble(p[5]);
                        if (p[4].equals("uid")) {
                            stats(r, uid).mah += mah;
                        } else {
                            Double old = r.otherMah.get(p[4]);
                            r.otherMah.put(p[4], (old == null ? 0 : old) + mah);
                        }
                    }
                    break;
                case "wl": {
                    // ... ,<süre>,p,<adet>,... -> kısmi (partial) wakelock = ekran kapalıyken CPU'yu uyanık tutar
                    for (int i = 5; i < p.length - 1; i++) {
                        if (p[i].equals("p")) {
                            stats(r, uid).wakelockMs += parseLong(p[i - 1]);
                            break;
                        }
                    }
                    break;
                }
                case "wua":
                    if (p.length >= 6) stats(r, uid).wakeupAlarms += parseLong(p[5]);
                    break;
                case "jb":
                    if (p.length >= 7) stats(r, uid).jobs += parseLong(p[6]);
                    break;
                case "nt":
                    if (p.length >= 8) {
                        UidStats s = stats(r, uid);
                        s.mobileRx = parseLong(p[4]);
                        s.mobileTx = parseLong(p[5]);
                        s.wifiRx = parseLong(p[6]);
                        s.wifiTx = parseLong(p[7]);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private static UidStats stats(Result r, int uid) {
        UidStats s = r.uids.get(uid);
        if (s == null) {
            s = new UidStats();
            r.uids.put(uid, s);
        }
        return s;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            try {
                return (long) Double.parseDouble(s.trim());
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
