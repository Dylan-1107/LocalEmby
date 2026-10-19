package com.local.embylite;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本地视频库扫描与缓存。
 * folderPath 非空且目录存在 → 递归遍历目录；否则走 MediaStore（folderPath 非空时按前缀过滤）。
 * folderPath 变化后需调用 {@link #invalidate()}。
 */
public class VideoLibrary {

    private static final Set<String> VIDEO_EXTS = new HashSet<String>();

    static {
        Collections.addAll(VIDEO_EXTS,
                "mp4", "mkv", "avi", "ts", "m2ts", "mov", "flv", "wmv", "webm", "m4v",
                "mpg", "mpeg", "3gp", "rmvb", "rm", "vob", "wtv");
    }

    private static volatile List<Item> cache;

    private VideoLibrary() {
    }

    public static List<Item> get(Context ctx, String folderPath) {
        List<Item> c = cache;
        if (c == null) {
            synchronized (VideoLibrary.class) {
                if (cache == null) cache = scanAuto(ctx, folderPath);
                c = cache;
            }
        }
        return c;
    }

    /** 扫描入口：全盘权限 > SAF 目录授权 > 手动路径/MediaStore。 */
    public static List<Item> scanAuto(Context ctx, String folderPath) {
        if (hasAllFilesAccess() && (Settings.getTreeUri(ctx) == null || Settings.getTreeUri(ctx).isEmpty())) {
            List<Item> all = scanWholeStorage();
            if (!all.isEmpty()) return all;
            EmbyLog.info("全盘扫描为空，回退 MediaStore");
        }
        String tree = Settings.getTreeUri(ctx);
        if (tree != null && !tree.isEmpty()) {
            List<Item> out = scanSaf(ctx, tree);
            if (!out.isEmpty()) return out;
            EmbyLog.info("SAF 扫描为空，回退手动路径/MediaStore");
        }
        return scan(ctx, folderPath);
    }

    /** 是否已获得「所有文件访问权限」（Android 11+）。 */
    public static boolean hasAllFilesAccess() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                return android.os.Environment.isExternalStorageManager();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 直接遍历全盘（需要 MANAGE_EXTERNAL_STORAGE），覆盖 MediaStore 未索引的目录。 */
    public static List<Item> scanWholeStorage() {
        List<Item> out = new ArrayList<Item>();
        List<File> roots = new ArrayList<File>();
        try {
            File primary = android.os.Environment.getExternalStorageDirectory();
            if (primary != null && primary.isDirectory()) roots.add(primary);
            File storage = new File("/storage");
            File[] vols = storage.listFiles();
            if (vols != null) {
                for (File v : vols) {
                    if (!v.isDirectory()) continue;
                    String n = v.getName();
                    if (n.equals("emulated") || n.equals("self") || n.equals("sdcard0")) continue;
                    if (primary != null && v.getAbsolutePath().equals(primary.getAbsolutePath())) continue;
                    roots.add(v);
                }
            }
        } catch (Throwable t) {
            EmbyLog.info("枚举存储卷失败: " + t);
        }
        for (File r : roots) {
            walkDeep(r, out, 0);
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return String.CASE_INSENSITIVE_ORDER.compare(nz(a.name), nz(b.name));
            }
        });
        EmbyLog.info("全盘扫描完成，共 " + out.size() + " 部视频");
        return out;
    }

    private static void walkDeep(File dir, List<Item> out, int depth) {
        if (depth > 8) return;
        File[] files;
        try {
            files = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (n.equalsIgnoreCase("Android") || n.equalsIgnoreCase("obb")) continue;
                walkDeep(f, out, depth + 1);
            } else if (isVideo(n)) {
                try {
                    if (f.length() < 1024) continue; // 跳过空文件/占位文件
                } catch (Throwable ignored) {
                }
                out.add(new Item(f.getAbsolutePath(), stripExt(n), ext(n), f.length(), 0));
            }
        }
    }

    public static void invalidate() {
        cache = null;
    }

    public static Item findById(Context ctx, String folderPath, String id) {
        if (id == null) return null;
        for (Item it : get(ctx, folderPath)) {
            if (id.equals(it.id)) return it;
        }
        return null;
    }

    public static List<Item> scan(Context ctx, String folderPath) {
        List<Item> out = new ArrayList<Item>();
        String fp = normalize(folderPath);
        boolean hasFolder = fp != null && !fp.isEmpty();
        File dir = hasFolder ? new File(fp) : null;
        if (dir != null && dir.isDirectory()) {
            walk(dir, out);
        } else {
            if (hasFolder) {
                EmbyLog.info("路径不可读，按前缀过滤 MediaStore: " + fp);
            }
            scanMediaStore(ctx, out, hasFolder ? fp : null);
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return String.CASE_INSENSITIVE_ORDER.compare(nz(a.name), nz(b.name));
            }
        });
        return out;
    }

    /** SAF 扫描：基于用户通过系统选择器授权的目录树，无需存储权限。 */
    public static List<Item> scanSaf(Context ctx, String treeUriStr) {
        List<Item> out = new ArrayList<Item>();
        try {
            android.net.Uri tree = android.net.Uri.parse(treeUriStr);
            String rootId = android.provider.DocumentsContract.getTreeDocumentId(tree);
            android.net.Uri rootDoc =
                    android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
            walkSaf(ctx, tree, rootDoc, out, 0);
        } catch (Throwable t) {
            EmbyLog.info("SAF 扫描失败: " + t);
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return String.CASE_INSENSITIVE_ORDER.compare(nz(a.name), nz(b.name));
            }
        });
        return out;
    }

    private static void walkSaf(Context ctx, android.net.Uri tree, android.net.Uri dirUri,
                                List<Item> out, int depth) {
        if (depth > 5) return;
        Cursor c = null;
        try {
            String dirId = android.provider.DocumentsContract.getDocumentId(dirUri);
            android.net.Uri children =
                    android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId);
            c = ctx.getContentResolver().query(children, new String[]{
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                    android.provider.DocumentsContract.Document.COLUMN_SIZE
            }, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String docId = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.getLong(3);
                if (name == null) continue;
                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    android.net.Uri sub = android.provider.DocumentsContract
                            .buildDocumentUriUsingTree(tree, docId);
                    walkSaf(ctx, tree, sub, out, depth + 1);
                } else if (isVideo(name)) {
                    android.net.Uri doc = android.provider.DocumentsContract
                            .buildDocumentUriUsingTree(tree, docId);
                    out.add(new Item(doc.toString(), stripExt(name), ext(name), size, 0));
                }
            }
        } catch (Throwable t) {
            EmbyLog.info("SAF 遍历失败: " + t);
        } finally {
            if (c != null) try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 路径归一化：/sdcard → /storage/emulated/0；相对路径补全；去尾部斜杠。 */
    public static String normalize(String p) {
        if (p == null) return null;
        String s = p.trim().replace('\\', '/');
        if (s.isEmpty()) return "";
        if (!s.startsWith("/")) {
            s = "/storage/emulated/0/" + s;
        } else if (s.equalsIgnoreCase("/sdcard")) {
            s = "/storage/emulated/0";
        } else if (s.toLowerCase(Locale.US).startsWith("/sdcard/")) {
            s = "/storage/emulated/0/" + s.substring(8);
        }
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static void walk(File dir, List<Item> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                walk(f, out);
            } else if (f.isFile() && isVideo(f.getName())) {
                String ext = ext(f.getName());
                out.add(new Item(f.getAbsolutePath(), stripExt(f.getName()), ext,
                        f.length(), probeDuration(f.getAbsolutePath())));
            }
        }
    }

    private static void scanMediaStore(Context ctx, List<Item> out, String folderPrefix) {
        String prefix = null;
        if (folderPrefix != null) {
            prefix = folderPrefix.replace('\\', '/');
            if (!prefix.endsWith("/")) prefix = prefix + "/";
        }
        String prefixLower = prefix == null ? null : prefix.toLowerCase(Locale.US);
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{
                            MediaStore.Video.Media.DATA,
                            MediaStore.Video.Media.DISPLAY_NAME,
                            MediaStore.Video.Media.SIZE,
                            MediaStore.Video.Media.DURATION
                    },
                    null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String data = c.getString(0);
                if (data == null || data.isEmpty()) continue;
                String norm = data.replace('\\', '/');
                if (prefixLower != null
                        && !norm.toLowerCase(Locale.US).startsWith(prefixLower)) continue;
                String name = c.getString(1);
                if (name == null || name.isEmpty()) name = new File(data).getName();
                if (!isVideo(name)) continue;
                long size = c.getLong(2);
                long dur = c.getLong(3);
                out.add(new Item(data, stripExt(name), ext(name), size, dur));
            }
        } catch (Throwable t) {
            EmbyLog.info("MediaStore scan failed: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {
            }
        }
    }

    /** 文件遍历时提取时长；失败给 0。 */
    private static long probeDuration(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) return Long.parseLong(d.trim());
        } catch (Throwable t) {
            // setDataSource 可能失败
        } finally {
            try { r.release(); } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private static boolean isVideo(String fileName) {
        String e = ext(fileName);
        return !e.isEmpty() && VIDEO_EXTS.contains(e);
    }

    private static String ext(String name) {
        int i = name == null ? -1 : name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(Locale.US);
    }

    private static String stripExt(String name) {
        int i = name == null ? -1 : name.lastIndexOf('.');
        if (i <= 0) return name == null ? "" : name;
        return name.substring(0, i);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
