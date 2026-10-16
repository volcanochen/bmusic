package com.volcano.bmusic.lyric;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsed LRC lyrics: a list of time-stamped lines, plus helpers for syncing. */
public class Lyric {

    public static class Line {
        public long time; // ms
        public String text;

        public Line(long t, String s) {
            time = t;
            text = s;
        }
    }

    public final List<Line> lines = new ArrayList<Line>();

    private static final Pattern TAG = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");

    public static Lyric parse(String lrc) {
        Lyric ly = new Lyric();
        if (lrc == null || lrc.isEmpty()) return ly;
        for (String raw : lrc.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = TAG.matcher(line);
            List<Long> times = new ArrayList<Long>();
            int last = 0;
            while (m.find()) {
                int mm = Integer.parseInt(m.group(1));
                int ss = Integer.parseInt(m.group(2));
                int ms = 0;
                if (m.group(3) != null) {
                    String msStr = m.group(3);
                    while (msStr.length() < 3) msStr += "0";
                    ms = Integer.parseInt(msStr.substring(0, 3));
                }
                times.add(mm * 60000L + ss * 1000L + ms);
                last = m.end();
            }
            String text = line.substring(last).trim();
            if (times.isEmpty()) {
                if (!text.isEmpty()) ly.lines.add(new Line(0, text));
            } else {
                for (long t : times) ly.lines.add(new Line(t, text));
            }
        }
        java.util.Collections.sort(ly.lines, new java.util.Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                if (a.time < b.time) return -1;
                if (a.time > b.time) return 1;
                return 0;
            }
        });
        return ly;
    }

    /** Index of the last line whose time <= ms, or -1 if none. */
    public int indexAt(long ms) {
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).time <= ms) idx = i;
            else break;
        }
        return idx;
    }
}
