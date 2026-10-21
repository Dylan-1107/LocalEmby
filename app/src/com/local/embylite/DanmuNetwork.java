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
    private static final OkHttpClient BOOTSTRAP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .dns(host -> {
                if (host.equals("cloudflare-dns.com")) return Arrays.asList(InetAddress.getByAddress(new byte[]{1,1,1,1}), InetAddress.getByAddress(new byte[]{1,0,0,1}));
                return okhttp3.Dns.SYSTEM.lookup(host);
            }).build();
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS).dns(host -> {
                List<InetAddress> found = CACHE.get(host);
                if (found != null) return found;
                // Restrict alternative DNS to the configured service hosting domain.
                if (host.endsWith(".vercel.app")) {
                    try {
                        Request req = new Request.Builder().url("https://cloudflare-dns.com/dns-query?name=" + host + "&type=A")
                                .header("Accept", "application/dns-json").build();
                        try (Response res = BOOTSTRAP.newCall(req).execute()) {
                            if (res.isSuccessful() && res.body() != null) {
                                JSONArray answers = new JSONObject(res.body().string()).optJSONArray("Answer");
                                List<InetAddress> ips = new ArrayList<>();
                                if (answers != null) for (int i=0; i<answers.length(); i++) {
                                    JSONObject a=answers.optJSONObject(i);
                                    if (a != null && a.optInt("type") == 1 && a.optString("data").matches("[0-9.]+")) ips.add(InetAddress.getByName(a.getString("data")));
                                }
                                if (!ips.isEmpty()) { CACHE.put(host, ips); EmbyLog.info("danmu HTTPS DNS resolved " + host); return ips; }
                            }
                        }
                    } catch (Exception e) { EmbyLog.info("danmu HTTPS DNS: " + e.getClass().getSimpleName()); }
                }
                return okhttp3.Dns.SYSTEM.lookup(host);
            }).build();
    static String request(String url, String body) {
        ERROR.remove();
        try {
            Request.Builder builder = new Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "LocalEmby/2.5.2");
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
