package com.local.embylite;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 刮削元数据覆盖层：按文件名（去扩展名）匹配，覆盖条目标题/简介/年份等。
 * 存储文件：getExternalFilesDir(null)/metadata.json；海报：meta_posters/<key>.jpg
 */
public class MetadataStore {

    private static volatile JSONObject cache; // {"items":{ "<file>": {...} }}

    private MetadataStore() {
    }

    private static File storeFile(Context ctx) {
        return new File(ctx.getExternalFilesDir(null), "metadata.json");
    }

    public static synchronized JSONObject raw(Context ctx) {
        if (cache != null) return cache;
        JSONObject o = new JSONObject();
        try {
            File f = storeFile(ctx);
            if (f.exists() && f.length() > 0) {
                FileInputStream in = new FileInputStream(f);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                o = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            }
        } catch (Throwable ignored) {
        }
        cache = o;
        return o;
    }

    /** 批量写入：body = {"items":[{file,title,plot,year,genres[],rating,people[],posterBase64...}]} */
    public static synchronized void apply(Context ctx, JSONObject body) throws Exception {
        JSONObject root = raw(ctx);
        JSONObject items = root.optJSONObject("items");
        if (items == null) {
            items = new JSONObject();
            put(root, "items", items);
        }
        JSONArray arr = body.optJSONArray("items");
        if (arr == null && body.has("file")) {
            arr = new JSONArray();
            arr.put(body);
        }
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            String key = e.optString("file", "").trim();
            if (key.isEmpty()) continue;
            String b64 = e.optString("posterBase64", "");
            if (b64 != null && b64.length() > 100) {
                savePoster(ctx, key, Base64.decode(b64, Base64.DEFAULT));
                e.remove("posterBase64");
            }
            items.put(key, e);
        }
        save(ctx, root);
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void save(Context ctx, JSONObject root) throws Exception {
        File f = storeFile(ctx);
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(root.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
        cache = root;
    }

    /** 按文件名（去扩展名）取条目元数据；无则返回 null。 */
    public static JSONObject forName(Context ctx, String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        JSONObject items = raw(ctx).optJSONObject("items");
        if (items == null) return null;
        JSONObject e = items.optJSONObject(fileName);
        if (e == null) {
            // 宽松匹配：忽略大小写
            java.util.Iterator<String> it = items.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.equalsIgnoreCase(fileName)) return items.optJSONObject(k);
            }
        }
        return e;
    }

    /** 取海报 JPEG 字节；无则 null。 */
    public static byte[] posterBytes(Context ctx, String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        File f = posterFile(ctx, fileName);
        if (!f.exists() || f.length() == 0) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static File posterFile(Context ctx, String key) {
        File dir = new File(ctx.getExternalFilesDir(null), "meta_posters");
        // key 可能含路径非法字符，做一次安全化
        String safe = key.replaceAll("[^\\w\\u4e00-\\u9fa5.-]", "_");
        return new File(dir, safe + ".jpg");
    }

    public static void savePoster(Context ctx, String key, byte[] jpg) throws Exception {
        if (jpg == null || jpg.length == 0) return;
        File f = posterFile(ctx, key);
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(jpg);
        } finally {
            out.close();
        }
    }
}
