package com.local.embylite;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * 播放进度存储（单例）。文件：getExternalFilesDir(null)/progress.json
 * 结构：{"<itemId>": {"positionTicks":long,"durationTicks":long,"played":bool,"playCount":int,"lastPlayedMs":long}}
 * 单位均为 Emby ticks（100ns）。
 */
public class ProgressDb {

    private static final long SAVE_THROTTLE_MS = 1000;

    private static volatile ProgressDb instance;

    private final File file;
    private JSONObject data = new JSONObject();
    private long lastSaveMs;

    private ProgressDb(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        file = new File(dir, "progress.json");
        load();
    }

    public static ProgressDb get(Context ctx) {
        ProgressDb d = instance;
        if (d == null) {
            synchronized (ProgressDb.class) {
                if (instance == null) {
                    instance = new ProgressDb(ctx.getApplicationContext());
                }
                d = instance;
            }
        }
        return d;
    }

    private void load() {
        JSONObject loaded = null;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            loaded = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            loaded = null; // 读失败容忍：当作空库
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {
            }
        }
        data = loaded != null ? loaded : new JSONObject();
    }

    private JSONObject entry(String itemId, long durationTicks) {
        JSONObject e = data.optJSONObject(itemId);
        if (e == null) {
            e = new JSONObject();
            setLong(e, "positionTicks", 0L);
            setLong(e, "durationTicks", durationTicks);
            setBool(e, "played", false);
            setInt(e, "playCount", 0);
            setLong(e, "lastPlayedMs", 0L);
            try {
                data.put(itemId, e);
            } catch (JSONException ex) {
                throw new RuntimeException(ex);
            }
        }
        return e;
    }

    public synchronized void onStart(String itemId, long durationTicks) {
        if (itemId == null || itemId.isEmpty()) return;
        JSONObject e = entry(itemId, durationTicks);
        // Keep the saved position until the client reports its actual resumed position.
        if (durationTicks > 0) setLong(e, "durationTicks", durationTicks);
        setInt(e, "playCount", e.optInt("playCount", 0) + 1);
        setLong(e, "lastPlayedMs", System.currentTimeMillis());
        save();
    }

    public synchronized void onProgress(String itemId, long positionTicks, long durationTicks, boolean paused) {
        if (itemId == null || itemId.isEmpty()) return;
        JSONObject e = entry(itemId, durationTicks);
        setLong(e, "positionTicks", Math.max(0L, positionTicks));
        if (durationTicks > 0) setLong(e, "durationTicks", durationTicks);
        setLong(e, "lastPlayedMs", System.currentTimeMillis());
        save();
    }

    public synchronized void onStop(String itemId, long positionTicks, long durationTicks) {
        if (itemId == null || itemId.isEmpty()) return;
        JSONObject e = entry(itemId, durationTicks);
        setLong(e, "positionTicks", Math.max(0L, positionTicks));
        if (durationTicks > 0) setLong(e, "durationTicks", durationTicks);
        long d = e.optLong("durationTicks", 0L);
        boolean played = d > 0 && (double)positionTicks / d >= 0.95; // >=95% 算看完
        setBool(e, "played", played);
        setLong(e, "lastPlayedMs", System.currentTimeMillis());
        lastSaveMs = 0; // Stop events must survive immediate process exit.
        save();
    }

    /** 手动标记/取消已看（PlayedItems 端点用）。 */
    public synchronized void setPlayed(String itemId, boolean played) {
        if (itemId == null || itemId.isEmpty()) return;
        JSONObject e = entry(itemId, 0L);
        setBool(e, "played", played);
        if (played) setLong(e, "lastPlayedMs", System.currentTimeMillis());
        save();
    }

    public synchronized JSONObject userData(String itemId) {
        JSONObject e = data.optJSONObject(itemId);
        long p = e != null ? e.optLong("positionTicks", 0L) : 0L;
        long d = e != null ? e.optLong("durationTicks", 0L) : 0L;
        int n = e != null ? e.optInt("playCount", 0) : 0;
        boolean played = e != null && e.optBoolean("played", false);
        double pct = 0.0;
        if (d > 0) pct = Math.round(p * 1000.0 / d) / 10.0; // 保留 1 位小数
        JSONObject u = new JSONObject();
        try {
            u.put("PlaybackPositionTicks", p);
            u.put("PlayCount", n);
            u.put("Played", played);
            u.put("PlayedPercentage", pct);
            u.put("IsFavorite", false);
        } catch (JSONException ex) {
            throw new RuntimeException(ex);
        }
        return u;
    }

    public synchronized JSONArray allUserData() {
        JSONArray out = new JSONArray();
        Iterator<String> it = data.keys();
        while (it.hasNext()) {
            String id = it.next();
            try {
                JSONObject u = userData(id);
                u.put("ItemId", id);
                out.put(u);
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    /** 内部条目副本（含 itemId 字段），Resume 端点用。 */
    public synchronized JSONArray rawEntries() {
        JSONArray out = new JSONArray();
        Iterator<String> it = data.keys();
        while (it.hasNext()) {
            String id = it.next();
            JSONObject e = data.optJSONObject(id);
            if (e == null) continue;
            try {
                JSONObject c = new JSONObject(e.toString());
                c.put("itemId", id);
                out.put(c);
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    /** 临时文件 + rename 原子替换；1 秒内重复保存跳过。 */
    private void save() {
        long now = System.currentTimeMillis();
        if (now - lastSaveMs < SAVE_THROTTLE_MS) return;
        lastSaveMs = now;
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp);
            fos.write(data.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.getFD().sync();
            fos.close();
            fos = null;
            if (!tmp.renameTo(file)) {
                file.delete();
                tmp.renameTo(file);
            }
        } catch (Throwable t) {
            try { tmp.delete(); } catch (Throwable ignored) {
            }
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ignored) {
            }
        }
    }

    private static void setLong(JSONObject o, String k, long v) {
        try { o.put(k, v); } catch (JSONException e) { throw new RuntimeException(e); }
    }

    private static void setInt(JSONObject o, String k, int v) {
        try { o.put(k, v); } catch (JSONException e) { throw new RuntimeException(e); }
    }

    private static void setBool(JSONObject o, String k, boolean v) {
        try { o.put(k, v); } catch (JSONException e) { throw new RuntimeException(e); }
    }
}
