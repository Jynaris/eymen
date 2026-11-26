package com.eymen.pillog;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Bluetooth: eşleşmiş/bağlı cihazlar ve Bluetooth yığınının kendi olay/hata geçmişi. */
final class BluetoothReader {

    private BluetoothReader() {}

    static boolean hasConnectPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return ctx.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("MissingPermission")
    static void appendStatus(Context ctx, StringBuilder sb) {
        sb.append("\n--- BLUETOOTH: CİHAZLAR ---\n");
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) {
            sb.append("  Bluetooth donanımı yok\n");
            return;
        }
        if (!hasConnectPermission(ctx)) {
            sb.append("  [Yakındaki cihazlar (BLUETOOTH_CONNECT) izni yok: adb shell pm grant ")
                    .append(ctx.getPackageName()).append(" android.permission.BLUETOOTH_CONNECT]\n");
            return;
        }
        try {
            sb.append("  Adaptör: ").append(stateName(ad.getState()));
            if (ad.isEnabled()) sb.append(" | ad: ").append(ad.getName());
            sb.append('\n');
            if (!ad.isEnabled()) return;

            // Profil bazında bağlı cihazlar
            Map<String, List<String>> profilesByAddr = new HashMap<>();
            int[] profiles = {BluetoothProfile.HEADSET, BluetoothProfile.A2DP, BluetoothProfile.HEARING_AID,
                    22 /* LE_AUDIO */, 4 /* HID_HOST */, 5 /* PAN */, 19 /* HID_DEVICE */};
            for (int p : profiles) {
                for (BluetoothDevice d : connectedOnProfile(ctx, ad, p)) {
                    List<String> l = profilesByAddr.get(d.getAddress());
                    if (l == null) { l = new ArrayList<>(); profilesByAddr.put(d.getAddress(), l); }
                    l.add(profileName(p));
                }
            }
            try {
                for (BluetoothDevice d : bm.getConnectedDevices(BluetoothProfile.GATT)) {
                    List<String> l = profilesByAddr.get(d.getAddress());
                    if (l == null) { l = new ArrayList<>(); profilesByAddr.put(d.getAddress(), l); }
                    l.add("GATT/BLE");
                }
            } catch (Exception ignored) {
            }

            long now = System.currentTimeMillis();
            android.content.SharedPreferences bsp = ctx.getSharedPreferences("bt_batt", Context.MODE_PRIVATE);
            Set<BluetoothDevice> bonded = ad.getBondedDevices();
            Set<String> listed = new HashSet<>();
            int connected = 0;
            StringBuilder rows = new StringBuilder();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    listed.add(d.getAddress());
                    Boolean acl = isConnected(d);
                    List<String> prof = profilesByAddr.get(d.getAddress());
                    boolean on = (acl != null && acl) || prof != null;
                    if (on) connected++;
                    rows.append("  ").append(on ? "[BAĞLI]    " : "[bağlı değil]").append(' ').append(describe(d));
                    if (prof != null) rows.append(" | profiller: ").append(String.join(",", prof));
                    int batt = battery(d);
                    if (batt >= 0) {
                        rows.append(" | pil: %").append(batt);
                        String pv = bsp.getString(d.getAddress(), null);
                        if (pv != null) {
                            try {
                                String[] x = pv.split(",");
                                int pb = Integer.parseInt(x[0]);
                                long pt = Long.parseLong(x[1]);
                                double hours = (now - pt) / 3600_000.0;
                                if (hours > 0.01) {
                                    rows.append(String.format(java.util.Locale.US, " (önceki kayda göre %+d puan, %.1f %%/saat)",
                                            batt - pb, (pb - batt) / hours));
                                }
                            } catch (Exception ignored) { }
                        }
                        bsp.edit().putString(d.getAddress(), batt + "," + now).apply();
                    }
                    rows.append('\n');
                }
            }
            // Eşleşmemiş ama bağlı (ör. BLE)
            for (Map.Entry<String, List<String>> en : profilesByAddr.entrySet()) {
                if (listed.contains(en.getKey())) continue;
                connected++;
                BluetoothDevice d = ad.getRemoteDevice(en.getKey());
                rows.append("  [BAĞLI]     ").append(describe(d)).append(" (eşleşmemiş) | profiller: ")
                        .append(String.join(",", en.getValue())).append('\n');
            }
            sb.append("  Eşleşmiş: ").append(bonded == null ? 0 : bonded.size()).append(" | şu an bağlı: ").append(connected).append('\n');
            sb.append(rows);
        } catch (SecurityException e) {
            sb.append("  [izin hatası: ").append(e.getMessage()).append("]\n");
        }
    }

    static void appendStackEvents(Context ctx, StringBuilder sb) {
        DumpDiff.append(ctx, sb, "BLUETOOTH: YIĞIN OLAYLARI / HATALAR / KOPMALAR", "bluetooth_manager", new String[0], "bt_seen.txt");
    }

    /** Şu an ses çalan oynatıcılar (hangi uygulama, ne tür ses) — Bluetooth kulaklığın neden şarj harcadığını gösterir. */
    static void appendAudioNow(Context ctx, StringBuilder sb, PackageManager pm) {
        sb.append("\n--- SES: ŞU AN ÇALAN / AKTİF (audio) ---\n");
        final List<String> active = new ArrayList<>();
        final List<String> routes = new ArrayList<>();
        try {
            Dumper.dump("audio", new String[0], l -> {
                String t = l.trim();
                if (t.startsWith("AudioPlaybackConfiguration") && t.contains("state:started")) active.add(t);
                else if (t.startsWith("AudioRecordingConfiguration")) active.add("[KAYIT] " + t);
                else if ((t.startsWith("mode:") || t.startsWith("Audio mode:") || t.startsWith("Ringer mode:")
                        || t.contains("BT SCO") || t.startsWith("A2DP") || t.startsWith("mBluetoothA2dpEnabled")
                        || t.startsWith("Communication route") || t.startsWith("Computed Communication Device")
                        || t.startsWith("mBluetoothName")) && routes.size() < 20) routes.add(t);
            });
        } catch (Exception e) {
            sb.append("  [okunamadı: ").append(e).append("]\n");
            return;
        }
        for (String r : routes) sb.append("  ").append(r).append('\n');
        if (active.isEmpty()) sb.append("  (şu an ses çalan uygulama yok)\n");
        java.util.regex.Matcher m;
        java.util.regex.Pattern up = java.util.regex.Pattern.compile("u/pid:(\\d+)/");
        for (String a : active) {
            m = up.matcher(a);
            String who = "";
            if (m.find()) {
                try {
                    String[] pk = pm.getPackagesForUid(Integer.parseInt(m.group(1)));
                    if (pk != null && pk.length > 0) who = " <= " + pk[0];
                } catch (Exception ignored) { }
            }
            sb.append("  ÇALIYOR: ").append(a).append(who).append('\n');
        }
    }

    // ---------------------------------------------------------------

    @SuppressWarnings("MissingPermission")
    static String describe(BluetoothDevice d) {
        if (d == null) return "?";
        StringBuilder s = new StringBuilder();
        String name = null;
        try { name = d.getName(); } catch (Exception ignored) { }
        s.append(name == null ? "(adsız)" : name).append(" (").append(d.getAddress()).append(")");
        try {
            BluetoothClass c = d.getBluetoothClass();
            if (c != null) s.append(" ").append(className(c.getMajorDeviceClass()));
            int type = d.getType();
            s.append(type == BluetoothDevice.DEVICE_TYPE_LE ? " BLE" : type == BluetoothDevice.DEVICE_TYPE_DUAL ? " Klasik+BLE"
                    : type == BluetoothDevice.DEVICE_TYPE_CLASSIC ? " Klasik" : "");
        } catch (Exception ignored) {
        }
        return s.toString();
    }

    private static Boolean isConnected(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("isConnected");
            return (Boolean) m.invoke(d);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int battery(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("getBatteryLevel");
            Object v = m.invoke(d);
            return v instanceof Integer ? (Integer) v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    @SuppressWarnings("MissingPermission")
    private static List<BluetoothDevice> connectedOnProfile(Context ctx, BluetoothAdapter ad, int profile) {
        final List<BluetoothDevice> out = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final BluetoothProfile[] holder = new BluetoothProfile[1];
        boolean ok;
        try {
            ok = ad.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int p, BluetoothProfile proxy) {
                    holder[0] = proxy;
                    latch.countDown();
                }

                @Override
                public void onServiceDisconnected(int p) {
                }
            }, profile);
        } catch (Throwable t) {
            return out;
        }
        if (!ok) return out;
        try {
            if (latch.await(2, TimeUnit.SECONDS) && holder[0] != null) {
                out.addAll(holder[0].getConnectedDevices());
            }
        } catch (Throwable ignored) {
        } finally {
            if (holder[0] != null) {
                try { ad.closeProfileProxy(profile, holder[0]); } catch (Throwable ignored) { }
            }
        }
        return out;
    }




    static String stateName(int s) {
        switch (s) {
            case BluetoothAdapter.STATE_OFF: return "KAPALI";
            case BluetoothAdapter.STATE_TURNING_ON: return "açılıyor";
            case BluetoothAdapter.STATE_ON: return "AÇIK";
            case BluetoothAdapter.STATE_TURNING_OFF: return "kapanıyor";
            default: return "durum#" + s;
        }
    }

    static String connStateName(int s) {
        switch (s) {
            case BluetoothProfile.STATE_DISCONNECTED: return "KOPUK";
            case BluetoothProfile.STATE_CONNECTING: return "bağlanıyor";
            case BluetoothProfile.STATE_CONNECTED: return "BAĞLI";
            case BluetoothProfile.STATE_DISCONNECTING: return "kopuyor";
            default: return "durum#" + s;
        }
    }

    private static String profileName(int p) {
        switch (p) {
            case BluetoothProfile.HEADSET: return "HFP(çağrı)";
            case BluetoothProfile.A2DP: return "A2DP(müzik)";
            case BluetoothProfile.HEARING_AID: return "İşitme cihazı";
            case 22: return "LE Audio";
            case 4: return "HID(klavye/fare)";
            case 5: return "PAN(internet)";
            case 19: return "HID cihaz";
            default: return "profil#" + p;
        }
    }

    private static String className(int major) {
        switch (major) {
            case BluetoothClass.Device.Major.AUDIO_VIDEO: return "[ses/görüntü]";
            case BluetoothClass.Device.Major.COMPUTER: return "[bilgisayar]";
            case BluetoothClass.Device.Major.PHONE: return "[telefon]";
            case BluetoothClass.Device.Major.PERIPHERAL: return "[çevre birimi]";
            case BluetoothClass.Device.Major.WEARABLE: return "[giyilebilir]";
            case BluetoothClass.Device.Major.HEALTH: return "[sağlık]";
            case BluetoothClass.Device.Major.NETWORKING: return "[ağ]";
            case BluetoothClass.Device.Major.IMAGING: return "[görüntüleme]";
            case BluetoothClass.Device.Major.TOY: return "[oyuncak]";
            default: return "";
        }
    }
}
