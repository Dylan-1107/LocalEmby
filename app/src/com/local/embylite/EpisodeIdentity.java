package com.local.embylite;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 无 Android 依赖的剧集身份；未知季与显式 S00 由 seasonExplicit 区分。 */
public final class EpisodeIdentity {
    private static final String CN_NUMBER = "[0-9零〇一二两三四五六七八九十百]+";
    private static final String LEFT = "(?<![A-Za-z0-9])";
    private static final Pattern SE = Pattern.compile(
            "(?i)" + LEFT + "S(\\d{1,2})[ ._-]*E(\\d{1,4})(?!\\d)");
    private static final Pattern X = Pattern.compile(
            "(?i)" + LEFT + "(\\d{1,2})x(\\d{1,4})(?![A-Za-z0-9])");
    private static final Pattern CN_SE = Pattern.compile(
            "第\\s*(" + CN_NUMBER + ")\\s*季\\s*第?\\s*(" + CN_NUMBER + ")\\s*[集话話期]");
    private static final Pattern EP = Pattern.compile(
            "(?i)" + LEFT + "(?:Episode|EP|E)\\s*(\\d{1,4})(?![A-Za-z0-9])");
    private static final Pattern CN_EP = Pattern.compile(
            "第\\s*(" + CN_NUMBER + ")\\s*[集话話期]");
    private static final Pattern SEASON = Pattern.compile(
            "(?i)" + LEFT + "(?:Season[ ._-]*|S)(\\d{1,2})(?![A-Za-z0-9])|第\\s*("
                    + CN_NUMBER + ")\\s*季");
    private static final Pattern BARE = Pattern.compile(
            "^(?:(.*?)[\\s._\\-\\[\\]()]+)?(\\d{1,3})[\\s\\])]*$");
    private static final Pattern RANGE = Pattern.compile(
            "(?i)(?:S\\d{1,2}[ ._-]*E|EP?|第)?\\d{1,4}\\s*[集话話]?\\s*[-~～—–+&、/]\\s*"
                    + "(?:S\\d{1,2}[ ._-]*E|EP?|第)?\\d{1,4}(?:[集话話]|(?![A-Za-z0-9]))");
    private static final Pattern COMBINED_SE = Pattern.compile(
            "(?i)S\\d{1,2}[ ._-]*E\\d{1,4}(?:E\\d{1,4})+");
    private static final Pattern COLLECTION = Pattern.compile(
            "(?i)合集|合辑|全集|全\\s*\\d+\\s*集|上[下中]?篇|中篇|下篇|上[下中]?集|中集|下集"
                    + "|第[零〇一二两三四五六七八九十百0-9]+[集话話]\\s*[(\\[【]?\\s*[上中下]"
                    + "|\\b(?:complete|collection|batch|parts?\\s*\\d+)\\b");
    private static final Pattern TECH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:4320p|2160p|1440p|1080[pi]|720p|576p|480p|[248]K"
                    + "|WEB[ ._-]?DL|WEBRip|Blu[ ._-]?Ray|BDRip|BRRip|HDTV|DVDRip"
                    + "|x26[45]|H[ .]?26[45]|HEVC|AVC|HDR10?\\+?|HDR|SDR|REMUX"
                    + "|AAC(?:[ .]?[257]\\.1)?|AC3|DTS(?:[ ._-]?HD)?|DDP?[ .]?[257]\\.1"
                    + "|10bit|8bit|PROPER|REPACK|CHS|CHT|CHS&CHT|ENG|JPN|简体|繁体|简中|繁中|中字|中英双字)"
                    + "(?![A-Za-z0-9])");
    private static final Pattern BRACKETS = Pattern.compile(
            "\\[([^\\[\\]]*)\\]|\\(([^()]*)\\)|【([^【】]*)】");
    private static final Pattern RELEASE_GROUP = Pattern.compile(
            "(?i)^(?:ANi|Lilith-Raws|NC-Raws|SubsPlease|Erai-raws|桜都字幕组|喵萌奶茶屋)$");
    private static final Pattern EXT = Pattern.compile(
            "(?i)\\.(?:mkv|mp4|avi|ts|m2ts|mov|flv|wmv|webm|m4v|mpg|mpeg|3gp|rmvb|rm|vob|wtv)$");

    public final String seriesName;
    public final int seasonNumber;
    public final int episodeNumber;
    public final boolean seasonExplicit;
    public final boolean identityAmbiguous;

    private EpisodeIdentity(String seriesName, int seasonNumber, int episodeNumber,
                            boolean seasonExplicit, boolean identityAmbiguous) {
        this.seriesName = seriesName;
        this.seasonNumber = seasonNumber;
        this.episodeNumber = episodeNumber;
        this.seasonExplicit = seasonExplicit;
        this.identityAmbiguous = identityAmbiguous;
    }

    /** parentNames 为根目录到直接父目录的 displayName，不接受 URI 推断目录。 */
    public static EpisodeIdentity parse(String name, String[] parentNames) {
        String source = EXT.matcher(text(name)).replaceFirst("");
        Directory directory = directory(parentNames);
        boolean ambiguous = directory.ambiguous || ambiguous(source);
        Marker marker = marker(source);
        int season = directory.season;
        boolean explicit = directory.explicit;
        String title;
        int episode;
        if (marker != null) {
            title = cleanTitle(source.substring(0, marker.start));
            episode = marker.episode;
            if (marker.explicit) {
                if (directory.explicit && directory.season != marker.season) ambiguous = true;
                season = marker.season;
                explicit = true;
            } else {
                Matcher seasonMatch = SEASON.matcher(title);
                if (seasonMatch.find()) {
                    season = seasonNumber(seasonMatch);
                    explicit = true;
                    title = cleanTitle(title.substring(0, seasonMatch.start()));
                }
            }
            if (title.isEmpty()) title = directory.title;
            ambiguous |= multipleMarkers(source, marker);
        } else {
            String cleaned = cleanTitle(source);
            Matcher bare = BARE.matcher(cleaned);
            if (!bare.matches()) return empty(ambiguous);
            episode = number(bare.group(2));
            if (isResolution(episode)) return empty(ambiguous);
            title = cleanTitle(bare.group(1));
            if (title.isEmpty()) title = directory.title;
        }
        if (!meaningful(title) || episode <= 0 || episode > 999) return empty(ambiguous);
        return new EpisodeIdentity(title, season, episode, explicit, ambiguous);
    }

    /** 严格比较只统一大小写、全半角、分隔符和明确技术标签；不删除续作或季名。 */
    public static String normalizeTitle(String value) {
        return cleanTitle(text(value)).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s._\\-]+", " ").trim();
    }

    private static Directory directory(String[] names) {
        Directory out = new Directory();
        if (names == null) return out;
        for (String name : names) {
            String value = text(name);
            if (value.isEmpty()) continue;
            out.ambiguous |= ambiguous(value);
            String clean = cleanTitle(value);
            Matcher season = SEASON.matcher(clean);
            if (season.find() && cleanTitle(clean.substring(season.end())).isEmpty()) {
                out.season = seasonNumber(season);
                out.explicit = true;
                clean = cleanTitle(clean.substring(0, season.start()));
            }
            if (meaningful(clean)) out.title = clean;
        }
        return out;
    }

    private static Marker marker(String source) {
        Matcher m = SE.matcher(source);
        if (m.find()) return new Marker(m.start(), m.end(), number(m.group(1)), number(m.group(2)), true);
        m = X.matcher(source);
        if (m.find()) return new Marker(m.start(), m.end(), number(m.group(1)), number(m.group(2)), true);
        m = CN_SE.matcher(source);
        if (m.find()) return new Marker(m.start(), m.end(), number(m.group(1)), number(m.group(2)), true);
        m = EP.matcher(source);
        if (m.find()) return new Marker(m.start(), m.end(), 0, number(m.group(1)), false);
        m = CN_EP.matcher(source);
        if (m.find()) return new Marker(m.start(), m.end(), 0, number(m.group(1)), false);
        return null;
    }

    private static boolean multipleMarkers(String source, Marker first) {
        return marker(source.substring(first.end)) != null;
    }

    private static boolean ambiguous(String source) {
        return RANGE.matcher(source).find() || COMBINED_SE.matcher(source).find()
                || COLLECTION.matcher(source).find();
    }

    private static boolean isResolution(int value) {
        return value == 480 || value == 576 || value == 720;
    }

    private static int seasonNumber(Matcher m) {
        return number(m.group(1) != null ? m.group(1) : m.group(2));
    }

    private static boolean meaningful(String title) {
        if (title == null || title.isEmpty()) return false;
        String key = title.toLowerCase(Locale.ROOT).replaceAll("[\\s._\\-]+", "");
        if (key.matches("\\d{1,3}|[a-z]:|[0-9a-f]{8}[-0-9a-f]*")) return false;
        if (key.matches("tv|tvshows|shows|series|anime|video|videos|movie|movies|media|download|downloads"
                + "|storage|emulated|sdcard|self|primary|电视剧|剧集|动漫|动画|视频|电影|下载|特别篇|specials?")) return false;
        return Pattern.compile("[\\p{L}\\p{N}]").matcher(title).find();
    }

    private static String cleanTitle(String value) {
        String source = text(value);
        Matcher m = BRACKETS.matcher(source);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String inside = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            String remaining = TECH.matcher(inside).replaceAll(" ")
                    .replaceAll("[\\s._+&/\\-]+", "");
            boolean tag = remaining.isEmpty() || RELEASE_GROUP.matcher(inside.trim()).matches();
            m.appendReplacement(out, Matcher.quoteReplacement(tag ? " " : m.group()));
        }
        m.appendTail(out);
        String clean = TECH.matcher(out.toString()).replaceAll(" ");
        return clean.replaceAll("[\\s._]+", " ")
                .replaceAll("^[\\s\\-\\[\\]()【】]+|[\\s\\-\\[\\]()【】]+$", "")
                .trim();
    }

    private static String text(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
    }

    private static int number(String value) {
        if (value.matches("[0-9]+")) return Integer.parseInt(value);
        int total = 0;
        int digit = 0;
        String digits = "零一二三四五六七八九";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '十' || c == '百') {
                total += (digit == 0 ? 1 : digit) * (c == '十' ? 10 : 100);
                digit = 0;
            } else {
                int next = c == '两' ? 2 : c == '〇' ? 0 : digits.indexOf(c);
                if (next < 0) return 0;
                digit = digit * 10 + next;
            }
        }
        return total + digit;
    }

    private static EpisodeIdentity empty(boolean ambiguous) {
        return new EpisodeIdentity("", 0, 0, false, ambiguous);
    }

    private static final class Directory {
        String title = "";
        int season;
        boolean explicit;
        boolean ambiguous;
    }

    private static final class Marker {
        final int start;
        final int end;
        final int season;
        final int episode;
        final boolean explicit;

        Marker(int start, int end, int season, int episode, boolean explicit) {
            this.start = start;
            this.end = end;
            this.season = season;
            this.episode = episode;
            this.explicit = explicit;
        }
    }
}
