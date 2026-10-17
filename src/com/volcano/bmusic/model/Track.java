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
    /**
     * 视频原始标题（多 P 时 {@link #title} 只是分 P 名，这里保留合集标题，
     * 歌手/专辑推断要用）。
     */
    public String srcTitle;
    /** 推断出的歌名（去掉 UP 主前缀、MV/官方等噪声），空则退回 title。 */
    public String song;
    /** 推断出的歌手。 */
    public String artist;
    /** 推断出的专辑。 */
    public String album;
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
        return displayTitle();
    }

    /** 展示用歌名：优先用识别出的歌名，否则用原标题。 */
    public String displayTitle() {
        if (song != null && !song.isEmpty()) return song;
        if (title != null && !title.isEmpty()) return title;
        return bvid;
    }

    /** 展示用副标题：歌手 · 专辑（能识别到哪个用哪个），都没有则用 UP 主。 */
    public String subTitle() {
        StringBuilder sb = new StringBuilder();
        if (artist != null && !artist.isEmpty()) sb.append(artist);
        if (album != null && !album.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(album);
        }
        if (sb.length() == 0) {
            return author == null ? "" : author;
        }
        return sb.toString();
    }

    /** 列表主标题：歌手 - 歌名；歌手未解析到写 Unknown。 */
    public String listTitle() {
        String a = (artist != null && !artist.isEmpty()) ? artist : "Unknown";
        return a + " - " + displayTitle();
    }

    /** 列表副标题（小字）：专辑；未解析到写 Unknown。 */
    public String listSub() {
        if (album != null && !album.isEmpty()) return album;
        return "Unknown";
    }

    /** 是否已经推断过歌手/歌名。 */
    public boolean hasMeta() {
        return (artist != null && !artist.isEmpty()) || (song != null && !song.isEmpty());
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
