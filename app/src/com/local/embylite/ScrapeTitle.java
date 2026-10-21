package com.local.embylite;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从视频文件名提取搜索标题与年份；纯 Java，无 Android 依赖。 */
public final class ScrapeTitle {
    private static final Pattern EXT = Pattern.compile(
            "(?i)\\.(?:mkv|mp4|avi|ts|m2ts|mov|flv|wmv|webm|m4v|mpg|mpeg|3gp|rmvb|rm|vob|wtv)$");
    /** 年份：前后不能紧邻数字，避免吞并 2160p/1080p 里的数字。 */
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)");
    private static final Pattern BRACKETS = Pattern.compile(
            "\\[([^\\[\\]]*)\\]|\\(([^()]*)\\)|【([^【】]*)】");
    private static final Pattern TECH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:4320p|2160p|1440p|1080[pi]|720p|576p|480p|[248]K"
                    + "|WEB[ ._-]?DL|WEBRip|Blu[ ._-]?Ray|BDRip|BRRip|HDTV|DVDRip"
                    + "|x26[45]|H[ .]?26[45]|HEVC|AVC|HDR10?\\+?|HDR|SDR|REMUX"
                    + "|AAC(?:[ .]?[257]\\.1)?|AC3|FLAC|TrueHD|Atmos|DDP?[ .]?[257]\\.1"
                    + "|DTS(?:[ ._-]?HD)?|10bit|8bit|PROPER|REPACK"
                    + "|CHS|CHT|ENG|JPN|简体|繁体|简中|繁中|中字|中英双字|内嵌)"
                    + "(?![A-Za-z0-9])");
    /** 括号内容去掉技术词与分隔符后只剩一个“单 token”（字幕组名/CRC 哈希等）视为标签。 */
    private static final Pattern SINGLE_TOKEN = Pattern.compile(
            "^[\\p{L}\\p{N}+'\\-._]+$");
    /** 尾部的集数/季数标记（Under Ninja - 07 / 庆余年 第二季 第05集 / Show S02E03）。 */
    private static final Pattern TAIL = Pattern.compile(
            "(?i)(?:[\\s._\\-]+S\\d{1,2}E\\d{1,4}"
                    + "|[\\s._\\-]+第?\\s*\\d{1,4}\\s*[集话話期]"
                    + "|[\\s._\\-]+第\\s*[0-9零〇一二两三四五六七八九十百]+\\s*季"
                    + "|[\\s._\\-]+\\d{1,4})+$");

    /** 解析结果；title 永不为 null。 */
    public static final class Result {
        public String title = "";
        public int year;
    }

    private ScrapeTitle() {}

    /** 从文件名提取搜索标题与年份；永不返回 null。 */
    public static Result parse(String fileName) {
        Result out = new Result();
        String source = text(fileName);
        String noExt = EXT.matcher(source).replaceFirst("");
        int year = 0;
        Matcher m = YEAR.matcher(noExt);
        if (m.find()) {
            year = Integer.parseInt(m.group(1));
            noExt = new StringBuilder(noExt).replace(m.start(), m.end(), " ").toString();
        }
        String title = stripTail(clean(noExt));
        if (title.isEmpty()) title = noExt.replaceAll("[\\s._]+", " ").trim();
        out.title = title;
        out.year = year;
        return out;
    }

    /** 技术标签/括号清理 + 分隔符压缩；永不返回 null。 */
    public static String clean(String value) {
        String source = text(value);
        Matcher m = BRACKETS.matcher(source);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String inside = m.group(1) != null ? m.group(1)
                    : m.group(2) != null ? m.group(2) : m.group(3);
            String remaining = TECH.matcher(inside).replaceAll(" ")
                    .replaceAll("[\\s._+&/\\-]+", "");
            boolean tag = remaining.isEmpty() || SINGLE_TOKEN.matcher(remaining).matches();
            m.appendReplacement(sb, Matcher.quoteReplacement(tag ? " " : m.group()));
        }
        m.appendTail(sb);
        String clean = TECH.matcher(sb.toString()).replaceAll(" ");
        return clean.replaceAll("[\\s._]+", " ")
                .replaceAll("^[\\s\\-\\[\\]()【】]+|[\\s\\-\\[\\]()【】]+$", "")
                .trim();
    }

    /** 归一化标题（小写、压分隔符），供策略比较使用。 */
    public static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[\\s._\\-]+", " ").trim();
    }

    /** 剥掉尾部的集数/季数标记；若剥空则保留原标题。 */
    private static String stripTail(String title) {
        String out = TAIL.matcher(title).replaceFirst("").trim();
        return out.isEmpty() ? title : out;
    }

    private static String text(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
    }
}
