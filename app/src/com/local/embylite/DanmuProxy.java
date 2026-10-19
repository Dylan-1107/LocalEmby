package com.local.embylite;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONObject;

/**
 * 弹幕代理：把客户端对 /api/danmu/{id} 的请求转发到弹弹play 兼容的弹幕 API。
 * 流程：文件名 → /api/v2/match 得到 episodeId → /api/v2/comment/{episodeId} 取弹幕。
 */
public class DanmuProxy {

    public static final String DEFAULT_API = "https://danmu-api-gules-theta.vercel.app/87654321";

    /** itemId → episodeId 缓存，避免每次播放都重新匹配。 */
    private static final Map<String, Integer> MATCH_CACHE = new HashMap<String, Integer>();

    private DanmuProxy() {
    }

    public static String base(android.content.Context ctx) {
        String s = Settings.getDanmuApi(ctx);
        if (s == null || s.trim().isEmpty()) s = DEFAULT_API;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 按文件名匹配剧集，返回 episodeId；失败返回 -1。 */
    public static synchronized int matchEpisode(android.content.Context ctx, String itemId, String fileName) {
        Integer cached = MATCH_CACHE.get(itemId);
        if (cached != null) return cached;
        try {
            String body = "{\"fileName\":" + JSONObject.quote(fileName) + "}";
            String resp = post(base(ctx) + "/api/v2/match", body);
            if (resp == null) return -1;
            JSONObject o = new JSONObject(resp);
            if (!o.optBoolean("success", false) || !o.optBoolean("isMatched", false)) {
                EmbyLog.info("danmu match 未命中: " + fileName);
                return -1;
            }
            org.json.JSONArray ms = o.optJSONArray("matches");
            if (ms == null || ms.length() == 0) return -1;
            int ep = ms.optJSONObject(0).optInt("episodeId", -1);
            if (ep > 0) MATCH_CACHE.put(itemId, ep);
            return ep;
        } catch (Throwable t) {
            EmbyLog.info("danmu match 失败: " + t);
            return -1;
        }
    }

    /** 取弹幕 JSON（弹弹play 格式：{count, comments:[{p,m}]}）。 */
    public static String comments(android.content.Context ctx, int episodeId) {
        try {
            String url = base(ctx) + "/api/v2/comment/" + episodeId + "?withRelated=true&chConvert=1";
            String resp = get(url);
            if (resp == null || resp.trim().isEmpty()) return null;
            // 校验是合法 JSON，避免把错误页返回给客户端
            new JSONObject(resp);
            return resp;
        } catch (Throwable t) {
            EmbyLog.info("danmu comment 失败: " + t);
            return null;
        }
    }

    public static String emptyPayload() {
        return "{\"count\":0,\"comments\":[]}";
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "LocalEmby/1.0");
            if (c.getResponseCode() / 100 != 2) return null;
            return readAll(c.getInputStream());
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String post(String url, String body) {
        HttpURLConnection c = null;
        try {
            byte[] data = body.getBytes("UTF-8");
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "LocalEmby/1.0");
            c.setFixedLengthStreamingMode(data.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(data);
            } finally {
                os.close();
            }
            if (c.getResponseCode() / 100 != 2) return null;
            return readAll(c.getInputStream());
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 文件名用于匹配：优先 "剧名 S01E01.mkv" 形式。 */
    public static String matchFileName(Item it) {
        String ext = (it.container == null || it.container.isEmpty()) ? "mp4" : it.container;
        String base = it.name;
        if (it.isEpisode()) {
            // 规范化成弹幕源最容易识别的形式
            base = it.seriesName + " S" + String.format("%02d", it.seasonNumber)
                    + "E" + String.format("%02d", it.episodeNumber);
        }
        return base + "." + ext;
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }
}
