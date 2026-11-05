package com.moment.gallery;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地相册数据源：MediaStore 查询 + 自带示例相册 + 后台解码 + 内存缓存 + 实况图识别/抽取。
 */
public final class MediaRepo {

    public static final String DEMO_ALBUM = "DEMO";
    public static final String ALL_ALBUM = "ALL";
    public static final String DEMO_PREFIX = "asset://demo/";

    private static final String[] DEMO_FILES = {"p1.jpg", "p2.jpg", "p3.jpg", "p4.jpg", "p5.jpg"};
    private static final String[] DEMO_LABELS = {"2025.06.12", "2025.06.18", "2025.07.02", "2025.07.21", "2025.08.09"};
    private static final String[] DEMO_TAGS = {"DAILY LOOK", "COFFEE TIME", "NIGHT WALK", "AT HOME", "BY THE SEA"};

    /** HDR / 广色域：为 true 时按 Display P3 解码（窗口需为 HDR 色彩模式） */
    public static volatile boolean wideGamut = false;

    public static void setWideGamut(boolean on) {
        if (wideGamut == on) return;
        wideGamut = on;
        try {
            CACHE.evictAll();
        } catch (Throwable ignored) {
        }
    }

    /** 单张图解码上限（像素），防止超大图 OOM */
    private static final long MAX_PIXELS = 8_000_000L;

    public static class Photo {
        public String uri;
        public String label;      // 播放器里显示的时间
        public String tag;        // 大图下方的 pill 文字
        public String bucketId;
        public String bucketName;
        public String displayName;
        public String mime;
        public long dateAdded;
        public long sizeBytes;
        public int width;
        public int height;
        public boolean isVideo;
        public long durationMs;

        public Photo(String uri, String label, String tag) {
            this.uri = uri;
            this.label = label;
            this.tag = tag;
        }
    }

    public static class Album {
        public String id;
        public String name;
        public int count;
        public String coverUri;

        public Album(String id, String name, int count, String coverUri) {
            this.id = id;
            this.name = name;
            this.count = count;
            this.coverUri = coverUri;
        }
    }

    /** 实况图（Motion Photo）信息 */
    public static class LiveInfo {
        public boolean isLive;
        public long videoLength;   // 尾部视频字节数
        public long videoOffset;   // 视频在原文件里的起始位置
    }

    public interface Callback {
        void onBitmap(String uri, Bitmap bitmap);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE;
    private static final Map<String, LiveInfo> LIVE_CACHE = new ConcurrentHashMap<>();

    static {
        int max = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        CACHE = new LruCache<String, Bitmap>(max / 5) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    private MediaRepo() {
    }

    // ------------------------------------------------------------------ 示例相册

    public static List<Photo> demoPhotos() {
        List<Photo> list = new ArrayList<>();
        for (int i = 0; i < DEMO_FILES.length; i++) {
            Photo p = new Photo(DEMO_PREFIX + DEMO_FILES[i], DEMO_LABELS[i], DEMO_TAGS[i]);
            p.bucketId = DEMO_ALBUM;
            p.bucketName = "示例相册";
            p.displayName = DEMO_FILES[i];
            p.mime = "image/jpeg";
            p.width = 1080;
            p.height = 1350;
            list.add(p);
        }
        return list;
    }

    // ------------------------------------------------------------------ MediaStore

    public static List<Photo> queryAll(Context ctx) {
        return query(ctx, null, null);
    }

    public static List<Photo> queryAlbum(Context ctx, String bucketId) {
        return query(ctx, MediaStore.Images.Media.BUCKET_ID + "=?", new String[]{bucketId});
    }

    private static List<Photo> query(Context ctx, String selection, String[] args) {
        List<Photo> out = new ArrayList<>();
        Uri base = MediaStore.Files.getContentUri("external");
        String[] proj = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.BUCKET_ID,
                MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.WIDTH,
                MediaStore.Files.FileColumns.HEIGHT,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MEDIA_TYPE
        };
        String typeSel = MediaStore.Files.FileColumns.MEDIA_TYPE + " IN ("
                + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + ","
                + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")";
        String fullSel = selection == null ? typeSel : "(" + selection + ") AND " + typeSel;

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy.MM.dd", Locale.getDefault());
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(base, proj, fullSel, args,
                    MediaStore.Files.FileColumns.DATE_ADDED + " DESC");
            if (c == null) return out;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                int mediaType = c.getInt(9);
                Photo p = new Photo(ContentUris.withAppendedId(base, id).toString(),
                        fmt.format(new Date(c.getLong(3) * 1000L)), "DAILY LOOK");
                p.bucketId = c.getString(1);
                p.bucketName = c.getString(2) == null ? "相册" : c.getString(2);
                p.dateAdded = c.getLong(3);
                p.sizeBytes = c.getLong(4);
                p.mime = c.getString(5);
                p.width = c.getInt(6);
                p.height = c.getInt(7);
                p.displayName = c.getString(8);
                p.isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                if (p.isVideo) {
                    p.durationMs = durationOf(ctx, p.uri);
                }
                out.add(p);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    private static long durationOf(Context ctx, String uri) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, Uri.parse(uri));
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : Long.parseLong(d);
        } catch (Throwable t) {
            return 0;
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** mm:ss 格式的时长 */
    public static String durationText(long ms) {
        if (ms <= 0) return "";
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s);
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }

    /** 按相册分组，最近有照片的相册排前面。 */
    public static List<Album> queryAlbums(Context ctx) {
        Map<String, Album> map = new LinkedHashMap<>();
        for (Photo p : queryAll(ctx)) {
            Album a = map.get(p.bucketId);
            if (a == null) {
                a = new Album(p.bucketId, p.bucketName, 0, p.uri);
                map.put(p.bucketId, a);
            }
            a.count++;
        }
        return new ArrayList<>(map.values());
    }

    // ------------------------------------------------------------------ 解码

    /** 按 Photo 加载（视频走系统缩略图，图片走解码）。 */
    public static void load(final Context ctx, final Photo p, final int reqW, final int reqH,
                            final Callback cb) {
        if (p != null && p.isVideo) {
            loadVideoThumb(ctx, p, reqW, reqH, cb);
            return;
        }
        load(ctx, p == null ? null : p.uri, reqW, reqH, cb);
    }

    /** 视频封面：优先系统缩略图，退化到 MediaMetadataRetriever */
    public static void loadVideoThumb(final Context ctx, final Photo p, final int reqW, final int reqH,
                                      final Callback cb) {
        final String key = p.uri + "@v" + reqW + "x" + reqH;
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            cb.onBitmap(p.uri, hit);
            return;
        }
        final Context app = ctx.getApplicationContext();
        POOL.execute(() -> {
            Bitmap bm = null;
            try {
                long id = ContentUris.parseId(Uri.parse(p.uri));
                bm = MediaStore.Video.Thumbnails.getThumbnail(app.getContentResolver(), id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null);
                if (bm == null) {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    mmr.setDataSource(app, Uri.parse(p.uri));
                    bm = mmr.getFrameAtTime(0);
                    mmr.release();
                }
            } catch (Throwable ignored) {
            }
            if (bm != null) CACHE.put(key, bm);
            final Bitmap result = bm;
            MAIN.post(() -> cb.onBitmap(p.uri, result));
        });
    }

    public static void prefetch(final Context ctx, final Photo p, final int reqW, final int reqH) {
        if (p == null) return;
        if (p.isVideo) {
            loadVideoThumb(ctx, p, reqW, reqH, (u, b) -> {
            });
            return;
        }
        prefetch(ctx, p.uri, reqW, reqH);
    }

    /** 取缩略图/大图，命中缓存直接回调（主线程），否则后台解码。 */
    public static void load(final Context ctx, final String uri, final int reqW, final int reqH,
                            final Callback cb) {
        final String key = uri + "@" + reqW + "x" + reqH;
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            cb.onBitmap(uri, hit);
            return;
        }
        final Context app = ctx.getApplicationContext();
        POOL.execute(() -> {
            Bitmap bm = null;
            try {
                bm = decode(app, uri, reqW, reqH, true);
            } catch (Throwable ignored) {
            }
            if (bm != null) CACHE.put(key, bm);
            final Bitmap result = bm;
            MAIN.post(() -> cb.onBitmap(uri, result));
        });
    }

    /** 原图画质（放大用）：尽量解到接近原始分辨率，但不超过 MAX_PIXELS。 */
    public static void loadHiRes(final Context ctx, final String uri, final int reqW, final int reqH,
                                 final Callback cb) {
        load(ctx, uri, reqW, reqH, cb);
    }

    /** 预热（不回调），用于提前解码下一张。 */
    public static void prefetch(final Context ctx, final String uri, final int reqW, final int reqH) {
        final String key = uri + "@" + reqW + "x" + reqH;
        if (CACHE.get(key) != null) return;
        final Context app = ctx.getApplicationContext();
        POOL.execute(() -> {
            try {
                Bitmap bm = decode(app, uri, reqW, reqH, true);
                if (bm != null) CACHE.put(key, bm);
            } catch (Throwable ignored) {
            }
        });
    }

    private static InputStream open(Context ctx, String uri) throws Exception {
        if (uri.startsWith(DEMO_PREFIX)) {
            return ctx.getAssets().open("demo/" + uri.substring(DEMO_PREFIX.length()));
        }
        return ctx.getContentResolver().openInputStream(Uri.parse(uri));
    }

    private static final long MAX_IN_MEMORY = 16L * 1024 * 1024;

    private static Bitmap decode(Context ctx, String uri, int reqW, int reqH, boolean rotate)
            throws Exception {
        // 大图（过场/主图）一次读进内存再解析，避免对 content:// 反复 open（快 2~3 倍）；
        // 缩略图很小，走流式解码更省内存
        byte[] data = null;
        if (reqW >= 200) {
            InputStream raw = open(ctx, uri);
            try {
                data = readAll(raw, MAX_IN_MEMORY);
            } finally {
                close(raw);
            }
        }

        int outW, outH;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        if (data != null) {
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        } else {
            InputStream in = open(ctx, uri);
            BitmapFactory.decodeStream(in, null, bounds);
            close(in);
        }
        outW = bounds.outWidth;
        outH = bounds.outHeight;
        if (outW <= 0 || outH <= 0) return null;

        long pixels = (long) outW * outH;
        int sample = 1;
        while (outW / (sample * 2) >= reqW && outH / (sample * 2) >= reqH) {
            sample *= 2;
        }
        while (pixels / ((long) sample * sample) > MAX_PIXELS) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = reqW <= 200 ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
        if (wideGamut && Build.VERSION.SDK_INT >= 26) {
            try {
                opts.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3);
            } catch (Throwable ignored) {
            }
        }
        Bitmap bm;
        if (data != null) {
            bm = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } else {
            InputStream in = open(ctx, uri);
            bm = BitmapFactory.decodeStream(in, null, opts);
            close(in);
        }
        if (bm == null) return null;

        if (rotate) {
            int degrees = data != null ? exifDegrees(data) : exifDegrees(ctx, uri);
            if (degrees != 0) {
                Matrix m = new Matrix();
                m.postRotate(degrees);
                Bitmap rotated = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
                if (rotated != bm) bm.recycle();
                bm = rotated;
            }
        }
        // 首次显示只解到 1.4 倍卡片尺寸就够清晰；再放大时会有单独的「高清」二次加载
        int maxW = Math.max((int) (reqW * 1.4f), 720);
        int maxH = Math.max((int) (reqH * 1.4f), 720);
        if (bm.getWidth() > maxW || bm.getHeight() > maxH) {
            float sc = Math.min(maxW / (float) bm.getWidth(), maxH / (float) bm.getHeight());
            if (sc < 1f) {
                Bitmap small = Bitmap.createScaledBitmap(bm,
                        Math.max(1, (int) (bm.getWidth() * sc)),
                        Math.max(1, (int) (bm.getHeight() * sc)), true);
                if (small != bm) bm.recycle();
                bm = small;
            }
        }
        return bm;
    }

    private static byte[] readAll(InputStream in, long cap) {
        if (in == null) return null;
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(256 * 1024);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > cap) {
                    bos = null;
                    return null;
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int exifDegrees(byte[] data) {
        try {
            ExifInterface exif = new ExifInterface(new java.io.ByteArrayInputStream(data));
            return degreesOf(exif);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int degreesOf(ExifInterface exif) {
        int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        switch (o) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return 270;
            default:
                return 0;
        }
    }

    private static int exifDegrees(Context ctx, String uri) {
        InputStream in = null;
        try {
            in = open(ctx, uri);
            ExifInterface exif = new ExifInterface(in);
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Throwable t) {
            return 0;
        } finally {
            close(in);
        }
    }

    private static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 实况图（Motion Photo）

    private static final Pattern P_LENGTH =
            Pattern.compile("Item:Length\\s*=\\s*\"(\\d+)\"[^>]*Item:Semantic\\s*=\\s*\"MotionPhoto\"");
    private static final Pattern P_SEMANTIC_FIRST =
            Pattern.compile("Item:Semantic\\s*=\\s*\"MotionPhoto\"[^>]*Item:Length\\s*=\\s*\"(\\d+)\"");
    private static final Pattern P_MICRO_OFFSET =
            Pattern.compile("(?:GCamera:)?MicroVideoOffset\\s*=\\s*\"(\\d+)\"");
    private static final Pattern P_MICRO_FLAG =
            Pattern.compile("(?:GCamera:)?MicroVideo\\s*=\\s*\"1\"");

    /** 识别是否是实况图（Google Motion Photo / MicroVideo），结果带缓存。 */
    public static LiveInfo detectLive(Context ctx, Photo p) {
        LiveInfo cached = LIVE_CACHE.get(p.uri);
        if (cached != null) return cached;
        LiveInfo info = new LiveInfo();
        InputStream in = null;
        try {
            String xmp = null;
            in = open(ctx, p.uri);
            ExifInterface exif = new ExifInterface(in);
            xmp = exif.getAttribute(ExifInterface.TAG_XMP);
            long fileSize = sizeOf(ctx, p);
            if (xmp != null && fileSize > 0) {
                long len = -1;
                Matcher m1 = P_LENGTH.matcher(xmp);
                Matcher m2 = P_SEMANTIC_FIRST.matcher(xmp);
                Matcher m3 = P_MICRO_OFFSET.matcher(xmp);
                if (m1.find()) {
                    len = Long.parseLong(m1.group(1));
                } else if (m2.find()) {
                    len = Long.parseLong(m2.group(1));
                } else if (m3.find()) {
                    len = Long.parseLong(m3.group(1));
                } else if (P_MICRO_FLAG.matcher(xmp).find() && xmp.contains("MotionPhoto")) {
                    len = 0;
                }
                if (len > 0 && len < fileSize) {
                    info.isLive = true;
                    info.videoLength = len;
                    info.videoOffset = fileSize - len;
                }
            }
            if (!info.isLive && fileSize > 0) {
                // 兜底：直接在文件尾部找 MP4 的 ftyp box（适配部分厂商私有封装）
                long start = findMp4Start(ctx, p.uri, fileSize);
                if (start > 0 && fileSize - start > 64 * 1024) {
                    info.isLive = true;
                    info.videoOffset = start;
                    info.videoLength = fileSize - start;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            close(in);
        }
        LIVE_CACHE.put(p.uri, info);
        return info;
    }

    /**
     * 把实况图尾部的视频抽到缓存文件；第二次直接复用缓存。
     * 抽不出来（格式不认识）返回 null。
     */
    public static File extractLiveVideo(Context ctx, Photo p, LiveInfo info) throws Exception {
        String name = "live_" + Math.abs((p.uri + info.videoLength).hashCode()) + ".mp4";
        File out = new File(ctx.getCacheDir(), name);
        if (out.exists() && out.length() > 1024) return out;

        InputStream in = null;
        FileOutputStream fos = null;
        try {
            in = open(ctx, p.uri);
            skipFully(in, info.videoOffset);
            fos = new FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            long left = info.videoLength;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) break;
                fos.write(buf, 0, n);
                left -= n;
            }
            fos.flush();
        } finally {
            closeQuietly(fos);
            close(in);
        }
        return out.length() > 1024 ? out : null;
    }

    /** 在文件尾部一段数据里找 'ftyp'，推算内嵌 MP4 的起始位置；找不到返回 -1。 */
    private static long findMp4Start(Context ctx, String uri, long fileSize) {
        if (fileSize > 60L * 1024 * 1024) return -1;   // 太大就别扫了，省流量和电
        final int tailMax = 2 * 1024 * 1024;
        int tail = (int) Math.min(fileSize, tailMax);
        InputStream in = null;
        try {
            in = open(ctx, uri);
            if (fileSize > tail) skipFully(in, fileSize - tail);
            byte[] buf = new byte[tail];
            int off = 0;
            while (off < tail) {
                int n = in.read(buf, off, tail - off);
                if (n <= 0) break;
                off += n;
            }
            for (int i = 4; i + 4 <= off; i++) {
                if (buf[i] == 'f' && buf[i + 1] == 't' && buf[i + 2] == 'y' && buf[i + 3] == 'p') {
                    int boxStart = i - 4;
                    long boxSize = ((long) (buf[boxStart] & 0xFF) << 24)
                            | ((buf[boxStart + 1] & 0xFF) << 16)
                            | ((buf[boxStart + 2] & 0xFF) << 8)
                            | (buf[boxStart + 3] & 0xFF);
                    if (boxSize >= 8 && boxStart + boxSize <= off) {
                        return fileSize - off + boxStart;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            close(in);
        }
        return -1;
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = bytes;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped > 0) {
                left -= skipped;
            } else {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("unexpected end of file");
                left -= n;
            }
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static long sizeOf(Context ctx, Photo p) {
        if (p.sizeBytes > 0) return p.sizeBytes;
        if (p.uri.startsWith(DEMO_PREFIX)) {
            try {
                return ctx.getAssets().openFd("demo/" + p.uri.substring(DEMO_PREFIX.length())).getLength();
            } catch (Throwable t) {
                return 0;
            }
        }
        try (android.content.res.AssetFileDescriptor afd =
                     ctx.getContentResolver().openAssetFileDescriptor(Uri.parse(p.uri), "r")) {
            return afd == null ? 0 : afd.getLength();
        } catch (Throwable t) {
            return 0;
        }
    }
}
