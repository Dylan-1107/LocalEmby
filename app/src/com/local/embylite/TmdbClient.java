package com.local.embylite;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * TMDB API v3 客户端：搜索 / 详情 / 分集 / 海报下载。
 * JSON 请求复用 DanmuNetwork.request(url, null)（GET，超时用其内建配置）；
 * 图片是二进制，DanmuNetwork 只回字符串，所以用独立 OkHttp 下载。
 */
public final class TmdbClient {

    private static final String BASE = "https://api.themoviedb.org/3";
    private static final String IMAGE_BASE = "https://image.tmdb.org/t/p/w500";
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    /** 图片下载专用客户端：connect 6s / read 10s / call 20s；DNS 复用 DanmuNetwork 的 DoH 兜底。 */
    private static final OkHttpClient IMAGE_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .dns(DanmuNetwork.SMART)
            .build();

    private TmdbClient() {
    }

    /** 搜索。movie=true 用 /search/movie，否则 /search/tv。返回整个响应 JSONObject（含 results 数组），失败 null。 */
    public static JSONObject search(String apiKey, boolean movie, String query, int year) {
        if (empty(apiKey)) return null;
        StringBuilder url = new StringBuilder(BASE)
                .append(movie ? "/search/movie" : "/search/tv")
                .append("?api_key=").append(enc(apiKey))
                .append("&language=zh-CN")
                .append("&query=").append(enc(query));
        if (year > 0) url.append(movie ? "&year=" : "&first_air_date_year=").append(year);
        return get(url.toString());
    }

    /** 详情。movie=true /movie/{id}，否则 /tv/{id}，都追加 credits。 */
    public static JSONObject detail(String apiKey, boolean movie, int tmdbId) {
        if (empty(apiKey)) return null;
        return get(BASE + (movie ? "/movie/" : "/tv/") + tmdbId
                + "?api_key=" + enc(apiKey) + "&language=zh-CN&append_to_response=credits");
    }

    /** /tv/{id}/season/{n}，剧集级 NFO 用的分集信息。 */
    public static JSONObject season(String apiKey, int tmdbId, int season) {
        if (empty(apiKey)) return null;
        return get(BASE + "/tv/" + tmdbId + "/season/" + season
                + "?api_key=" + enc(apiKey) + "&language=zh-CN");
    }

    /** 下载海报字节（w500），posterPath 形如 "/abc.jpg"。失败返回 null。 */
    public static byte[] image(String posterPath) {
        if (posterPath == null || posterPath.isEmpty()) return null;
        try (Response res = IMAGE_CLIENT.newCall(new Request.Builder()
                .url(IMAGE_BASE + posterPath)
                .header("User-Agent", "Embylite/2.6.0")
                .build()).execute()) {
            if (!res.isSuccessful() || res.body() == null) return null;
            String type = res.header("Content-Type", "");
            if (type == null || !type.startsWith("image/")) return null;
            if (res.body().contentLength() > MAX_IMAGE_BYTES) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = res.body().byteStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_IMAGE_BYTES) return null;
                }
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            EmbyLog.info("tmdb image error: " + t);
            return null;
        }
    }

    /** GET 请求：网络错误返回 null（DanmuNetwork.ERROR 有错误信息），JSON 解析失败也返回 null。 */
    private static JSONObject get(String url) {
        String resp = DanmuNetwork.request(url, null);
        if (resp == null) return null;
        try {
            return new JSONObject(resp);
        } catch (Throwable t) {
            EmbyLog.info("tmdb 响应解析失败: " + t);
            return null;
        }
    }

    private static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return s == null ? "" : s;
        }
    }
}
