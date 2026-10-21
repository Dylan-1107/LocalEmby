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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Conservative episode binding. Source, identity and file size are part of the cache key. */
public final class EpisodeMatcher {
    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor();
    public static volatile String progress = "尚未识别";
    private static boolean running;
    private EpisodeMatcher() {}

    private static SharedPreferences store(Context ctx) {
        return ctx.getSharedPreferences("episode_bindings_v1", Context.MODE_PRIVATE);
    }
    private static String fingerprint(Context ctx, Item item) {
        return DanmuProxy.base(ctx) + "|" + item.path + "|" + item.sizeBytes + "|"
                + item.name + "|" + item.seriesName + "|" + item.seasonNumber + "|"
                + item.seasonExplicit + "|" + item.episodeNumber;
    }
    public static JSONObject binding(Context ctx, Item item) {
        try {
            JSONObject value = new JSONObject(store(ctx).getString(item.id, "{}"));
            return fingerprint(ctx, item).equals(value.optString("fingerprint")) ? value : new JSONObject();
        } catch (Exception e) { return new JSONObject(); }
    }
    public static void enqueue(Context ctx, java.util.List<Item> items) {
        enqueue(ctx, items, false);
    }
    public static synchronized void enqueue(Context ctx, java.util.List<Item> items, boolean force) {
        if (running) return;
        running = true;
        Context app = ctx.getApplicationContext();
        java.util.List<Item> snapshot = new java.util.ArrayList<Item>(items);
        QUEUE.execute(() -> {
            int done = 0, matched = 0, pending = 0, network = 0;
            try {
                for (Item item : snapshot) {
                    if (item.episodeNumber <= 0) continue;
                    progress = "正在识别：" + item.name;
                    if (resolve(app, item, force) > 0) matched++;
                    else if ("network_error".equals(binding(app, item).optString("status"))) network++;
                    else pending++;
                    done++;
                }
                progress = "已处理 " + done + " 集 · 匹配 " + matched + " · 待确认 " + pending + " · 网络失败 " + network;
            } catch (Exception e) {
                progress = "识别中断，请重试：" + e.getMessage();
                EmbyLog.info(progress);
            } finally { synchronized (EpisodeMatcher.class) { running = false; } }
        });
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    public static int resolve(Context ctx, Item item, boolean force) {
        Object lock = LOCKS.computeIfAbsent(item.id, key -> new Object());
        synchronized (lock) { return resolveLocked(ctx, item, force); }
    }
    private static int resolveLocked(Context ctx, Item item, boolean force) {
        if (!item.isEpisode() || item.identityAmbiguous) return -1;
        JSONObject old = binding(ctx, item);
        if (!force && old.optInt("episodeId") > 0) return old.optInt("episodeId");
        if (!force && System.currentTimeMillis() - old.optLong("attemptedAt")
                < ("network_error".equals(old.optString("status")) ? 15000L : 300000L)) return -1;
        JSONObject state = new JSONObject();
        try {
            state.put("fingerprint", fingerprint(ctx, item));
            state.put("attemptedAt", System.currentTimeMillis());
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
