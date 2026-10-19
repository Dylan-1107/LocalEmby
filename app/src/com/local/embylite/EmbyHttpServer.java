package com.local.embylite;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简原生 HTTP/1.1 服务器（ServerSocket + 线程池），仅依赖 Android 内置 API。
 * 路由前统一剥掉开头的 "/emby" 前缀；每个请求都记录到 EmbyLog。
 */
public class EmbyHttpServer {

    private static final int MAX_BODY = 1024 * 1024;

    /** 本次连接是否已开始写响应体（用于区分“客户端主动断开”和真实错误）。 */
    private static final ThreadLocal<Boolean> RESP_STARTED = new ThreadLocal<Boolean>();

    /** 当前监听端口（EmbyApi 读它拼 LocalAddress）。 */
    public static volatile int currentPort = 8096;
    /** 视频目录；null 时用 MediaStore 全库。宿主应在 start 前设置。 */
    public static volatile String libraryFolder = null;

    private static volatile ServerSocket serverSocket;
    private static volatile ExecutorService pool;
    private static volatile Context appContext;

    private static final Pattern P_STREAM = Pattern.compile(
            "^/videos/([^/]+)(?:/[^/]+)?/stream(\\.[^/]*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_IMAGE = Pattern.compile(
            "^/items/([^/]+)/images/([^/]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_DOWNLOAD = Pattern.compile(
            "^/items/([^/]+)/(download|file)$", Pattern.CASE_INSENSITIVE);

    private EmbyHttpServer() {
    }

    public static synchronized void start(Context ctx, int port) {
        appContext = ctx.getApplicationContext();
        libraryFolder = Settings.getFolderPath(appContext);
        if (isRunning() && currentPort == port) return;
        stopInternal();
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            // 绑定通配地址，兼容 IPv4/IPv6 回环及局域网访问
            ss.bind(new InetSocketAddress(port), 64);
            serverSocket = ss;
            currentPort = port;
            final ExecutorService ex = Executors.newCachedThreadPool();
            pool = ex;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    ServerSocket s = serverSocket;
                    while (s != null && !s.isClosed()) {
                        final Socket sock;
                        try {
                            sock = s.accept();
                        } catch (IOException e) {
                            break;
                        }
                        ex.execute(new Runnable() {
                            @Override
                            public void run() {
                                handleConnection(sock);
                            }
                        });
                    }
                }
            }, "EmbyLite-Accept");
            t.setDaemon(true);
            t.start();
            EmbyLog.info("EmbyLite started at http://127.0.0.1:" + port);
        } catch (IOException e) {
            Log.e("EmbyLite", "HTTP server bind failed on port " + port, e);
            EmbyLog.info("EmbyLite start failed on " + port + ": " + e.getClass().getName()
                    + ": " + e.getMessage());
        }
    }

    public static synchronized void stop() {
        stopInternal();
    }

    private static void stopInternal() {
        ServerSocket ss = serverSocket;
        serverSocket = null;
        if (ss != null) try { ss.close(); } catch (IOException ignored) {
        }
        ExecutorService ex = pool;
        pool = null;
        if (ex != null) ex.shutdownNow();
    }

    public static boolean isRunning() {
        ServerSocket ss = serverSocket;
        return ss != null && !ss.isClosed();
    }

    // ---------- 连接处理 ----------

    private static void handleConnection(Socket socket) {
        String method = "?";
        String fullPath = "/";
        int status = 500;
        BufferedOutputStream out = null;
        RESP_STARTED.set(Boolean.FALSE);
        try {
            socket.setSoTimeout(30000);
            socket.setTcpNoDelay(true);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.trim().isEmpty()) {
                try { socket.close(); } catch (IOException ignored) {
                }
                return;
            }
            String[] parts = requestLine.trim().split("\\s+");
            method = parts[0].toUpperCase(Locale.US);
            String target = parts.length > 1 ? parts[1] : "/";
            boolean head = method.equals("HEAD");

            Map<String, String> headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
            }

            int len = 0;
            String cl = headers.get("Content-Length");
            if (cl != null) {
                try { len = Integer.parseInt(cl.trim()); } catch (NumberFormatException ignored) {
                    len = 0;
                }
            }
            if (len < 0) len = 0;
            if (len > MAX_BODY) len = MAX_BODY;
            byte[] bodyBuf = new byte[len];
            int got = 0;
            while (got < len) {
                int r = in.read(bodyBuf, got, len - got);
                if (r < 0) break;
                got += r;
            }
            String body = new String(bodyBuf, 0, got, StandardCharsets.UTF_8);

            String rawPath = target;
            String rawQuery = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                rawPath = target.substring(0, q);
                rawQuery = target.substring(q + 1);
            }
            String path = URLDecoder.decode(rawPath, "UTF-8").replaceAll("/+", "/");
            Map<String, String> query = new LinkedHashMap<String, String>();
            if (!rawQuery.isEmpty()) {
                for (String pair : rawQuery.split("&")) {
                    if (pair.isEmpty()) continue;
                    int eq = pair.indexOf('=');
                    String k = eq >= 0 ? pair.substring(0, eq) : pair;
                    String v = eq >= 0 ? pair.substring(eq + 1) : "";
                    query.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
                }
            }
            // 剥掉客户端可能带的 /emby 前缀（裸 "/emby" 保留，交给 EmbyApi 当 health）
            while (path.toLowerCase(Locale.US).startsWith("/emby/")) {
                path = path.substring(5);
            }
            fullPath = path + (rawQuery.isEmpty() ? "" : "?" + rawQuery);

            EmbyApi.Req req = new EmbyApi.Req();
            req.method = method;
            req.path = path;
            req.query = query;
            req.headers = headers;
            req.body = body;
            req.ctx = appContext;

            status = route(out, req, path, head);
        } catch (Throwable t) {
            boolean started = Boolean.TRUE.equals(RESP_STARTED.get());
            if (started) {
                // 播放器拖进度/切集时会主动断开连接，属正常现象，不算错误
                EmbyLog.info("client aborted: " + t.getClass().getSimpleName());
                status = 499;
            } else {
                EmbyLog.info("request error: " + t.getClass().getName() + ": " + t.getMessage());
                try {
                    if (out != null) {
                        sendResp(out, EmbyApi.Resp.json(500,
                                "{\"error\":\"internal server error\"}"), false);
                    }
                } catch (Throwable ignored) {
                }
                status = 500;
            }
        } finally {
            EmbyLog.log(method, fullPath, status);
            try { socket.close(); } catch (IOException ignored) {
            }
        }
    }

    private static int route(OutputStream out, EmbyApi.Req req, String path, boolean head)
            throws IOException {
        String method = req.method;
        Context ctx = appContext;

        if (method.equals("OPTIONS")) {
            EmbyApi.Resp r = EmbyApi.Resp.empty(200);
            r.extraHeaders = "Allow: GET, POST, DELETE, OPTIONS, HEAD\r\n";
            sendResp(out, r, false);
            return 200;
        }

        // 调试页
        if (path.isEmpty() || path.equals("/")) {
            byte[] html = readAsset(ctx, "web/index.html");
            if (html == null) {
                sendResp(out, EmbyApi.Resp.json(404, "{\"error\":\"index.html missing\"}"), head);
                return 404;
            }
            sendResp(out, EmbyApi.Resp.bytes(200, "text/html; charset=utf-8", html), head);
            return 200;
        }

        if (method.equals("GET") && path.equalsIgnoreCase("/api/dev/log")) {
            JSONObject o = new JSONObject();
            try {
                o.put("entries", EmbyLog.toJson());
            } catch (Exception ignored) {
            }
            sendResp(out, EmbyApi.Resp.json(o.toString()), head);
            return 200;
        }

        // 元数据覆盖层管理：GET 查看 / POST 写入（{"items":[{file,title,plot,year,...,posterBase64}]}）
        if (path.equalsIgnoreCase("/api/dev/metadata")) {
            if (method.equals("POST")) {
                try {
                    JSONObject body = new JSONObject(req.body == null ? "{}" : req.body);
                    MetadataStore.apply(ctx, body);
                    sendResp(out, EmbyApi.Resp.empty(204), head);
                    return 204;
                } catch (Throwable t) {
                    EmbyLog.info("metadata apply failed: " + t);
                    sendResp(out, EmbyApi.Resp.json(500, "{\"error\":\"apply failed\"}"), head);
                    return 500;
                }
            }
            sendResp(out, EmbyApi.Resp.json(MetadataStore.raw(ctx).toString()), head);
            return 200;
        }

        // 弹幕：客户端请求 /api/danmu/{itemId}?option=GetJsonById，代理到弹弹play 兼容 API
        if (method.equals("GET") && path.toLowerCase(Locale.US).startsWith("/api/danmu/")) {
            String rest = path.substring("/api/danmu/".length());
            int slash = rest.indexOf('/');
            String id = slash >= 0 ? rest.substring(0, slash) : rest;
            Item dmItem = VideoLibrary.findById(ctx, Settings.getFolderPath(ctx), id);
            if (dmItem == null) {
                sendResp(out, EmbyApi.Resp.json(404, "{\"error\":\"item not found\"}"), head);
                return 404;
            }
            String payload = null;
            int epId = DanmuProxy.matchEpisode(ctx, dmItem.id, DanmuProxy.matchFileName(dmItem));
            if (epId > 0) payload = DanmuProxy.comments(ctx, epId);
            if (payload == null) payload = DanmuProxy.emptyPayload();
            EmbyLog.info("danmu " + dmItem.name + " episodeId=" + epId);
            sendResp(out, EmbyApi.Resp.json(200, payload), head);
            return 200;
        }

        // 视频流（支持 Range）
        Matcher m = P_STREAM.matcher(path);
        if (m.matches()) {
            Item item = VideoLibrary.findById(ctx, Settings.getFolderPath(ctx), m.group(1));
            if (item == null) {
                sendResp(out, notFoundJson(), head);
                return 404;
            }
            return streamVideo(out, req, item, true, head);
        }

        // 下载/原文件：全量 200
        m = P_DOWNLOAD.matcher(path);
        if (m.matches()) {
            Item item = VideoLibrary.findById(ctx, Settings.getFolderPath(ctx), m.group(1));
            if (item == null) {
                sendResp(out, notFoundJson(), head);
                return 404;
            }
            return streamVideo(out, req, item, false, head);
        }

        // 图片：刮削海报优先 → 视频抽帧 → 占位图
        m = P_IMAGE.matcher(path);
        if (m.matches()) {
            Item item = VideoLibrary.findById(ctx, Settings.getFolderPath(ctx), m.group(1));
            byte[] jpg = null;
            String metaKey = item == null ? m.group(1) : item.name;
            jpg = MetadataStore.posterBytes(ctx, metaKey);
            if (jpg == null && item != null) jpg = ThumbProvider.get(ctx, item);
            if (jpg == null) {
                jpg = ThumbProvider.placeholder(ctx, item == null ? "本地影院" : item.name);
            }
            sendResp(out, EmbyApi.Resp.bytes(200, "image/jpeg", jpg), head);
            return 200;
        }

        EmbyApi.Resp r = EmbyApi.handle(req);
        sendResp(out, r, head);
        return r.code;
    }

    // ---------- 视频流 ----------

    private static int streamVideo(OutputStream out, EmbyApi.Req req, Item item,
                                   boolean allowRange, boolean head) throws IOException {
        boolean isContent = item.path != null && item.path.startsWith("content:");
        File f = isContent ? null : new File(item.path);
        long total = isContent ? 0 : f.length();
        android.os.ParcelFileDescriptor pfd = null;
        if (isContent) {
            try {
                pfd = req.ctx.getContentResolver()
                        .openFileDescriptor(android.net.Uri.parse(item.path), "r");
            } catch (Throwable t) {
                EmbyLog.info("SAF 打开失败: " + t);
            }
            if (pfd == null) {
                sendResp(out, EmbyApi.Resp.json(404, "{\"error\":\"file missing\"}"), head);
                return 404;
            }
            total = pfd.getStatSize();
        }
        if (total <= 0) {
            if (pfd != null) try { pfd.close(); } catch (IOException ignored) {
            }
            sendResp(out, EmbyApi.Resp.json(404, "{\"error\":\"file missing\"}"), head);
            return 404;
        }
        long start = 0;
        long end = total - 1;
        boolean partial = false;

        String range = req.headers.get("Range");
        if (allowRange && range != null) {
            String rs = range.trim();
            if (rs.toLowerCase(Locale.US).startsWith("bytes=")) {
                String spec = rs.substring(6).trim();
                int dash = spec.indexOf('-');
                if (dash >= 0) {
                    String a = spec.substring(0, dash).trim();
                    String b = spec.substring(dash + 1).trim();
                    try {
                        if (a.isEmpty() && !b.isEmpty()) {
                            // bytes=-N：末尾 N 字节
                            long suffix = Long.parseLong(b);
                            start = Math.max(0, total - suffix);
                            end = total - 1;
                            partial = true;
                        } else if (!a.isEmpty()) {
                            start = Long.parseLong(a);
                            end = b.isEmpty() ? total - 1 : Math.min(Long.parseLong(b), total - 1);
                            if (start < total && end >= start) partial = true;
                        }
                    } catch (NumberFormatException nfe) {
                        partial = false;
                    }
                }
            }
        }

        if (partial && start >= total) {
            StringBuilder h = new StringBuilder(160);
            h.append("HTTP/1.1 416 Range Not Satisfiable\r\n");
            h.append("Content-Range: bytes */").append(total).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            h.append("Access-Control-Allow-Origin: *\r\n");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            return 416;
        }
        if (!partial) {
            start = 0;
            end = total - 1;
        }
        long length = end - start + 1;

        StringBuilder h = new StringBuilder(256);
        h.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
        h.append("Content-Type: ").append(contentTypeFor(item.container)).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Connection: close\r\n");
        if (partial) {
            h.append("Content-Range: bytes ").append(start).append('-')
                    .append(end).append('/').append(total).append("\r\n");
        }
        h.append("Content-Length: ").append(length).append("\r\n\r\n");
        RESP_STARTED.set(Boolean.TRUE);
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));

        if (!head) {
            try {
                FileInputStream fis = isContent
                        ? new FileInputStream(pfd.getFileDescriptor())
                        : new FileInputStream(f);
                try {
                    FileChannel ch = fis.getChannel();
                    ch.position(start);
                    byte[] buf = new byte[65536];
                    long remaining = length;
                    while (remaining > 0) {
                        int r = fis.read(buf, 0, (int) Math.min(buf.length, remaining));
                        if (r < 0) break;
                        out.write(buf, 0, r);
                        remaining -= r;
                    }
                } finally {
                    try { fis.close(); } catch (IOException ignored) {
                    }
                }
            } finally {
                if (pfd != null) try { pfd.close(); } catch (IOException ignored) {
                }
            }
        }
        out.flush();
        return partial ? 206 : 200;
    }

    private static String contentTypeFor(String container) {
        String c = container == null ? "" : container.toLowerCase(Locale.US);
        if (c.equals("mp4") || c.equals("m4v")) return "video/mp4";
        if (c.equals("mkv")) return "video/x-matroska";
        if (c.equals("ts") || c.equals("m2ts")) return "video/mp2t";
        if (c.equals("avi")) return "video/x-msvideo";
        if (c.equals("mov")) return "video/quicktime";
        if (c.equals("webm")) return "video/webm";
        return "application/octet-stream";
    }

    // ---------- 通用 IO ----------

    private static void sendResp(OutputStream out, EmbyApi.Resp r, boolean head) throws IOException {
        RESP_STARTED.set(Boolean.TRUE);
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(r.code).append(' ').append(reason(r.code)).append("\r\n");
        boolean noBody = r.code == 204 || r.code == 304;
        if (!noBody) {
            sb.append("Content-Type: ").append(r.contentType).append("\r\n");
            sb.append("Content-Length: ").append(r.bytes.length).append("\r\n");
        }
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Headers: Content-Type, Authorization, "
                + "X-Emby-Authorization, X-Requested-With, Accept\r\n");
        sb.append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS, HEAD\r\n");
        if (r.extraHeaders != null) sb.append(r.extraHeaders);
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        if (!head && !noBody && r.bytes.length > 0) out.write(r.bytes);
        out.flush();
    }

    private static EmbyApi.Resp notFoundJson() {
        return EmbyApi.Resp.json(404, "{\"error\":\"not found\"}");
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            case 500: return "Internal Server Error";
            default: return "Status";
        }
    }

    private static byte[] readAsset(Context ctx, String name) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(80);
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
        }
        if (!any) return null;
        return buf.toString("UTF-8");
    }
}
