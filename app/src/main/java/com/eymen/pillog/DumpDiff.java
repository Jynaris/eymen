package com.eymen.pillog;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bir sistem servisinin dump'ındaki zaman damgalı satırlardan, önceki kayıtta görülmeyenleri yazar.
 * Servislerin kendi olay geçmişlerini (bağlanma, kopma, hata, ses çalma vb.) kaçırmadan toplamak için.
 */
final class DumpDiff {
    private static final Pattern TIME = Pattern.compile("\\d{1,2}:\\d{2}:\\d{2}");
    private static final String[] IMPORTANT = {"DISCONNECT", "ERROR", "FAIL", "TIMEOUT", "CRASH", "LOSS", "ABORT",
            "REASON", "UNEXPECTED", "DIED", "DROP"};

    private DumpDiff() {}

    static void append(Context ctx, StringBuilder sb, String title, String service, String[] args, String seenFile) {
        sb.append("\n--- ").append(title).append(" (").append(service).append(", son kayıttan beri yeni satırlar) ---\n");
        Set<Long> prev = load(ctx, seenFile);
        boolean first = prev.isEmpty();
        Set<Long> cur = new HashSet<>();
        List<String> fresh = new ArrayList<>();
        try {
            Dumper.dump(service, args, l -> {
                if (l.length() > 600 || l.length() < 8) return;
                if (!TIME.matcher(l).find()) return;
                String t = l.trim();
                long h = hash(t);
                if (!cur.add(h)) return;
                if (!prev.contains(h)) fresh.add(t);
            });
        } catch (Exception e) {
            sb.append("  [okunamadı: ").append(e).append("]\n");
            return;
        }
        save(ctx, seenFile, cur);
        if (first) sb.append("  (İlk okuma: servisin bellekteki geçmişinin tamamı)\n");
        if (fresh.isEmpty()) {
            sb.append("  (yeni olay yok)\n");
            return;
        }
        for (String f : fresh) {
            String up = f.toUpperCase(Locale.ROOT);
            boolean imp = false;
            for (String k : IMPORTANT) if (up.contains(k)) { imp = true; break; }
            sb.append(imp ? "  !! " : "     ").append(f).append('\n');
        }
    }

    private static long hash(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return h;
    }

    private static Set<Long> load(Context ctx, String file) {
        Set<Long> s = new HashSet<>();
        File f = new File(ctx.getFilesDir(), file);
        if (!f.exists()) return s;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.US_ASCII))) {
            String l;
            while ((l = br.readLine()) != null) {
                try { s.add(Long.parseLong(l)); } catch (NumberFormatException ignored) { }
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    private static void save(Context ctx, String file, Set<Long> s) {
        StringBuilder sb = new StringBuilder(s.size() * 21);
        for (Long x : s) sb.append(x).append('\n');
        try (FileOutputStream fos = new FileOutputStream(new File(ctx.getFilesDir(), file))) {
            fos.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ignored) {
        }
    }
}
