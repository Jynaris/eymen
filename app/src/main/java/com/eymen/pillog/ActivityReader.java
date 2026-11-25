package com.eymen.pillog;

import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Şu an çalışan tüm uygulama süreçleri ve servisler (dumpsys activity lru / services). DUMP izni gerekir. */
final class ActivityReader {
    // "#77: fg     TOP  LCMNFUA 12345:com.android.chrome/u0a123  act:activities|recents"
    private static final Pattern LRU = Pattern.compile("#\\d+:\\s+(.+?)\\s+(\\d+):(\\S+?)/(\\S+)(.*)");
    // "* ServiceRecord{1a2b3c u0 com.pkg/.Svc}" veya "* ServiceRecord{1a2b3c u0 com.pkg/.Svc c:com.pkg}"
    private static final Pattern SVC = Pattern.compile("\\* ServiceRecord\\{[0-9a-f]+ u(\\d+) ([^ }]+)");

    static final class Result {
        /** süreç adı/kullanıcı -> durum (pid, öncelik, ne için çalıştığı) */
        final TreeMap<String, String> procs = new TreeMap<>();
        /** servis bileşeni -> "ön plan" / "arka plan" */
        final TreeMap<String, String> services = new TreeMap<>();
        String procError, svcError;
    }

    private ActivityReader() {}

    static Result read() {
        Result r = new Result();
        try {
            Dumper.dump("activity", new String[]{"lru"}, l -> {
                Matcher m = LRU.matcher(l.trim());
                if (m.find()) {
                    String key = m.group(3) + "/" + m.group(4);
                    String state = m.group(1).replaceAll("\\s+", " ") + " pid=" + m.group(2);
                    String extra = m.group(5).trim();
                    if (!extra.isEmpty()) state += " " + extra.replaceAll("\\s+", " ");
                    r.procs.put(key, state);
                }
            });
            if (r.procs.isEmpty()) r.procError = "süreç listesi boş döndü";
        } catch (Exception e) {
            r.procError = "süreç listesi okunamadı: " + e;
        }
        try {
            final String[] cur = {null};
            Dumper.dump("activity", new String[]{"services"}, l -> {
                Matcher m = SVC.matcher(l);
                if (m.find()) {
                    cur[0] = m.group(2) + (m.group(1).equals("0") ? "" : " (kullanıcı " + m.group(1) + ")");
                    if (!r.services.containsKey(cur[0])) r.services.put(cur[0], "arka plan");
                } else if (cur[0] != null && l.contains("isForeground=true")) {
                    r.services.put(cur[0], "ÖN PLAN");
                }
            });
            if (r.services.isEmpty()) r.svcError = "servis listesi boş döndü";
        } catch (Exception e) {
            r.svcError = "servis listesi okunamadı: " + e;
        }
        return r;
    }
}
