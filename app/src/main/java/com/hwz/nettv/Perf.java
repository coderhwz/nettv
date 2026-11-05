package com.hwz.nettv;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Timing metadata only; keep a bounded local copy for TVs with broken logcat. */
final class Perf {
    private static volatile File destination;
    private static final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), new ThreadPoolExecutor.DiscardOldestPolicy());
    static void init(Context context) { destination = new File(context.getFilesDir(), "performance.log"); }
    static void log(String message) {
        Log.i("NetTV.Perf", message);
        File file = destination;
        if (file == null) return;
        String line = System.currentTimeMillis() + " " + message + "\n";
        writer.execute(() -> {
            try {
                if (file.length() >= 256 * 1024) {
                    File previous = new File(file.getParentFile(), "performance.previous.log");
                    if (previous.exists() && !previous.delete()) return;
                    if (!file.renameTo(previous)) return;
                }
                try (FileOutputStream out = new FileOutputStream(file, true)) { out.write(line.getBytes(StandardCharsets.UTF_8)); }
            } catch (java.io.IOException e) { Log.w("NetTV.Perf", "Timing log write failed"); }
        });
    }
}
