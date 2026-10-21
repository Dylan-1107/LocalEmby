package com.local.embylite;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 弹幕剧集绑定（保守策略）。缓存键 = 弹幕源 + 路径 + 大小 + 解析身份。
 * 任务模式：INCREMENTAL 只处理新增与失败到期重试；RETRY 处理失败/待确认/未命中；FULL 全量（确认过的绑定永不覆盖）。
 */
public final class EpisodeMatcher {

    public static final int MODE_INCREMENTAL = 0;
    public static final int MODE_RETRY = 1;
    public static final int MODE_FULL = 2;

    /** 失败重试指数退避表（毫秒）：15s → 1min → 5min → 30min → 2h。 */
    private static final long[] BACKOFF = {15_000L, 60_000L, 300_000L, 1_800_000L, 7_200_000L};

    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor();
    public static volatile String progress = "尚未识别";
    /** 结构化进度：UI 进度条读取。 */
    public static volatile int doneCount;
    public static volatile int totalCount;
    public static volatile String currentItem = "";
    private static volatile boolean cancelRequested;
    /** 批次是否在跑（UI 进度展示用）。 */
    public static volatile boolean isActive;
    /** isActive 时新请求不丢弃：暂存为待跑批次（最新一次覆盖旧的），当前批次结束后执行。 */
    private static List<Item> pendingItems;
    private static int pendingMode = MODE_INCREMENTAL;

    private EpisodeMatcher() {}

    private static SharedPreferences store(Context ctx) {
        return ctx.getSharedPreferences("episode_bindings_v1", Context.MODE_PRIVATE);
    }

    private static String fingerprint(Context ctx, Item item) {
        return "r1|" + DanmuProxy.base(ctx) + "|" + item.path + "|" + item.sizeBytes + "|"
                + item.name + "|" + item.seriesName + "|" + item.seasonNumber + "|"
                + item.seasonExplicit + "|" + item.episodeNumber;
    }

    public static JSONObject binding(Context ctx, Item item) {
        try {
            JSONObject value = new JSONObject(store(ctx).getString(item.id, "{}"));
            return fingerprint(ctx, item).equals(value.optString("fingerprint")) ? value : new JSONObject();
        } catch (Exception e) { return new JSONObject(); }
    }

    public static void enqueue(Context ctx, List<Item> items) {
        enqueue(ctx, items, MODE_INCREMENTAL);
    }

    /** 提交一批识别任务。isActive 时改为暂存待跑批次（最新覆盖），不再静默丢弃。 */
    public static synchronized void enqueue(Context ctx, List<Item> items, int mode) {
        if (isActive) {
            pendingItems = new ArrayList<Item>(items);
            pendingMode = mode;
            progress = "识别排队中，当前批次结束后执行…";
            return;
        }
        isActive = true;
        cancelRequested = false;
        Context app = ctx.getApplicationContext();
        List<Item> snapshot = new ArrayList<Item>(items);
        int m = mode;
        QUEUE.execute(() -> runBatch(app, snapshot, m));
    }

    /** 用户请求取消当前批次。 */
    public static void cancel() {
        cancelRequested = true;
    }

    private static void runBatch(Context app, List<Item> snapshot, int mode) {
        try {
            int done = 0, matched = 0, pending = 0, network = 0, skipped = 0;
            List<Item> work = new ArrayList<Item>();
            for (Item item : snapshot) {
                if (item == null || !item.isEpisode() || item.identityAmbiguous) continue;
                if (shouldProcess(app, item, mode)) work.add(item);
                else skipped++;
            }
            totalCount = work.size();
            doneCount = 0;
            currentItem = "";
            progress = totalCount == 0 ? "没有需要处理的条目（跳过 " + skipped + "）" : "正在识别…";
            for (Item item : work) {
                if (cancelRequested) {
                    progress = "已取消：处理 " + done + "/" + totalCount;
                    return;
                }
                currentItem = item.name;
                progress = "正在识别：" + item.name + " (" + (done + 1) + "/" + totalCount + ")";
                if (resolve(app, item, mode) > 0) matched++;
                else if ("network_error".equals(binding(app, item).optString("status"))) network++;
                else pending++;
                done++;
                doneCount = done;
            }
            progress = "已处理 " + done + " 集 · 匹配 " + matched + " · 待确认 " + pending
                    + " · 网络失败 " + network + (skipped > 0 ? " · 跳过 " + skipped : "");
        } catch (Exception e) {
            progress = "识别中断，请重试：" + e.getMessage();
            EmbyLog.info(progress);
        } finally {
            List<Item> next;
            int nextMode;
            synchronized (EpisodeMatcher.class) {
                isActive = false;
                next = pendingItems;
                nextMode = pendingMode;
                pendingItems = null;
            }
            if (next != null) enqueue(app, next, nextMode);
        }
    }

    /** 该条目在本批次模式下是否需要处理（决定增量的关键开关）。 */
    private static boolean shouldProcess(Context ctx, Item item, int mode) {
        JSONObject old = binding(ctx, item);
        String status = old.optString("status");
        boolean bound = old.optInt("episodeId") > 0;
        if (bound || "confirmed".equals(status)) return false; // 人工确认与已绑定永不重做
        if (old.length() == 0 || !old.has("fingerprint")) return true; // 新文件
        boolean failed = "network_error".equals(status) || "error".equals(status);
        if (mode == MODE_INCREMENTAL) {
            if (failed) return backoffExpired(old);
            return false; // needs_confirmation / unmatched 安静等待
        }
        if (mode == MODE_RETRY) return failed || "needs_confirmation".equals(status)
                || "unmatched".equals(status);
        return true; // FULL：除确认外全量
    }

    private static boolean backoffExpired(JSONObject old) {
        long waited = System.currentTimeMillis() - old.optLong("attemptedAt");
        int attempts = old.optInt("attempts", 0);
        long limit = BACKOFF[Math.min(attempts, BACKOFF.length - 1)];
        return waited >= limit;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    public static int resolve(Context ctx, Item item, int mode) {
        Object lock = LOCKS.computeIfAbsent(item.id, key -> new Object());
        synchronized (lock) { return resolveLocked(ctx, item, mode); }
    }

    private static int resolveLocked(Context ctx, Item item, int mode) {
        if (!item.isEpisode() || item.identityAmbiguous) return -1;
        JSONObject old = binding(ctx, item);
        if (old.optInt("episodeId") > 0) return old.optInt("episodeId"); // 已绑定/确认
        if (!shouldProcess(ctx, item, mode)) return -1;
        JSONObject state = new JSONObject();
        try {
            state.put("fingerprint", fingerprint(ctx, item));
            state.put("attemptedAt", System.currentTimeMillis());
            state.put("attempts", mode == MODE_INCREMENTAL ? old.optInt("attempts", 0) + 1 : 1);
            state.put("status", "unmatched");
            String response = DanmuProxy.post(DanmuProxy.base(ctx) + "/api/v2/match",
                    new JSONObject().put("fileName", DanmuProxy.matchFileName(item)).toString());
            if (response == null) {
                state.put("status", "network_error");
                state.put("error", DanmuNetwork.ERROR.get());
            } else {
                JSONObject body = new JSONObject(response);
                JSONArray candidates = body.optJSONArray("matches");
                if (candidates == null) candidates = new JSONArray();
                state.put("candidates", candidates);
                JSONObject chosen = null;
                boolean conflict = false;
                if (body.optBoolean("success") && body.optBoolean("isMatched")) {
                    for (int i = 0; i < candidates.length(); i++) {
                        JSONObject candidate = candidates.optJSONObject(i);
                        if (candidate == null || !accepts(item, candidate)) continue;
                        if (chosen != null && chosen.optInt("episodeId") != candidate.optInt("episodeId")) conflict = true;
                        chosen = candidate;
                    }
                }
                if (chosen != null && !conflict) {
                    state.put("episodeId", chosen.getInt("episodeId"));
                    state.put("animeTitle", chosen.optString("animeTitle"));
                    state.put("episodeTitle", chosen.optString("episodeTitle"));
                    state.put("status", "matched");
                    store(ctx).edit().putString(item.id, state.toString()).commit();
                    savePoster(ctx, item, chosen.optString("imageUrl"));
                    EmbyLog.info("episode matched: " + item.name + " -> " + chosen.optString("episodeTitle"));
                    return chosen.getInt("episodeId");
                }
                if (candidates.length() > 0) state.put("status", "needs_confirmation");
            }
            store(ctx).edit().putString(item.id, state.toString()).commit();
            EmbyLog.info("episode " + state.optString("status") + ": " + item.name);
        } catch (Exception e) { EmbyLog.info("episode match error: " + e); }
        return -1;
    }

    /** Explicit user choice from saved candidates; never accept an arbitrary external ID. */
    public static synchronized boolean confirm(Context ctx, Item item, int episodeId) {
        JSONObject state = binding(ctx, item);
        JSONArray candidates = state.optJSONArray("candidates");
        if (candidates == null || item.identityAmbiguous) return false;
        try {
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject choice = candidates.optJSONObject(i);
                if (choice == null || choice.optInt("episodeId", -1) != episodeId || episodeId <= 0) continue;
                state.put("episodeId", episodeId);
                state.put("animeTitle", choice.optString("animeTitle"));
                state.put("episodeTitle", choice.optString("episodeTitle"));
                state.put("status", "confirmed");
                if (!store(ctx).edit().putString(item.id, state.toString()).commit()) return false;
                savePoster(ctx, item, choice.optString("imageUrl"));
                return true;
            }
        } catch (Exception e) { EmbyLog.info("episode confirmation failed: " + e); }
        return false;
    }

    /** Title and episode must match; explicit later seasons need evidence from the source. */
    static boolean accepts(Item item, JSONObject candidate) {
        if (candidate.optInt("episodeId", -1) <= 0) return false;
        return EpisodeMatchPolicy.accepts(item, candidate.optString("animeTitle"), candidate.optString("episodeTitle"));
    }

    private static void savePoster(Context ctx, Item item, String address) {
        if (address == null || !address.startsWith("https://")) return;
        HttpURLConnection conn = null;
        try {
            if (MetadataStore.posterBytes(ctx, "episode_" + item.id) != null) return;
            conn = (HttpURLConnection) new URL(address.replace("\r", "").replace("\n", "")).openConnection();
            conn.setConnectTimeout(6000); conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) return;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (java.io.InputStream in = conn.getInputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) {
                    bytes.write(buffer, 0, count);
                    if (bytes.size() > 5 * 1024 * 1024) return;
                }
            }
            byte[] data = bytes.toByteArray();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return;
            options.inJustDecodeBounds = false;
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 800 || options.outHeight / options.inSampleSize > 1200)
                options.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (bitmap == null) return;
            try {
                ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, jpg);
                MetadataStore.savePoster(ctx, "episode_" + item.id, jpg.toByteArray());
            } finally { bitmap.recycle(); }
        } catch (Exception e) { EmbyLog.info("episode poster unavailable: " + item.name); }
        finally { if (conn != null) conn.disconnect(); }
    }
}
