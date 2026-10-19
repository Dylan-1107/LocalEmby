package com.local.embylite;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 视频缩略图：抽帧 → 缩放到宽 320 → JPEG，落缓存目录。 */
public class ThumbProvider {

    private static final int TARGET_WIDTH = 320;
    private static final long MAX_SEEK_MS = 8000;

    /** 防重入：同一 item 只有一个线程在生成。 */
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<String, Object>();

    private ThumbProvider() {
    }

    /** 返回 JPEG 字节；任何异常返回 null（调用方 404）。 */
    public static byte[] get(Context ctx, Item item) {
        if (item == null || item.id == null) return null;
        File dir = new File(ctx.getCacheDir(), "thumbs");
        File f = new File(dir, item.id + ".jpg");
        if (f.exists() && f.length() > 0) return readFile(f);

        Object lock = LOCKS.get(item.id);
        if (lock == null) {
            lock = new Object();
            Object prev = LOCKS.putIfAbsent(item.id, lock);
            if (prev != null) lock = prev;
        }
        synchronized (lock) {
            if (f.exists() && f.length() > 0) return readFile(f);
            return generate(ctx, item, f);
        }
    }

    /** 无缩略图时的占位图（深底 + 标题文字），用于库封面或失效条目。 */
    public static byte[] placeholder(Context ctx, String title) {
        try {
            int w = 320, h = 480;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            c.drawColor(0xFF1E1E28);
            android.graphics.Paint p = new android.graphics.Paint();
            p.setColor(0xFF5B6BFF);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(6);
            c.drawRect(8, 8, w - 8, h - 8, p);
            p.setColor(0xFFAAAAAA);
            p.setTextSize(30);
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            String t = (title == null || title.isEmpty()) ? "本地影院" : title;
            if (t.length() > 12) t = t.substring(0, 12);
            p.setAntiAlias(true);
            c.drawText(t, w / 2f, h / 2f, p);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            bmp.recycle();
            return baos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Context appCtx;

    private static byte[] generate(Context ctx, Item item, File cacheFile) {
        appCtx = ctx.getApplicationContext();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if (item.path != null && item.path.startsWith("content:")) {
                r.setDataSource(appCtx, android.net.Uri.parse(item.path));
            } else {
                r.setDataSource(item.path);
            }
            long ms = Math.min(Math.max(item.durationMs, 0), MAX_SEEK_MS);
            Bitmap frame = r.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;

            int w = frame.getWidth();
            int h = frame.getHeight();
            if (w > TARGET_WIDTH && w > 0) {
                int nh = Math.max(1, (int) Math.round((double) h * TARGET_WIDTH / w));
                Bitmap scaled = Bitmap.createScaledBitmap(frame, TARGET_WIDTH, nh, true);
                if (scaled != frame) frame.recycle();
                frame = scaled;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            frame.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            frame.recycle();
            byte[] bytes = baos.toByteArray();

            try {
                File parent = cacheFile.getParentFile();
                if (parent != null) parent.mkdirs();
                FileOutputStream fos = new FileOutputStream(cacheFile);
                try {
                    fos.write(bytes);
                } finally {
                    fos.close();
                }
            } catch (IOException ignored) {
                // 缓存写失败不影响返回
            }
            return bytes;
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) {
            }
        }
    }

    private static byte[] readFile(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {
            }
        }
    }
}
