package com.local.embylite;

import android.content.Context;
import android.content.SharedPreferences;

/** SharedPreferences（"embylite"）封装。空路径 = 扫描全手机视频。 */
public final class Settings {

    private static final String PREFS = "embylite";
    private static final String KEY_FOLDER = "folder_path";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_PORT = "port";
    private static final String KEY_SERVER_ENABLED = "server_enabled";
    private static final String KEY_DANMU_API = "danmu_api";
    private static final int DEFAULT_PORT = 8096;

    private Settings() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getFolderPath(Context ctx) {
        return prefs(ctx).getString(KEY_FOLDER, "");
    }

    public static void setFolderPath(Context ctx, String path) {
        prefs(ctx).edit().putString(KEY_FOLDER, path == null ? "" : path.trim()).apply();
    }

    /** SAF 目录授权 URI（选择文件夹后保存）；非空时优先于手动路径。 */
    public static String getTreeUri(Context ctx) {
        return prefs(ctx).getString(KEY_TREE_URI, "");
    }

    public static void setTreeUri(Context ctx, String uri) {
        prefs(ctx).edit().putString(KEY_TREE_URI, uri == null ? "" : uri.trim()).apply();
    }

    public static int getPort(Context ctx) {
        return prefs(ctx).getInt(KEY_PORT, DEFAULT_PORT);
    }

    public static void setPort(Context ctx, int port) {
        prefs(ctx).edit().putInt(KEY_PORT, port).apply();
    }

    public static boolean isServerEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SERVER_ENABLED, false);
    }

    public static void setServerEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_SERVER_ENABLED, enabled).apply();
    }

    /** 弹幕 API 地址（弹弹play 兼容），空则用内置默认值。 */
    public static String getDanmuApi(Context ctx) {
        return prefs(ctx).getString(KEY_DANMU_API, "");
    }

    public static void setDanmuApi(Context ctx, String url) {
        prefs(ctx).edit().putString(KEY_DANMU_API, url == null ? "" : url.trim()).apply();
    }
}
