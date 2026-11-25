package com.eymen.pillog;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** İndirilenler/PilLog/pil_log_YYYY-MM-DD.txt dosyasına ekleme yapar (her gün yeni dosya). */
final class LogWriter {
    static final String FOLDER = "PilLog";

    private LogWriter() {}

    static String todayFileName() {
        return "pil_log_" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".txt";
    }

    static synchronized void append(Context ctx, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        String name = todayFileName();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendQ(ctx, name, data);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(new File(dir, name), true)) {
                fos.write(data);
            }
        }
    }

    private static void appendQ(Context ctx, String name, byte[] data) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        SharedPreferences sp = ctx.getSharedPreferences("writer", Context.MODE_PRIVATE);
        String cachedName = sp.getString("name", null);
        String cachedUri = sp.getString("uri", null);

        Uri uri = null;
        if (name.equals(cachedName) && cachedUri != null) uri = Uri.parse(cachedUri);
        if (uri == null) uri = findExisting(cr, name);

        if (uri != null) {
            try (OutputStream os = cr.openOutputStream(uri, "wa")) {
                if (os != null) {
                    os.write(data);
                    sp.edit().putString("name", name).putString("uri", uri.toString()).apply();
                    return;
                }
            } catch (Exception ignored) {
                // Dosya kullanıcı tarafından silinmiş olabilir: yenisini oluştur.
            }
        }

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/");
        Uri created = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (created == null) throw new IllegalStateException("İndirilenler'de dosya oluşturulamadı");
        try (OutputStream os = cr.openOutputStream(created, "wa")) {
            if (os == null) throw new IllegalStateException("Dosya açılamadı");
            os.write(data);
        }
        sp.edit().putString("name", name).putString("uri", created.toString()).apply();
    }

    private static Uri findExisting(ContentResolver cr, String name) {
        String[] proj = {MediaStore.MediaColumns._ID};
        String sel = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        String[] args = {name, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"};
        try (Cursor c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel, args, null)) {
            if (c != null && c.moveToFirst()) {
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(c.getLong(0)));
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
