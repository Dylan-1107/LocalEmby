package com.local.embylite;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 媒体库持久化索引（library_index_v1）。
 * 每条记录 path/size/mtime/duration，扫描后 diff：
 * - 新增/变化（size 或 mtime 不同）→ 需要探测时长、进入识别/刮削队列
 * - 未变 → 时长直接读索引，不 probeDuration
 * - 消失 → 从索引删除，并清理该条目的弹幕绑定与刮削状态
 * mtime 取不到（为 0）时按 size 单因子比对。
 */
public final class LibraryIndex {

    /** diff 结果：toProbe = 新增或变化的条目；removed = 已消失的条目 id。 */
    public static class Result {
        public final List<Item> toProbe = new ArrayList<Item>();
        public final List<String> removed = new ArrayList<String>();
        public boolean isEmpty() { return toProbe.isEmpty() && removed.isEmpty(); }
    }

    private LibraryIndex() {
    }

    private static SharedPreferences store(Context ctx) {
        return ctx.getSharedPreferences("library_index_v1", Context.MODE_PRIVATE);
    }

    /**
     * 与索引比对并更新：返回需要探测时长的新增/变化条目与已消失的 id。
     * 未变化条目的 durationMs 直接从索引回填（>0 时）。
     */
    public static synchronized Result reconcile(Context ctx, List<Item> scanned) {
        Result result = new Result();
        SharedPreferences sp = store(ctx);
        Set<String> scannedIds = new HashSet<String>();
        try {
            for (Item item : scanned) {
                if (item == null || item.id == null || item.id.isEmpty()) continue;
                scannedIds.add(item.id);
                JSONObject e = new JSONObject(sp.getString(item.id, "{}"));
                boolean has = e.has("s");
                boolean same = has
                        && e.optLong("s") == item.sizeBytes
                        && (e.optLong("m") == 0 || item.mtime == 0 || e.optLong("m") == item.mtime);
                if (same) {
                    long duration = e.optLong("d");
                    if (duration > 0 && item.durationMs <= 0) item.durationMs = duration;
                } else {
                    result.toProbe.add(item);
                }
            }
            // 消失的条目：删索引 + 清理状态
            java.util.Map<String, ?> all = sp.getAll();
            for (String id : all.keySet()) {
                if (!scannedIds.contains(id)) result.removed.add(id);
            }
            if (!result.removed.isEmpty()) {
                SharedPreferences.Editor editor = sp.edit();
                for (String id : result.removed) {
                    editor.remove(id);
                    ctx.getSharedPreferences("episode_bindings_v1", Context.MODE_PRIVATE)
                            .edit().remove(id).apply();
                    ctx.getSharedPreferences("videoinfo_v1", Context.MODE_PRIVATE)
                            .edit().remove(id).apply();
                }
                editor.apply();
                EmbyLog.info("library index: 清理 " + result.removed.size() + " 个已消失的条目");
            }
        } catch (Throwable t) {
            EmbyLog.info("library index reconcile error: " + t);
        }
        return result;
    }

    /** 记录/更新一个条目（探测完时长后调用）。 */
    public static synchronized void put(Context ctx, Item item) {
        try {
            JSONObject e = new JSONObject();
            e.put("p", item.path == null ? "" : item.path);
            e.put("s", item.sizeBytes);
            e.put("m", item.mtime);
            e.put("d", item.durationMs);
            store(ctx).edit().putString(item.id, e.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 批量记录（探测完时长后调用）。 */
    public static synchronized void putAll(Context ctx, Collection<Item> items) {
        try {
            SharedPreferences.Editor editor = store(ctx).edit();
            for (Item item : items) {
                if (item == null || item.id == null) continue;
                JSONObject e = new JSONObject();
                e.put("p", item.path == null ? "" : item.path);
                e.put("s", item.sizeBytes);
                e.put("m", item.mtime);
                e.put("d", item.durationMs);
                editor.putString(item.id, e.toString());
            }
            editor.apply();
        } catch (Throwable ignored) {
        }
    }

    /** 时长探测：真实路径才做，content:// 返回 0。 */
    static long probeDuration(Item item) {
        try {
            if (item.path == null || item.path.startsWith("content://")) return 0L;
            if (!new File(item.path).isFile()) return 0L;
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            try {
                r.setDataSource(item.path);
                String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) return Long.parseLong(d.trim());
            } catch (Throwable t) {
                // setDataSource 可能失败
            } finally {
                try { r.release(); } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }
}
