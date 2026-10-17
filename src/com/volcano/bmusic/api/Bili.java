package com.volcano.bmusic.api;

import android.text.TextUtils;

import com.volcano.bmusic.model.Track;
import com.volcano.bmusic.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bilibili client: link parsing, video info, audio stream url. */
public final class Bili {

    public static class ApiException extends Exception {
        public final int code;

        public ApiException(int code, String msg) {
            super(msg == null ? ("code " + code) : msg);
            this.code = code;
        }
    }

    /** A reference to one video (optionally a specific part). */
    public static class Ref {
        public String bvid;
        public long aid;
        public int page = 1;
    }

    private static final Pattern P_BV = Pattern.compile("(BV[0-9A-Za-z]{10})");
    private static final Pattern P_AV = Pattern.compile("(?i)\\bav([0-9]{1,15})");
    private static final Pattern P_URL = Pattern.compile("https?://[^\\s，。\\u4e00-\\u9fa5\"']+");
    private static final Pattern P_PAGE = Pattern.compile("[?&]p=([0-9]{1,4})");

    private static final Wbi wbi = new Wbi();
    private static boolean sessionReady = false;
    private static String userCookie = "";

    public static void setUserCookie(String c) {
        userCookie = c == null ? "" : c.trim();
    }

    /** Must be called off the main thread. */
    public static void resetSession() {
        sessionReady = false;
        wbi.setKeys("", "");
    }

    // ------------------------------------------------------------------ refs

    /** Extract all bvid/av or resolvable links out of arbitrary shared text. */
    public static List<Ref> extractAll(String text) {
        List<Ref> out = new ArrayList<Ref>();
        if (text == null) return out;
        List<String> candidates = new ArrayList<String>();
        for (String piece : text.split("\\s+")) {
            if (piece.length() > 3) candidates.add(piece);
        }
        if (candidates.isEmpty() && text.length() > 3) candidates.add(text);
        for (String c : candidates) {
            Ref r = quickRef(c);
            if (r != null) out.add(r);
        }
        return out;
    }

    /** No network. Returns null when the text carries no direct bvid/av. */
    public static Ref quickRef(String s) {
        if (s == null) return null;
        Ref r = new Ref();
        Matcher m = P_BV.matcher(s);
        if (m.find()) {
            r.bvid = m.group(1);
            Matcher pm = P_PAGE.matcher(s);
            if (pm.find()) {
                try {
                    r.page = Integer.parseInt(pm.group(1));
                } catch (Exception ignored) {
                }
            }
            return r;
        }
        Matcher am = P_AV.matcher(s);
        if (am.find()) {
            try {
                r.aid = Long.parseLong(am.group(1));
            } catch (Exception ignored) {
                return null;
            }
            Matcher pm = P_PAGE.matcher(s);
            if (pm.find()) {
                try {
                    r.page = Integer.parseInt(pm.group(1));
                } catch (Exception ignored) {
                }
            }
            return r;
        }
        return null;
    }

    /**
     * Resolve one user input (link / share text / bvid / av id) into a Ref.
     * Performs network requests for short b23.tv links.
     *
     * The {@code p} index of the shared link survives the whole redirect chain:
     * every hop is followed by hand, so the {@code ?p=} of the real video url is
     * never dropped (a b23.tv link carries no {@code p}, its destination does).
     */
    public static Ref resolve(String input) throws Exception {
        Ref direct = quickRef(input);
        if (direct != null) return direct;
        List<String> urls = new ArrayList<String>();
        Matcher um = P_URL.matcher(input == null ? "" : input);
        while (um.find()) {
            urls.add(um.group());
        }
        if (urls.isEmpty()) throw new ApiException(-1, "没有识别到有效的B站链接或BV号");
        String finalUrl = urls.get(0);
        for (int hop = 0; hop < 6; hop++) {
            Ref maybe = quickRef(finalUrl);
            if (maybe != null) return maybe;
            // get(), not follow(): HttpURLConnection would swallow the Location of
            // every hop and the ?p= of the destination url would be lost.
            Http.Resp r = Http.get(finalUrl, Http.baseHeaders());
            String loc = (r.location != null && !r.location.isEmpty()) ? r.location : null;
            if (loc == null && r.body != null) {
                Matcher m = P_BV.matcher(r.body);
                if (m.find()) {
                    Ref rr = new Ref();
                    rr.bvid = m.group(1);
                    rr.page = pageOf(finalUrl, r.body);
                    return rr;
                }
                Matcher am = P_AV.matcher(r.body);
                if (am.find()) {
                    Ref rr = new Ref();
                    try {
                        rr.aid = Long.parseLong(am.group(1));
                    } catch (Exception ignored) {
                        break;
                    }
                    rr.page = pageOf(finalUrl, r.body);
                    return rr;
                }
            }
            if (loc == null) break;
            finalUrl = absolutize(loc, finalUrl);
        }
        throw new ApiException(-1, "无法解析该链接：" + input);
    }

    /** {@code ?p=} index found in the url, falling back to the page body. */
    private static int pageOf(String url, String body) {
        int p = 1;
        if (url != null) {
            Matcher m = P_PAGE.matcher(url);
            if (m.find()) p = parseInt(m.group(1), 1);
        }
        if (p == 1 && body != null) {
            Matcher m = P_PAGE.matcher(body);
            if (m.find()) p = parseInt(m.group(1), 1);
        }
        return p < 1 ? 1 : p;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static String absolutize(String loc, String base) {
        try {
            return new java.net.URL(new java.net.URL(base), loc).toString();
        } catch (Exception e) {
            return loc;
        }
    }

    // ----------------------------------------------------------- http helpers

    private static synchronized void ensureSession() {
        if (sessionReady && !wbi.stale()) return;
        try {
            // 1. bilibili.com首页下发 buvid3 / b_nut
            Http.follow("https://www.bilibili.com/", Http.baseHeaders());
            if (!hasBuvid()) {
                try {
                    Http.Resp r = Http.follow("https://api.bilibili.com/x/frontend/finger/spi", Http.baseHeaders());
                    JSONObject jo = new JSONObject(r.body);
                    JSONObject d = jo.optJSONObject("data");
                    if (d != null) {
                        String b3 = d.optString("b_3");
                        String b4 = d.optString("b_4");
                        storeCookie("buvid3", b3);
                        storeCookie("buvid4", b4);
                        storeCookie("b_nut", String.valueOf(System.currentTimeMillis() / 1000));
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        // 2. wbi key material
        try {
            Http.Resp nv = Http.follow("https://api.bilibili.com/x/web-interface/nav", apiHeaders());
            if (nv.body != null) {
                JSONObject jo = new JSONObject(nv.body);
                JSONObject d = jo.optJSONObject("data");
                if (d != null) {
                    JSONObject im = d.optJSONObject("wbi_img");
                    if (im != null) {
                        String iu = im.optString("img_url", "");
                        String su = im.optString("sub_url", "");
                        String a = tailName(iu);
                        String b = tailName(su);
                        if (a.length() > 0 && b.length() > 0) wbi.setKeys(a, b);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        sessionReady = true;
    }

    private static String tailName(String url) {
        if (url == null) return "";
        int slash = url.lastIndexOf('/');
        String tail = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = tail.lastIndexOf('.');
        return dot > 0 ? tail.substring(0, dot) : tail;
    }

    private static boolean hasBuvid() {
        try {
            CookieManager cm = (CookieManager) CookieHandler.getDefault();
            if (cm == null) return false;
            for (HttpCookie k : cm.getCookieStore().getCookies()) {
                if ("buvid3".equals(k.getName())) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void storeCookie(String name, String value) {
        if (name == null || value == null || value.isEmpty()) return;
        try {
            CookieManager cm = (CookieManager) CookieHandler.getDefault();
            if (cm == null) return;
            HttpCookie ck = new HttpCookie(name, value);
            ck.setPath("/");
            cm.getCookieStore().add(URI.create("https://www.bilibili.com/"), ck);
        } catch (Exception ignored) {
        }
    }

    private static String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        try {
            CookieManager cm = (CookieManager) CookieHandler.getDefault();
            if (cm != null) {
                for (HttpCookie k : cm.getCookieStore().getCookies()) {
                    if (k == null || k.getValue() == null) continue;
                    sb.append(k.getName()).append('=').append(k.getValue()).append("; ");
                }
            }
        } catch (Exception ignored) {
        }
        if (userCookie.length() > 0) {
            String c = userCookie.trim();
            if (!c.endsWith(";")) c += ";";
            sb.append(c);
        }
        return sb.toString();
    }

    private static Map<String, String> apiHeaders() {
        Map<String, String> h = Http.baseHeaders();
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Origin", "https://www.bilibili.com");
        String ck = cookieHeader();
        if (ck.length() > 0) h.put("Cookie", ck.trim());
        return h;
    }

    /** GET a json api endpoint with wbi signature when available. */
    private static JSONObject call(String url, Map<String, String> params) throws Exception {
        String qs = toQuery(params);
        String full = url + qs;
        Exception lastFail = null;
        if (wbi.ready()) {
            try {
                String signed = wbi.sign(params, System.currentTimeMillis() / 1000L);
                Http.Resp r = Http.follow(url + "?" + signed, apiHeaders());
                JSONObject jo = new JSONObject(r.body);
                if (jo.optInt("code", -1) == 0) return jo;
                // key expired
                if (jo.optInt("code", -1) == -403) wbi.setKeys("", "");
                lastFail = new ApiException(jo.optInt("code", -1), jo.optString("message"));
            } catch (Exception e) {
                lastFail = e;
            }
        }
        Http.Resp r = Http.follow(full, apiHeaders());
        if (r.body == null) throw new ApiException(-1, "empty response");
        JSONObject jo = new JSONObject(r.body);
        int code = jo.optInt("code", -1);
        if (code != 0) {
            throw explain(code, jo.optString("message"));
        }
        return jo;
    }

    private static Exception explain(int code, String msg) {
        switch (code) {
            case -404:
                return new ApiException(code, "视频不存在或已被删除");
            case -403:
                return new ApiException(code, "无权访问（可能是会员/地区限制）");
            case -352:
                return new ApiException(code, "触发风控(-352)，请在设置里填写Cookie重试");
            case -101:
                return new ApiException(code, "账号未登录(-101)");
            case -509:
            case -799:
                return new ApiException(code, "请求过于频繁，请稍后再试");
            case -412:
                return new ApiException(code, "请求被拦截(-412)，请稍后再试");
            default:
                if (msg == null || msg.isEmpty()) msg = "接口错误 " + code;
                return new ApiException(code, msg);
        }
    }

    private static String toQuery(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- api

    /** Raw "data" object of the view api for this ref (one network call). */
    public static JSONObject view(Ref ref) throws Exception {
        ensureSession();
        Map<String, String> p = new HashMap<String, String>();
        if (ref.bvid != null && ref.bvid.length() > 0) {
            p.put("bvid", ref.bvid);
        } else {
            p.put("aid", String.valueOf(ref.aid));
        }
        JSONObject jo = call("https://api.bilibili.com/x/web-interface/view", p);
        return jo.getJSONObject("data");
    }

    /** How many parts (P) the video has. 1 when the api reports none. */
    public static int pageCount(JSONObject data) {
        if (data == null) return 1;
        JSONArray pages = data.optJSONArray("pages");
        return (pages == null || pages.length() == 0) ? 1 : pages.length();
    }

    /** Fetch full metadata for one Ref (network). */
    public static Track fetch(Ref ref) throws Exception {
        return buildTrack(view(ref), ref);
    }

    /**
     * Build the Track of {@code ref.page} out of an already fetched view data
     * object. Lets the caller import every part of a multi-part video without
     * re-querying the view api for each part.
     */
    public static Track fetch(Ref ref, JSONObject data) throws Exception {
        return buildTrack(data, ref);
    }

    private static Track buildTrack(JSONObject data, Ref ref) throws Exception {
        String bvid = data.optString("bvid");
        JSONObject owner = data.optJSONObject("owner");
        String ownerName = owner != null ? owner.optString("name") : "";
        String pic = data.optString("pic");
        String title = data.optString("title");
        long totalSec = data.optLong("duration", 0);

        JSONArray pages = data.optJSONArray("pages");
        int want = ref.page < 1 ? 1 : ref.page;
        long cid = 0;
        long durSec = totalSec;
        String part = null;
        if (pages != null && pages.length() > 0) {
            if (want > pages.length()) {
                JSONObject pj = pages.getJSONObject(pages.length() - 1);
                cid = pj.optLong("cid", 0);
                durSec = pj.optLong("duration", totalSec);
                part = pj.optString("part");
                want = pages.length();
            } else {
                JSONObject pj = pages.getJSONObject(want - 1);
                cid = pj.optLong("cid", 0);
                durSec = pj.optLong("duration", totalSec);
                part = pj.optString("part");
            }
        } else {
            cid = data.optLong("cid", 0);
        }
        if (cid <= 0) throw new ApiException(-1, "无法获取 cid");

        Track t = new Track();
        t.bvid = bvid;
        t.cid = cid;
        t.page = want;
        t.id = bvid + "_p" + want;
        t.title = (pages != null && pages.length() > 1 && part != null && part.length() > 0)
                ? part : title;
        t.srcTitle = title;
        t.author = ownerName;
        t.cover = normalizePic(pic);
        t.duration = durSec * 1000L;
        t.added = System.currentTimeMillis();
        return t;
    }

    private static String normalizePic(String pic) {
        if (pic == null) return "";
        if (pic.startsWith("//")) pic = "https:" + pic;
        return pic;
    }

    /** Resolve input then fetch metadata. */
    public static Track fetchByInput(String input) throws Exception {
        Ref r = resolve(input);
        return fetch(r);
    }

    /** Push numeric audio ids, best first. 30250 is dolby (skipped unless enabled). */
    private static final int[] AUDIO_IDS = {30280, 30232, 30216};

    /**
     * Ask bilibili for the audio-only dash stream of this track.
     * Result is put into {@link Track#streamUrl}.
     */
    public static String audioUrl(Track t) throws Exception {
        ensureSession();
        Map<String, String> p = new HashMap<String, String>();
        p.put("bvid", t.bvid);
        p.put("cid", String.valueOf(t.cid));
        p.put("fnval", "16");
        p.put("fnver", "0");
        p.put("fourk", "1");
        p.put("otype", "json");
        p.put("platform", "pc");
        JSONObject jo = call("https://api.bilibili.com/x/player/playurl", p);
        JSONObject data = jo.getJSONObject("data");
        JSONObject dash = data.optJSONObject("dash");
        String best = null;
        int bestId = -1;
        if (dash != null) {
            JSONArray arr = dash.optJSONArray("audio");
            if (arr != null) {
                int bestRank = 99;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject a = arr.getJSONObject(i);
                    int id = a.optInt("id", 0);
                    if (id == 30250) continue; // dolby atmos
                    String u = a.optString("base_url", a.optString("baseUrl", ""));
                    if (u.isEmpty()) continue;
                    int rank = rank(id);
                    if (rank < bestRank) {
                        bestRank = rank;
                        best = u;
                        bestId = id;
                    }
                }
            }
        }
        if (best == null) {
            JSONArray durl = data.optJSONArray("durl");
            if (durl != null && durl.length() > 0) {
                best = durl.getJSONObject(0).optString("url", "");
                bestId = 0;
            }
        }
        if (best == null || best.isEmpty()) {
            throw new ApiException(-1, "没有取到音频流");
        }
        t.streamUrl = best;
        t.streamAt = System.currentTimeMillis();
        return best;
    }

    private static int rank(int id) {
        for (int i = 0; i < AUDIO_IDS.length; i++) if (AUDIO_IDS[i] == id) return i;
        return 50;
    }

    public static String qualityName(int id) {
        if (id == 30280) return "192K";
        if (id == 30232) return "132K";
        if (id == 30216) return "64K";
        return String.valueOf(id);
    }

    /** Plain headers required for CDN requests of media files. */
    public static Map<String, String> mediaHeaders(String referer) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", Http.UA);
        h.put("Referer", referer == null ? Http.REFERER : referer);
        String ck = cookieHeader();
        if (ck.length() > 0) h.put("Cookie", ck.trim());
        return h;
    }

    public static String refererOf(Track t) {
        if (t == null || TextUtils.isEmpty(t.bvid)) return Http.REFERER;
        return "https://www.bilibili.com/video/" + t.bvid
                + (t.page > 1 ? ("?p=" + t.page) : "");
    }

    public static String fmtSize(long b) {
        if (b <= 0) return "0";
        String[] u = {"B", "KB", "MB", "GB"};
        int i = 0;
        double v = b;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, "%.1f%s", v, u[i]);
    }
}
