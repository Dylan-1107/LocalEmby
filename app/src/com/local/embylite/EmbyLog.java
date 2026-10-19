package com.local.embylite;

import org.json.JSONArray;
import org.json.JSONException;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** 内存环形请求日志，最近 800 条。 */
public class EmbyLog {

    private static final int MAX = 800;
    private static final ArrayDeque<String> BUF = new ArrayDeque<String>(MAX);
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private EmbyLog() {
    }

    public static synchronized void log(String method, String path, int status) {
        add(FMT.format(new Date()) + " " + method + " " + path + " -> " + status);
    }

    public static synchronized void info(String msg) {
        add(FMT.format(new Date()) + " INFO " + msg);
    }

    private static void add(String line) {
        while (BUF.size() >= MAX) {
            BUF.pollFirst();
        }
        BUF.addLast(line);
    }

    public static synchronized JSONArray toJson() {
        JSONArray arr = new JSONArray();
        for (String s : BUF) {
            arr.put(s);
        }
        return arr;
    }

    public static synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String s : BUF) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }
}
