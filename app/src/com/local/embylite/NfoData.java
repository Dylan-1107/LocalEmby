package com.local.embylite;

import java.util.ArrayList;
import java.util.List;

/** NFO 元数据载体；供 NfoWriter / NfoParser 共用。 */
public final class NfoData {
    /** movie | tvshow | episode */
    public String type = "movie";
    public String title = "";
    public String plot = "";
    public String premiereDate = "";
    public String tmdbId = "";
    public String episodeTitle = "";
    public int year;
    public double rating;
    public int season = -1;
    public int episode = -1;
    public List<String> genres = new ArrayList<>();
    public List<String> studios = new ArrayList<>();
    public List<Person> people = new ArrayList<>();

    public static class Person {
        public String name = "";
        public String role = "";
        public String type = "Actor";
    }
}
