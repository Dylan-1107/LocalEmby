package com.local.embylite;
public final class EpisodeRegression {
    static int count;
    static void check(boolean ok, String message) { count++; if (!ok) throw new AssertionError(message); }
    static void parse(String name, String[] dirs, String title, int season, int ep, boolean explicit, boolean ambiguous) {
        EpisodeIdentity v = EpisodeIdentity.parse(name, dirs);
        check(v.seriesName.equals(title) && v.seasonNumber == season && v.episodeNumber == ep
              && v.seasonExplicit == explicit && v.identityAmbiguous == ambiguous,
              name + " -> " + v.seriesName + " S" + v.seasonNumber + "E" + v.episodeNumber + " explicit=" + v.seasonExplicit + " ambiguous=" + v.identityAmbiguous);
    }
    static Item item(String name) { return new Item("/storage/emulated/0/Download/" + name + ".mkv", name, "mkv", 1234, 0); }
    public static void main(String[] args) {
        String[] none = {};
        parse("剧名 S02E03", none, "剧名", 2, 3, true, false);
        parse("剧名.s02e03.1080p.WEB-DL", none, "剧名", 2, 3, true, false);
        parse("Show.Name.2x03", none, "Show Name", 2, 3, true, false);
        parse("剧名 第二季第3集", none, "剧名", 2, 3, true, false);
        parse("剧名 第十二集", none, "剧名", 0, 12, false, false);
        parse("剧名 EP03", none, "剧名", 0, 3, false, false);
        parse("剧名 E03", none, "剧名", 0, 3, false, false);
        parse("剧名 03", none, "剧名", 0, 3, false, false);
        parse("03.mkv", new String[]{"剧名", "Season 2"}, "剧名", 2, 3, true, false);
        parse("03", new String[]{"剧名", "第二季"}, "剧名", 2, 3, true, false);
        parse("EP03", new String[]{"剧名", "S02"}, "剧名", 2, 3, true, false);
        parse("第3集", new String[]{"剧名"}, "剧名", 0, 3, false, false);
        parse("剧名 S00E01", none, "剧名", 0, 1, true, false);
        parse("剧名 S01E01-E02", none, "剧名", 1, 1, true, true);
        parse("剧名 S01E01E02", none, "剧名", 1, 1, true, true);
        parse("剧名 第3集上篇", none, "剧名", 0, 3, false, true);
        parse("剧名 1984", none, "", 0, 0, false, false);
        parse("剧名 1080p", none, "", 0, 0, false, false);
        parse("03", none, "", 0, 0, false, false);
        parse("03", new String[]{"Download"}, "", 0, 0, false, false);
        parse("[SubsPlease] Show.Name S02E03 [1080p]", none, "Show Name", 2, 3, true, false);
        parse("剧名 EP003", none, "剧名", 0, 3, false, false);
        parse("剧名 Episode 03", none, "剧名", 0, 3, false, false);
        parse("剧名 第两百零三集", none, "剧名", 0, 203, false, false);
        Item saf = new Item("content://provider/document/opaque", "03", "mkv", 1234, 0);
        check(!saf.isEpisode(), "opaque URI must not supply a title");
        saf.setDirectoryContext(java.util.Arrays.asList("剧名", "Season 2"));
        check(saf.isEpisode() && saf.seasonNumber == 2, "SAF display names");
        Item windows = new Item("C:\\Video\\剧名\\Season 2\\03.mkv", "03", "mkv", 1234, 0);
        check(windows.seasonNumber == 2 && windows.seriesName.equals("剧名"), "Windows directories");
        parse("剧名 S01E03", new String[]{"剧名", "Season 2"}, "剧名", 1, 3, true, true);
        check(EpisodeMatchPolicy.accepts(item("余红旧事 S01E01"), "余红旧事(2026)【国产剧】from iqiyi", "【qiyi】 余红旧事第1集 逃出火场"), "real source fixture");
        check(!EpisodeMatchPolicy.accepts(item("余红旧事 S01E02"), "余红旧事(2026)【国产剧】from iqiyi", "【qiyi】 余红旧事第1集 逃出火场"), "wrong episode");
        check(!EpisodeMatchPolicy.accepts(item("剧名 S02E01"), "剧名", "第1集"), "later season needs evidence");
        check(EpisodeMatchPolicy.accepts(item("剧名 S02E01"), "剧名 第二季", "第1集"), "matching season");
        check(!EpisodeMatchPolicy.accepts(item("剧名 S02E01"), "剧名 第三季", "第1集"), "wrong season");
        check(!EpisodeMatchPolicy.accepts(item("剧名 S00E01"), "剧名", "第1集"), "special must not select regular");
        check(!EpisodeMatchPolicy.accepts(item("剧名 EP01"), "剧名 第二季", "第1集"), "unknown season cannot choose second");
        check(!EpisodeMatchPolicy.accepts(item("剧名 EP01"), "另一部剧", "第1集"), "wrong title");
        check(!EpisodeMatchPolicy.accepts(item("剧名 S01E01-E02"), "剧名", "第1集"), "range cannot bind");
        check(!EpisodeMatchPolicy.accepts(item("剧名 S02E01"), "剧名 第二季", "S03E01"), "conflicting source season");
        System.out.println("PASS " + count + " episode identity and candidate regression cases");
    }
}
