package com.local.embylite;

/** 本地刮削核心库回归测试；纯 Java，无 JUnit。 */
public final class ScrapeRegression {
    static int count;
    static void check(boolean ok, String message) {
        count++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        // ---- ScrapeTitle ----
        ScrapeTitle.Result r = ScrapeTitle.parse(
                "流浪地球2.The.Wandering.Earth.II.2023.2160p.WEB-DL.x265.mkv");
        check(r.year == 2023 && r.title.contains("流浪地球2"),
              "wandering earth -> '" + r.title + "' / " + r.year);
        r = ScrapeTitle.parse("[SubsPlease] Under Ninja - 07 (1080p) [8F592BA].mkv");
        check(r.title.equals("Under Ninja") && r.year == 0,
              "under ninja -> '" + r.title + "' / " + r.year);
        r = ScrapeTitle.parse("Some Movie (2020).mp4");
        check(r.title.equals("Some Movie") && r.year == 2020,
              "some movie -> '" + r.title + "' / " + r.year);
        r = ScrapeTitle.parse("庆余年 第二季 第05集.mp4");
        check(r.title.contains("庆余年") && !r.title.contains("05") && !r.title.contains("第二季"),
              "qing yu nian -> '" + r.title + "' / " + r.year);
        r = ScrapeTitle.parse("Show.Name.S02E03.720p.mkv");
        check(r.title.equals("Show Name") && r.year == 0,
              "sxxexx tail -> '" + r.title + "'");
        check(ScrapeTitle.parse(null) != null, "parse(null) never null");
        check(ScrapeTitle.parse("1080p.mkv").title.equals("1080p"), "fallback to raw name");
        check(ScrapeTitle.clean("Test [1080p] Name").equals("Test Name"),
              "clean -> '" + ScrapeTitle.clean("Test [1080p] Name") + "'");
        check(ScrapeTitle.clean(null) != null, "clean(null) never null");

        // ---- ScrapePolicy ----
        check(ScrapePolicy.accepts("流浪地球2", 2023, "流浪地球2", "The Wandering Earth II", "2023-01-22"),
              "exact match accepted");
        check(ScrapePolicy.accepts("The Wandering Earth", 0, "流浪地球2", "The Wandering Earth II", ""),
              "containment (short >= 4) accepted");
        check(!ScrapePolicy.accepts("Some Movie", 2020, "Some Movie", "", "1990-05-01"),
              "year diff > 1 rejected");
        check(ScrapePolicy.accepts("Some Movie", 2022, "Some Movie", "", "2023-05-01"),
              "year diff == 1 accepted");
        check(!ScrapePolicy.accepts("Up", 0, "Up (2009) Remastered", "", ""),
              "containment (short < 4) rejected");
        check(!ScrapePolicy.accepts("", 0, "Some Movie", "", ""), "empty query rejected");
        check(!ScrapePolicy.accepts("另一部剧", 2020, "Some Movie", "", "2020-01-01"),
              "wrong title rejected");
        check(ScrapePolicy.accepts("Some Movie", 2021, "Some Movie 其它语言名", "Foreign Title", "2020-12-31"),
              "original title match accepted");

        // ---- NfoWriter -> NfoParser roundtrip (movie) ----
        NfoData m = new NfoData();
        m.type = "movie";
        m.title = "A&B <续> \"特典\" 'O'Neill'";
        m.plot = "第一行\n第二行 中文 & 符号 <测试>";
        m.premiereDate = "2023-01-22";
        m.tmdbId = "12345";
        m.year = 2023;
        m.rating = 8.7;
        m.genres.add("科幻");
        m.genres.add("冒险");
        m.studios.add("中影");
        NfoData.Person actor = new NfoData.Person();
        actor.name = "吴京";
        actor.role = "刘培强";
        actor.type = "Actor";
        m.people.add(actor);
        NfoData.Person director = new NfoData.Person();
        director.name = "郭帆";
        director.type = "Director";
        m.people.add(director);
        NfoData mb = NfoParser.parse(NfoWriter.write(m));
        check("movie".equals(mb.type)
              && m.title.equals(mb.title) && m.plot.equals(mb.plot)
              && m.premiereDate.equals(mb.premiereDate) && m.tmdbId.equals(mb.tmdbId)
              && m.year == mb.year && m.rating == mb.rating
              && m.genres.equals(mb.genres) && m.studios.equals(mb.studios)
              && mb.people.size() == 2
              && "吴京".equals(mb.people.get(0).name) && "刘培强".equals(mb.people.get(0).role)
              && "Actor".equals(mb.people.get(0).type)
              && "Director".equals(mb.people.get(1).type) && "郭帆".equals(mb.people.get(1).name),
              "movie roundtrip: " + mb.title);

        // ---- NfoWriter -> NfoParser roundtrip (episode) ----
        NfoData e = new NfoData();
        e.type = "episode";
        e.title = "庆余年";
        e.episodeTitle = "第5集 名场面";
        e.season = 2;
        e.episode = 5;
        e.plot = "A&B";
        NfoData eb = NfoParser.parse(NfoWriter.write(e));
        check("episode".equals(eb.type) && "庆余年".equals(eb.title)
              && "第5集 名场面".equals(eb.episodeTitle)
              && eb.season == 2 && eb.episode == 5 && "A&B".equals(eb.plot),
              "episode roundtrip");

        // ---- NfoParser 手写 Kodi XML ----
        NfoData n1 = NfoParser.parse("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<tvshow>\n  <title>Test Show</title>\n  <id moviedb=\"tmdb\">999</id>\n"
                + "  <genre>剧情</genre>\n  <studio>某厂牌</studio>\n</tvshow>");
        check("tvshow".equals(n1.type) && n1.tmdbId.equals("999")
              && "Test Show".equals(n1.title) && n1.genres.contains("剧情")
              && n1.studios.contains("某厂牌") && n1.season == -1,
              "id moviedb attr");
        NfoData n2 = NfoParser.parse("<episodedetails><title>Show</title>"
                + "<episodetitle>EP名</episodetitle><season>1</season><episode>3</episode>"
                + "<uniqueid type=\"tmdb\">888</uniqueid></episodedetails>");
        check("episode".equals(n2.type) && n2.tmdbId.equals("888")
              && n2.season == 1 && n2.episode == 3 && "EP名".equals(n2.episodeTitle),
              "uniqueid type attr");
        NfoData n3 = NfoParser.parse("<movie><title>Old Style</title><id>777</id>"
                + "<rating>bad</rating></movie>");
        check("movie".equals(n3.type) && n3.tmdbId.equals("777")
              && n3.rating == 0 && n3.season == -1 && n3.episode == -1,
              "plain numeric id + bad rating");

        System.out.println("PASS " + count + " scrape regression cases");
    }
}
