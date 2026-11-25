package com.eymen.pillog;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sistemin kendi pil istatistiklerini (dumpsys batterystats -c --charged, CSV) okur.
 * UID bazında özet + süreç / wakelock / alarm / iş / servis / senkronizasyon / kernel ayrıntıları.
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
        /**
         * Ayrıntılı sayaçlar. Anahtar: tür \t uid \t ad \t ölçü  (ör. "pr\t1000\tsurfaceflinger\tcpu").
         * Değer: son tam şarjdan beri toplam (ms veya adet).
         */
        final Map<String, Long> detail = new HashMap<>();
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
            r.error = "DUMP izni yok (kurulum dosyasındaki adb komutunu bir kez çalıştırın)";
            return r;
        }
        try {
            r.source = Dumper.dump("batterystats", new String[]{"-c", "--charged"}, l -> parseLine(l, r));
            if (r.uids.isEmpty()) r.error = "batterystats boş döndü (" + r.source + ")";
        } catch (Exception e) {
            r.error = "batterystats okunamadı: " + e;
        }
        return r;
    }

    /** Test için */
    static void parse(InputStream in, Result r) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String l;
        while ((l = br.readLine()) != null) parseLine(l, r);
    }

    /** Satır biçimi: 9,<uid>,<kategori>,<tür>,alanlar... ("l" = son tam şarjdan beri, "i" = bilgi) */
    static void parseLine(String line, Result r) {
        // Geçmiş (h) satırları çok sayıdadır ve gerekmez: bölmeden önce hızlıca ele
        int c1 = line.indexOf(',');
        if (c1 < 0) return;
        int c2 = line.indexOf(',', c1 + 1);
        if (c2 < 0) return;
        int c3 = line.indexOf(',', c2 + 1);
        if (c3 < 0) return;
        String cat = line.substring(c2 + 1, c3);
        if (!cat.equals("l") && !cat.equals("i")) return;

        String[] p = line.split(",", -1);
        if (p.length < 5) return;
        int uid;
        try {
            uid = Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return;
        }
        String type = p[3];
        int n = p.length;

        if (cat.equals("i")) {
            if (type.equals("uid") && n >= 6) {
                try {
                    int u = Integer.parseInt(p[4]);
                    String name = join(p, 5, n - 1);
                    String old = r.uidNames.get(u);
                    r.uidNames.put(u, old == null ? name : (old.contains(name) ? old : old + "+" + name));
                } catch (NumberFormatException ignored) {
                }
            }
            return;
        }

        switch (type) {
            case "cpu":
                if (n >= 6) {
                    UidStats s = stats(r, uid);
                    s.cpuUserMs = num(p[4]);
                    s.cpuSysMs = num(p[5]);
                }
                break;
            case "pwi":
                if (n >= 6) {
                    double mah = dbl(p[5]);
                    if (p[4].equals("uid")) {
                        stats(r, uid).mah += mah;
                    } else {
                        Double old = r.otherMah.get(p[4]);
                        r.otherMah.put(p[4], (old == null ? 0 : old) + mah);
                    }
                }
                break;
            case "wl": {
                // ad, tamSüre,f,adet,..., kısmiSüre,p,adet,...  (kısmi = ekran kapalıyken CPU'yu uyanık tutar)
                for (int i = 5; i < n - 1; i++) {
                    if (p[i].equals("p")) {
                        long ms = num(p[i - 1]);
                        long cnt = num(p[i + 1]);
                        stats(r, uid).wakelockMs += ms;
                        String name = findWlName(p);
                        add(r, "wl", uid, name, "ms", ms);
                        add(r, "wl", uid, name, "adet", cnt);
                        break;
                    }
                }
                break;
            }
            case "wua": // ad, adet
                if (n >= 6) {
                    long c = num(p[n - 1]);
                    stats(r, uid).wakeupAlarms += c;
                    add(r, "alarm", uid, join(p, 4, n - 2), "adet", c);
                }
                break;
            case "jb": // ad, süre, adet
                if (n >= 7) {
                    long c = num(p[n - 1]);
                    stats(r, uid).jobs += c;
                    String name = join(p, 4, n - 3);
                    add(r, "job", uid, name, "ms", num(p[n - 2]));
                    add(r, "job", uid, name, "adet", c);
                }
                break;
            case "sy": // ad, süre, adet
                if (n >= 7) {
                    String name = join(p, 4, n - 3);
                    add(r, "sync", uid, name, "ms", num(p[n - 2]));
                    add(r, "sync", uid, name, "adet", num(p[n - 1]));
                }
                break;
            case "pr": // ad, user, sys, fg, başlatma, anr, çökme
                if (n >= 11) {
                    String name = join(p, 4, n - 7);
                    add(r, "proc", uid, name, "cpu", num(p[n - 6]) + num(p[n - 5]));
                    add(r, "proc", uid, name, "başlatma", num(p[n - 3]));
                    add(r, "proc", uid, name, "ANR", num(p[n - 2]));
                    add(r, "proc", uid, name, "çökme", num(p[n - 1]));
                }
                break;
            case "apk": // uyandırma, paket, servis, başlamaSüresi, başlatma, çalıştırma
                if (n >= 10) {
                    String name = p[n - 5] + "/" + p[n - 4];
                    add(r, "servis", uid, name, "ms", num(p[n - 3]));
                    add(r, "servis", uid, name, "başlatma", num(p[n - 2]));
                    add(r, "servis", uid, name, "çalıştırma", num(p[n - 1]));
                }
                break;
            case "kwl": // ad, süre, adet
                if (n >= 7) {
                    String name = join(p, 4, n - 3);
                    add(r, "kernelWL", uid, name, "ms", num(p[n - 2]));
                    add(r, "kernelWL", uid, name, "adet", num(p[n - 1]));
                }
                break;
            case "wr": // uyanma nedeni, süre, adet
                if (n >= 7) {
                    String name = join(p, 4, n - 3);
                    add(r, "uyanma", uid, name, "ms", num(p[n - 2]));
                    add(r, "uyanma", uid, name, "adet", num(p[n - 1]));
                }
                break;
            case "sr": // sensör no, süre, adet
                if (n >= 7) {
                    String name = "sensör#" + p[4];
                    add(r, "sensör", uid, name, "ms", num(p[n - 2]));
                    add(r, "sensör", uid, name, "adet", num(p[n - 1]));
                }
                break;
            case "nt":
                if (n >= 8) {
                    UidStats s = stats(r, uid);
                    s.mobileRx = num(p[4]);
                    s.mobileTx = num(p[5]);
                    s.wifiRx = num(p[6]);
                    s.wifiTx = num(p[7]);
                }
                break;
            default:
                break;
        }
    }

    private static String findWlName(String[] p) {
        // ad alanı; içinde virgül olsa bile ilk sayısal alana kadar birleştir
        int end = 5;
        while (end < p.length && !isNum(p[end])) end++;
        return join(p, 4, end - 1);
    }

    private static void add(Result r, String kind, int uid, String name, String metric, long v) {
        if (v == 0) return;
        String k = kind + "\t" + uid + "\t" + name + "\t" + metric;
        Long old = r.detail.get(k);
        r.detail.put(k, old == null ? v : old + v);
    }

    private static String join(String[] p, int from, int to) {
        if (to < from) return "?";
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (i > from) sb.append(',');
            sb.append(p[i]);
        }
        String s = sb.toString();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s.replace('\t', ' ');
    }

    private static UidStats stats(Result r, int uid) {
        UidStats s = r.uids.get(uid);
        if (s == null) {
            s = new UidStats();
            r.uids.put(uid, s);
        }
        return s;
    }

    private static boolean isNum(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < '0' || c > '9') && c != '-' && c != '.') return false;
        }
        return true;
    }

    private static long num(String s) {
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

    private static double dbl(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
