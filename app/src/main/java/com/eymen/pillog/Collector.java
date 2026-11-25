package com.eymen.pillog;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Tek bir ölçüm turu: pil durumu + UsageStats + batterystats → metin bloğu → İndirilenler'deki log. */
final class Collector {
    private static final Locale L = Locale.US;
    private static final int MAX_ROWS = 40;

    private Collector() {}

    static synchronized void collectAndWrite(Context ctx) throws Exception {
        long startElapsed = SystemClock.elapsedRealtime();
        long startCpu = Process.getElapsedCpuTime();

        SharedPreferences sp = ctx.getSharedPreferences("state", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = sp.getLong("lastRun", 0);
        boolean firstRun = last == 0 || last > now || now - last > 24L * 3600_000L;
        long from = firstRun ? now - Scheduler.INTERVAL_MS : last;

        StringBuilder sb = new StringBuilder(8 * 1024);
        SimpleDateFormat full = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", L);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm:ss", L);
        sb.append("\n==================== ").append(full.format(new Date(now))).append(" ====================\n");
        sb.append("Aralık: ").append(hm.format(new Date(from))).append(" -> ").append(hm.format(new Date(now)))
                .append(" (").append(dur(now - from)).append(")").append(firstRun ? " [ilk ölçüm]" : "").append('\n');

        // ---------- Pil durumu ----------
        appendBattery(ctx, sb, sp);

        // ---------- UsageStats ----------
        UsageReader.Result usage = UsageReader.read(ctx, from, now);

        // ---------- batterystats ----------
        BatteryStatsReader.Result bs = BatteryStatsReader.read(ctx);

        PackageManager pm = ctx.getPackageManager();
        Map<String, String> labelCache = new HashMap<>();

        if (usage.ok) {
            sb.append("Ekran açık (aralıkta): ").append(dur(usage.screenOnMs))
                    .append(" | ekran açılma sayısı: ").append(usage.screenOnCount).append('\n');
        }

        int myUid = Process.myUid();
        double myMahDelta = -1;
        long myCpuDelta = -1;

        if (bs.error == null && !bs.uids.isEmpty()) {
            Map<Integer, long[]> prev = loadPrev(ctx);
            Map<Integer, long[]> cur = new HashMap<>();
            List<Row> rows = new ArrayList<>();
            for (Map.Entry<Integer, BatteryStatsReader.UidStats> en : bs.uids.entrySet()) {
                int uid = en.getKey();
                BatteryStatsReader.UidStats s = en.getValue();
                long[] v = {Math.round(s.mah * 1000), s.cpuMs(), s.wakelockMs, s.wakeupAlarms, s.jobs,
                        s.mobileRx + s.mobileTx, s.wifiRx + s.wifiTx};
                cur.put(uid, v);
                long[] pv = prev.get(uid);
                long[] d = new long[v.length];
                boolean reset = false;
                for (int i = 0; i < v.length; i++) {
                    long p = pv == null ? 0 : pv[i];
                    if (v[i] < p) reset = true;
                }
                for (int i = 0; i < v.length; i++) {
                    d[i] = (pv == null || reset) ? (firstRun ? 0 : v[i]) : v[i] - pv[i];
                }
                Row row = new Row(uid, s, d);
                rows.add(row);
                if (uid == myUid) {
                    myMahDelta = d[0] / 1000.0;
                    myCpuDelta = d[1];
                }
            }
            saveCur(ctx, cur);

            if (firstRun) {
                sb.append("(İlk ölçüm: aralık farkları bir sonraki kayıttan itibaren hesaplanacak.)\n");
            } else {
                rows.sort((a, b) -> {
                    int c = Long.compare(b.d[0], a.d[0]);
                    return c != 0 ? c : Long.compare(b.d[1], a.d[1]);
                });
                sb.append("\n--- UYGULAMA/SÜREÇ BAZINDA, BU ARALIKTA (sistem dahil) ---\n");
                sb.append(String.format(L, "%8s %9s %9s %6s %5s %10s %10s  %s%n",
                        "mAh", "CPU(sn)", "WakeLock", "Alarm", "İş", "Mobil(KB)", "WiFi(KB)", "Uygulama"));
                int n = 0, hidden = 0;
                for (Row row : rows) {
                    boolean any = row.d[0] > 0 || row.d[1] > 0 || row.d[2] > 0 || row.d[3] > 0 || row.d[4] > 0
                            || row.d[5] > 0 || row.d[6] > 0;
                    if (!any) continue;
                    if (n >= MAX_ROWS && row.uid != myUid) { hidden++; continue; }
                    n++;
                    sb.append(String.format(L, "%8.2f %9.1f %9s %6d %5d %10d %10d  %s%n",
                            row.d[0] / 1000.0, row.d[1] / 1000.0, durShort(row.d[2]), row.d[3], row.d[4],
                            row.d[5] / 1024, row.d[6] / 1024, uidLabel(pm, row.uid, bs, labelCache)));
                }
                if (n == 0) sb.append("  (bu aralıkta kayda değer etkinlik yok)\n");
                if (hidden > 0) sb.append("  ... ve ").append(hidden).append(" küçük kalem daha\n");
            }

            // Son tam şarjdan beri toplamlar
            rows.sort((a, b) -> Double.compare(b.s.mah, a.s.mah));
            sb.append("\n--- SON TAM ŞARJDAN BERİ EN ÇOK TÜKETENLER (ilk 15) ---\n");
            sb.append(String.format(L, "%8s %9s %9s  %s%n", "mAh", "CPU(dk)", "WakeLock", "Uygulama"));
            for (int i = 0; i < Math.min(15, rows.size()); i++) {
                Row row = rows.get(i);
                sb.append(String.format(L, "%8.1f %9.1f %9s  %s%n", row.s.mah, row.s.cpuMs() / 60000.0,
                        durShort(row.s.wakelockMs), uidLabel(pm, row.uid, bs, labelCache)));
            }
            if (!bs.otherMah.isEmpty()) {
                sb.append("Donanım kalemleri (mAh, son şarjdan beri): ");
                boolean f = true;
                for (Map.Entry<String, Double> en : bs.otherMah.entrySet()) {
                    if (!f) sb.append(", ");
                    f = false;
                    sb.append(hwName(en.getKey())).append('=').append(String.format(L, "%.1f", en.getValue()));
                }
                sb.append('\n');
            }
        } else {
            sb.append("\n[batterystats] ").append(bs.error).append('\n');
            sb.append("  -> Uygulama bazında mAh/CPU için bilgisayardan bir kez: adb shell pm grant ")
                    .append(ctx.getPackageName()).append(" android.permission.DUMP\n");
        }

        // ---------- UsageStats tablosu ----------
        if (usage.ok) {
            List<Map.Entry<String, UsageReader.AppUsage>> list = new ArrayList<>(usage.apps.entrySet());
            list.removeIf(en -> en.getValue().fgMs <= 0 && en.getValue().fgsMs <= 0 && en.getValue().resumes == 0
                    && en.getValue().fgsStarts == 0);
            list.sort((a, b) -> Long.compare(b.getValue().fgMs + b.getValue().fgsMs, a.getValue().fgMs + a.getValue().fgsMs));
            sb.append("\n--- NE YAPILDI: EKRAN / ÖN PLAN SERVİSİ (UsageStats, bu aralıkta) ---\n");
            if (list.isEmpty()) {
                sb.append("  (ekranda veya ön plan servisinde çalışan uygulama yok)\n");
            } else {
                sb.append(String.format(L, "%10s %7s %12s %7s  %s%n", "Ekranda", "Açılış", "ÖnPlanServis", "Başlat", "Uygulama"));
                int n = 0;
                for (Map.Entry<String, UsageReader.AppUsage> en : list) {
                    if (n++ >= MAX_ROWS) break;
                    UsageReader.AppUsage a = en.getValue();
                    sb.append(String.format(L, "%10s %7d %12s %7d  %s%n", durShort(a.fgMs), a.resumes,
                            durShort(a.fgsMs), a.fgsStarts, pkgLabel(pm, en.getKey(), labelCache)));
                }
            }
        } else {
            sb.append("\n[UsageStats] Kullanım erişimi izni yok (Ayarlar > Kullanım erişimi > Pil Log).\n");
        }

        // ---------- Kendi tüketimi ----------
        long tookMs = SystemClock.elapsedRealtime() - startElapsed;
        long cpuMs = Process.getElapsedCpuTime() - startCpu;
        sb.append("\nPil Log'un kendisi: bu ölçüm ").append(tookMs).append(" ms sürdü, ").append(cpuMs).append(" ms CPU");
        if (myMahDelta >= 0 && !firstRun) {
            sb.append(String.format(L, " | sistem ölçümüne göre aralıkta %.3f mAh, %.2f sn CPU", myMahDelta, myCpuDelta / 1000.0));
        }
        sb.append('\n');

        LogWriter.append(ctx, sb.toString());
        sp.edit().putLong("lastRun", now).apply();
    }

    // ------------------------------------------------------------------

    private static void appendBattery(Context ctx, StringBuilder sb, SharedPreferences sp) {
        Intent bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        BatteryManager bm = ctx.getSystemService(BatteryManager.class);
        PowerManager pw = ctx.getSystemService(PowerManager.class);
        if (bi == null) {
            sb.append("PİL: okunamadı\n");
            return;
        }
        int level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        float pct = scale > 0 ? level * 100f / scale : level;
        int status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        int volt = bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        int health = bi.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);

        float lastPct = sp.getFloat("lastPct", -1);
        sp.edit().putFloat("lastPct", pct).apply();

        sb.append(String.format(L, "PİL: %%%.0f", pct));
        if (lastPct >= 0) sb.append(String.format(L, " (önceki ölçüme göre %+.0f puan)", pct - lastPct));
        sb.append(" | Durum: ").append(statusName(status))
                .append(" | Şarj kaynağı: ").append(plugName(plugged))
                .append(" | Sağlık: ").append(healthName(health));
        if (temp != Integer.MIN_VALUE) sb.append(String.format(L, " | Sıcaklık: %.1f°C", temp / 10f));
        if (volt > 0) sb.append(" | Voltaj: ").append(volt).append(" mV");
        sb.append('\n');

        if (bm != null) {
            long now = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            long avg = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
            long cc = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            sb.append("Akım: anlık ").append(ma(now)).append(", ortalama ").append(ma(avg));
            if (cc > 0 && cc != Long.MIN_VALUE) sb.append(" | Kalan şarj: ").append(cc / 1000).append(" mAh");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                long rem = bm.computeChargeTimeRemaining();
                if (rem > 0) sb.append(" | Dolmasına: ").append(dur(rem));
            }
        }
        if (pw != null) {
            sb.append(" | Ekran şu an: ").append(pw.isInteractive() ? "açık" : "kapalı")
                    .append(" | Doze: ").append(pw.isDeviceIdleMode() ? "evet" : "hayır")
                    .append(" | Güç tasarrufu: ").append(pw.isPowerSaveMode() ? "açık" : "kapalı");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                sb.append(" | Isıl durum: ").append(thermalName(pw.getCurrentThermalStatus()));
            }
        }
        sb.append('\n');
    }

    private static String ma(long v) {
        if (v == Long.MIN_VALUE || v == Integer.MIN_VALUE || v == 0) return "?";
        // Çoğu cihaz mikroamper verir, bazıları miliamper
        long m = Math.abs(v) > 20000 ? v / 1000 : v;
        return m + " mA";
    }

    private static final class Row {
        final int uid;
        final BatteryStatsReader.UidStats s;
        final long[] d;

        Row(int uid, BatteryStatsReader.UidStats s, long[] d) {
            this.uid = uid;
            this.s = s;
            this.d = d;
        }
    }

    private static Map<Integer, long[]> loadPrev(Context ctx) {
        Map<Integer, long[]> m = new HashMap<>();
        File f = new File(ctx.getFilesDir(), "prev_stats.txt");
        if (!f.exists()) return m;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split(" ");
                if (p.length != 8) continue;
                long[] v = new long[7];
                for (int i = 0; i < 7; i++) v[i] = Long.parseLong(p[i + 1]);
                m.put(Integer.parseInt(p[0]), v);
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    private static void saveCur(Context ctx, Map<Integer, long[]> cur) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, long[]> en : cur.entrySet()) {
            sb.append(en.getKey());
            for (long x : en.getValue()) sb.append(' ').append(x);
            sb.append('\n');
        }
        try (FileOutputStream fos = new FileOutputStream(new File(ctx.getFilesDir(), "prev_stats.txt"))) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String uidLabel(PackageManager pm, int uid, BatteryStatsReader.Result bs, Map<String, String> cache) {
        int appId = uid % 100000;
        String special = specialUid(appId);
        String[] pkgs = null;
        try {
            pkgs = pm.getPackagesForUid(uid);
        } catch (Exception ignored) {
        }
        if (special != null) return special + " [SİSTEM uid " + uid + "]";
        if (pkgs != null && pkgs.length > 0) {
            String s = pkgLabel(pm, pkgs[0], cache);
            if (pkgs.length > 1) s += " +" + (pkgs.length - 1) + " paket";
            return s;
        }
        String n = bs.uidNames.get(uid);
        if (n != null) return n + " [uid " + uid + "]";
        if (appId >= 90000 && appId < 100000) return "İzole süreç [uid " + uid + "]";
        String name = null;
        try {
            name = pm.getNameForUid(uid);
        } catch (Exception ignored) {
        }
        return (name != null ? name : "Bilinmeyen/kaldırılmış") + " [uid " + uid + "]";
    }

    private static String pkgLabel(PackageManager pm, String pkg, Map<String, String> cache) {
        String c = cache.get(pkg);
        if (c != null) return c;
        String out;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence lbl = pm.getApplicationLabel(ai);
            boolean sys = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            out = lbl + " (" + pkg + ")" + (sys ? " [SİSTEM]" : "");
        } catch (Exception e) {
            out = pkg;
        }
        cache.put(pkg, out);
        return out;
    }

    private static String specialUid(int appId) {
        switch (appId) {
            case 0: return "Çekirdek / root süreçleri";
            case 1000: return "Android Sistemi (system_server)";
            case 1001: return "Telefon / hücresel radyo";
            case 1002: return "Bluetooth";
            case 1010: return "Wi-Fi";
            case 1013: return "Medya sunucusu";
            case 1019: return "DRM";
            case 1021: return "GPS";
            case 1041: return "Ses sunucusu";
            case 1047: return "Kamera sunucusu";
            case 1068: return "Ağ yığını";
            case 2000: return "Shell (adb)";
            default: return null;
        }
    }

    private static String hwName(String k) {
        switch (k) {
            case "scrn": return "Ekran";
            case "cell": return "Hücresel";
            case "phone": return "Telefon görüşmesi";
            case "wifi": return "WiFi";
            case "blue": return "Bluetooth";
            case "idle": return "Boşta";
            case "flashlight": case "flash": return "Fener";
            case "camera": return "Kamera";
            case "over": return "Fazla sayılan";
            case "unacc": return "Hesaplanamayan";
            case "user": return "Kullanıcı";
            default: return k;
        }
    }

    private static String statusName(int s) {
        switch (s) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Şarj oluyor";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Deşarj";
            case BatteryManager.BATTERY_STATUS_FULL: return "Dolu";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Şarj olmuyor";
            default: return "?";
        }
    }

    private static String plugName(int p) {
        if (p == 0) return "yok";
        if ((p & BatteryManager.BATTERY_PLUGGED_AC) != 0) return "Adaptör";
        if ((p & BatteryManager.BATTERY_PLUGGED_USB) != 0) return "USB";
        if ((p & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) return "Kablosuz";
        return "diğer";
    }

    private static String healthName(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "İyi";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Aşırı ısınma";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Ölü";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Aşırı voltaj";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Soğuk";
            default: return "?";
        }
    }

    private static String thermalName(int t) {
        switch (t) {
            case 0: return "normal";
            case 1: return "hafif";
            case 2: return "orta";
            case 3: return "ciddi";
            case 4: return "kritik";
            case 5: return "acil";
            case 6: return "kapanma";
            default: return "?";
        }
    }

    static String dur(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + " sa " + m + " dk";
        if (m > 0) return m + " dk " + sec + " sn";
        return sec + " sn";
    }

    private static String durShort(long ms) {
        if (ms <= 0) return "-";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m" + (s % 60) + "s";
        return (m / 60) + "h" + (m % 60) + "m";
    }
}
