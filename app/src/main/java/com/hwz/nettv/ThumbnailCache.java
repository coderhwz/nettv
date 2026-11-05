package com.hwz.nettv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Only encrypted previews reach disk; decoding and compression stay on worker threads. */
final class ThumbnailCache {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private final File directory;
    private SecretKey key;
    ThumbnailCache(Context context) { directory = new File(context.getCacheDir(), "thumbnails"); }

    static String id(SmbBrowser server, SmbBrowser.Entry entry) {
        try {
            String value = "v1\0" + server.root + "\0" + server.domain + "\0" + server.user + "\0"
                    + entry.url + "\0" + entry.size + "\0" + entry.modified;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private synchronized SecretKey key() throws Exception {
        if (key == null) key = Vault.key("nettv-thumbnails");
        return key;
    }
    private static byte[] finish(Cipher cipher, byte[] bytes, int offset, int length) throws Exception {
        // Some Android 9 TV Keymaster implementations corrupt large single-call GCM operations.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (length > 4096) {
            byte[] block = cipher.update(bytes, offset, 4096);
            if (block != null) out.write(block);
            offset += 4096; length -= 4096;
        }
        out.write(cipher.doFinal(bytes, offset, length));
        return out.toByteArray();
    }
    Bitmap read(String id) {
        File file = new File(directory, id);
        long start = SystemClock.elapsedRealtime();
        byte[] sealed;
        try {
            synchronized (this) {
                if (!file.isFile()) return null;
                sealed = new AtomicFile(file).readFully();
                file.setLastModified(System.currentTimeMillis());
            }
            long read = SystemClock.elapsedRealtime();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, sealed, 0, 12));
            cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            byte[] plain = finish(cipher, sealed, 12, sealed.length - 12);
            long decrypted = SystemClock.elapsedRealtime();
            Bitmap bitmap = BitmapFactory.decodeByteArray(plain, 0, plain.length);
            if (bitmap == null) throw new java.io.IOException("Invalid preview");
            Perf.log("cache_read id=" + id.substring(0, Math.min(8, id.length()))
                    + " bytes=" + sealed.length + " diskMs=" + (read-start) + " decryptMs=" + (decrypted-read)
                    + " decodeMs=" + (SystemClock.elapsedRealtime()-decrypted));
            return bitmap;
        } catch (Exception e) {
            synchronized (this) { new AtomicFile(file).delete(); }
            Perf.log("cache_read failed=" + e.getClass().getSimpleName() + " totalMs=" + (SystemClock.elapsedRealtime()-start));
            return null;
        }
    }
    void write(String id, Bitmap bitmap) throws Exception {
        long start = SystemClock.elapsedRealtime();
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, plain);
        long encoded = SystemClock.elapsedRealtime();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
        byte[] encodedBytes = plain.toByteArray();
        byte[] sealed = finish(cipher, encodedBytes, 0, encodedBytes.length);
        long encrypted = SystemClock.elapsedRealtime();
        synchronized (this) {
            directory.mkdirs();
            AtomicFile file = new AtomicFile(new File(directory, id));
            FileOutputStream out = null;
            try {
                out = file.startWrite(); out.write(cipher.getIV()); out.write(sealed); file.finishWrite(out);
            } catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
            File[] files = directory.listFiles();
            if (files == null) return;
            long bytes = 0; for (File item : files) bytes += item.length();
            if (bytes > MAX_BYTES) {
                Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                for (File item : files) {
                    long size = item.length(); if (item.delete()) bytes -= size;
                    if (bytes <= MAX_BYTES) break;
                }
            }
        }
        Perf.log("cache_write id=" + id.substring(0, Math.min(8, id.length()))
                + " bytes=" + sealed.length + " encodeMs=" + (encoded-start) + " encryptMs=" + (encrypted-encoded)
                + " commitMs=" + (SystemClock.elapsedRealtime()-encrypted));
    }
}
