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
    private static final String KEY_TMDB_API = "tmdb_api";
    private static final String KEY_AUTO_SCRAPE = "auto_scrape";
    private static final String KEY_ROOT_KEEP_ALIVE = "root_keep_alive";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final int DEFAULT_PORT = 8096;
    /** 内置默认 TMDB Key（非商业使用 + 署名，用户可在设置中替换为自己的）。 */
    private static final String DEFAULT_TMDB_KEY = "2c60f014a303792a0b27a2c4c224d4c5";

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

    /** TMDB API Key（视频信息刮削用）；默认内置公共 Key，开箱即用，可在设置里替换。 */
    public static String getTmdbKey(Context ctx) {
        return prefs(ctx).getString(KEY_TMDB_API, DEFAULT_TMDB_KEY);
    }

    public static void setTmdbKey(Context ctx, String key) {
        prefs(ctx).edit().putString(KEY_TMDB_API, key == null ? "" : key.trim()).apply();
    }

    /** 扫描后自动刮削 TMDB 元数据（默认开启）。 */
    public static boolean isAutoScrape(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO_SCRAPE, true);
    }

    public static void setAutoScrape(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_SCRAPE, enabled).apply();
    }

    /** Root 保活是否开启（仅在用户显式开启后才会执行 su 操作）。 */
    public static boolean isRootKeepAlive(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ROOT_KEEP_ALIVE, false);
    }

    public static void setRootKeepAlive(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ROOT_KEEP_ALIVE, enabled).apply();
    }

    /** 外观模式：0 = 跟随系统，1 = 浅色，2 = 深色。 */
    public static int getThemeMode(Context ctx) {
        return prefs(ctx).getInt(KEY_THEME_MODE, 0);
    }

    public static void setThemeMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt(KEY_THEME_MODE, mode).apply();
    }
}
