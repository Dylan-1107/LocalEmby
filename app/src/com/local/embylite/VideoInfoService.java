package com.local.embylite;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 刮削编排服务：TMDB 元数据 → MetadataStore 覆盖层 + Kodi NFO/海报落盘。
 *
 * 增量设计（docs/incremental-scan-scrape-plan.md）：
 * - 任务模式：INCREMENTAL（新增 + 失败到期重试）/ RETRY（失败 + 待确认 + 未命中）/ FULL（全量，locked 除外）
 * - 剧集按身份聚合（series_meta_v1）：一部剧只 search/detail/海报一次，分集共享
 * - (tmdbId, season) 分集信息持久化缓存（season_cache_v1），FULL 才强制刷新
 * - 人工选定 locked=true：任何模式不覆盖；candidates 留存支持"换一个"
 * - 状态机：local_nfo / scraped / needs_confirmation / unmatched / network_error / skipped / scraping / error
 */
public class VideoInfoService {

    public static final int MODE_INCREMENTAL = 0;
    public static final int MODE_RETRY = 1;
    public static final int MODE_FULL = 2;

    /** 解析规则版本：改 ScrapeTitle/EpisodeIdentity 规则时 +1，让旧结果按需失效。 */
    private static final String RULES_VER = "1";

    /** 失败重试指数退避表（毫秒）：15s → 1min → 5min → 30min → 2h。 */
    private static final long[] BACKOFF = {15_000L, 60_000L, 300_000L, 1_800_000L, 7_200_000L};

    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor();
    public static volatile String progress = "尚未刮削";
    public static volatile int doneCount;
    public static volatile int totalCount;
    public static volatile String currentItem = "";
    private static volatile boolean cancelRequested;
    public static volatile boolean isActive;
    private static List<Item> pendingItems;
    private static int pendingMode = MODE_INCREMENTAL;

    private VideoInfoService() {
    }

    /* ==================== 存储 ==================== */

    private static SharedPreferences store(Context ctx) {
        return ctx.getSharedPreferences("videoinfo_v1", Context.MODE_PRIVATE);
    }

    private static SharedPreferences seriesStore(Context ctx) {
        return ctx.getSharedPreferences("series_meta_v1", Context.MODE_PRIVATE);
    }

    private static SharedPreferences seasonStore(Context ctx) {
        return ctx.getSharedPreferences("season_cache_v1", Context.MODE_PRIVATE);
    }

    /** API key 与规则版本参与指纹：换 key / 改规则后旧结果自动失效。 */
    private static String fingerprint(Context ctx, Item item) {
        return "r" + RULES_VER + "|tmdb|" + Settings.getTmdbKey(ctx) + "|" + item.path + "|" + item.sizeBytes + "|"
                + item.name + "|" + item.seriesName + "|" + item.seasonNumber + "|" + item.episodeNumber;
    }

    /** 当前条目的刮削状态（指纹不匹配返回空对象 {}）。 */
    public static JSONObject state(Context ctx, Item item) {
        try {
            JSONObject value = new JSONObject(store(ctx).getString(item.id, "{}"));
            return fingerprint(ctx, item).equals(value.optString("fingerprint")) ? value : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 剧集身份键：归一化剧名 + 剧集根目录（季目录上翻一层）。 */
    static String seriesKey(Item item) {
        if (!item.isEpisode()) return "";
        return Item.normalizeTitle(item.seriesName == null ? "" : item.seriesName) + "|" + seriesDir(item);
    }

    private static String seriesDir(Item item) {
        try {
            if (item.path == null || item.path.startsWith("content://")) return "";
            File parent = new File(item.path).getParentFile();
            if (parent == null) return "";
            File dir = parent;
            // 与 writeNfo 的季目录判断保持一致：季目录则取上一层
            if (isSeasonDir(parent.getName())) {
                File up = parent.getParentFile();
                if (up != null) dir = up;
            }
            return dir.getAbsolutePath();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String seriesFingerprint(Context ctx, String key) {
        return "r" + RULES_VER + "|tmdb|" + Settings.getTmdbKey(ctx) + "|" + key;
    }

    /** 指纹匹配的剧集级缓存；失配返回空对象。 */
    private static JSONObject seriesState(Context ctx, String key) {
        try {
            JSONObject value = new JSONObject(seriesStore(ctx).getString(hash(key), "{}"));
            return seriesFingerprint(ctx, key).equals(value.optString("fingerprint")) ? value : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void saveSeries(Context ctx, String key, JSONObject st) {
        try {
            st.put("fingerprint", seriesFingerprint(ctx, key));
            seriesStore(ctx).edit().putString(hash(key), st.toString()).commit();
        } catch (Throwable t) {
            EmbyLog.info("videoinfo series 保存失败: " + t);
        }
    }

    private static String seriesPosterKey(String key) {
        return "series_" + hash(key);
    }

    private static String hash(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest((s == null ? "" : s).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf((s == null ? "" : s).hashCode());
        }
    }

    /* ==================== NfoData 序列化（剧集级缓存用） ==================== */

    private static JSONObject nfoToJson(NfoData d) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", d.type);
            o.put("tmdbId", d.tmdbId == null ? "" : d.tmdbId);
            o.put("title", nz(d.title));
            o.put("plot", nz(d.plot));
            o.put("premiereDate", nz(d.premiereDate));
            o.put("rating", d.rating);
            o.put("year", d.year);
            o.put("season", d.season);
            o.put("episode", d.episode);
            o.put("episodeTitle", nz(d.episodeTitle));
            o.put("genres", new JSONArray(d.genres));
            JSONArray studios = new JSONArray();
            if (d.studios != null) for (String s : d.studios) studios.put(s);
            o.put("studios", studios);
            JSONArray people = new JSONArray();
            if (d.people != null) {
                for (NfoData.Person p : d.people) {
                    people.put(new JSONObject().put("name", nz(p.name)).put("role", nz(p.role)).put("type", nz(p.type)));
                }
            }
            o.put("people", people);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static NfoData nfoFromJson(JSONObject o) {
        try {
            NfoData d = new NfoData();
            d.type = o.optString("type", "tvshow");
            d.tmdbId = o.optString("tmdbId");
            d.title = o.optString("title");
            d.plot = o.optString("plot");
            d.premiereDate = o.optString("premiereDate");
            d.rating = o.optDouble("rating", 0.0);
            d.year = o.optInt("year");
            d.season = o.optInt("season");
            d.episode = o.optInt("episode");
            d.episodeTitle = o.optString("episodeTitle");
            JSONArray g = o.optJSONArray("genres");
            if (g != null) for (int i = 0; i < g.length(); i++) d.genres.add(g.optString(i));
            JSONArray s = o.optJSONArray("studios");
            if (s != null) for (int i = 0; i < s.length(); i++) d.studios.add(s.optString(i));
            JSONArray p = o.optJSONArray("people");
            if (p != null) {
                for (int i = 0; i < p.length(); i++) {
                    JSONObject po = p.optJSONObject(i);
                    if (po == null) continue;
                    NfoData.Person person = new NfoData.Person();
                    person.name = po.optString("name");
                    person.role = po.optString("role");
                    person.type = po.optString("type", "Actor");
                    d.people.add(person);
                }
            }
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /* ==================== 批次与模式 ==================== */

    public static void enqueue(Context ctx, List<Item> items) {
        enqueue(ctx, items, MODE_INCREMENTAL);
    }

    /** 提交一批刮削。running 时暂存待跑批次（最新覆盖），不再静默丢弃。 */
    public static synchronized void enqueue(Context ctx, List<Item> items, int mode) {
        if (isActive) {
            pendingItems = new ArrayList<Item>(items);
            pendingMode = mode;
            progress = "刮削排队中，当前批次结束后执行…";
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
        Map<String, SeriesMemo> memos = new HashMap<String, SeriesMemo>();
        try {
            int done = 0, ok = 0, pending = 0, missed = 0, failed = 0, skipped = 0;
            List<Item> work = new ArrayList<Item>();
            for (Item item : snapshot) {
                if (item == null || item.id == null || item.id.isEmpty()) continue;
                if (shouldProcess(app, item, mode)) work.add(item);
                else skipped++;
            }
            totalCount = work.size();
            doneCount = 0;
            currentItem = "";
            progress = totalCount == 0 ? "没有需要刮削的条目（跳过 " + skipped + "）" : "正在刮削…";
            for (Item item : work) {
                if (cancelRequested) {
                    progress = "已取消：处理 " + done + "/" + totalCount;
                    return;
                }
                currentItem = item.name;
                progress = "正在刮削：" + item.name + " (" + (done + 1) + "/" + totalCount + ")";
                String status = item.isEpisode()
                        ? resolveEpisode(app, item, mode, memos)
                        : resolveMovie(app, item, mode);
                if ("scraped".equals(status) || "local_nfo".equals(status)) ok++;
                else if ("needs_confirmation".equals(status)) pending++;
                else if ("unmatched".equals(status)) missed++;
                else failed++;
                done++;
                doneCount = done;
            }
            progress = "已处理 " + done + " · 刮削成功 " + ok + " · 待确认 " + pending
                    + " · 未命中 " + missed + " · 失败 " + failed + (skipped > 0 ? " · 跳过 " + skipped : "");
        } catch (Throwable t) {
            progress = "刮削中断，请重试：" + t.getMessage();
            EmbyLog.info("videoinfo batch error: " + t);
        } finally {
            List<Item> next;
            int nextMode;
            synchronized (VideoInfoService.class) {
                isActive = false;
                next = pendingItems;
                nextMode = pendingMode;
                pendingItems = null;
            }
            if (next != null) enqueue(app, next, nextMode);
        }
    }

    /** 本批次模式下是否处理该条目（locked 的人工结果任何模式都不覆盖）。 */
    private static boolean shouldProcess(Context ctx, Item item, int mode) {
        JSONObject old = state(ctx, item);
        if (old.length() == 0 || !old.has("fingerprint")) return true; // 新文件
        if (old.optBoolean("locked")) return false;
        String status = old.optString("status");
        boolean doneState = "scraped".equals(status) || "local_nfo".equals(status);
        boolean failed = "network_error".equals(status) || "error".equals(status);
        if (mode == MODE_INCREMENTAL) {
            if (doneState) return false;
            if (failed) return backoffExpired(old);
            return false; // needs_confirmation / unmatched / scraping 安静等待
        }
        if (mode == MODE_RETRY) {
            return failed || "needs_confirmation".equals(status) || "unmatched".equals(status);
        }
        return true; // FULL
    }

    private static boolean backoffExpired(JSONObject old) {
        long waited = System.currentTimeMillis() - old.optLong("attemptedAt");
        int attempts = old.optInt("attempts", 0);
        return waited >= BACKOFF[Math.min(attempts, BACKOFF.length - 1)];
    }

    /* ==================== 电影（文件级） ==================== */

    private static String resolveMovie(Context ctx, Item item, int mode) {
        try {
            JSONObject old = state(ctx, item);
            if (!shouldProcess(ctx, item, mode)) return old.optString("status", "skipped");
            JSONObject st = new JSONObject();
            st.put("fingerprint", fingerprint(ctx, item));
            st.put("attemptedAt", System.currentTimeMillis());
            st.put("attempts", mode == MODE_INCREMENTAL ? old.optInt("attempts", 0) + 1 : 1);

            ScrapeTitle.Result parsed = ScrapeTitle.parse(item.name);
            String query = parsed == null ? "" : parsed.title;
            int year = parsed == null ? 0 : parsed.year;
            if (query.isEmpty()) {
                st.put("status", "skipped");
                save(ctx, item.id, st);
                return "skipped";
            }
            if (tryLocalNfo(ctx, item, st)) return "local_nfo";
            if (Settings.getTmdbKey(ctx).trim().isEmpty()) {
                st.put("status", "network_error");
                st.put("error", "未配置 TMDB API Key，请在「设置 → 视频信息」填写");
                save(ctx, item.id, st);
                return "network_error";
            }

            pace();
            JSONObject resp = TmdbClient.search(Settings.getTmdbKey(ctx), true, query, year);
            if (resp == null) return failNetwork(ctx, item, st);
            JSONArray results = resp.optJSONArray("results");
            int chosen = 0;
            boolean conflict = false;
            JSONArray candidates = new JSONArray();
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject cand = results.optJSONObject(i);
                if (cand == null) continue;
                String title = cand.optString("title");
                String original = cand.optString("original_title");
                String date = cand.optString("release_date");
                if (!ScrapePolicy.accepts(query, year, title, original, date)) continue;
                int id = cand.optInt("id", 0);
                if (id <= 0) continue;
                if (chosen > 0 && chosen != id) conflict = true;
                if (chosen == 0) chosen = id;
                if (candidates.length() < 5) candidates.put(candidateJson(id, title, original, yearOf(date), cand.optString("overview")));
            }
            if (chosen > 0 && !conflict) {
                st.put("candidates", candidates);
                return scrape(ctx, item, chosen, false, st);
            }
            if (candidates.length() > 0) {
                st.put("status", "needs_confirmation");
                st.put("candidates", candidates);
                save(ctx, item.id, st);
                return "needs_confirmation";
            }
            st.put("status", "unmatched");
            save(ctx, item.id, st);
            return "unmatched";
        } catch (Throwable t) {
            EmbyLog.info("videoinfo movie error: " + item.name + ": " + t);
            return "error";
        }
    }

    /* ==================== 剧集（身份级聚合） ==================== */

    /** 一次批次内的剧集解析备忘：一部剧只做一次 search/detail/海报。 */
    private static class SeriesMemo {
        String state = ""; // resolved / candidates / unmatched / network_error
        int tmdbId;
        NfoData show;
        byte[] poster;
        JSONArray candidates;
        String error = "";
        boolean seriesFilesWritten; // tvshow.nfo + 剧集 poster.jpg 本批次已写
        boolean fromCache;
    }

    private static String resolveEpisode(Context ctx, Item item, int mode, Map<String, SeriesMemo> memos) throws Exception {
        try {
            JSONObject old = state(ctx, item);
            if (old.optBoolean("locked")) return old.optString("status", "scraped");
            if (!shouldProcess(ctx, item, mode)) return old.optString("status", "skipped");

            String key = seriesKey(item);
            SeriesMemo memo = memos.get(key);
            if (memo == null) {
                memo = resolveSeries(ctx, item, key);
                memos.put(key, memo);
            }

            JSONObject st = new JSONObject();
            st.put("fingerprint", fingerprint(ctx, item));
            st.put("attemptedAt", System.currentTimeMillis());
            st.put("attempts", mode == MODE_INCREMENTAL ? old.optInt("attempts", 0) + 1 : 1);
            st.put("seriesKey", key);

            if ("network_error".equals(memo.state)) {
                st.put("status", "network_error");
                st.put("error", memo.error);
                save(ctx, item.id, st);
                return "network_error";
            }
            if ("unmatched".equals(memo.state)) {
                st.put("status", "unmatched");
                save(ctx, item.id, st);
                return "unmatched";
            }
            if ("candidates".equals(memo.state)) {
                st.put("status", "needs_confirmation");
                st.put("candidates", memo.candidates);
                save(ctx, item.id, st);
                return "needs_confirmation";
            }

            // memo.state == resolved：本地分集 NFO 仍最优先（含 tvshow.nfo 导入），零网络
            if (tryLocalNfo(ctx, item, st)) return "local_nfo";

            if (memo.show == null) return "error";
            return applyEpisodeFromSeries(ctx, item, st, memo);
        } catch (Throwable t) {
            EmbyLog.info("videoinfo episode error: " + item.name + ": " + t);
            return "error";
        }
    }

    /** 剧集级解析：series 缓存 → TMDB（search + detail + poster 一次）。网络失败不缓存。 */
    private static SeriesMemo resolveSeries(Context ctx, Item first, String key) throws Exception {
        SeriesMemo memo = new SeriesMemo();
        JSONObject cached = seriesState(ctx, key);
        if (cached.has("fingerprint")) {
            String status = cached.optString("status");
            if ("scraped".equals(status)) {
                NfoData show = nfoFromJson(cached.optJSONObject("show"));
                if (show != null && show.tmdbId != null) {
                    memo.state = "resolved";
                    memo.tmdbId = cached.optInt("tmdbId");
                    memo.show = show;
                    memo.fromCache = true;
                    memo.candidates = cached.optJSONArray("candidates");
                    byte[] poster = MetadataStore.posterBytes(ctx, seriesPosterKey(key));
                    if (poster != null) memo.poster = poster;
                    return memo;
                }
            } else if ("needs_confirmation".equals(status)) {
                memo.state = "candidates";
                memo.candidates = cached.optJSONArray("candidates");
                return memo;
            } else if ("unmatched".equals(status)) {
                memo.state = "unmatched";
                return memo;
            }
        }

        String apiKey = Settings.getTmdbKey(ctx);
        if (apiKey.trim().isEmpty()) {
            memo.state = "network_error";
            memo.error = "未配置 TMDB API Key，请在「设置 → 视频信息」填写";
            return memo;
        }
        String query = first.seriesName == null ? "" : first.seriesName.trim();
        if (query.isEmpty()) {
            memo.state = "unmatched";
            return memo;
        }

        pace();
        JSONObject resp = TmdbClient.search(apiKey, false, query, 0);
        if (resp == null) {
            memo.state = "network_error";
            memo.error = String.valueOf(DanmuNetwork.ERROR.get());
            return memo;
        }
        JSONArray results = resp.optJSONArray("results");
        int chosen = 0;
        boolean conflict = false;
        JSONArray candidates = new JSONArray();
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject cand = results.optJSONObject(i);
            if (cand == null) continue;
            String title = cand.optString("name");
            String original = cand.optString("original_name");
            String date = cand.optString("first_air_date");
            if (!ScrapePolicy.accepts(query, 0, title, original, date)) continue;
            int id = cand.optInt("id", 0);
            if (id <= 0) continue;
            if (chosen > 0 && chosen != id) conflict = true;
            if (chosen == 0) chosen = id;
            if (candidates.length() < 5) {
                candidates.put(candidateJson(id, title, original, yearOf(date), cand.optString("overview")));
            }
        }
        if (chosen <= 0) {
            JSONObject st = new JSONObject();
            st.put("status", "unmatched");
            saveSeries(ctx, key, st);
            memo.state = "unmatched";
            return memo;
        }
        if (conflict) {
            JSONObject st = new JSONObject();
            st.put("status", "needs_confirmation");
            st.put("candidates", candidates);
            saveSeries(ctx, key, st);
            memo.state = "candidates";
            memo.candidates = candidates;
            return memo;
        }

        // 唯一命中：detail + 海报各一次
        pace();
        JSONObject detail = TmdbClient.detail(apiKey, false, chosen);
        if (detail == null) {
            memo.state = "network_error";
            memo.error = String.valueOf(DanmuNetwork.ERROR.get());
            return memo;
        }
        NfoData show = showFromDetail(false, chosen, detail);
        byte[] poster = null;
        try {
            pace();
            String posterPath = detail.optString("poster_path");
            if (!posterPath.isEmpty()) poster = TmdbClient.image(posterPath);
        } catch (Throwable ignored) {
        }
        JSONObject st = new JSONObject();
        st.put("status", "scraped");
        st.put("tmdbId", chosen);
        st.put("show", nfoToJson(show));
        st.put("candidates", candidates);
        if (poster != null) {
            try {
                MetadataStore.savePoster(ctx, seriesPosterKey(key), poster);
            } catch (Throwable ignored) {
            }
        }
        saveSeries(ctx, key, st);
        memo.state = "resolved";
        memo.tmdbId = chosen;
        memo.show = show;
        memo.poster = poster;
        memo.candidates = candidates;
        return memo;
    }

    /** 用已解析的剧集数据完成单集：分集信息 → 覆盖层 → 海报/NFO 落盘。 */
    private static String applyEpisodeFromSeries(Context ctx, Item item, JSONObject st, SeriesMemo memo) throws Exception {
        NfoData show = memo.show;
        NfoData ep = new NfoData();
        ep.type = "episode";
        ep.tmdbId = show.tmdbId;
        ep.title = show.title;
        ep.season = item.seasonNumber;
        ep.episode = item.episodeNumber;
        ep.rating = show.rating;
        ep.genres.addAll(show.genres);
        JSONObject seasonData = seasonCached(ctx, Settings.getTmdbKey(ctx), memo.tmdbId, item.seasonNumber, false);
        JSONArray eps = seasonData == null ? null : seasonData.optJSONArray("episodes");
        for (int i = 0; eps != null && i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e == null || e.optInt("episode_number", -1) != item.episodeNumber) continue;
            ep.episodeTitle = e.optString("name");
            ep.plot = e.optString("overview");
            ep.premiereDate = e.optString("air_date");
            ep.year = yearOf(ep.premiereDate);
            break;
        }
        if (ep.episodeTitle == null || ep.episodeTitle.isEmpty())
            ep.episodeTitle = show.title + " 第" + item.episodeNumber + "集";
        ep.year = show.year;

        applyMeta(ctx, item, ep);
        if (memo.poster != null) {
            try {
                MetadataStore.savePoster(ctx, item.name, memo.poster);
            } catch (Throwable ignored) {
            }
        }
        // tvshow.nfo 与剧集海报每剧每批次只写一次；分集 NFO 逐文件写
        writeSeriesFiles(item, show, memo);
        writeNfo(item, false, show, ep, null);

        st.put("status", "scraped");
        st.put("tmdbId", memo.tmdbId);
        st.put("title", ep.episodeTitle);
        st.put("year", show.year);
        st.put("kind", "tv");
        st.put("source", "series");
        if (memo.candidates != null && memo.candidates.length() > 0) st.put("candidates", memo.candidates);
        save(ctx, item.id, st);
        EmbyLog.info("videoinfo scraped: " + item.name + " -> tmdb " + memo.tmdbId + (memo.fromCache ? " (series cache)" : ""));
        return "scraped";
    }

    /** tvshow.nfo + 剧集根目录 poster.jpg 每批次只写一次。 */
    private static void writeSeriesFiles(Item item, NfoData show, SeriesMemo memo) {
        if (memo.seriesFilesWritten) return;
        try {
            if (item.path == null || item.path.startsWith("content://")) {
                memo.seriesFilesWritten = true;
                return;
            }
            File parent = new File(item.path).getParentFile();
            if (parent == null || !parent.canWrite()) {
                memo.seriesFilesWritten = true;
                return;
            }
            File seriesDir = parent;
            if (isSeasonDir(parent.getName())) {
                File up = parent.getParentFile();
                if (up != null && up.canWrite()) seriesDir = up;
            }
            writeFile(new File(seriesDir, "tvshow.nfo"), NfoWriter.write(show).getBytes("UTF-8"));
            if (memo.poster != null) writeFile(new File(seriesDir, "poster.jpg"), memo.poster);
        } catch (Throwable t) {
            EmbyLog.info("videoinfo series nfo 落盘失败: " + t);
        }
        memo.seriesFilesWritten = true;
    }

    /* ==================== 详情抓取与落盘（电影 + 人工确认共用） ==================== */

    /** 唯一命中/人工选定后的完整刮削：detail → NfoData → 覆盖层 + 海报 + NFO。st 需已含 fingerprint。 */
    private static String scrape(Context ctx, Item item, int tmdbId, boolean movie, JSONObject st) {
        try {
            String apiKey = Settings.getTmdbKey(ctx);
            pace();
            JSONObject detail = TmdbClient.detail(apiKey, movie, tmdbId);
            if (detail == null) return failNetwork(ctx, item, st);
            NfoData show = showFromDetail(movie, tmdbId, detail);
            NfoData ep = null;
            if (!movie && item.isEpisode()) {
                ep = new NfoData();
                ep.type = "episode";
                ep.tmdbId = show.tmdbId;
                ep.title = show.title;
                ep.season = item.seasonNumber;
                ep.episode = item.episodeNumber;
                ep.rating = show.rating;
                ep.genres.addAll(show.genres);
                pace();
                JSONObject seasonData = seasonCached(ctx, apiKey, tmdbId, item.seasonNumber, false);
                JSONArray eps = seasonData == null ? null : seasonData.optJSONArray("episodes");
                for (int i = 0; eps != null && i < eps.length(); i++) {
                    JSONObject e = eps.optJSONObject(i);
                    if (e == null || e.optInt("episode_number", -1) != item.episodeNumber) continue;
                    ep.episodeTitle = e.optString("name");
                    ep.plot = e.optString("overview");
                    ep.premiereDate = e.optString("air_date");
                    ep.year = yearOf(ep.premiereDate);
                    break;
                }
                if (ep.episodeTitle == null || ep.episodeTitle.isEmpty())
                    ep.episodeTitle = show.title + " 第" + item.episodeNumber + "集";
                ep.year = show.year;
            }

            NfoData applied = ep != null ? ep : show;
            applyMeta(ctx, item, applied);

            byte[] poster = null;
            try {
                pace();
                String posterPath = detail.optString("poster_path");
                if (!posterPath.isEmpty()) poster = TmdbClient.image(posterPath);
                if (poster != null && poster.length > 0) MetadataStore.savePoster(ctx, item.name, poster);
            } catch (Throwable t) {
                EmbyLog.info("videoinfo 海报失败: " + item.name);
            }

            writeNfo(item, movie, show, ep, poster);

            st.put("status", "scraped");
            st.put("tmdbId", tmdbId);
            st.put("title", applied.title);
            st.put("year", applied.year);
            st.put("kind", movie ? "movie" : "tv");
            save(ctx, item.id, st);
            EmbyLog.info("videoinfo scraped: " + item.name + " -> tmdb " + tmdbId);
            return "scraped";
        } catch (Throwable t) {
            EmbyLog.info("videoinfo scrape error: " + t);
            try {
                st.put("status", "error");
                st.put("attemptedAt", System.currentTimeMillis());
                save(ctx, item.id, st);
            } catch (Throwable ignored) {
            }
            return "error";
        }
    }

    private static NfoData showFromDetail(boolean movie, int tmdbId, JSONObject detail) {
        NfoData show = new NfoData();
        show.tmdbId = String.valueOf(tmdbId);
        if (movie) {
            show.type = "movie";
            show.title = detail.optString("title");
            show.plot = detail.optString("overview");
            show.premiereDate = detail.optString("release_date");
            show.rating = detail.optDouble("vote_average", 0.0);
            collectNames(detail.optJSONArray("genres"), show.genres);
            collectNames(detail.optJSONArray("production_companies"), show.studios);
        } else {
            show.type = "tvshow";
            show.title = detail.optString("name");
            show.plot = detail.optString("overview");
            show.premiereDate = detail.optString("first_air_date");
            show.rating = detail.optDouble("vote_average", 0.0);
            collectNames(detail.optJSONArray("genres"), show.genres);
            collectNames(detail.optJSONArray("networks"), show.studios);
        }
        show.year = yearOf(show.premiereDate);
        collectCast(detail.optJSONObject("credits"), show.people);
        return show;
    }

    private static String failNetwork(Context ctx, Item item, JSONObject st) {
        try {
            st.put("status", "network_error");
            st.put("error", String.valueOf(DanmuNetwork.ERROR.get()));
            save(ctx, item.id, st);
        } catch (Throwable ignored) {
        }
        EmbyLog.info("videoinfo network 失败: " + item.name);
        return "network_error";
    }

    /* ==================== 人工确认 / 纠错 ==================== */

    /**
     * 人工选定候选（待确认确认 / 换一个 / 重新搜索后的选择）。
     * all 用于剧集继承：同剧其他待确认分集跟随本次选择。locked=true 后任何自动模式不覆盖。
     */
    public static synchronized boolean pickCandidate(Context ctx, List<Item> all, Item item, int tmdbId) {
        try {
            if (item == null || item.id == null || item.identityAmbiguous || tmdbId <= 0) return false;
            boolean tv = item.isEpisode();
            JSONObject st = state(ctx, item);
            // 候选来源：条目状态 → 剧集级缓存
            JSONArray candidates = st.optJSONArray("candidates");
            if (candidates == null && tv) {
                candidates = seriesState(ctx, seriesKey(item)).optJSONArray("candidates");
            }
            boolean found = false;
            for (int i = 0; candidates != null && i < candidates.length(); i++) {
                JSONObject c = candidates.optJSONObject(i);
                if (c != null && c.optInt("tmdbId", -1) == tmdbId) { found = true; break; }
            }
            if (!found) return false;

            st.put("status", "scraping");
            st.put("tmdbId", tmdbId);
            st.put("locked", true);
            st.put("attemptedAt", System.currentTimeMillis());
            if (!store(ctx).edit().putString(item.id, st.toString()).commit()) return false;

            final boolean fTv = tv;
            final List<Item> snapshot = all == null ? new ArrayList<Item>() : new ArrayList<Item>(all);
            QUEUE.execute(() -> {
                try {
                    progress = "正在刮削：" + item.name;
                    JSONObject fresh = state(ctx, item);
                    String status = scrape(ctx, item, tmdbId, fTv, fresh);
                    doneCount = 0;
                    totalCount = 1;
                    progress = "已处理 1 · 刮削成功 " + ("scraped".equals(status) ? 1 : 0)
                            + " · 失败 " + ("scraped".equals(status) ? 0 : 1);
                    if ("scraped".equals(status) && fTv && item.isEpisode()) {
                        inheritToSeries(ctx, snapshot, item, tmdbId);
                    }
                } catch (Throwable t) {
                    EmbyLog.info("videoinfo pick scrape error: " + t);
                }
            });
            return true;
        } catch (Throwable t) {
            EmbyLog.info("videoinfo pick error: " + t);
            return false;
        }
    }

    /** 人工选择反写剧集缓存并广播给同剧其他待确认分集。 */
    private static void inheritToSeries(Context ctx, List<Item> all, Item chosen, int tmdbId) {
        try {
            String key = seriesKey(chosen);
            JSONObject sc = seriesState(ctx, key);
            if (!sc.has("fingerprint") || sc.optInt("tmdbId") != tmdbId || !"scraped".equals(sc.optString("status"))) {
                // 缓存里没有该剧的 show 数据：用本次详情补齐
                JSONObject detail = TmdbClient.detail(Settings.getTmdbKey(ctx), false, tmdbId);
                if (detail == null) return;
                NfoData show = showFromDetail(false, tmdbId, detail);
                JSONObject st = new JSONObject();
                st.put("status", "scraped");
                st.put("tmdbId", tmdbId);
                st.put("show", nfoToJson(show));
                byte[] poster = null;
                String posterPath = detail.optString("poster_path");
                if (!posterPath.isEmpty()) poster = TmdbClient.image(posterPath);
                if (poster != null) {
                    try { MetadataStore.savePoster(ctx, seriesPosterKey(key), poster); } catch (Throwable ignored) { }
                }
                saveSeries(ctx, key, st);
            }
            for (Item sibling : all) {
                if (sibling == null || sibling.id == null || sibling.id.equals(chosen.id)) continue;
                if (!sibling.isEpisode() || !key.equals(seriesKey(sibling))) continue;
                JSONObject sst = state(ctx, sibling);
                if (sst.optBoolean("locked")) continue;
                String ss = sst.optString("status");
                if (!"needs_confirmation".equals(ss) && !"unmatched".equals(ss) && !"network_error".equals(ss)) continue;
                JSONObject fresh = new JSONObject();
                fresh.put("fingerprint", fingerprint(ctx, sibling));
                fresh.put("attemptedAt", System.currentTimeMillis());
                fresh.put("seriesKey", key);
                fresh.put("locked", true);
                applyEpisodeFromSeries(ctx, sibling, fresh, seriesMemoFromCache(ctx, key, tmdbId));
            }
        } catch (Throwable t) {
            EmbyLog.info("videoinfo inherit error: " + t);
        }
    }

    /** 从剧集缓存构建 memo（供继承广播使用），缓存缺失时拉一次详情。 */
    private static SeriesMemo seriesMemoFromCache(Context ctx, String key, int tmdbId) {
        SeriesMemo memo = new SeriesMemo();
        JSONObject sc = seriesState(ctx, key);
        NfoData show = nfoFromJson(sc.optJSONObject("show"));
        if (show == null) {
            JSONObject detail = TmdbClient.detail(Settings.getTmdbKey(ctx), false, tmdbId);
            show = detail == null ? null : showFromDetail(false, tmdbId, detail);
        }
        memo.state = "resolved";
        memo.tmdbId = tmdbId;
        memo.show = show;
        byte[] poster = MetadataStore.posterBytes(ctx, seriesPosterKey(key));
        if (poster != null) memo.poster = poster;
        return memo;
    }

    /** 手动搜索（重新搜索纠错）：用用户输入的关键词搜 TMDB，返回候选 JSON，不做任何写入。 */
    public static JSONObject manualSearch(Context ctx, Item item, String query) {
        try {
            String apiKey = Settings.getTmdbKey(ctx);
            if (apiKey.trim().isEmpty() || query == null || query.trim().isEmpty()) return null;
            boolean movie = !item.isEpisode();
            pace();
            JSONObject resp = TmdbClient.search(apiKey, movie, query.trim(), 0);
            if (resp == null) return null;
            JSONArray results = resp.optJSONArray("results");
            JSONArray out = new JSONArray();
            for (int i = 0; results != null && i < results.length() && out.length() < 8; i++) {
                JSONObject cand = results.optJSONObject(i);
                if (cand == null || cand.optInt("id", 0) <= 0) continue;
                String title = movie ? cand.optString("title") : cand.optString("name");
                String original = movie ? cand.optString("original_title") : cand.optString("original_name");
                String date = movie ? cand.optString("release_date") : cand.optString("first_air_date");
                out.put(candidateJson(cand.optInt("id"), title, original, yearOf(date), cand.optString("overview")));
            }
            JSONObject result = new JSONObject();
            result.put("results", out);
            return result;
        } catch (Throwable t) {
            EmbyLog.info("videoinfo manual search error: " + t);
            return null;
        }
    }

    /** 清除该条刮削结果（状态 + 分集 NFO + 覆盖层），回到未刮削。 */
    public static boolean clearResult(Context ctx, Item item) {
        try {
            if (item == null || item.id == null) return false;
            store(ctx).edit().remove(item.id).commit();
            if (item.path != null && !item.path.startsWith("content://")) {
                File vf = new File(item.path);
                File parent = vf.getParentFile();
                if (vf.isFile() && parent != null && parent.canWrite()) {
                    String base = baseName(vf.getName());
                    new File(parent, base + ".nfo").delete();
                    if (!item.isEpisode()) {
                        new File(parent, "movie.nfo").delete();
                        new File(parent, "poster.jpg").delete();
                    }
                }
            }
            MetadataStore.remove(ctx, item.name);
            EmbyLog.info("videoinfo cleared: " + item.name);
            return true;
        } catch (Throwable t) {
            EmbyLog.info("videoinfo clear error: " + t);
            return false;
        }
    }

    private static JSONObject candidateJson(int id, String title, String original, int year, String overview) {
        JSONObject c = new JSONObject();
        try {
            c.put("tmdbId", id);
            c.put("title", title == null || title.isEmpty() ? original : title);
            c.put("year", year);
            String ov = overview == null ? "" : overview;
            if (ov.length() > 100) ov = ov.substring(0, 100);
            c.put("overview", ov);
        } catch (Throwable ignored) {
        }
        return c;
    }

    /* ==================== 本地 NFO 优先 ==================== */

    /** 本地 NFO 优先：同目录 <视频名去扩展名>.nfo；movie 另查 movie.nfo；tv 另查 tvshow.nfo（父目录或剧集目录）。 */
    private static boolean tryLocalNfo(Context ctx, Item item, JSONObject st) {
        try {
            if (item.path == null || item.path.startsWith("content://")) return false;
            File vf = new File(item.path);
            File parent = vf.getParentFile();
            if (!vf.isFile() || parent == null) return false;
            String base = baseName(vf.getName());
            boolean tv = item.isEpisode();
            List<File> nfos = new ArrayList<File>();
            nfos.add(new File(parent, base + ".nfo"));
            if (tv) {
                nfos.add(new File(parent, "tvshow.nfo"));
                if (isSeasonDir(parent.getName())) {
                    File up = parent.getParentFile();
                    if (up != null) nfos.add(new File(up, "tvshow.nfo"));
                }
            } else {
                nfos.add(new File(parent, "movie.nfo"));
            }
            for (File f : nfos) {
                if (f == null || !f.isFile() || f.length() == 0 || f.length() > 2L * 1024 * 1024) continue;
                byte[] raw = readFileBytes(f);
                if (raw == null) continue;
                String xml = new String(raw, "UTF-8");
                if (!xml.contains("<")) continue;
                NfoData d;
                try {
                    d = NfoParser.parse(xml);
                } catch (Exception e) {
                    continue;
                }
                if (d == null) continue;
                applyMeta(ctx, item, d);
                File seriesDir = tv && isSeasonDir(parent.getName()) ? parent.getParentFile() : null;
                importLocalPoster(ctx, item, parent, seriesDir);
                st.put("status", "local_nfo");
                st.put("source", f.getName());
                st.put("title", d.title);
                st.put("year", d.year);
                st.put("kind", tv ? "tv" : "movie");
                save(ctx, item.id, st);
                EmbyLog.info("videoinfo local nfo: " + item.name + " <- " + f.getName());
                return true;
            }
        } catch (Throwable t) {
            EmbyLog.info("videoinfo local nfo error: " + t);
        }
        return false;
    }

    /* ==================== 元数据应用与落盘 ==================== */

    private static void applyMeta(Context ctx, Item item, NfoData d) {
        try {
            JSONObject body = new JSONObject();
            body.put("file", item.name);
            String title = "episode".equals(d.type) && d.episodeTitle != null && !d.episodeTitle.isEmpty()
                    ? d.episodeTitle : d.title;
            body.put("title", title);
            body.put("plot", d.plot);
            body.put("year", d.year);
            body.put("rating", d.rating);
            body.put("premiereDate", d.premiereDate);
            body.put("genres", new JSONArray(d.genres));
            JSONArray studios = new JSONArray();
            if (d.studios != null) for (String s : d.studios) studios.put(new JSONObject().put("Name", s));
            body.put("studios", studios);
            JSONArray people = new JSONArray();
            if (d.people != null) for (NfoData.Person p : d.people) {
                people.put(new JSONObject()
                        .put("Name", p.name == null ? "" : p.name)
                        .put("Role", p.role == null ? "" : p.role)
                        .put("Type", p.type == null ? "Actor" : p.type));
            }
            body.put("people", people);
            MetadataStore.apply(ctx, body);
        } catch (Throwable t) {
            EmbyLog.info("videoinfo 元数据写入失败: " + item.name + ": " + t);
        }
    }

    private static void importLocalPoster(Context ctx, Item item, File... dirs) {
        try {
            for (File dir : dirs) {
                if (dir == null) continue;
                File p = new File(dir, "poster.jpg");
                if (!p.isFile() || p.length() == 0 || p.length() > 8L * 1024 * 1024) continue;
                byte[] data = readFileBytes(p);
                if (data != null && data.length > 0) {
                    MetadataStore.savePoster(ctx, item.name, data);
                    return;
                }
            }
        } catch (Throwable t) {
            EmbyLog.info("videoinfo 本地海报导入失败: " + item.name);
        }
    }

    /** NFO/海报落盘（电影与人工确认路径）：仅本地可写目录。 */
    private static void writeNfo(Item item, boolean movie, NfoData show, NfoData ep, byte[] poster) {
        try {
            if (item.path == null || item.path.startsWith("content://")) return;
            File vf = new File(item.path);
            File parent = vf.getParentFile();
            if (parent == null || !parent.canWrite()) return;
            String base = baseName(vf.getName());
            if (movie) {
                writeFile(new File(parent, base + ".nfo"), NfoWriter.write(show).getBytes("UTF-8"));
                if (poster != null) writeFile(new File(parent, "poster.jpg"), poster);
            } else {
                File seriesDir = parent;
                if (isSeasonDir(parent.getName())) {
                    File up = parent.getParentFile();
                    if (up != null && up.canWrite()) seriesDir = up;
                }
                writeFile(new File(seriesDir, "tvshow.nfo"), NfoWriter.write(show).getBytes("UTF-8"));
                if (poster != null) writeFile(new File(seriesDir, "poster.jpg"), poster);
                if (ep != null) writeFile(new File(parent, base + ".nfo"), NfoWriter.write(ep).getBytes("UTF-8"));
            }
        } catch (Throwable t) {
            EmbyLog.info("videoinfo nfo 落盘失败: " + t);
        }
    }

    /** 父目录名是否为季目录：Season xx / 第x季（含中文数字）/ Specials / S xx。 */
    private static boolean isSeasonDir(String name) {
        if (name == null) return false;
        String n = name.trim();
        if (n.equalsIgnoreCase("Specials")) return true;
        return n.matches("(?i)season[ ._]?\\d{1,3}")
                || n.matches("第\\s*[0-9零〇一二两三四五六七八九十百]+\\s*[季部]")
                || n.matches("(?i)s[ ._]?\\d{1,3}");
    }

    /** (tmdbId, season) 分集信息持久化缓存；FULL/强制刷新时走网络更新。 */
    private static JSONObject seasonCached(Context ctx, String apiKey, int tmdbId, int season, boolean forceRefresh) {
        String key = tmdbId + ":" + season;
        if (!forceRefresh) {
            String cached = seasonStore(ctx).getString(key, null);
            if (cached != null) {
                try {
                    return new JSONObject(cached).optJSONObject("data");
                } catch (Throwable ignored) {
                }
            }
        }
        JSONObject data = TmdbClient.season(apiKey, tmdbId, season);
        if (data != null) {
            try {
                JSONObject wrap = new JSONObject();
                wrap.put("fetchedAt", System.currentTimeMillis());
                wrap.put("data", data);
                seasonStore(ctx).edit().putString(key, wrap.toString()).commit();
            } catch (Throwable ignored) {
            }
        }
        return data;
    }

    private static void collectNames(JSONArray arr, List<String> out) {
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String n = o.optString("name");
            if (!n.isEmpty()) out.add(n);
        }
    }

    private static void collectCast(JSONObject credits, List<NfoData.Person> out) {
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        for (int i = 0; cast != null && i < cast.length() && out.size() < 20; i++) {
            JSONObject o = cast.optJSONObject(i);
            if (o == null) continue;
            NfoData.Person p = new NfoData.Person();
            p.name = o.optString("name");
            p.role = o.optString("character");
            p.type = "Actor";
            if (!p.name.isEmpty()) out.add(p);
        }
    }

    private static int yearOf(String date) {
        if (date == null || date.length() < 4) return 0;
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String baseName(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static byte[] readFileBytes(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean writeFile(File f, byte[] data) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(data);
            } finally {
                out.close();
            }
            return true;
        } catch (Throwable t) {
            EmbyLog.info("videoinfo 写入失败: " + f.getName());
            return false;
        }
    }

    private static void save(Context ctx, String itemId, JSONObject st) {
        try {
            store(ctx).edit().putString(itemId, st.toString()).commit();
        } catch (Throwable t) {
            EmbyLog.info("videoinfo state 保存失败: " + t);
        }
    }

    /** 每次 TMDB 请求间隔 300ms，避免触发限流。 */
    private static void pace() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
