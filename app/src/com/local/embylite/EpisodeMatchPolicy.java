package com.local.embylite;

/** Pure-Java candidate validation, independently regression tested. */
public final class EpisodeMatchPolicy {
    private EpisodeMatchPolicy() {}
    public static boolean accepts(Item item, String animeTitle, String episodeTitle) {
        if (!item.isEpisode()) return false;
        String anime = cleanSourceTitle(animeTitle);
        String episode = episodeTitle.replaceFirst("^【[^】]*】\\s*", "")
                .replaceFirst("^\\[[^\\]]*\\]\\s*", "");
        EpisodeIdentity parsed = EpisodeIdentity.parse(episode, new String[]{anime});
        if (parsed.identityAmbiguous || parsed.episodeNumber != item.episodeNumber) return false;
        EpisodeIdentity work = EpisodeIdentity.parse("EP" + item.episodeNumber, new String[]{anime});
        boolean explicit = parsed.seasonExplicit || work.seasonExplicit;
        int season = parsed.seasonExplicit ? parsed.seasonNumber : work.seasonNumber;
        if (parsed.seasonExplicit && work.seasonExplicit && parsed.seasonNumber != work.seasonNumber) return false;
        String title = work.seriesName.isEmpty() ? anime : work.seriesName;
        if (!EpisodeIdentity.normalizeTitle(title).equals(EpisodeIdentity.normalizeTitle(item.seriesName))) return false;
        if (item.seasonExplicit) return explicit ? season == item.seasonNumber : item.seasonNumber == 1;
        return !explicit;
    }
    public static String cleanSourceTitle(String title) {
        return (title == null ? "" : title).replaceFirst("(?i)(?:\\s+|(?<=】))from\\s+.*$", "")
                .replaceAll("[（(](?:19|20)\\d{2}[）)]", "")
                .replaceAll("【(?:国产剧|电视剧|日剧|韩剧|美剧|动画|动漫|番剧|电影|其他)】", "").trim();
    }
}
