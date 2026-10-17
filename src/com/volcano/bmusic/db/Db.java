package com.volcano.bmusic.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.volcano.bmusic.model.Playlist;
import com.volcano.bmusic.model.Track;

import java.util.ArrayList;
import java.util.List;

public class Db extends SQLiteOpenHelper {

    private static final String NAME = "bmusic.db";
    private static final int VER = 1;
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
                + "cached INTEGER DEFAULT 0, path TEXT, fav INTEGER DEFAULT 0)");
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
        d.execSQL("DROP TABLE IF EXISTS tracks");
        d.execSQL("DROP TABLE IF EXISTS playlists");
        d.execSQL("DROP TABLE IF EXISTS pl_tracks");
        d.execSQL("DROP TABLE IF EXISTS history");
        onCreate(d);
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
        return t;
    }

    private static final String TRACK_COLS =
            "id,bvid,cid,page,title,author,cover,duration,added,last_played,plays,cached,path,fav";

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

    public synchronized void upsert(Track t) {
        SQLiteDatabase d = getWritableDatabase();
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
        d.insertWithOnConflict("tracks", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
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
