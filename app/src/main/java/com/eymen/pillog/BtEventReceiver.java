package com.eymen.pillog;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Bluetooth bağlanma/kopma olaylarını o anda (saniyesiyle) log'a yazar.
 * Sadece olay olduğunda sistem tarafından kısa süreliğine çalıştırılır; sürekli çalışmaz.
 */
public class BtEventReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        final String line = format(intent);
        new Thread(() -> {
            try {
                LogWriter.append(app, line);
            } catch (Throwable ignored) {
            } finally {
                pr.finish();
            }
        }, "pillog-bt").start();
    }

    @SuppressWarnings("deprecation")
    private static String format(Intent intent) {
        String a = intent.getAction() == null ? "?" : intent.getAction();
        StringBuilder sb = new StringBuilder(160);
        sb.append("[BT ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())).append("] ");
        String what;
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) what = "BAĞLANDI (ACL)";
        else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) what = "!! BAĞLANTI KOPTU (ACL)";
        else if (BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED.equals(a)) what = "kopma istendi";
        else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(a)) what = "eşleşme durumu değişti";
        else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) what = "Bluetooth adaptörü durumu";
        else if (a.endsWith("BATTERY_LEVEL_CHANGED")) what = "cihaz pil seviyesi değişti";
        else if (a.endsWith("CONNECTION_STATE_CHANGED")) what = "profil bağlantısı (" + shortAction(a) + ")";
        else if (a.endsWith("AUDIO_STATE_CHANGED")) what = "ses bağlantısı (" + shortAction(a) + ")";
        else what = shortAction(a);
        sb.append(what);

        Bundle ex = intent.getExtras();
        if (ex != null) {
            for (String k : ex.keySet()) {
                Object v;
                try { v = ex.get(k); } catch (Throwable t) { v = "?"; }
                String key = k.substring(k.lastIndexOf('.') + 1);
                sb.append(" | ").append(key).append('=');
                if (v instanceof BluetoothDevice) {
                    sb.append(BluetoothReader.describe((BluetoothDevice) v));
                } else if (v instanceof Integer && key.contains("STATE")) {
                    int s = (Integer) v;
                    sb.append(a.equals(BluetoothAdapter.ACTION_STATE_CHANGED) ? BluetoothReader.stateName(s)
                            : key.contains("BOND") ? bondName(s) : BluetoothReader.connStateName(s));
                } else {
                    sb.append(v);
                }
            }
        }
        return sb.append('\n').toString();
    }

    private static String shortAction(String a) {
        String[] p = a.split("\\.");
        return p.length >= 2 ? p[p.length - 2] + "." + p[p.length - 1] : a;
    }

    private static String bondName(int s) {
        switch (s) {
            case BluetoothDevice.BOND_NONE: return "eşleşmesiz";
            case BluetoothDevice.BOND_BONDING: return "eşleşiyor";
            case BluetoothDevice.BOND_BONDED: return "eşleşmiş";
            default: return String.valueOf(s);
        }
    }
}
