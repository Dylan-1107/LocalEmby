package com.local.embylite;

import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** 宽松解析 Kodi NFO XML（movie/tvshow/episodedetails）；缺字段给默认值，格式错误抛异常。 */
public final class NfoParser {
    private NfoParser() {}

    public static NfoData parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            // 禁止加载外部 DTD，避免联网与 XXE
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception ignored) {
            // 实现不支持该 feature 时忽略
        }
        factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
        Element root = doc.getDocumentElement();
        NfoData d = new NfoData();
        String rootTag = root.getTagName();
        if ("tvshow".equals(rootTag)) d.type = "tvshow";
        else if ("episodedetails".equals(rootTag)) d.type = "episode";
        else d.type = "movie";

        d.title = textOf(child(root, "title"));
        d.plot = textOf(child(root, "plot"));
        d.premiereDate = textOf(child(root, "premiered"));
        d.episodeTitle = textOf(child(root, "episodetitle"));
        d.year = intOf(child(root, "year"), 0);
        d.rating = doubleOf(child(root, "rating"));
        d.season = intOf(child(root, "season"), -1);
        d.episode = intOf(child(root, "episode"), -1);

        NodeList ids = root.getElementsByTagName("id");
        for (int i = 0; i < ids.getLength(); i++) {
            Element e = (Element) ids.item(i);
            if ("tmdb".equals(e.getAttribute("moviedb"))) {
                String value = textOf(e);
                if (!value.isEmpty()) d.tmdbId = value;
            }
        }
        NodeList uniqueIds = root.getElementsByTagName("uniqueid");
        for (int i = 0; i < uniqueIds.getLength(); i++) {
            Element e = (Element) uniqueIds.item(i);
            if ("tmdb".equals(e.getAttribute("type"))) {
                String value = textOf(e);
                if (!value.isEmpty()) d.tmdbId = value;
            }
        }
        if (d.tmdbId.isEmpty()) {
            for (int i = 0; i < ids.getLength(); i++) {
                String value = textOf((Element) ids.item(i));
                if (value.matches("\\d+")) {
                    d.tmdbId = value;
                    break;
                }
            }
        }

        collect(root, "genre", d.genres);
        collect(root, "studio", d.studios);

        NodeList actors = root.getElementsByTagName("actor");
        for (int i = 0; i < actors.getLength(); i++) {
            Element e = (Element) actors.item(i);
            NfoData.Person p = new NfoData.Person();
            p.name = textOf(child(e, "name"));
            p.role = textOf(child(e, "role"));
            String type = textOf(child(e, "type"));
            p.type = type.isEmpty() ? "Actor" : type;
            if (!p.name.isEmpty()) d.people.add(p);
        }
        NodeList directors = root.getElementsByTagName("director");
        for (int i = 0; i < directors.getLength(); i++) {
            String name = textOf((Element) directors.item(i));
            if (!name.isEmpty()) {
                NfoData.Person p = new NfoData.Person();
                p.name = name;
                p.type = "Director";
                d.people.add(p);
            }
        }
        return d;
    }

    private static void collect(Element parent, String tag, java.util.List<String> out) {
        NodeList list = parent.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            String value = textOf((Element) list.item(i));
            if (!value.isEmpty()) out.add(value);
        }
    }

    /** 取直接子元素中第一个匹配 tag 的节点；无则返回 null。 */
    private static Element child(Element parent, String tag) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element && tag.equals(((Element) n).getTagName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String textOf(Element e) {
        return e == null ? "" : e.getTextContent().trim();
    }

    private static int intOf(Element e, int fallback) {
        String value = textOf(e);
        if (!value.matches("-?\\d+")) return fallback;
        return Integer.parseInt(value);
    }

    private static double doubleOf(Element e) {
        String value = textOf(e);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
