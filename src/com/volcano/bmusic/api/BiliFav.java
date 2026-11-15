package com.volcano.bmusic.api;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.volcano.bmusic.App;
import com.volcano.bmusic.db.Db;
import com.volcano.bmusic.model.Track;
import com.volcano.bmusic.util.MetaResolver;
import com.volcano.bmusic.util.TrackKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The "bmusic" favourite folder on bilibili: find it, create it when missing,
 * and import everything in it into the local library.
 *
 * A folder entry is not always a single video: type 21 is a whole video
 * collection (UGC season) and has no bvid, only a season id. Those are expanded
 * into every member video. Multi-part videos import all parts, not just P1.
 */
public final class BiliFav {

    public static final String NAME = "bmusic";

    private static final String LIST_ALL =
            "https://api.bilibili.com/x/v3/fav/folder/created/list-all";
    private static final String LIST =
            "https://api.bilibili.com/x/v3/fav/folder/created/list";
    private static final String ADD = "https://api.bilibili.com/x/v3/fav/folder/add";
    private static final String RESOURCES =
            "https://api.bilibili.com/x/v3/fav/resource/list";
    private static final String SEASON_ARCHIVES =
            "https://api.bilibili.com/x/polymer/web-space/seasons_archives_list";
    private static final String REFERER = "https://space.bilibili.com/";
    private static final int PAGE_SIZE = 30;

    /** folder entry types returned by x/v3/fav/resource/list */
    private static final int TYPE_VIDEO = 2;
    private static final int TYPE_COLLECTION = 21;

    public static class Folder {
        public long id;
        public String title = "";
        public int count;
    }

    public static class Media {
        public String bvid = "";
        public long aid;
        public String title = "";
        public String cover = "";
        public String author = "";
        public long mid;
        /** 2 = video, 12 = audio, 21 = video collection (season) */
        public int type = TYPE_VIDEO;
        /** part count reported by the folder (informational) */
        public int page;
        /** for type 21: the season id, i.e. the entry's "id" */
        public long seasonId;
    }

    public static class Result {
        /** entries after expanding collections (one entry may yield several tracks) */
        public int total;
        public int added;
        public int skipped;
        public int failed;
        public int collections;
        public int expanded;
        public String firstError;
    }

    public static interface Progress {
        void on(int done, int total, String title);
    }

    /** Look for a folder named "bmusic", create a public one if it isn't there. */
    public static Folder ensureFolder() throws Exception {
        long mid = currentMid();
        Folder f = find(mid);
        if (f != null) {
            App.setFavFolderId(f.id);
            return f;
        }
        Map<String, String> p = new HashMap<String, String>();
        p.put("title", NAME);
        p.put("intro", "BMusic 曲库同步");
        p.put("privacy", "0");
        p.put("cover", "");
        Bili.postForm(ADD, p, REFERER);
        // re-read: the id comes back from the server, this also handles mirrors
        // that return a slightly different payload
        Folder created = find(mid);
        if (created == null) throw new Bili.ApiException(-1, "已提交创建，但没能读回 bmusic 收藏夹，请稍后重试");
        App.setFavFolderId(created.id);
        return created;
    }

    private static long currentMid() throws Exception {
        long mid = App.biliMid();
        if (mid > 0) return mid;
        BiliAuth.Account a = BiliAuth.verify();
        if (a == null || a.mid <= 0) {
            throw new Bili.ApiException(-101, "账号未登录，请先在「B站账号」里授权登录");
        }
        return a.mid;
    }

    private static Folder find(long mid) throws Exception {
        List<Folder> all = folders(mid);
        for (Folder f : all) {
            if (NAME.equalsIgnoreCase(f.title.trim())) return f;
        }
        return null;
    }

    private static List<Folder> folders(long mid) throws Exception {
        List<Folder> out = new ArrayList<Folder>();
        Map<String, String> p = new HashMap<String, String>();
        p.put("up_mid", String.valueOf(mid));
        p.put("type", "2");
        JSONObject jo = null;
        try {
            jo = Bili.api(LIST_ALL, p, REFERER, true);
        } catch (Exception e) {
            Map<String, String> p2 = new HashMap<String, String>();
            p2.put("up_mid", String.valueOf(mid));
            p2.put("pn", "1");
            p2.put("ps", "100");
            jo = Bili.api(LIST, p2, REFERER, true);
        }
        JSONObject d = jo.optJSONObject("data");
        JSONArray arr = null;
        if (d != null) arr = d.optJSONArray("list");
        if (arr == null && jo.opt("data") instanceof JSONArray) arr = jo.optJSONArray("data");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Folder f = new Folder();
            f.id = o.optLong("id", o.optLong("media_id", 0));
            f.title = o.optString("title", o.optString("name", ""));
            f.count = o.optInt("media_count", 0);
            if (f.id > 0) out.add(f);
        }
        return out;
    }

    /** All entries of a folder, newest first, across pages. */
    public static List<Media> list(long folderId) throws Exception {
        List<Media> out = new ArrayList<Media>();
        for (int pn = 1; pn <= 20; pn++) {
            Map<String, String> p = new HashMap<String, String>();
            p.put("media_id", String.valueOf(folderId));
            p.put("pn", String.valueOf(pn));
            p.put("ps", "20");
            p.put("platform", "web");
            p.put("order", "mtime");
            p.put("type", "0");
            JSONObject jo = Bili.api(RESOURCES, p, REFERER, true);
            JSONObject d = jo.optJSONObject("data");
            if (d == null) break;
            JSONArray arr = d.optJSONArray("medias");
            if (arr == null || arr.length() == 0) break;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                // attr != 0 means the item was deleted / taken down
                if (o.optInt("attr", 0) != 0) continue;
                Media m = new Media();
                m.type = o.optInt("type", TYPE_VIDEO);
                m.title = o.optString("title", "");
                m.cover = o.optString("cover", "");
                m.page = o.optInt("page", 0);
                long rawId = o.optLong("id", 0);
                JSONObject up = o.optJSONObject("upper");
                m.author = up != null ? up.optString("name", "") : "";
                m.mid = up != null ? up.optLong("mid", 0) : 0;
                m.bvid = o.optString("bvid", o.optString("bv_id", ""));
                if (m.type == TYPE_COLLECTION) {
                    // "id" is a season id here, NOT an avid, and bvid is usually empty
                    m.seasonId = rawId;
                    if (m.seasonId <= 0) continue;
                } else if (m.type == TYPE_VIDEO) {
                    m.aid = rawId;
                    if (m.bvid.isEmpty() && m.aid <= 0) continue;
                } else {
                    // audio (12) and other media types cannot be turned into a video track
                    continue;
                }
                if (m.title.contains("已失效")) continue;
                out.add(m);
            }
            if (arr.length() < 20) break;
            try {
                Thread.sleep(250);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Every video of a UGC season (video collection), across pages. */
    private static List<Media> seasonVideos(Media m) throws Exception {
        List<Media> out = new ArrayList<Media>();
        // The endpoint accepts any non-negative mid; the owner's mid is the safest choice.
        long mid = m.mid > 0 ? m.mid : 1;
        String ref = "https://space.bilibili.com/" + mid
                + "/channel/seriesdetail?sid=" + m.seasonId;
        for (int pn = 1; pn <= 40; pn++) {
            Map<String, String> p = new HashMap<String, String>();
            p.put("mid", String.valueOf(mid));
            p.put("season_id", String.valueOf(m.seasonId));
            p.put("sort_reverse", "false");
            p.put("page_num", String.valueOf(pn));
            p.put("page_size", String.valueOf(PAGE_SIZE));
            JSONObject jo = Bili.api(SEASON_ARCHIVES, p, ref, true);
            JSONObject d = jo.optJSONObject("data");
            if (d == null) break;
            JSONArray arr = d.optJSONArray("archives");
            if (arr == null || arr.length() == 0) break;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Media v = new Media();
                v.type = TYPE_VIDEO;
                v.bvid = o.optString("bvid", "");
                v.aid = o.optLong("aid", 0);
                v.title = o.optString("title", m.title);
                v.cover = o.optString("pic", m.cover);
                v.author = m.author;
                v.mid = m.mid;
                if (v.bvid.isEmpty() && v.aid <= 0) continue;
                out.add(v);
            }
            JSONObject page = d.optJSONObject("page");
            int total = page != null ? page.optInt("total", 0) : 0;
            if (arr.length() < PAGE_SIZE || (total > 0 && out.size() >= total)) break;
            try {
                Thread.sleep(250);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Replace collection entries with all of their member videos. */
    private static List<Media> expand(List<Media> src, Result r) {
        List<Media> out = new ArrayList<Media>();
        for (Media m : src) {
            if (m.type != TYPE_COLLECTION) {
                out.add(m);
                continue;
            }
            r.collections++;
            try {
                List<Media> videos = seasonVideos(m);
                if (videos.isEmpty()) {
                    r.failed++;
                    if (r.firstError == null) r.firstError = "合集《" + m.title + "》没有可读视频";
                } else {
                    out.addAll(videos);
                    r.expanded += videos.size();
                }
            } catch (Exception e) {
                r.failed++;
                if (r.firstError == null) {
                    r.firstError = "合集《" + m.title + "》展开失败："
                            + (e.getMessage() == null ? "未知错误" : e.getMessage());
                }
            }
            try {
                Thread.sleep(250);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /**
     * Import everything in the folder: collections are expanded to all their
     * videos, and every part of a multi-part video becomes its own track.
     * Must run off the UI thread.
     */
    public static Result importAll(Context c, long folderId, Progress cb) throws Exception {
        Result r = new Result();
        List<Media> flat = expand(list(folderId), r);
        r.total = flat.size();
        Db db = Db.get(c);
        // 同一个视频可能同时出现在多个合集里；按链接去重，避免本批次内互相覆盖
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < flat.size(); i++) {
            Media m = flat.get(i);
            if (cb != null) cb.on(i + 1, flat.size(), m.title);
            try {
                Bili.Ref ref = new Bili.Ref();
                if (!TextUtils.isEmpty(m.bvid)) ref.bvid = m.bvid;
                else ref.aid = m.aid;
                ref.page = 1;
                // one view call per entry; reuse it for every part
                JSONObject data = Bili.view(ref);
                int parts = Bili.pageCount(data);
                for (int pg = 1; pg <= parts; pg++) {
                    ref.page = pg;
                    Track t = Bili.fetch(ref, data);
                    if (t == null || TextUtils.isEmpty(t.id)) {
                        throw new Bili.ApiException(-1, "视频信息为空");
                    }
                    String link = TrackKey.of(t);
                    if (link == null || !seen.add(link)) {
                        r.skipped++;
                        continue;
                    }
                    // 按链接判重：收藏夹导入的和单独添加的只要链接相同就算同一首，
                    // 已有行（含缓存路径、播放次数）一律不覆盖。
                    if (db.findByLink(link) != null) {
                        r.skipped++;
                        continue;
                    }
                    t.added = System.currentTimeMillis();
                    MetaResolver.parseLocal(t);
                    db.upsert(t);
                    r.added++;
                    MetaResolver.enqueue(c, t);
                }
            } catch (Exception e) {
                r.failed++;
                if (r.firstError == null) {
                    r.firstError = e.getMessage() == null ? "解析失败" : e.getMessage();
                }
            }
            // stay well below the risk-engine's rate limit
            try {
                Thread.sleep(320);
            } catch (Exception ignored) {
            }
        }
        Intent i = new Intent(Importer.ACT_ADDED);
        i.setPackage(c.getPackageName());
        i.putExtra("n", r.added);
        c.sendBroadcast(i);
        return r;
    }

    public static String summary(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(r.total).append(" 个条目");
        if (r.collections > 0) {
            sb.append("（合集 ").append(r.collections).append(" 个，展开 ")
                    .append(r.expanded).append(" 个视频）");
        }
        sb.append("，新增 ").append(r.added).append(" 首");
        if (r.skipped > 0) sb.append("，已在曲库 ").append(r.skipped).append(" 首");
        if (r.failed > 0) {
            sb.append("，失败 ").append(r.failed).append(" 首");
            if (r.firstError != null) sb.append("（").append(r.firstError).append("）");
        }
        sb.append("；多 P 视频全部分 P 已导入");
        return sb.toString();
    }

    public static String folderLabel(Folder f) {
        return NAME + " · " + f.count + " 个内容";
    }
}
