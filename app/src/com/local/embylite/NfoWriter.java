package com.local.embylite;

/** 生成 Kodi 兼容 NFO XML；手工拼接 + 正确转义，无 Android 依赖。 */
public final class NfoWriter {
    private NfoWriter() {}

    /** 输出 UTF-8 文本（带 XML 声明）；type 决定根元素 movie/tvshow/episodedetails。 */
    public static String write(NfoData d) {
        String root = "tvshow".equals(d.type) ? "tvshow"
                : "episode".equals(d.type) ? "episodedetails" : "movie";
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append('<').append(root).append(">\n");
        tag(sb, "title", d.title);
        tag(sb, "plot", d.plot);
        if (!d.premiereDate.isEmpty()) tag(sb, "premiered", d.premiereDate);
        if (d.year > 0) tag(sb, "year", String.valueOf(d.year));
        if (d.rating != 0) tag(sb, "rating", String.valueOf(d.rating));
        if ("episode".equals(d.type)) {
            if (d.season >= 0) tag(sb, "season", String.valueOf(d.season));
            if (d.episode >= 0) tag(sb, "episode", String.valueOf(d.episode));
            if (!d.episodeTitle.isEmpty()) tag(sb, "episodetitle", d.episodeTitle);
        }
        if (!d.tmdbId.isEmpty()) {
            attr(sb, "id", "moviedb", "tmdb", d.tmdbId);
            attr(sb, "uniqueid", "type", "tmdb", d.tmdbId);
        }
        for (String genre : d.genres) tag(sb, "genre", genre);
        for (String studio : d.studios) tag(sb, "studio", studio);
        for (NfoData.Person p : d.people) {
            if ("Actor".equals(p.type)) {
                sb.append("  <actor>\n");
                tag(sb, "name", p.name);
                tag(sb, "role", p.role);
                tag(sb, "type", p.type);
                sb.append("  </actor>\n");
            } else if ("Director".equals(p.type)) {
                tag(sb, "director", p.name);
            }
            // 其它类型（Writer/Producer 等）暂不输出
        }
        sb.append("</").append(root).append(">\n");
        return sb.toString();
    }

    private static void tag(StringBuilder sb, String name, String value) {
        sb.append("  <").append(name).append('>').append(esc(value))
                .append("</").append(name).append(">\n");
    }

    private static void attr(StringBuilder sb, String name, String attrName,
                             String attrValue, String value) {
        sb.append("  <").append(name).append(' ').append(attrName).append("=\"")
                .append(esc(attrValue)).append("\">").append(esc(value))
                .append("</").append(name).append(">\n");
    }

    /** XML 转义：& < > " ' 与非法控制字符（保留 \t \n \r）。 */
    private static String esc(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                case '\n': sb.append('\n'); break;
                case '\t': sb.append('\t'); break;
                case '\r': sb.append('\r'); break;
                default:
                    if (c >= 0x20) sb.append(c); // 其余控制字符直接丢弃
            }
        }
        return sb.toString();
    }
}
