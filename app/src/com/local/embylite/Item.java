package com.local.embylite;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/** 纯数据类：一个本地视频条目。 */
public class Item {

    public String id;
    public String path;
    public String name;
    public String container;
    public long sizeBytes;
    public long durationMs;

    /** 从文件名解析出的剧集信息：seriesName 为空表示当电影处理。 */
    public String seriesName = "";
    public int seasonNumber = 0;
    public int episodeNumber = 0;
    public boolean seasonExplicit = false;
    public boolean identityAmbiguous = false;

    public Item() {
    }

    public Item(String path, String name, String container, long sizeBytes, long durationMs) {
        this.path = path;
        this.name = name;
        this.container = container;
        this.sizeBytes = sizeBytes;
        this.durationMs = durationMs;
        this.id = idForPath(path);
        parseSeriesInfo(parentNamesFromPath(path));
    }

    /** 用显式父目录名称重新解析，SAF 调用方传入 document displayName 层级。 */
    public void parseSeriesInfo(String[] parentNames) {
        EpisodeIdentity identity = EpisodeIdentity.parse(name, parentNames);
        seriesName = identity.seriesName;
        seasonNumber = identity.seasonNumber;
        episodeNumber = identity.episodeNumber;
        seasonExplicit = identity.seasonExplicit;
        identityAmbiguous = identity.identityAmbiguous;
    }

    public void parseSeriesInfo() {
        parseSeriesInfo(parentNamesFromPath(path));
    }

    public void setDirectoryContext(List<String> parentNames) {
        parseSeriesInfo(parentNames == null ? null : parentNames.toArray(new String[0]));
    }

    public static String normalizeTitle(String value) {
        return EpisodeIdentity.normalizeTitle(value);
    }

    public boolean isEpisode() {
        return !identityAmbiguous && seriesName != null && !seriesName.isEmpty() && episodeNumber > 0;
    }

    private static String[] parentNamesFromPath(String value) {
        if (value == null || value.startsWith("content://")) return new String[0];
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash <= 0) return new String[0];
        String[] parts = normalized.substring(0, slash).split("/");
        List<String> names = new ArrayList<String>();
        for (String part : parts) if (!part.isEmpty()) names.add(part);
        return names.toArray(new String[0]);
    }

    /** 对绝对路径生成稳定的 16 进制 id。 */
    public static String idForPath(String path) {
        CRC32 crc = new CRC32();
        crc.update((path == null ? "" : path).getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue());
    }

    /** 文件修改时间，用于 DateCreated/DatePlayed 排序。 */
    public long lastModified() {
        try {
            return new File(path).lastModified();
        } catch (Throwable t) {
            return 0L;
        }
    }

    @Override
    public String toString() {
        return "Item{" + id + "," + name + "}";
    }
}
