package com.hwz.nettv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.os.SystemClock;
import jcifs.smb.SmbRandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.util.function.BooleanSupplier;
import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import org.json.JSONObject;

final class SmbBrowser implements AutoCloseable {
    final String root, user, password, domain;
    private final CIFSContext context;
    static final class Entry {
        final String name, url; final boolean directory; final long size, modified;
        Entry(String name, String url, boolean directory, long size) {
            this(name, url, directory, size, 0);
        }
        Entry(String name, String url, boolean directory, long size, long modified) {
            this.name = name; this.url = url; this.directory = directory; this.size = size; this.modified = modified;
        }
        boolean image() { return name.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png|webp|bmp|gif|heic|heif)$"); }
        boolean video() { return name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|avi|mov|wmv|flv|ts|m2ts|mts|mpg|mpeg|m4v|webm|vob|rm|rmvb|3gp|ogv|asf|divx|iso)$"); }
    }
    SmbBrowser(JSONObject config) throws Exception {
        root = config.getString("url"); user = config.optString("user");
        password = config.optString("password"); domain = config.optString("domain");
        Properties props = new Properties();
        props.setProperty("jcifs.smb.client.minVersion", "SMB202");
        props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        props.setProperty("jcifs.smb.client.connTimeout", "8000");
        props.setProperty("jcifs.smb.client.responseTimeout", "15000");
        props.setProperty("jcifs.smb.client.soTimeout", "15000");
        CIFSContext base = new BaseContext(new PropertyConfiguration(props));
        context = user.isEmpty() ? base.withGuestCrendentials()
                : base.withCredentials(new NtlmPasswordAuthenticator(domain, user, password));
    }
    static String normalizeUrl(String input) {
        String raw = input.trim().replace('\\', '/');
        if (raw.startsWith("//")) raw = "smb:" + raw;
        if (!raw.startsWith("smb://")) raw = "smb://" + raw;
        Uri uri = Uri.parse(raw);
        if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || uri.getPath() == null
                || uri.getPath().replace("/", "").isEmpty())
            throw new IllegalArgumentException("请输入服务器和共享名，例如 smb://192.168.1.10/Movies/");
        for (String part : uri.getPathSegments()) {
            if (part.equals("..") || part.equals(".")) throw new IllegalArgumentException("共享路径不能包含 . 或 ..");
        }
        // jCIFS uses literal SMB paths, while VLC expects an encoded URI.
        String path = uri.getPath();
        return "smb://" + uri.getEncodedAuthority() + path + (path.endsWith("/") ? "" : "/");
    }
    static Uri mediaUri(String url) {
        int start = url.indexOf('/', "smb://".length());
        return new Uri.Builder().scheme("smb").encodedAuthority(url.substring(6, start))
                .path(url.substring(start)).build();
    }

    List<Entry> list(String url) throws Exception {
        List<Entry> result = new ArrayList<>();
        try (SmbFile folder = new SmbFile(url, context)) {
            for (SmbFile file : folder.listFiles()) {
                try (SmbFile child = file) {
                    boolean directory = child.isDirectory();
                    result.add(new Entry(child.getName().replaceAll("/$", ""), child.getCanonicalPath(), directory,
                            directory ? 0 : child.length(), directory ? 0 : child.lastModified()));
                }
            }
        }
        result.sort((a,b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
        return result;
    }
    Bitmap thumbnail(Entry entry) throws Exception {
        return thumbnail(entry, () -> false);
    }
    Bitmap thumbnail(Entry entry, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        if (entry.image()) return ThumbnailUtils.extractThumbnail(image(entry.url, 640, 360, true, cancelled),
                640, 360, ThumbnailUtils.OPTIONS_RECYCLE_INPUT);
        if (!entry.video()) return null;
        try (SmbFile file = new SmbFile(entry.url, context);
             SmbRandomAccessFile random = new SmbRandomAccessFile(file, "r")) {
            final long length = random.length();
            long start = SystemClock.elapsedRealtime();
            long[] io = new long[3];
            MediaDataSource source = new MediaDataSource() {
                @Override public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws java.io.IOException {
                    checkCancelled(cancelled);
                    if (size == 0) return 0;
                    if (position >= length) return -1;
                    long start = SystemClock.elapsedRealtime();
                    try {
                        random.seek(position);
                        int read = random.read(buffer, offset, size);
                        if (read > 0) io[0] += read;
                        io[1]++; return read;
                    } finally { io[2] += SystemClock.elapsedRealtime()-start; }
                }
                @Override public long getSize() { return length; }
                @Override public void close() { /* The enclosing scope owns the SMB handle. */ }
            };
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(source);
                checkCancelled(cancelled);
                String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                int frameWidth = width == null ? 640 : Integer.parseInt(width), frameHeight = height == null ? 360 : Integer.parseInt(height);
                String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if ("90".equals(rotation) || "270".equals(rotation)) { int swap = frameWidth; frameWidth = frameHeight; frameHeight = swap; }
                float scale = Math.min(1f, Math.max(640f / frameWidth, 360f / frameHeight));
                Bitmap frame = retriever.getScaledFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        Math.round(frameWidth * scale), Math.round(frameHeight * scale));
                return frame == null ? null : ThumbnailUtils.extractThumbnail(frame, 640, 360, ThumbnailUtils.OPTIONS_RECYCLE_INPUT);
            } finally {
                retriever.release();
                Perf.log("video_thumbnail bytes=" + io[0] + " reads=" + io[1] + " smbMs=" + io[2]
                        + " extractMs=" + (SystemClock.elapsedRealtime()-start));
            }
        }
    }
    Bitmap image(String url) throws Exception { return image(url, 1920, 1080, () -> false); }
    Bitmap image(String url, int width, int height, BooleanSupplier cancelled) throws Exception {
        Bitmap decoded = image(url,width,height,false,cancelled);
        if (cancelled.getAsBoolean()) { decoded.recycle(); throw new java.io.InterruptedIOException("Image cancelled"); }
        float scale = Math.min(1f,Math.min(width/(float)decoded.getWidth(),height/(float)decoded.getHeight()));
        if (scale < 1f) {
            Bitmap fitted = Bitmap.createScaledBitmap(decoded,Math.max(1,Math.round(decoded.getWidth()*scale)),
                    Math.max(1,Math.round(decoded.getHeight()*scale)),true);
            if (fitted != decoded) decoded.recycle();
            decoded = fitted;
        }
        return decoded;
    }
    private Bitmap image(String url, int width, int height, boolean crop, BooleanSupplier cancelled) throws Exception {
        long start = SystemClock.elapsedRealtime();
        long[] io = new long[3];
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        // Keep one SMB handle. Most headers fit in the marked buffer and need no second read.
        try (SmbFile file = new SmbFile(url, context);
             SmbRandomAccessFile random = new SmbRandomAccessFile(file, "r")) {
            BufferedInputStream in = imageStream(random, cancelled, io);
            in.mark(60 * 1024);
            BitmapFactory.decodeStream(in, null, options);
            long header = SystemClock.elapsedRealtime();
            checkCancelled(cancelled);
            options.inSampleSize = 1;
            float scale = crop ? Math.max(width / (float)options.outWidth, height / (float)options.outHeight)
                    : Math.min(width / (float)options.outWidth, height / (float)options.outHeight);
            while (options.inSampleSize * 2 <= 1 / scale) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            boolean headerReused = true;
            try { in.reset(); }
            catch (java.io.IOException largeHeader) { headerReused = false; random.seek(0); in = imageStream(random, cancelled, io); }
            Bitmap image = BitmapFactory.decodeStream(in, null, options);
            if (cancelled.getAsBoolean()) { if (image != null) image.recycle(); checkCancelled(cancelled); }
            if (image == null) throw new java.io.IOException("图片格式不受支持或文件损坏");
            Perf.log("image_decode preview=" + crop + " headerReused=" + headerReused + " headerMs=" + (header-start)
                    + " smbDecodeMs=" + (SystemClock.elapsedRealtime()-header)
                    + " readBytes=" + io[0] + " readCalls=" + io[1] + " readMs=" + io[2]
                    + " width=" + image.getWidth() + " height=" + image.getHeight());
            return image;
        }
    }
    private static void checkCancelled(BooleanSupplier cancelled) throws java.io.InterruptedIOException {
        if (cancelled.getAsBoolean()) throw new java.io.InterruptedIOException("Preview cancelled");
    }
    private BufferedInputStream imageStream(SmbRandomAccessFile random, BooleanSupplier cancelled, long[] io) throws java.io.IOException {
        checkCancelled(cancelled);
        // Below jCIFS' default negotiated SMB2 limit (64936), avoiding a tiny second request per fill.
        return new BufferedInputStream(new InputStream() {
            @Override public int read() throws java.io.IOException {
                byte[] single = new byte[1];
                return read(single, 0, 1) < 0 ? -1 : single[0] & 255;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                checkCancelled(cancelled); long start = SystemClock.elapsedRealtime();
                try { int read = random.read(bytes, offset, Math.min(length, 60 * 1024)); if (read > 0) io[0] += read; io[1]++; return read; }
                finally { io[2] += SystemClock.elapsedRealtime()-start; }
            }
            @Override public long skip(long count) throws java.io.IOException {
                checkCancelled(cancelled);
                long position = random.getFilePointer(), skipped = Math.max(0,Math.min(count,random.length()-position));
                random.seek(position+skipped); return skipped;
            }
        }, 60 * 1024);
    }
    @Override public void close() throws Exception { context.close(); }
}
