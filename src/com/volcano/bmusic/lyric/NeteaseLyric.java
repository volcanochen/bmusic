package com.volcano.bmusic.lyric;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.volcano.bmusic.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches lyrics from Netease Cloud Music. Uses the unauthenticated PC search
 * (api/cloudsearch/pc) and lyric (api/song/lyric) endpoints — no encryption,
 * no third-party service. Results are cached per track under getCacheDir().
 */
public final class NeteaseLyric {

    public static class Song {
        public String id;
        public String name;
        public String artist;
        public String album;
    }

    public interface LyricCallback {
        void onLyric(Lyric lyric, boolean fromCache);

        void onError(String msg);
    }

    public interface SearchCallback {
        void onSongs(List<Song> songs);

        void onError(String msg);
    }

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private NeteaseLyric() {
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("Referer", "https://music.163.com/");
        return h;
    }

    private static File cacheFile(Context ctx, String key) {
        File dir = new File(ctx.getCacheDir(), "lyrics");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "ly_" + Integer.toHexString(key.hashCode()) + ".lrc");
    }

    /** Auto search + fetch lyric for a query, cached by cacheKey (the B站 track id). */
    public static void getLyric(final Context ctx, final String cacheKey,
                                 final String query, final LyricCallback cb) {
        final File f = cacheFile(ctx, cacheKey);
        if (f.exists() && f.length() > 0) {
            try {
                Lyric ly = Lyric.parse(readFile(f));
                if (!ly.lines.isEmpty()) {
                    cb.onLyric(ly, true);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Song s = searchBest(query);
                    if (s == null) {
                        err(cb, "未找到匹配的歌曲");
                        return;
                    }
                    String lrc = lyricOf(s.id);
                    if (lrc == null || lrc.trim().isEmpty()) {
                        err(cb, "该歌曲暂无歌词");
                        return;
                    }
                    try {
                        writeFile(f, lrc);
                    } catch (Exception ignored) {
                    }
                    ui(cb, Lyric.parse(lrc), false);
                } catch (Exception e) {
                    err(cb, e.getMessage());
                }
            }
        }).start();
    }

    /** Search for candidate songs by keyword. */
    public static void search(final String query, final SearchCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Song> songs = searchList(query);
                    if (songs == null || songs.isEmpty()) {
                        errS(cb, "未找到结果");
                        return;
                    }
                    uiS(cb, songs);
                } catch (Exception e) {
                    errS(cb, e.getMessage());
                }
            }
        }).start();
    }

    /** Fetch lyric for a specific Netease song id, cached by cacheKey. */
    public static void lyricById(final Context ctx, final String cacheKey,
                                 final String songId, final LyricCallback cb) {
        final File f = cacheFile(ctx, cacheKey);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String lrc = lyricOf(songId);
                    if (lrc == null || lrc.trim().isEmpty()) {
                        err(cb, "该歌曲暂无歌词");
                        return;
                    }
                    try {
                        writeFile(f, lrc);
                    } catch (Exception ignored) {
                    }
                    ui(cb, Lyric.parse(lrc), false);
                } catch (Exception e) {
                    err(cb, e.getMessage());
                }
            }
        }).start();
    }

    /** Strip common B站 title tags so the query matches a real song name. */
    public static String cleanQuery(String title) {
        if (title == null) return "";
        String t = title;
        t = t.replaceAll("\\[[^\\]]*\\]", " ");
        t = t.replaceAll("【[^】]*】", " ");
        t = t.replaceAll("（[^）]*）", " ");
        t = t.replaceAll("\\([^)]*\\)", " ");
        t = t.replaceAll("「[^」]*」", " ");
        t = t.replaceAll("[#＃]\\S+", " ");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private static Song searchBest(String query) throws Exception {
        List<Song> l = searchList(query);
        return (l != null && !l.isEmpty()) ? l.get(0) : null;
    }

    private static List<Song> searchList(String query) throws Exception {
        String url = "https://music.163.com/api/cloudsearch/pc?type=1&s="
                + URLEncoder.encode(query, "UTF-8") + "&limit=10&offset=0";
        Http.Resp r = Http.get(url, headers());
        if (!r.ok() || r.body == null) return null;
        JSONObject o = new JSONObject(r.body);
        if (!o.has("result")) return null;
        JSONObject res = o.getJSONObject("result");
        if (!res.has("songs")) return null;
        JSONArray arr = res.getJSONArray("songs");
        List<Song> songs = new ArrayList<Song>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.getJSONObject(i);
            Song song = new Song();
            song.id = s.optString("id");
            song.name = s.optString("name");
            JSONArray arts = s.optJSONArray("artists");
            StringBuilder ab = new StringBuilder();
            if (arts != null) {
                for (int j = 0; j < arts.length(); j++) {
                    if (j > 0) ab.append("/");
                    ab.append(arts.getJSONObject(j).optString("name"));
                }
            }
            song.artist = ab.toString();
            JSONObject al = s.optJSONObject("album");
            song.album = al != null ? al.optString("name") : "";
            songs.add(song);
        }
        return songs;
    }

    private static String lyricOf(String id) throws Exception {
        String url = "https://music.163.com/api/song/lyric?id=" + id + "&lv=1&kv=1&tv=1";
        Http.Resp r = Http.get(url, headers());
        if (!r.ok() || r.body == null) return null;
        JSONObject o = new JSONObject(r.body);
        if (!o.has("lrc")) return null;
        JSONObject lrc = o.getJSONObject("lrc");
        return lrc.optString("lyric");
    }

    private static void ui(final LyricCallback cb, final Lyric ly, final boolean cache) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                cb.onLyric(ly, cache);
            }
        });
    }

    private static void err(final LyricCallback cb, final String m) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                cb.onError(m);
            }
        });
    }

    private static void uiS(final SearchCallback cb, final List<Song> s) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                cb.onSongs(s);
            }
        });
    }

    private static void errS(final SearchCallback cb, final String m) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                cb.onError(m);
            }
        });
    }

    private static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[8192];
        int n;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeFile(File f, String s) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.close();
    }
}
