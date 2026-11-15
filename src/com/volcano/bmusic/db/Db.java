package com.volcano.bmusic.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.volcano.bmusic.model.Playlist;
import com.volcano.bmusic.model.Track;
import com.volcano.bmusic.util.TrackKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Db extends SQLiteOpenHelper {

    private static final String NAME = "bmusic.db";
    private static final int VER = 3;
    private static Db sInst;

    public static synchronized Db get(Context c) {
        if (sInst == null) sInst = new Db(c.getApplicationContext());
        return sInst;
    }

    private Db(Context c) {
        super(c, NAME, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE IF NOT EXISTS tracks("
                + "id TEXT PRIMARY KEY,"
                + "bvid TEXT, cid INTEGER, page INTEGER,"
                + "title TEXT, author TEXT, cover TEXT, duration INTEGER,"
                + "added INTEGER, last_played INTEGER, plays INTEGER,"
                + "cached INTEGER DEFAULT 0, path TEXT, fav INTEGER DEFAULT 0,"
                + "src_title TEXT, song TEXT, artist TEXT, album TEXT,"
                + "link TEXT)");
        // 同一链接在曲库里只能有一行；link 是链接身份，不是来源/渠道信息
        d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_tracks_link ON tracks(link)");
        d.execSQL("CREATE TABLE IF NOT EXISTS playlists("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT, created INTEGER, kind INTEGER DEFAULT 0)");
        d.execSQL("CREATE TABLE IF NOT EXISTS pl_tracks("
                + "pid INTEGER, tid TEXT, ord INTEGER, PRIMARY KEY(pid, tid))");
        d.execSQL("CREATE TABLE IF NOT EXISTS history("
                + "tid TEXT, played INTEGER)");
        ContentValues cv = new ContentValues();
        cv.put("name", "我喜欢的音乐");
        cv.put("created", System.currentTimeMillis());
        cv.put("kind", 1);
        d.insert("playlists", null, cv);
    }

    @Override
    public void onUpgrade(SQLiteDatabase d, int oldV, int newV) {
        // v2: 歌名/歌手/专辑识别 —— 只加列，不重建表，老用户的曲库/歌单原样保留
        if (oldV < 2) {
            addColumn(d, "src_title", "TEXT");
            addColumn(d, "song", "TEXT");
            addColumn(d, "artist", "TEXT");
            addColumn(d, "album", "TEXT");
        }
        // v3: 按链接去重。先回填 link、合并老数据里的重复行并迁移歌单/历史引用，
        // 最后才建唯一索引（重复值还在时建索引会失败）。
        if (oldV < 3) {
            addColumn(d, "link", "TEXT");
            backfillLinks(d);
            dedupIn(d);
            try {
                d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_tracks_link ON tracks(link)");
            } catch (Exception ignored) {
            }
        }
    }

    private static void backfillLinks(SQLiteDatabase d) {
        Cursor c = null;
        try {
            c = d.rawQuery("SELECT " + TRACK_COLS + " FROM tracks", null);
            ArrayList<String[]> rows = new ArrayList<String[]>();
            while (c.moveToNext()) {
                Track t = readTrack(c);
                rows.add(new String[]{t.id, TrackKey.of(t)});
            }
            c.close();
            c = null;
            for (String[] row : rows) {
                d.execSQL("UPDATE tracks SET link=? WHERE id=?", new Object[]{row[1], row[0]});
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    private static void addColumn(SQLiteDatabase d, String name, String type) {
        try {
            d.execSQL("ALTER TABLE tracks ADD COLUMN " + name + " " + type);
        } catch (Exception ignored) {
            // column already exists
        }
    }

    // ------------------------------------------------------------- tracks

    private static Track readTrack(Cursor c) {
        Track t = new Track();
        t.id = c.getString(c.getColumnIndex("id"));
        t.bvid = c.getString(c.getColumnIndex("bvid"));
        t.cid = c.getLong(c.getColumnIndex("cid"));
        t.page = c.getInt(c.getColumnIndex("page"));
        t.title = c.getString(c.getColumnIndex("title"));
        t.author = c.getString(c.getColumnIndex("author"));
        t.cover = c.getString(c.getColumnIndex("cover"));
        t.duration = c.getLong(c.getColumnIndex("duration"));
        t.added = c.getLong(c.getColumnIndex("added"));
        t.lastPlayed = c.getLong(c.getColumnIndex("last_played"));
        t.plays = c.getInt(c.getColumnIndex("plays"));
        t.cached = c.getInt(c.getColumnIndex("cached")) == 1;
        t.path = c.getString(c.getColumnIndex("path"));
        t.fav = c.getInt(c.getColumnIndex("fav")) == 1;
        t.srcTitle = optString(c, "src_title");
        t.song = optString(c, "song");
        t.artist = optString(c, "artist");
        t.album = optString(c, "album");
        return t;
    }

    private static String optString(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 ? null : c.getString(i);
    }

    private static final String TRACK_COLS =
            "id,bvid,cid,page,title,author,cover,duration,added,last_played,plays,cached,path,fav,"
                    + "src_title,song,artist,album";

    public synchronized Track get(String id) {
        SQLiteDatabase d = getReadableDatabase();
        Cursor c = null;
        try {
            c = d.rawQuery("SELECT " + TRACK_COLS + " FROM tracks WHERE id=?", new String[]{id});
            if (c.moveToFirst()) return readTrack(c);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    public synchronized ArrayList<Track> allTracks() {
        return queryList("SELECT " + TRACK_COLS + " FROM tracks ORDER BY added DESC", null);
    }

    public synchronized ArrayList<Track> recent(int limit) {
        return queryList("SELECT t." + TRACK_COLS.replace(",", ",t.")
                + " FROM history h JOIN tracks t ON t.id=h.tid"
                + " GROUP BY h.tid ORDER BY MAX(h.played) DESC LIMIT " + limit, null);
    }

    public synchronized ArrayList<Track> tracksOf(long pid) {
        return queryList("SELECT t." + TRACK_COLS.replace(",", ",t.")
                + " FROM pl_tracks p JOIN tracks t ON t.id=p.tid"
                + " WHERE p.pid=? ORDER BY p.ord ASC", new String[]{String.valueOf(pid)});
    }

    private ArrayList<Track> queryList(String sql, String[] args) {
        ArrayList<Track> out = new ArrayList<Track>();
        SQLiteDatabase d = getReadableDatabase();
        Cursor c = null;
        try {
            c = d.rawQuery(sql, args);
            while (c.moveToNext()) out.add(readTrack(c));
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    private static ContentValues toValues(Track t) {
        ContentValues cv = new ContentValues();
        cv.put("id", t.id);
        cv.put("bvid", t.bvid);
        cv.put("cid", t.cid);
        cv.put("page", t.page);
        cv.put("title", t.title);
        cv.put("author", t.author);
        cv.put("cover", t.cover);
        cv.put("duration", t.duration);
        cv.put("added", t.added);
        cv.put("last_played", t.lastPlayed);
        cv.put("plays", t.plays);
        cv.put("cached", t.cached ? 1 : 0);
        cv.put("path", t.path);
        cv.put("fav", t.fav ? 1 : 0);
        cv.put("src_title", t.srcTitle);
        cv.put("song", t.song);
        cv.put("artist", t.artist);
        cv.put("album", t.album);
        cv.put("link", TrackKey.of(t));
        return cv;
    }

    public synchronized void upsert(Track t) {
        SQLiteDatabase d = getWritableDatabase();
        d.insertWithOnConflict("tracks", null, toValues(t), SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ----------------------------------------------------------- 链接去重

    /**
     * 按链接查找曲库里已有的条目（不看 id 字符串）。
     * 所有「添加」入口都应该先用它判断，避免同链接不同 id 造成重复。
     */
    public synchronized Track findByLink(String link) {
        if (link == null) return null;
        SQLiteDatabase d = getReadableDatabase();
        Cursor c = null;
        try {
            c = d.rawQuery("SELECT " + TRACK_COLS + " FROM tracks WHERE link=? LIMIT 1",
                    new String[]{link});
            if (c.moveToFirst()) return readTrack(c);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        // 兜底：万一 link 列缺失或没回填，退回内存扫描
        for (Track t : allTracks()) {
            if (link.equals(TrackKey.of(t))) return t;
        }
        return null;
    }

    /** 手动/开机去重：返回被合并掉的重复行数。 */
    public synchronized int dedup() {
        SQLiteDatabase d = getWritableDatabase();
        try {
            d.beginTransaction();
            int n = dedupIn(d);
            try {
                d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_tracks_link ON tracks(link)");
            } catch (Exception ignored) {
            }
            d.setTransactionSuccessful();
            return n;
        } catch (Exception ignored) {
            return 0;
        } finally {
            try {
                d.endTransaction();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 合并同一 link 的多行：保留信息最全的一行，把重复行的歌单/历史引用迁过去，
     * 然后删掉重复行。重复音频文件不删除（不擅自删用户数据），只清数据库记录。
     * 使用调用方给的 db 对象，避免 onUpgrade 里递归获取连接。
     */
    private static int dedupIn(SQLiteDatabase d) {
        ArrayList<Track> all = new ArrayList<Track>();
        Cursor c = null;
        try {
            c = d.rawQuery("SELECT " + TRACK_COLS + " FROM tracks ORDER BY added ASC, rowid ASC", null);
            while (c.moveToNext()) all.add(readTrack(c));
        } catch (Exception e) {
            return 0;
        } finally {
            if (c != null) c.close();
        }

        LinkedHashMap<String, ArrayList<Track>> groups = new LinkedHashMap<String, ArrayList<Track>>();
        for (Track t : all) {
            String k = TrackKey.of(t);
            if (k == null) continue;
            ArrayList<Track> g = groups.get(k);
            if (g == null) {
                g = new ArrayList<Track>();
                groups.put(k, g);
            }
            g.add(t);
        }

        int removed = 0;
        for (Map.Entry<String, ArrayList<Track>> e : groups.entrySet()) {
            String key = e.getKey();
            ArrayList<Track> g = e.getValue();
            if (g.size() < 2) {
                d.execSQL("UPDATE tracks SET link=? WHERE id=?", new Object[]{key, g.get(0).id});
                continue;
            }
            Track keep = g.get(0);
            for (int i = 1; i < g.size(); i++) {
                if (better(g.get(i), keep, key)) keep = g.get(i);
            }
            for (Track t : g) {
                if (t == keep) continue;
                mergeInto(keep, t);
                // 歌单引用：同样的位置改指向保留行，重复位置直接忽略
                Cursor pc = null;
                ArrayList<Object[]> refs = new ArrayList<Object[]>();
                try {
                    pc = d.rawQuery("SELECT pid, ord FROM pl_tracks WHERE tid=?",
                            new String[]{t.id});
                    while (pc.moveToNext()) refs.add(new Object[]{pc.getLong(0), pc.getInt(1)});
                } catch (Exception ignored) {
                } finally {
                    if (pc != null) pc.close();
                }
                for (Object[] r : refs) {
                    ContentValues cv = new ContentValues();
                    cv.put("pid", (Long) r[0]);
                    cv.put("tid", keep.id);
                    cv.put("ord", (Integer) r[1]);
                    d.insertWithOnConflict("pl_tracks", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                }
                d.delete("pl_tracks", "tid=?", new String[]{t.id});
                d.execSQL("UPDATE history SET tid=? WHERE tid=?", new Object[]{keep.id, t.id});
                d.delete("tracks", "id=?", new String[]{t.id});
                removed++;
            }
            ContentValues cv = toValues(keep);
            cv.put("link", key); // 以分组判定时的链接为准，避免合并后字段变化导致 key 漂移
            d.update("tracks", cv, "id=?", new String[]{keep.id});
        }
        return removed;
    }

    /**
     * 优先保留有本地缓存的；其次保留 id 就是规范链接的那一行，避免保留老格式 id
     * 导致播放队列/断点里记录的 id 失效；再比较播放次数、最近播放、最早加入时间。
     */
    private static boolean better(Track a, Track b, String key) {
        boolean ac = a.cached && a.path != null && a.path.length() > 0;
        boolean bc = b.cached && b.path != null && b.path.length() > 0;
        if (ac != bc) return ac;
        boolean an = key.equals(a.id), bn = key.equals(b.id);
        if (an != bn) return an;
        if (a.plays != b.plays) return a.plays > b.plays;
        if (a.lastPlayed != b.lastPlayed) return a.lastPlayed > b.lastPlayed;
        return a.added < b.added;
    }

    private static void mergeInto(Track w, Track t) {
        if (w.cid <= 0) w.cid = t.cid;
        // 只有「保留行为空」且「被合并行有值」时才补，避免用空值/null 覆盖已有元信息
        if (empty(w.bvid) && !empty(t.bvid)) w.bvid = t.bvid;
        if (w.page <= 0 && t.page > 0) w.page = t.page;
        if (empty(w.title) && !empty(t.title)) w.title = t.title;
        if (empty(w.author) && !empty(t.author)) w.author = t.author;
        if (empty(w.cover) && !empty(t.cover)) w.cover = t.cover;
        if (empty(w.srcTitle) && !empty(t.srcTitle)) w.srcTitle = t.srcTitle;
        if (empty(w.song) && !empty(t.song)) w.song = t.song;
        if (empty(w.artist) && !empty(t.artist)) w.artist = t.artist;
        if (empty(w.album) && !empty(t.album)) w.album = t.album;
        if (w.duration <= 0) w.duration = t.duration;
        if (w.added <= 0 || (t.added > 0 && t.added < w.added)) w.added = t.added;
        w.lastPlayed = Math.max(w.lastPlayed, t.lastPlayed);
        // 同一首歌被拆成两行时分别计数，取较大值而不是相加
        w.plays = Math.max(w.plays, t.plays);
        w.fav = w.fav || t.fav;
        boolean wc = w.cached && w.path != null && w.path.length() > 0;
        boolean tc = t.cached && t.path != null && t.path.length() > 0;
        if (!wc && tc) {
            w.cached = true;
            w.path = t.path;
        }
    }

    private static boolean empty(String s) {
        return s == null || s.length() == 0;
    }

    /** 只更新识别出来的元信息（歌手/歌名/专辑），不碰播放统计与缓存字段。 */
    public synchronized void setMeta(String id, String song, String artist, String album) {
        SQLiteDatabase d = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("song", song);
        cv.put("artist", artist);
        cv.put("album", album);
        d.update("tracks", cv, "id=?", new String[]{id});
    }

    public synchronized void setCache(String id, boolean cached, String path) {
        SQLiteDatabase d = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("cached", cached ? 1 : 0);
        cv.put("path", path);
        d.update("tracks", cv, "id=?", new String[]{id});
    }

    public synchronized void setDuration(String id, long ms) {
        SQLiteDatabase d = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("duration", ms);
        d.update("tracks", cv, "id=?", new String[]{id});
    }

    public synchronized void setFav(String id, boolean fav) {
        SQLiteDatabase d = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("fav", fav ? 1 : 0);
        d.update("tracks", cv, "id=?", new String[]{id});
    }

    public synchronized void touchPlay(String id) {
        long now = System.currentTimeMillis();
        SQLiteDatabase d = getWritableDatabase();
        d.execSQL("UPDATE tracks SET plays=plays+1, last_played=? WHERE id=?",
                new Object[]{now, id});
        ContentValues hv = new ContentValues();
        hv.put("tid", id);
        hv.put("played", now);
        d.insert("history", null, hv);
        try {
            d.delete("history", "rowid NOT IN (SELECT rowid FROM history ORDER BY played DESC LIMIT 300)", null);
        } catch (Exception ignored) {
        }
    }

    public synchronized void execResetCache() {
        try {
            getWritableDatabase().execSQL("UPDATE tracks SET cached=0, path=NULL");
        } catch (Exception ignored) {
        }
    }

    // -------------------------------------------------------------- backup

    /** 全部播放历史 {tid(String), played(Long)}，按时间升序。 */
    public synchronized ArrayList<Object[]> historyAll() {
        SQLiteDatabase d = getReadableDatabase();
        Cursor c = null;
        ArrayList<Object[]> out = new ArrayList<Object[]>();
        try {
            c = d.rawQuery("SELECT tid, played FROM history ORDER BY played ASC", null);
            while (c.moveToNext()) out.add(new Object[]{c.getString(0), c.getLong(1)});
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public synchronized void insertHistory(String tid, long played) {
        ContentValues hv = new ContentValues();
        hv.put("tid", tid);
        hv.put("played", played);
        getWritableDatabase().insert("history", null, hv);
    }

    /** 全部歌单元数据 {id(Long), name(String), created(Long), kind(Integer)}。 */
    public synchronized ArrayList<Object[]> playlistsFull() {
        SQLiteDatabase d = getReadableDatabase();
        Cursor c = null;
        ArrayList<Object[]> out = new ArrayList<Object[]>();
        try {
            c = d.rawQuery("SELECT id, name, created, kind FROM playlists ORDER BY created ASC", null);
            while (c.moveToNext()) {
                out.add(new Object[]{c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3)});
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public synchronized void deleteTrack(String id) {
        SQLiteDatabase d = getWritableDatabase();
        d.delete("tracks", "id=?", new String[]{id});
        d.delete("pl_tracks", "tid=?", new String[]{id});
        d.delete("history", "tid=?", new String[]{id});
    }

    /** Remove every track from the library (keeps playlist rows but empties them). */
    public synchronized void clearTracks() {
        SQLiteDatabase d = getWritableDatabase();
        d.delete("tracks", null, null);
        d.delete("pl_tracks", null, null);
        d.delete("history", null, null);
    }

    // ---------------------------------------------------------- playlists

    public synchronized List<Playlist> playlists() {
        List<Playlist> out = new ArrayList<Playlist>();
        SQLiteDatabase d = getReadableDatabase();
        Cursor c = null;
        try {
            c = d.rawQuery("SELECT p.id, p.name, p.created,"
                    + " (SELECT COUNT(*) FROM pl_tracks x WHERE x.pid=p.id) AS cnt"
                    + " FROM playlists p ORDER BY p.kind DESC, p.created ASC", null);
            while (c.moveToNext()) {
                Playlist p = new Playlist();
                p.id = c.getLong(c.getColumnIndex("id"));
                p.name = c.getString(c.getColumnIndex("name"));
                p.created = c.getLong(c.getColumnIndex("created"));
                p.count = c.getInt(c.getColumnIndex("cnt"));
                out.add(p);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** id of the built-in favourites playlist (created on demand). */
    public synchronized long favId() {
        SQLiteDatabase d = getReadableDatabase();
        Cursor c = null;
        try {
            c = d.rawQuery("SELECT id FROM playlists WHERE kind=1 LIMIT 1", null);
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        ContentValues cv = new ContentValues();
        cv.put("name", "我喜欢的音乐");
        cv.put("created", System.currentTimeMillis());
        cv.put("kind", 1);
        return d.insert("playlists", null, cv);
    }

    public synchronized long addPlaylist(String name) {
        SQLiteDatabase d = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("created", System.currentTimeMillis());
        cv.put("kind", 0);
        return d.insert("playlists", null, cv);
    }

    public synchronized void renamePlaylist(long id, String name) {
        SQLiteDatabase d = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        d.update("playlists", cv, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void delPlaylist(long id) {
        SQLiteDatabase d = getWritableDatabase();
        d.delete("playlists", "id=? AND kind=0", new String[]{String.valueOf(id)});
        d.delete("pl_tracks", "pid=?", new String[]{String.valueOf(id)});
    }

    public synchronized void addTo(long pid, String tid) {
        SQLiteDatabase d = getWritableDatabase();
        Cursor c = null;
        int max = 0;
        try {
            c = d.rawQuery("SELECT MAX(ord) FROM pl_tracks WHERE pid=?", new String[]{String.valueOf(pid)});
            if (c.moveToFirst()) max = c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        ContentValues cv = new ContentValues();
        cv.put("pid", pid);
        cv.put("tid", tid);
        cv.put("ord", max + 1);
        d.insertWithOnConflict("pl_tracks", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public synchronized void removeFrom(long pid, String tid) {
        SQLiteDatabase d = getWritableDatabase();
        d.delete("pl_tracks", "pid=? AND tid=?", new String[]{String.valueOf(pid), tid});
    }

    public synchronized boolean inPlaylist(long pid, String tid) {
        SQLiteDatabase d = getReadableDatabase();
        Cursor c = null;
        try {
            c = d.rawQuery("SELECT 1 FROM pl_tracks WHERE pid=? AND tid=? LIMIT 1",
                    new String[]{String.valueOf(pid), tid});
            return c.moveToFirst();
        } catch (Exception ignored) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** Re-write order for the given playlist using the supplied id sequence. */
    public synchronized void reorder(long pid, List<String> ids) {
        SQLiteDatabase d = getWritableDatabase();
        try {
            d.beginTransaction();
            for (int i = 0; i < ids.size(); i++) {
                ContentValues cv = new ContentValues();
                cv.put("ord", i + 1);
                d.update("pl_tracks", cv, "pid=? AND tid=?",
                        new String[]{String.valueOf(pid), ids.get(i)});
            }
            d.setTransactionSuccessful();
        } catch (Exception ignored) {
        } finally {
            try {
                d.endTransaction();
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized void clearPlaylist(long pid) {
        SQLiteDatabase d = getWritableDatabase();
        d.delete("pl_tracks", "pid=?", new String[]{String.valueOf(pid)});
    }
}
