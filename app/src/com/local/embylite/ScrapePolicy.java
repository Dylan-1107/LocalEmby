package com.local.embylite;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** TMDB 候选接受策略：标题关 + 年份关；纯 Java，无 Android 依赖。 */
public final class ScrapePolicy {
    private static final Pattern BRACKETS = Pattern.compile(
            "\\[([^\\[\\]]*)\\]|\\(([^()]*)\\)|【([^【】]*)】");

    private ScrapePolicy() {}

    /** 判断 TMDB 候选是否可接受。candDate 形如 "2023-01-22" 或 ""。 */
    public static boolean accepts(String queryTitle, int queryYear,
                                  String candTitle, String candOriginalTitle, String candDate) {
        String query = normalize(queryTitle);
        if (query.isEmpty()) return false;
        String a = normalize(candTitle);
        String b = normalize(candOriginalTitle);
        boolean titleOk = a.equals(query) || b.equals(query)
                || fuzzy(a, query) || fuzzy(b, query);
        if (!titleOk) return false;
        int candYear = yearOf(candDate);
        if (queryYear > 0 && candYear > 0 && Math.abs(queryYear - candYear) > 1) return false;
        return true;
    }

    /** 归一化：去括号、小写、分隔符压成单空格。 */
    private static String normalize(String value) {
        String s = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
        s = BRACKETS.matcher(s).replaceAll(" ");
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s._\\-]+", " ").trim();
    }

    /** 包含关系：一方包含另一方，且较短一方（去空格后）长度 >= 4。 */
    private static boolean fuzzy(String cand, String query) {
        if (cand.isEmpty() || query.isEmpty()) return false;
        String shorter;
        String longer;
        if (cand.length() <= query.length()) {
            shorter = cand;
            longer = query;
        } else {
            shorter = query;
            longer = cand;
        }
        if (!longer.contains(shorter)) return false;
        return shorter.replace(" ", "").length() >= 4;
    }

    /** 从 "2023-01-22" 的前 4 位解析年份；失败返回 0。 */
    private static int yearOf(String candDate) {
        if (candDate == null || candDate.length() < 4) return 0;
        String head = candDate.substring(0, 4);
        if (!head.matches("\\d{4}")) return 0;
        return Integer.parseInt(head);
    }
}
