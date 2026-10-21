package com.local.embylite;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Emby 协议 API 层。路径已由 EmbyHttpServer 剥掉 /emby 前缀并小写匹配。
 * ticks 单位：100ns，ticks = ms * 10000。
 */
public class EmbyApi {

    public static final String SERVER_ID = "localemby00000001";
    public static final String ACCESS_TOKEN = "localtoken123";
    public static final String USER_ID = "user1";

    private static final String EMPTY_LIST =
            "{\"Items\":[],\"TotalRecordCount\":0,\"StartIndex\":0}";
    private static final String DISPLAY_PREFS =
            "{\"Id\":\"usersettings\",\"Client\":\"\",\"UserId\":\"\",\"PageType\":\"\","
                    + "\"SortBy\":\"SortName\",\"SortOrder\":\"Ascending\",\"IndexBy\":null,"
                    + "\"CustomPrefs\":{},\"RememberIndexes\":false}";
    private static final String BRAND_CONFIG =
            "{\"SplashscreenEnabled\":false,\"CustomCss\":\"\"}";

    private static final Pattern P_USERS = Pattern.compile("^/users/([^/]+)$");
    private static final Pattern P_VIEWS = Pattern.compile("^/users/([^/]+)/views$");
    private static final Pattern P_VIEWS_SUB = Pattern.compile("^/users/([^/]+)/views/([^/]+)$");
    private static final Pattern P_RESUME = Pattern.compile("^/users/([^/]+)/items/resume$");
    private static final Pattern P_ITEMS = Pattern.compile("^/users/([^/]+)/items$");
    private static final Pattern P_LATEST = Pattern.compile("^/users/([^/]+)/items/latest$");
    private static final Pattern P_PLAYED = Pattern.compile("^/users/([^/]+)/playeditems/([^/]+)$");
    private static final Pattern P_USER_ITEM = Pattern.compile("^/users/([^/]+)/items/([^/]+)$");
    private static final Pattern P_ITEM = Pattern.compile("^/items/([^/]+)$");
    private static final Pattern P_ITEM_SUB =
            Pattern.compile("^/(?:users/[^/]+/items|items)/[^/]+/(intros|localtrailers|similar)$");
    private static final Pattern P_SHOWS = Pattern.compile("^/shows/([^/]+)/(seasons|episodes)$");
    private static final Pattern P_PLAYBACK_INFO =
            Pattern.compile("^/(?:users/[^/]+/items|items)/([^/]+)/playbackinfo$");

    private EmbyApi() {
    }

    /** 请求上下文。 */
    public static class Req {
        public String method;
        public String path;
        public Map<String, String> query;
        public Map<String, String> headers;
        public String body;
        public Context ctx;
    }

    /** 通用响应。 */
    public static class Resp {
        public int code = 200;
        public String contentType = "application/json; charset=utf-8";
        public byte[] bytes = new byte[0];
        public String extraHeaders = "";

        public static Resp json(String s) {
            return json(200, s);
        }

        public static Resp json(int code, String s) {
            Resp r = new Resp();
            r.code = code;
            // org.json 会把 0.0 序列化成 0，Dart 客户端按 double 解析会抛类型异常，
            // 这里强制把已知的浮点字段补成小数形式。
            r.bytes = normalizeNumbers(s == null ? "{}" : s).getBytes(StandardCharsets.UTF_8);
            return r;
        }

        /** 保证浮点字段带小数点（Emby 客户端通常用 double 强转解析）。 */
        private static String normalizeNumbers(String s) {
            s = s.replaceAll("\"(PlayedPercentage|CommunityRating|CriticRating|" +
                    "PrimaryImageAspectRatio)\":(-?\\d+)([,}])", "\"$1\":$2.0$3");
            return s;
        }

        public static Resp empty(int code) {
            Resp r = new Resp();
            r.code = code;
            r.bytes = new byte[0];
            return r;
        }

        public static Resp bytes(int code, String contentType, byte[] data) {
            Resp r = new Resp();
            r.code = code;
            r.contentType = contentType;
            r.bytes = data == null ? new byte[0] : data;
            return r;
        }
    }

    public static Resp handle(Req req) {
        try {
            return route(req);
        } catch (Throwable t) {
            EmbyLog.info("api error: " + t);
            String detail = String.valueOf(t).replace("\"", "'").replace("\n", " ");
            return Resp.json(500, "{\"error\":\"internal\",\"detail\":\"" + detail + "\"}");
        }
    }

    private static Resp route(Req req) {
        Context ctx = req.ctx;
        String m = req.method == null ? "GET" : req.method.toUpperCase(Locale.US);
        String p = req.path == null ? "/" : req.path;
        String lp = p.toLowerCase(Locale.US);

        if (m.equals("OPTIONS")) return Resp.empty(200);
        if (lp.equals("/emby") || lp.equals("/health")) return Resp.json("{}");

        // System
        if (m.equals("GET") && lp.equals("/system/info/public")) return Resp.json(systemInfo(false));
        if (m.equals("GET") && lp.equals("/system/info")) return Resp.json(systemInfo(true));

        // Auth
        if (m.equals("POST") && lp.equals("/users/authenticatebyname")) {
            return Resp.json(authenticate(req.body));
        }

        Matcher mm;

        // PlaybackInfo：客户端点播放前的必经接口，返回可直接播放的 MediaSource
        mm = P_PLAYBACK_INFO.matcher(lp);
        if (mm.matches()) {
            Item it = VideoLibrary.findById(ctx, Settings.getFolderPath(ctx), mm.group(1));
            if (it == null) return notFound(p);
            return Resp.json(playbackInfo(it, ctx).toString());
        }

        mm = P_VIEWS.matcher(lp);
        if (mm.matches()) return Resp.json(viewsJson(ctx));

        mm = P_RESUME.matcher(lp);
        if (mm.matches() && m.equals("GET")) return Resp.json(resumeJson(ctx, req));

        mm = P_LATEST.matcher(lp);
        if (mm.matches() && m.equals("GET")) return Resp.json(itemsJson(ctx, req));

        mm = P_ITEMS.matcher(lp);
        if (mm.matches() && m.equals("GET")) return Resp.json(itemsJson(ctx, req));

        mm = P_PLAYED.matcher(lp);
        if (mm.matches()) {
            boolean mark = !m.equals("DELETE");
            ProgressDb.get(ctx).setPlayed(mm.group(2), mark);
            return Resp.empty(204);
        }

        mm = P_ITEM_SUB.matcher(lp);
        if (mm.matches()) return Resp.json(EMPTY_LIST);

        mm = P_USER_ITEM.matcher(lp);
        if (mm.matches()) {
            Item it = VideoLibrary.findById(ctx, Settings.getFolderPath(ctx), mm.group(2));
            if (it == null) {
                JSONObject folder = virtualItem(ctx, mm.group(mm.groupCount()));
                return folder == null ? notFound(p) : Resp.json(folder.toString());
            }
            return Resp.json(itemJson(it, ctx).toString());
        }

        mm = P_USERS.matcher(lp);
        if (mm.matches()) return Resp.json(userJson());

        mm = P_VIEWS_SUB.matcher(lp);
        if (mm.matches()) return Resp.json(viewJson(ctx).toString());

        mm = P_ITEM.matcher(lp);
        if (mm.matches()) {
            Item it = VideoLibrary.findById(ctx, Settings.getFolderPath(ctx), mm.group(1));
            if (it == null) {
                JSONObject folder = virtualItem(ctx, mm.group(mm.groupCount()));
                return folder == null ? notFound(p) : Resp.json(folder.toString());
            }
            return Resp.json(itemJson(it, ctx).toString());
        }

        mm = P_SHOWS.matcher(lp);
        if (mm.matches()) return Resp.json(showItems(ctx, req, mm.group(1), mm.group(2)));
        if (lp.equals("/shows/nextup")) return Resp.json(nextUpJson(ctx, req));
        if (lp.equals("/items")) return Resp.json(itemsJson(ctx, req));

        // 筛选器聚合端点：无元数据，统一返回空列表
        if (lp.equals("/genres") || lp.equals("/years") || lp.equals("/studios")
                || lp.equals("/tags") || lp.equals("/officialratings")
                || lp.equals("/persons") || lp.equals("/musicgenres")) {
            return Resp.json(EMPTY_LIST);
        }

        // Sessions/Playing*
        if (lp.startsWith("/sessions/playing")) {
            return sessionsPlaying(req, ctx, lp, p);
        }

        // DisplayPreferences
        if (lp.equals("/displaypreferences") || lp.startsWith("/displaypreferences/")) {
            if (m.equals("GET")) return Resp.json(DISPLAY_PREFS);
            return Resp.empty(204);
        }

        // Branding
        if (lp.equals("/branding/configuration")) return Resp.json(BRAND_CONFIG);
        if (lp.equals("/branding/css")) {
            Resp r = Resp.bytes(200, "text/css; charset=utf-8", new byte[0]);
            return r;
        }

        return notFound(p);
    }

    private static Resp sessionsPlaying(Req req, Context ctx, String lp, String p) {
        JSONObject b = bodyJson(req);
        String itemId = b.optString("ItemId", "").trim();
        if (itemId.isEmpty()) itemId = nz(req.query.get("ItemId")).trim();
        long pos = b.optLong("PositionTicks", 0L);
        long dur = b.optLong("RunTimeTicks", 0L);
        if (dur <= 0) {
            Item media = VideoLibrary.findById(ctx, Settings.getFolderPath(ctx), itemId);
            if (media != null) dur = media.durationMs * 10000L;
        }
        boolean paused = b.optBoolean("IsPaused", false);

        String tail = lp.equals("/sessions/playing") ? "" : lp.substring("/sessions/playing".length());
        if (tail.isEmpty() || tail.equals("/")) {
            ProgressDb.get(ctx).onStart(itemId, dur);
        } else if (tail.equals("/progress") || tail.equals("/progress/")) {
            ProgressDb.get(ctx).onProgress(itemId, pos, dur, paused);
        } else if (tail.equals("/stopped") || tail.equals("/stopped/")) {
            ProgressDb.get(ctx).onStop(itemId, pos, dur);
        } else {
            return notFound(p);
        }
        return Resp.empty(204);
    }

    // ---------- JSON 构造 ----------

    private static String systemInfo(boolean full) {
        JSONObject j = obj();
        put(j, "Id", SERVER_ID);
        put(j, "ServerId", SERVER_ID);
        put(j, "ServerName", "Embylite");
        put(j, "FriendlyName", "Embylite");
        put(j, "Version", "4.7.13.0");
        put(j, "ProductName", "Emby Server");
        put(j, "OperatingSystem", "Android");
        put(j, "OsName", "Android");
        put(j, "SystemUpdateLevel", "Release");
        put(j, "HasPendingRestart", false);
        put(j, "IsShuttingDown", false);
        put(j, "SupportsLibraryMonitor", false);
        put(j, "StartUpProgressCompleted", true);
        put(j, "CompletedInstallations", arr());
        String lanIp = NetworkUtils.getLanIp();
        String localAddress = lanIp == null
                ? "http://127.0.0.1:" + EmbyHttpServer.currentPort
                : "http://" + lanIp + ":" + EmbyHttpServer.currentPort;
        put(j, "LocalAddress", localAddress);
        if (full) {
            put(j, "AccessToken", ACCESS_TOKEN);
            put(j, "WebSocketPortNumber", 8920);
            put(j, "TranscodingJobs", arr());
            put(j, "CanSelfRestart", false);
            put(j, "CanLaunchWebBrowser", false);
            put(j, "ProgramDataPath", "/data");
            put(j, "ItemsByNamePath", "");
            put(j, "CachePath", "/data/cache");
            put(j, "LogPath", "/data/logs");
            put(j, "InternalMetadataPath", "/data/metadata");
        }
        return j.toString();
    }

    private static String authenticate(String body) {
        String name = "user";
        try {
            JSONObject b = new JSONObject(body == null || body.trim().isEmpty() ? "{}" : body);
            String u = b.optString("Username", "").trim();
            if (u.isEmpty()) u = b.optString("Password", "").trim(); // 极端容错
            if (!u.isEmpty()) name = u;
        } catch (JSONException ignored) {
        }
        return authResponse(name).toString();
    }

    private static JSONObject authResponse(String name) {
        JSONObject user = obj();
        put(user, "Id", USER_ID);
        put(user, "Name", name);
        put(user, "ServerId", SERVER_ID);
        put(user, "HasPassword", false);
        put(user, "LastLoginDate", JSONObject.NULL);
        put(user, "Configuration", obj());
        JSONObject policy = obj();
        put(policy, "IsAdministrator", true);
        put(policy, "EnableAllFolders", true);
        put(policy, "EnableAllDevices", true);
        put(policy, "EnableContentDownloading", true);
        put(policy, "SimultaneousStreamLimit", 0);
        put(policy, "InvalidLoginAttemptCount", 0);
        put(user, "Policy", policy);

        JSONObject session = obj();
        put(session, "UserId", USER_ID);
        put(session, "Id", "session1");
        put(session, "ServerId", SERVER_ID);
        put(session, "UserName", name);
        put(session, "Client", "emby");
        put(session, "Device", "device");
        put(session, "DeviceName", "Android");
        put(session, "ApplicationVersion", "4.7.13.0");
        put(session, "RemoteEndPoint", "127.0.0.1");
        put(session, "IsSupported", true);
        put(session, "PlayableMediaTypes", arr());
        put(session, "SupportedCommands", arr());
        put(session, "SupportsRemoteControl", false);
        put(session, "LastActivityDate", "2026-01-01T00:00:00.0000000Z");

        JSONObject out = obj();
        put(out, "User", user);
        put(out, "SessionInfo", session);
        put(out, "AccessToken", ACCESS_TOKEN);
        // Emby 的 AuthenticationResult 顶层必须带 ServerId，部分客户端（YAmby）缺了会直接反序列化失败
        put(out, "ServerId", SERVER_ID);
        put(out, "Channels", arr());
        put(out, "AutoLogin", false);
        return out;
    }

    private static String userJson() {
        return authResponse("user").optJSONObject("User").toString();
    }

    private static JSONObject viewJson(Context ctx) {
        int n = VideoLibrary.get(ctx, Settings.getFolderPath(ctx)).size();
        JSONObject v = obj();
        put(v, "Id", "viewall");
        put(v, "Name", "全部视频");
        put(v, "ServerId", SERVER_ID);
        put(v, "Type", "CollectionFolder");
        put(v, "CollectionType", "mixed");
        put(v, "MediaType", "Video");
        put(v, "UserData", emptyUserData());
        put(v, "ChildCount", n);
        put(v, "PrimaryImageAspectRatio", 0.6666);
        JSONObject tags = obj();
        put(tags, "Primary", "thumb");
        put(v, "ImageTags", tags);
        put(v, "BackdropImageTags", arr());
        put(v, "ImageBlurHashes", obj());
        put(v, "ParentId", "");
        put(v, "IsFolder", true);
        return v;
    }

    private static String viewsJson(Context ctx) {
        JSONObject out = obj();
        JSONArray items = arr();
        put(items, viewJson(ctx));
        put(out, "Items", items);
        put(out, "TotalRecordCount", 1);
        put(out, "StartIndex", 0);
        return out.toString();
    }

    private static JSONObject itemJson(Item it, Context ctx) {
        return itemJson(it, ctx, true);
    }

    /**
     * @param detailed true = 条目详情/PlaybackInfo（带 MediaSources）；false = 列表项（真实 Emby 列表不带流信息）
     */
    private static JSONObject itemJson(Item it, Context ctx, boolean detailed) {
        JSONObject u = ProgressDb.get(ctx).userData(it.id);
        long runTimeTicks = it.durationMs * 10000L;

        JSONArray streams = arr();
        JSONObject vs = obj();
        put(vs, "Index", 0);
        put(vs, "Type", "Video");
        put(vs, "Codec", "h264");
        put(vs, "CodecTag", "avc1");
        put(vs, "Language", "und");
        put(vs, "IsInterlaced", false);
        put(vs, "IsDefault", true);
        put(vs, "IsForced", false);
        put(vs, "IsExternal", false);
        put(vs, "Width", 1920);
        put(vs, "Height", 1080);
        put(vs, "BitRate", 8000000);
        put(vs, "BitDepth", 8);
        put(vs, "Profile", "High");
        put(vs, "Level", 41);
        put(vs, "PixelFormat", "yuv420p");
        put(vs, "RefFrames", 1);
        put(vs, "IsAVC", true);
        put(vs, "AspectRatio", "16:9");
        put(vs, "AverageFrameRate", 25.0);
        put(vs, "RealFrameRate", 25.0);
        put(vs, "VideoRange", "SDR");
        put(vs, "ColorSpace", "bt709");
        put(vs, "ColorTransfer", "bt709");
        put(vs, "ColorPrimaries", "bt709");
        put(vs, "DisplayTitle", "1080p H264");
        put(vs, "ExtendedDisplayTitle", "1080p H264");
        put(streams, vs);
        JSONObject as = obj();
        put(as, "Index", 1);
        put(as, "Type", "Audio");
        put(as, "Codec", "aac");
        put(as, "CodecTag", "mp4a");
        put(as, "Language", "und");
        put(as, "IsInterlaced", false);
        put(as, "IsDefault", true);
        put(as, "IsForced", false);
        put(as, "IsExternal", false);
        put(as, "Channels", 2);
        put(as, "ChannelLayout", "stereo");
        put(as, "SampleRate", 44100);
        put(as, "BitRate", 128000);
        put(as, "Profile", "LC");
        put(as, "DisplayTitle", "AAC - Stereo");
        put(as, "ExtendedDisplayTitle", "AAC - Stereo - Default");
        put(streams, as);

        JSONObject ms = obj();
        put(ms, "Id", it.id);
        put(ms, "Path", it.path);
        put(ms, "Container", it.container);
        put(ms, "Size", it.sizeBytes);
        put(ms, "Name", it.name);
        put(ms, "Protocol", "File");
        put(ms, "Type", "Default");
        put(ms, "IsRemote", false);
        put(ms, "IsInfiniteStream", false);
        put(ms, "ReadAtNativeFramerate", false);
        put(ms, "RequiresOpening", false);
        put(ms, "RequiresClosing", false);
        put(ms, "RequiresLooping", false);
        put(ms, "SupportsProbing", true);
        put(ms, "SupportsTranscoding", false);
        put(ms, "SupportsDirectPlay", true);
        put(ms, "SupportsDirectStream", true);
        put(ms, "RunTimeTicks", runTimeTicks);
        put(ms, "Bitrate", 8000000);
        put(ms, "DefaultAudioStreamIndex", 1);
        put(ms, "DefaultSubtitleStreamIndex", -1);
        put(ms, "VideoType", "VideoFile");
        put(ms, "Formats", arr());
        put(ms, "RequiredHttpHeaders", obj());
        put(ms, "MediaAttachments", arr());
        put(ms, "MediaStreams", streams);
        JSONArray sources = arr();
        put(sources, ms);

        JSONObject j = obj();
        put(j, "Name", it.name);
        put(j, "SortName", it.name);
        put(j, "Id", it.id);
        put(j, "Etag", it.id);
        put(j, "ServerId", SERVER_ID);
        put(j, "ParentId", "viewall");
        put(j, "Type", it.isEpisode() ? "Episode" : "Movie");
        put(j, "MediaType", "Video");
        put(j, "VideoType", "VideoFile");
        put(j, "LocationType", "FileSystem");
        put(j, "Path", it.path);
        put(j, "Container", it.container);
        put(j, "Size", it.sizeBytes);
        put(j, "RunTimeTicks", runTimeTicks);
        put(j, "IsFolder", false);
        put(j, "IsHD", true);
        put(j, "SupportsPlaying", true);
        put(j, "PlayAccess", "Full");
        put(j, "CanDownload", true);
        put(j, "CanDelete", false);
        put(j, "MediaSourceCount", 1);
        put(j, "PartCount", 1);
        put(j, "ProductionYear", JSONObject.NULL);
        put(j, "Genres", arr());
        put(j, "Tags", arr());
        put(j, "Studios", arr());
        put(j, "ProviderIds", obj());
        put(j, "PremiereDate", JSONObject.NULL);
        put(j, "DateCreated", "2026-01-01T00:00:00.0000000Z");
        put(j, "UserData", u);
        put(j, "PrimaryImageAspectRatio", 0.6666666666666666);
        JSONObject tags = obj();
        put(tags, "Primary", "thumb");
        put(j, "ImageTags", tags);
        put(j, "BackdropImageTags", arr());
        put(j, "ImageBlurHashes", obj());
        if (detailed) {
            put(j, "Chapters", arr());
            put(j, "MediaSources", sources);
            put(j, "MediaStreams", streams);
        }
        // 剧集信息：客户端（弹幕匹配/剧集聚合）依赖 SeriesName + 季集号
        if (it.isEpisode()) {
            put(j, "SeriesName", it.seriesName);
            put(j, "SeriesId", Item.idForPath("series:" + it.seriesName));
            if (it.seasonExplicit) {
                put(j, "SeasonId", Item.idForPath("season:" + it.seriesName + "#" + it.seasonNumber));
                put(j, "ParentIndexNumber", it.seasonNumber);
                put(j, "SeasonName", it.seasonNumber == 0 ? "特别篇" : "第 " + it.seasonNumber + " 季");
            }
            put(j, "IndexNumber", it.episodeNumber);
            put(j, "IndexNumberEnd", it.episodeNumber);
            put(j, "ParentId", seasonId(it));
            put(j, "SeasonId", seasonId(it));
            put(j, "SeriesPrimaryImageTag", "thumb");
            put(j, "EpisodeTitle", "第 " + it.episodeNumber + " 集");
        }
        applyMetadata(j, ctx, it.name, detailed);
        return j;
    }

    /** 用刮削元数据覆盖条目信息（按文件名匹配）。 */
    private static void applyMetadata(JSONObject j, Context ctx, String fileName, boolean detailed) {
        try {
            JSONObject m = MetadataStore.forName(ctx, fileName);
            if (m == null) return;
            String t = m.optString("title", "");
            if (!t.isEmpty()) j.put("Name", t);
            String plot = m.optString("plot", "");
            if (!plot.isEmpty() && detailed) j.put("Overview", plot);
            int y = m.optInt("year", 0);
            if (y > 0) j.put("ProductionYear", y);
            String pd = m.optString("premiereDate", "");
            if (!pd.isEmpty()) {
                j.put("PremiereDate", pd.length() == 10 ? pd + "T00:00:00.0000000Z" : pd);
            }
            double r = m.optDouble("rating", 0);
            if (r > 0) j.put("CommunityRating", r);
            JSONArray g = m.optJSONArray("genres");
            if (g != null && g.length() > 0) j.put("Genres", g);
            JSONArray st = m.optJSONArray("studios");
            if (st != null && st.length() > 0) {
                // Emby 的 Studios 是对象数组 [{"Name":..,"Id":..}]，不能是字符串数组
                JSONArray normStudios = arr();
                for (int i = 0; i < st.length(); i++) {
                    JSONObject src = st.optJSONObject(i);
                    String nm = src != null ? src.optString("Name", "") : st.optString(i, "");
                    if (nm == null || nm.isEmpty()) continue;
                    JSONObject studio = obj();
                    put(studio, "Name", nm);
                    put(studio, "Id", Item.idForPath("studio:" + nm));
                    put(normStudios, studio);
                }
                if (normStudios.length() > 0) j.put("Studios", normStudios);
            }
            JSONArray p = m.optJSONArray("people");
            if (p != null && p.length() > 0) {
                JSONArray norm = arr();
                for (int i = 0; i < p.length(); i++) {
                    JSONObject src = p.optJSONObject(i);
                    if (src == null) continue;
                    String nm = src.optString("Name", "");
                    if (nm.isEmpty()) continue;
                    JSONObject person = obj();
                    // Emby 规范要求 Person 带 Id，缺失时客户端可能解析失败
                    put(person, "Id", Item.idForPath("person:" + nm));
                    put(person, "Name", nm);
                    put(person, "Role", src.optString("Role", ""));
                    put(person, "Type", src.optString("Type", "Actor"));
                    put(person, "PrimaryImageTag", JSONObject.NULL);
                    put(norm, person);
                }
                if (norm.length() > 0) j.put("People", norm);
            }
        } catch (Throwable ignored) {
        }
    }

    /** PlaybackInfo 响应：告知客户端可直接播放（direct play），无需转码。 */
    private static JSONObject playbackInfo(Item it, Context ctx) {
        if (it.durationMs <= 0) {
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            try {
                if (it.path.startsWith("content://")) retriever.setDataSource(ctx, android.net.Uri.parse(it.path));
                else retriever.setDataSource(it.path);
                String duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (duration != null) it.durationMs = Long.parseLong(duration);
            } catch (Exception e) { EmbyLog.info("duration unavailable: " + it.name); }
            finally { try { retriever.release(); } catch (Exception ignored) { } }
        }
        JSONObject base = itemJson(it, ctx);
        JSONArray srcs = base.optJSONArray("MediaSources");
        if (srcs == null) srcs = arr();
        for (int i = 0; i < srcs.length(); i++) {
            JSONObject s = srcs.optJSONObject(i);
            if (s == null) continue;
            put(s, "Protocol", "File");
            put(s, "IsRemote", false);
            put(s, "SupportsTranscoding", false);
            put(s, "SupportsDirectPlay", true);
            put(s, "SupportsDirectStream", true);
            put(s, "SupportsProbing", true);
            put(s, "DirectStreamUrl", "/Videos/" + it.id + "/stream."
                    + it.container + "?Static=true&MediaSourceId=" + it.id);
        }
        JSONObject out = obj();
        put(out, "MediaSources", srcs);
        put(out, "PlaySessionId", "ps-" + it.id);
        put(out, "ErrorCode", JSONObject.NULL);
        put(out, "StatsEnabled", false);
        return out;
    }

    private static String seriesId(Item item) { return Item.idForPath("series:" + item.seriesName); }
    private static String seasonId(Item item) {
        return Item.idForPath("season:" + item.seriesName + "#" + (item.seasonExplicit ? item.seasonNumber : "unknown"));
    }
    private static List<Item> library(Context ctx) {
        return VideoLibrary.get(ctx, Settings.getFolderPath(ctx));
    }
    private static Comparator<Item> episodeOrder() {
        return Comparator.comparingInt((Item i) -> i.seasonExplicit && i.seasonNumber == 0 ? Integer.MAX_VALUE : i.seasonNumber)
                .thenComparingInt(i -> i.episodeNumber).thenComparing(i -> i.name);
    }
    public static Item imageRepresentative(Context ctx, String id) {
        for (Item item : library(ctx)) {
            if (item.isEpisode() && (seriesId(item).equals(id) || seasonId(item).equals(id))) return item;
        }
        return null;
    }
    private static JSONObject folderJson(Context ctx, Item representative, boolean season) {
        String id = season ? seasonId(representative) : seriesId(representative);
        JSONObject j = itemJson(representative, ctx, false);
        put(j, "Id", id); put(j, "Etag", id);
        put(j, "Name", season ? (representative.seasonExplicit ? (representative.seasonNumber == 0 ? "特别篇" : "第 " + representative.seasonNumber + " 季") : "未分季") : representative.seriesName);
        put(j, "SortName", j.optString("Name"));
        put(j, "Type", season ? "Season" : "Series"); put(j, "IsFolder", true);
        put(j, "ParentId", season ? seriesId(representative) : "viewall");
        put(j, "SupportsPlaying", true); put(j, "CanDownload", false);
        for (String key : new String[]{"Path", "Container", "Size", "RunTimeTicks", "MediaSources", "MediaStreams", "MediaSourceCount", "IndexNumberEnd", "EpisodeTitle", "SeasonId", "ParentIndexNumber"}) j.remove(key);
        if (season && representative.seasonExplicit) put(j, "IndexNumber", representative.seasonNumber);
        else j.remove("IndexNumber");
        if (!season) { j.remove("SeriesId"); j.remove("SeasonName"); j.remove("SeriesName"); }
        int total = 0, unplayed = 0; Set<String> seasons = new HashSet<>();
        for (Item item : library(ctx)) {
            if (!item.isEpisode() || !(season ? seasonId(item).equals(id) : seriesId(item).equals(id))) continue;
            total++; seasons.add(seasonId(item));
            if (!ProgressDb.get(ctx).userData(item.id).optBoolean("Played")) unplayed++;
        }
        JSONObject user = emptyUserData();
        put(user, "Played", total > 0 && unplayed == 0); put(user, "UnplayedItemCount", unplayed);
        put(j, "UserData", user); put(j, "ChildCount", season ? total : seasons.size());
        put(j, "RecursiveItemCount", total); put(j, "UnplayedItemCount", unplayed);
        put(j, "DisplayOrder", "Aired"); put(j, "Status", "Continuing");
        return j;
    }
    private static JSONObject virtualItem(Context ctx, String id) {
        if ("viewall".equals(id)) return viewJson(ctx);
        for (Item item : library(ctx)) {
            if (!item.isEpisode()) continue;
            if (seriesId(item).equals(id)) return folderJson(ctx, item, false);
            if (seasonId(item).equals(id)) return folderJson(ctx, item, true);
        }
        return null;
    }
    private static String resultPage(List<JSONObject> values, Req req) {
        int total = values.size();
        int start = Math.max(0, Math.min(total, parseInt(req.query.get("StartIndex"), 0)));
        int limit = Math.max(0, parseInt(req.query.get("Limit"), total));
        int end = (int)Math.min(total, (long)start + limit);
        JSONArray items = arr(); for (int i = start; i < end; i++) put(items, values.get(i));
        JSONObject result = obj(); put(result, "Items", items); put(result, "TotalRecordCount", total); put(result, "StartIndex", start);
        return result.toString();
    }
    private static String showItems(Context ctx, Req req, String series, String kind) {
        List<Item> episodes = new ArrayList<>();
        String season = req.query.get("SeasonId");
        Integer number = parseIntOrNull(req.query.get("Season"));
        for (Item item : library(ctx)) {
            if (!item.isEpisode() || !seriesId(item).equals(series)) continue;
            if (season != null && !season.isEmpty() && !seasonId(item).equals(season)) continue;
            if (number != null && (!item.seasonExplicit || item.seasonNumber != number)) continue;
            episodes.add(item);
        }
        Collections.sort(episodes, episodeOrder());
        List<JSONObject> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        boolean after = !req.query.containsKey("StartItemId");
        String adjacent = req.query.get("AdjacentTo");
        for (int i = 0; i < episodes.size(); i++) {
            Item item = episodes.get(i);
            if (kind.equals("seasons")) {
                if (seen.add(seasonId(item))) result.add(folderJson(ctx, item, true));
            } else {
                if (!after && item.id.equals(req.query.get("StartItemId"))) after = true;
                if (!after) continue;
                if (adjacent != null && !adjacent.isEmpty()) {
                    int center = -1;
                    for (int k = 0; k < episodes.size(); k++) if (episodes.get(k).id.equals(adjacent)) center = k;
                    if (center < 0 || Math.abs(i - center) > 1) continue;
                }
                if ("true".equalsIgnoreCase(req.query.get("IsMissing"))) continue;
                result.add(itemJson(item, ctx, false));
            }
        }
        return resultPage(result, req);
    }
    private static String nextUpJson(Context ctx, Req req) {
        Map<String, List<Item>> groups = new java.util.LinkedHashMap<>();
        for (Item item : library(ctx)) if (item.isEpisode()) groups.computeIfAbsent(seriesId(item), k -> new ArrayList<>()).add(item);
        Map<String, JSONObject> history = new HashMap<>();
        JSONArray raw = ProgressDb.get(ctx).rawEntries();
        for (int i = 0; i < raw.length(); i++) { JSONObject e = raw.optJSONObject(i); if (e != null) history.put(e.optString("itemId"), e); }
        List<JSONObject> result = new ArrayList<>();
        String wanted = req.query.get("SeriesId");
        for (Map.Entry<String, List<Item>> group : groups.entrySet()) {
            if (wanted != null && !wanted.isEmpty() && !wanted.equals(group.getKey())) continue;
            List<Item> episodes = group.getValue(); Collections.sort(episodes, episodeOrder());
            int latest = -1; long timestamp = 0;
            for (int i = 0; i < episodes.size(); i++) {
                JSONObject h = history.get(episodes.get(i).id);
                if (h != null && h.optLong("lastPlayedMs") >= timestamp && h.optLong("lastPlayedMs") > 0) {
                    latest = i; timestamp = h.optLong("lastPlayedMs");
                }
            }
            if (latest < 0) continue;
            Item last = episodes.get(latest); JSONObject h = history.get(last.id);
            int index = h.optBoolean("played") ? latest + 1 : latest;
            while (index < episodes.size()) {
                Item next = episodes.get(index++);
                // Do not silently jump between unknown seasons or into specials.
                if (next != last && (!seasonId(next).equals(seasonId(last))
                        && (!last.seasonExplicit || !next.seasonExplicit || next.seasonNumber == 0))) break;
                if (!ProgressDb.get(ctx).userData(next.id).optBoolean("Played")) {
                    JSONObject j = itemJson(next, ctx, false); put(j, "NextUpLastPlayedMs", timestamp); result.add(j); break;
                }
            }
        }
        result.sort((a,b) -> Long.compare(b.optLong("NextUpLastPlayedMs"), a.optLong("NextUpLastPlayedMs")));
        return resultPage(result, req);
    }
    private static String itemsJson(Context ctx, Req req) {
        String types = nz(req.query.get("IncludeItemTypes")).toLowerCase(Locale.ROOT);
        String parent = nz(req.query.get("ParentId"));
        String ids = nz(req.query.get("Ids"));
        boolean recursive = "true".equalsIgnoreCase(req.query.get("Recursive"));
        List<JSONObject> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        Set<String> wanted = new HashSet<>(java.util.Arrays.asList(ids.split(",")));
        boolean leaf = recursive || types.contains("episode") || types.contains("movie") || !ids.isEmpty();
        for (Item item : library(ctx)) {
            boolean episode = item.isEpisode();
            if (!ids.isEmpty()) {
                if (wanted.contains(item.id)) result.add(itemJson(item, ctx, false));
                if (episode && wanted.contains(seriesId(item)) && seen.add(seriesId(item))) result.add(folderJson(ctx, item, false));
                if (episode && wanted.contains(seasonId(item)) && seen.add(seasonId(item))) result.add(folderJson(ctx, item, true));
                continue;
            }
            if (types.equals("series")) {
                if (episode && (parent.isEmpty() || parent.equals("viewall")) && seen.add(seriesId(item))) result.add(folderJson(ctx, item, false));
            } else if (episode && parent.equals(seriesId(item)) && !leaf) {
                if (seen.add(seasonId(item))) result.add(folderJson(ctx, item, true));
            } else if (episode && (parent.equals(seasonId(item)) || (parent.equals(seriesId(item)) && leaf))) {
                result.add(itemJson(item, ctx, false));
            } else if (parent.isEmpty() || parent.equals("viewall")) {
                if (episode && !leaf) { if (seen.add(seriesId(item))) result.add(folderJson(ctx, item, false)); }
                else if (types.isEmpty() || (episode ? types.contains("episode") : types.contains("movie"))) result.add(itemJson(item, ctx, false));
            }
        }
        String search = nz(req.query.get("SearchTerm")).toLowerCase(Locale.ROOT);
        result.removeIf(j -> !j.optString("Name").toLowerCase(Locale.ROOT).contains(search));
        result.sort((a,b) -> {
            int season = Integer.compare(a.optInt("ParentIndexNumber", -1), b.optInt("ParentIndexNumber", -1));
            if (season != 0) return season;
            if (a.has("IndexNumber") && b.has("IndexNumber")) return Integer.compare(a.optInt("IndexNumber"), b.optInt("IndexNumber"));
            return a.optString("SortName").compareToIgnoreCase(b.optString("SortName"));
        });
        if ("Descending".equalsIgnoreCase(req.query.get("SortOrder"))) Collections.reverse(result);
        return resultPage(result, req);
    }

    private static String legacyItemsJson(Context ctx, Req req) {
        List<Item> list = new ArrayList<Item>(
                VideoLibrary.get(ctx, Settings.getFolderPath(ctx)));

        String ids = req.query.get("Ids");
        if (ids != null && !ids.trim().isEmpty()) {
            Set<String> want = new HashSet<String>();
            for (String s : ids.split(",")) {
                if (!s.trim().isEmpty()) want.add(s.trim());
            }
            List<Item> filtered = new ArrayList<Item>();
            for (Item it : list) {
                if (want.contains(it.id)) filtered.add(it);
            }
            list = filtered;
        }

        String st = req.query.get("SearchTerm");
        if (st != null && !st.trim().isEmpty()) {
            String needle = st.trim().toLowerCase(Locale.US);
            List<Item> filtered = new ArrayList<Item>();
            for (Item it : list) {
                if (it.name != null && it.name.toLowerCase(Locale.US).contains(needle)) {
                    filtered.add(it);
                }
            }
            list = filtered;
        }

        String sortBy = req.query.get("SortBy");
        if (sortBy == null || sortBy.trim().isEmpty()) sortBy = "SortName";
        String sl = sortBy.toLowerCase(Locale.US);
        Comparator<Item> cmp;
        if (sl.contains("dateplayed") || sl.contains("datecreated")) {
            cmp = new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            };
        } else {
            cmp = new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return String.CASE_INSENSITIVE_ORDER.compare(nz(a.name), nz(b.name));
                }
            };
        }
        if ("Descending".equalsIgnoreCase(req.query.get("SortOrder"))) {
            cmp = Collections.reverseOrder(cmp);
        }
        Collections.sort(list, cmp);

        int total = list.size();
        int start = parseInt(req.query.get("StartIndex"), 0);
        if (start < 0) start = 0;
        if (start > total) start = total;
        int end = total;
        Integer limit = parseIntOrNull(req.query.get("Limit"));
        if (limit != null && limit > 0) end = Math.min(total, start + limit);

        JSONArray items = arr();
        for (int i = start; i < end; i++) {
            put(items, itemJson(list.get(i), ctx));
        }
        JSONObject out = obj();
        put(out, "Items", items);
        put(out, "TotalRecordCount", total);
        put(out, "StartIndex", start);
        return out.toString();
    }

    private static String resumeJson(Context ctx, Req req) {
        JSONArray entries = ProgressDb.get(ctx).rawEntries();
        List<JSONObject> res = new ArrayList<JSONObject>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            if (e.optLong("positionTicks", 0L) > 0 && !e.optBoolean("played", false)) {
                res.add(e);
            }
        }
        Collections.sort(res, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("lastPlayedMs", 0L), a.optLong("lastPlayedMs", 0L));
            }
        });
        int limit = parseInt(req.query.get("Limit"), 20);
        if (limit <= 0) limit = 20;

        Map<String, Item> byId = new HashMap<String, Item>();
        for (Item it : VideoLibrary.get(ctx, Settings.getFolderPath(ctx))) {
            byId.put(it.id, it);
        }
        JSONArray items = arr();
        int count = 0;
        for (JSONObject e : res) {
            if (count >= limit) break;
            Item it = byId.get(e.optString("itemId"));
            if (it != null) {
                put(items, itemJson(it, ctx));
                count++;
            }
        }
        JSONObject out = obj();
        put(out, "Items", items);
        put(out, "TotalRecordCount", count);
        put(out, "StartIndex", 0);
        return out.toString();
    }

    // ---------- 工具 ----------

    private static Resp notFound(String path) {
        JSONObject o = obj();
        put(o, "error", "not implemented");
        put(o, "path", path);
        return Resp.json(404, o.toString());
    }

    /** body JSON 优先，缺字段时回退 query 参数。 */
    private static JSONObject bodyJson(Req req) {
        JSONObject b = new JSONObject();
        String body = req.body;
        if (body != null && !body.trim().isEmpty()) {
            try {
                b = new JSONObject(body);
            } catch (JSONException ignored) {
            }
        }
        try {
            if (!b.has("ItemId") && req.query.containsKey("ItemId")) {
                b.put("ItemId", nz(req.query.get("ItemId")));
            }
            if (!b.has("PositionTicks") && req.query.containsKey("PositionTicks")) {
                b.put("PositionTicks", parseLongSafe(req.query.get("PositionTicks")));
            }
            if (!b.has("RunTimeTicks") && req.query.containsKey("RunTimeTicks")) {
                b.put("RunTimeTicks", parseLongSafe(req.query.get("RunTimeTicks")));
            }
            if (!b.has("IsPaused") && req.query.containsKey("IsPaused")) {
                b.put("IsPaused", Boolean.parseBoolean(req.query.get("IsPaused")));
            }
        } catch (JSONException ignored) {
        }
        return b;
    }

    private static JSONObject obj() {
        return new JSONObject();
    }

    private static JSONArray arr() {
        return new JSONArray();
    }

    private static JSONObject put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v == null ? JSONObject.NULL : v);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    private static JSONArray put(JSONArray a, Object v) {
        a.put(v);
        return a;
    }

    private static JSONObject emptyUserData() {
        JSONObject u = obj();
        put(u, "PlaybackPositionTicks", 0L);
        put(u, "PlayCount", 0);
        put(u, "Played", false);
        put(u, "PlayedPercentage", 0.0);
        put(u, "IsFavorite", false);
        return u;
    }

    private static int parseInt(String s, int def) {
        Integer v = parseIntOrNull(s);
        return v == null ? def : v.intValue();
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseLongSafe(String s) {
        if (s == null || s.trim().isEmpty()) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
