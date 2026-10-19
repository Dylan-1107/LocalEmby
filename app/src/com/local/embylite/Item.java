package com.local.embylite;

import java.io.File;
import java.nio.charset.StandardCharsets;
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

    public Item() {
    }

    public Item(String path, String name, String container, long sizeBytes, long durationMs) {
        this.path = path;
        this.name = name;
        this.container = container;
        this.sizeBytes = sizeBytes;
        this.durationMs = durationMs;
        this.id = idForPath(path);
        parseSeriesInfo();
    }

    /** 解析 "剧名 S01E02" / "剧名 第3集" / "剧名 EP03" / "剧名 03" 等常见命名。 */
    private void parseSeriesInfo() {
        if (name == null) return;
        String s = name.trim();
        java.util.regex.Matcher m;
        // SxxExx
        m = java.util.regex.Pattern
                .compile("^(.*?)[\\s._\\-\\[\\]]*[Ss](\\d{1,2})[\\s._-]*[Ee](\\d{1,4})\\b.*$")
                .matcher(s);
        if (m.matches()) {
            setSeries(m.group(1), m.group(2), m.group(3));
            return;
        }
        // 第N集 / 第N话
        m = java.util.regex.Pattern
                .compile("^(.*?)[\\s._\\-\\[\\]]*第\\s*(\\d{1,4})\\s*[集话話期].*$")
                .matcher(s);
        if (m.matches()) {
            setSeries(m.group(1), "1", m.group(2));
            return;
        }
        // EPxx / Exx
        m = java.util.regex.Pattern
                .compile("^(.*?)[\\s._\\-\\[\\]]*(?:EP|Ep|ep|E)(\\d{1,4})\\b.*$")
                .matcher(s);
        if (m.matches()) {
            setSeries(m.group(1), "1", m.group(2));
            return;
        }
        // 结尾纯数字（需有分隔符，避免把年份当集数）
        m = java.util.regex.Pattern
                .compile("^(.*?)[\\s._\\-\\[\\]]+(\\d{1,3})$")
                .matcher(s);
        if (m.matches()) {
            setSeries(m.group(1), "1", m.group(2));
        }
    }

    private void setSeries(String series, String season, String episode) {
        try {
            String s = series == null ? "" : series.replaceAll("[\\s._\\-\\[\\]()]+$", "").trim();
            int ep = Integer.parseInt(episode);
            int se = Integer.parseInt(season);
            if (s.isEmpty() || ep <= 0 || ep > 999) return;
            seriesName = s;
            seasonNumber = Math.max(se, 1);
            episodeNumber = ep;
        } catch (Throwable ignored) {
        }
    }

    public boolean isEpisode() {
        return seriesName != null && !seriesName.isEmpty() && episodeNumber > 0;
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
