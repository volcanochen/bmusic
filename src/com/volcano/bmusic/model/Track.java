package com.volcano.bmusic.model;

import java.io.Serializable;

/** One audio item = one page of a Bilibili video. */
public class Track implements Serializable {
    private static final long serialVersionUID = 1L;

    /** bvid + "_p" + page, primary key */
    public String id;
    public String bvid;
    public long cid;
    public int page;
    public String title;
    public String author;
    public String cover;
    /** ms */
    public long duration;
    public long added;
    public long lastPlayed;
    public int plays;
    /** local cache ready */
    public boolean cached;
    public String path;
    public boolean fav;

    /** runtime: remote audio url, not persisted */
    public transient String streamUrl;
    public transient long streamAt;
    /** runtime: download progress -1 = idle, 0..100 */
    public transient int dlProgress = -1;

    public Track() {
    }

    public Track(String id, String bvid, long cid, int page) {
        this.id = id;
        this.bvid = bvid;
        this.cid = cid;
        this.page = page;
    }

    public String playTitle() {
        if (title != null && !title.isEmpty()) return title;
        return bvid;
    }

    /** Display name: uses part title when multi-part. */
    public String subTitle() {
        return author == null || author.isEmpty() ? "" : author;
    }

    /** true when a readable local cache file exists */
    public boolean hasLocal() {
        if (!cached || path == null || path.isEmpty()) return false;
        java.io.File f = new java.io.File(path);
        return f.exists() && f.length() > 1024;
    }

    public static String fmt(long ms) {
        if (ms <= 0) return "--:--";
        long s = ms / 1000;
        long m = s / 60;
        long h = m / 60;
        s = s % 60;
        if (h > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m % 60, s);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }
}
