package com.volcano.bmusic.util;

import android.content.Context;

import com.volcano.bmusic.api.Wbi;
import com.volcano.bmusic.model.Track;

import java.io.File;

/** Local storage layout: audio cache, cover cache. */
public final class Cache {

    public static File root(Context c) {
        File ext = c.getExternalFilesDir(null);
        File base = ext != null ? ext : c.getFilesDir();
        File r = new File(base, "bmusic");
        if (!r.exists()) r.mkdirs();
        return r;
    }

    public static File audioDir(Context c) {
        File f = new File(root(c), "audio");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File coverDir(Context c) {
        File f = new File(root(c), "cover");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File audioFile(Context c, Track t) {
        return new File(audioDir(c), safe(t.id) + ".m4a");
    }

    public static File coverFile(Context c, String url) {
        return new File(coverDir(c), Wbi.md5(url) + ".jpg");
    }

    private static String safe(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-') {
                sb.append(ch);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    public static long audioSize(Context c) {
        return sizeOf(audioDir(c));
    }

    public static long coverSize(Context c) {
        return sizeOf(coverDir(c));
    }

    public static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) sum += sizeOf(k);
        }
        return sum;
    }

    public static void dropAudio(Context c) {
        deleteChildren(audioDir(c));
    }

    public static void dropCovers(Context c) {
        deleteChildren(coverDir(c));
    }

    private static void deleteChildren(File d) {
        if (d == null || !d.exists()) return;
        File[] kids = d.listFiles();
        if (kids != null) {
            for (File k : kids) {
                try {
                    k.delete();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
