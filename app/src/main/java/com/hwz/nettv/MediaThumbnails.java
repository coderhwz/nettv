package com.hwz.nettv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Visible cards only; images and videos have separate bounded worker pools. */
final class MediaThumbnails {
    private final ThreadPoolExecutor readers = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
    private final ThreadPoolExecutor images = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
    private final ThreadPoolExecutor videos = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
    private final ThreadPoolExecutor writers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThumbnailCache disk;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private final Map<String, Job> pending = new HashMap<>();
    MediaThumbnails(Context context) { disk = new ThumbnailCache(context); }

    void visible(SmbBrowser server, Map<ImageView, SmbBrowser.Entry> visible) {
        long start = SystemClock.elapsedRealtime();
        int memoryShown = 0, requested = 0;
        Map<String, ArrayList<ImageView>> targets = new LinkedHashMap<>();
        Map<String, SmbBrowser.Entry> entries = new HashMap<>();
        for (Map.Entry<ImageView, SmbBrowser.Entry> item : visible.entrySet()) {
            if (item.getKey().getDrawable() instanceof BitmapDrawable) continue;
            SmbBrowser.Entry entry = item.getValue();
            String id = ThumbnailCache.id(server, entry);
            entries.put(id, entry);
            ArrayList<ImageView> views = targets.get(id);
            if (views == null) { views = new ArrayList<>(); targets.put(id, views); }
            views.add(item.getKey());
        }
        for (String id : new ArrayList<>(pending.keySet())) {
            if (!targets.containsKey(id)) { pending.remove(id).cancel(); }
        }
        for (Map.Entry<String, ArrayList<ImageView>> item : targets.entrySet()) {
            String id = item.getKey(); Bitmap cached = cache.get(id);
            if (cached != null) {
                for (ImageView view : item.getValue()) {
                    if (!(view.getDrawable() instanceof BitmapDrawable) || ((BitmapDrawable)view.getDrawable()).getBitmap() != cached) {
                        view.setImageBitmap(cached); memoryShown++;
                    }
                }
                continue;
            }
            Job job = pending.get(id);
            if (job == null) {
                SmbBrowser.Entry entry = entries.get(id);
                job = new Job(id, server, entry);
                job.targets = item.getValue(); pending.put(id, job); job.start(readers, true);
                requested++;
            } else job.targets = item.getValue();
        }
        if (memoryShown > 0 || requested > 0) Perf.log("preview_batch visible=" + visible.size() + " memoryShown=" + memoryShown
                + " newJobs=" + requested + " bindMs=" + (SystemClock.elapsedRealtime()-start));
    }
    private final class Job {
        final String id;
        final SmbBrowser server;
        final SmbBrowser.Entry entry;
        final long requested = SystemClock.elapsedRealtime();
        ThreadPoolExecutor executor;
        FutureTask<Void> task;
        ArrayList<ImageView> targets;
        volatile boolean cancelled;
        Job(String id, SmbBrowser server, SmbBrowser.Entry entry) {
            this.id = id; this.server = server; this.entry = entry;
        }
        void cancel() { cancelled = true; task.cancel(true); executor.remove(task); }
        void start(ThreadPoolExecutor pool, boolean lookup) {
            executor = pool;
            long queued = SystemClock.elapsedRealtime();
            task = new FutureTask<>(() -> {
                if (cancelled) return null;
                long start = SystemClock.elapsedRealtime();
                Bitmap loaded = null;
                String error = "none";
                try { loaded = lookup ? disk.read(id) : server.thumbnail(entry, () -> cancelled); }
                catch (Exception e) { error = e.getClass().getSimpleName(); }
                Bitmap preview = loaded;
                String source = lookup ? "disk" : entry.video() ? "smb_video" : "smb_image";
                Perf.log("preview_load id=" + id.substring(0,8) + " source=" + source
                        + " queueMs=" + (start-queued) + " loadMs=" + (SystemClock.elapsedRealtime()-start)
                        + " ready=" + (preview != null) + " cancelled=" + cancelled + " error=" + error);
                main.post(() -> {
                    if (cancelled || pending.get(id) != this) { if (preview != null) preview.recycle(); return; }
                    if (lookup && preview == null) { start(entry.video() ? videos : images, false); return; }
                    pending.remove(id);
                    if (preview == null) return;
                    cache.put(id, preview);
                    for (ImageView view : targets) if (entry.url.equals(view.getTag())) view.setImageBitmap(preview);
                    Perf.log("preview_shown id=" + id.substring(0,8) + " source=" + source
                            + " totalMs=" + (SystemClock.elapsedRealtime()-requested));
                    if (!lookup) writers.execute(() -> {
                        try { disk.write(id, preview); }
                        catch (Exception e) { Perf.log("cache_write failed=" + e.getClass().getSimpleName()); }
                    });
                });
                return null;
            });
            pool.execute(task);
        }
    }
    void cancel() { for (Job job : pending.values()) job.cancel(); pending.clear(); }
    void clear() { cancel(); cache.evictAll(); writers.getQueue().clear(); }
    void close() { clear(); readers.shutdownNow(); images.shutdownNow(); videos.shutdownNow(); writers.shutdownNow(); }
}
