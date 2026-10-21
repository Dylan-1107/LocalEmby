package com.local.embylite;

import okhttp3.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** Verified HTTPS DNS fallback; original hostname, SNI and certificate validation stay intact. */
final class DanmuNetwork {
    static final ThreadLocal<String> ERROR = new ThreadLocal<>();
    private static final Map<String,List<InetAddress>> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** DoH 供应商：[域名, 直连 IP...]，按顺序尝试。 */
    private static final String[][] DOH_PROVIDERS = {
            {"cloudflare-dns.com", "1.1.1.1", "1.0.0.1"},
            {"dns.google", "8.8.8.8", "8.8.4.4"}
    };

    /** 需要 DoH 兜底的域名：弹幕源托管域 + TMDB（国内 DNS 普遍污染，假 IP 导致刮削失败）。 */
    private static boolean needsDoh(String host) {
        return host.endsWith(".vercel.app")
                || host.equals("api.themoviedb.org") || host.equals("image.tmdb.org")
                || host.endsWith(".themoviedb.org");
    }

    private static List<InetAddress> pinned(String name) {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (String[] provider : DOH_PROVIDERS) {
                if (provider[0].equals(name)) {
                    for (int i = 1; i < provider.length; i++) {
                        out.add(InetAddress.getByAddress(ipBytes(provider[i])));
                    }
                    return out;
                }
            }
        } catch (Exception ignored) {
        }
        return out.isEmpty() ? null : out;
    }

    private static byte[] ipBytes(String ip) {
        String[] parts = ip.split("\\.");
        byte[] out = new byte[4];
        for (int i = 0; i < 4 && i < parts.length; i++) out[i] = (byte) Integer.parseInt(parts[i]);
        return out;
    }

    /** 只用于访问 DoH 供应商自身的引导客户端：供应商域名走固定 IP，不查系统 DNS。 */
    private static final OkHttpClient BOOTSTRAP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .dns(host -> {
                List<InetAddress> pinnedIps = pinned(host);
                if (pinnedIps != null) return pinnedIps;
                return okhttp3.Dns.SYSTEM.lookup(host);
            }).build();

    /** 智能解析：先查缓存 → 需要兜底的域名走 DoH（两个供应商依次尝试，失败再退系统 DNS）→ 其余走系统 DNS。 */
    static final okhttp3.Dns SMART = host -> {
        List<InetAddress> found = CACHE.get(host);
        if (found != null) return found;
        if (needsDoh(host)) {
            for (String[] provider : DOH_PROVIDERS) {
                try {
                    Request req = new Request.Builder()
                            .url("https://" + provider[0] + "/dns-query?name=" + host + "&type=A")
                            .header("Accept", "application/dns-json").build();
                    try (Response res = BOOTSTRAP.newCall(req).execute()) {
                        if (res.isSuccessful() && res.body() != null) {
                            JSONArray answers = new JSONObject(res.body().string()).optJSONArray("Answer");
                            List<InetAddress> ips = new ArrayList<>();
                            if (answers != null) for (int i = 0; i < answers.length(); i++) {
                                JSONObject a = answers.optJSONObject(i);
                                if (a != null && a.optInt("type") == 1 && a.optString("data").matches("[0-9.]+")) {
                                    try {
                                        ips.add(InetAddress.getByName(a.getString("data")));
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                            if (!ips.isEmpty()) {
                                CACHE.put(host, ips);
                                EmbyLog.info("danmu HTTPS DNS resolved " + host + " via " + provider[0]);
                                return ips;
                            }
                        }
                    }
                } catch (Exception e) {
                    EmbyLog.info("danmu HTTPS DNS " + provider[0] + ": " + e.getClass().getSimpleName());
                }
            }
        }
        return okhttp3.Dns.SYSTEM.lookup(host);
    };

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS).dns(SMART).build();

    static String request(String url, String body) {
        ERROR.remove();
        try {
            Request.Builder builder = new Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "Embylite/2.7.0");
            if (body != null) builder.post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body));
            try (Response response = CLIENT.newCall(builder.build()).execute()) {
                if (!response.isSuccessful()) { ERROR.set("HTTP " + response.code()); return null; }
                if (response.body() == null) { ERROR.set("empty_response"); return null; }
                return response.body().string();
            }
        } catch (Exception e) {
            ERROR.set(e.getClass().getSimpleName() + ": " + e.getMessage());
            EmbyLog.info("danmu network " + ERROR.get());
            return null;
        }
    }
}
