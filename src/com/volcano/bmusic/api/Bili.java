package com.volcano.bmusic.api;

import android.content.SharedPreferences;
import android.text.TextUtils;

import com.volcano.bmusic.model.Track;
import com.volcano.bmusic.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bilibili client: link parsing, video info, audio stream url, account/fav api.
 *
 * Notes on -412: the web risk engine needs a consistent browser identity
 * (buvid3 + buvid4 + b_nut + buvid_fp + bili_ticket), a wbi signature, and a
 * video-page Referer. The old code fell back to an unsigned request on every
 * failure with the homepage as Referer, which is exactly what triggers it.
 */
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

    private static final String VIEW = "https://api.bilibili.com/x/web-interface/view";
    private static final String PLAYURL_WBI = "https://api.bilibili.com/x/player/wbi/playurl";
    private static final String PLAYURL = "https://api.bilibili.com/x/player/playurl";
    private static final String SPI = "https://api.bilibili.com/x/frontend/finger/spi";
    private static final String NAV = "https://api.bilibili.com/x/web-interface/nav";
    private static final String TICKET = "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket";

    private static final Wbi wbi = new Wbi();
    private static SharedPreferences store;

    private static boolean sessionReady = false;
    private static long sessionAt = 0;
    private static String userCookie = "";

    // device identity, persisted so cold starts don't look like a new device
    private static String buvid3 = "";
    private static String buvid4 = "";
    private static String bNut = "";
    private static String buvidFp = "";
    private static String uuid = "";
    private static String biliTicket = "";
    private static long ticketAt = 0;
    private static String wbiImg = "";
    private static String wbiSub = "";
    private static long wbiAt = 0;

    /** Wire up preferences and restore the anonymous identity. Called once in App.onCreate. */
    public static synchronized void attach(SharedPreferences p) {
        store = p;
        userCookie = p.getString("cookie", "");
        buvid3 = p.getString("buvid3", "");
        buvid4 = p.getString("buvid4", "");
        bNut = p.getString("b_nut", "");
        buvidFp = p.getString("buvid_fp", "");
        uuid = p.getString("b_uuid", "");
        biliTicket = p.getString("bili_ticket", "");
        ticketAt = p.getLong("bili_ticket_at", 0);
        if (ticketAt > 0 && System.currentTimeMillis() - ticketAt > 3 * 24 * 3600 * 1000L) {
            biliTicket = "";
            ticketAt = 0;
        }
        wbiImg = p.getString("wbi_img", "");
        wbiSub = p.getString("wbi_sub", "");
        wbiAt = p.getLong("wbi_at", 0);
        if (wbiImg.length() > 0 && wbiSub.length() > 0) {
            // keep the real fetch time: Wbi.stale() decides when to refresh
            wbi.setKeys(wbiImg, wbiSub, wbiAt);
        }
    }

    private static synchronized void save() {
        if (store == null) return;
        store.edit()
                .putString("buvid3", buvid3)
                .putString("buvid4", buvid4)
                .putString("b_nut", bNut)
                .putString("buvid_fp", buvidFp)
                .putString("b_uuid", uuid)
                .putString("bili_ticket", biliTicket)
                .putLong("bili_ticket_at", ticketAt)
                .putString("wbi_img", wbiImg)
                .putString("wbi_sub", wbiSub)
                .putLong("wbi_at", wbiAt)
                .apply();
    }

    public static void setUserCookie(String c) {
        userCookie = c == null ? "" : c.trim();
    }

    /** Drop the wbi keys / force a fresh session (e.g. after login or a -412). */
    public static synchronized void resetSession() {
        sessionReady = false;
        sessionAt = 0;
        wbi.setKeys("", "");
        wbiImg = "";
        wbiSub = "";
        wbiAt = 0;
        save();
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

    // ------------------------------------------------------------- session

    private static synchronized void ensureSession(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && sessionReady && !wbi.stale() && now - sessionAt < 30 * 60 * 1000L) return;
        // never hammer the identity endpoints: a -412 storm is worse than a wait
        if (now - sessionAt < 2000L) return;
        sessionAt = now;

        // 1. warm up (the homepage may set cookies / activate the buvid)
        try {
            Http.follow("https://www.bilibili.com/", Http.browserHeaders());
        } catch (Exception ignored) {
        }

        // 2. fresh device identity (buvid3 + buvid4 must belong to the same pair)
        if (force || buvid3.isEmpty() || buvid4.isEmpty() || bNut.isEmpty()) {
            try {
                Http.Resp r = Http.follow(SPI, apiHeaders(Http.REFERER));
                JSONObject d = new JSONObject(r.body).optJSONObject("data");
                if (d != null) {
                    String b3 = d.optString("b_3");
                    String b4 = d.optString("b_4");
                    if (b3.length() > 0 && b4.length() > 0) {
                        buvid3 = b3;
                        buvid4 = b4;
                        bNut = String.valueOf(now / 1000L);
                        buvidFp = Wbi.md5(b3 + Http.UA).substring(0, 16);
                        if (uuid.isEmpty()) uuid = UUID.randomUUID().toString().toUpperCase(Locale.US) + "infoc";
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 3. device ticket (optional, but lowers the risk score a lot)
        if (force || biliTicket.isEmpty() || now - ticketAt > 2 * 24 * 3600 * 1000L) {
            try {
                long ts = now / 1000L;
                String sign = hmacSha256("XgwSnGZ1p", "ts" + ts);
                String qs = "key_id=ec02&hexsign=" + sign
                        + "&context%5Bts%5D=" + ts + "&csrf=" + enc(csrf());
                Http.Resp r = Http.post(TICKET + "?" + qs, apiHeaders(Http.REFERER),
                        "{}", "application/json");
                JSONObject d = new JSONObject(r.body).optJSONObject("data");
                if (d != null && d.optString("ticket").length() > 0) {
                    biliTicket = d.optString("ticket");
                    ticketAt = now;
                }
            } catch (Exception ignored) {
            }
        }

        // 4. wbi key material from nav (works even when not logged in)
        try {
            Http.Resp nv = Http.follow(NAV, apiHeaders(Http.REFERER));
            JSONObject d = new JSONObject(nv.body).optJSONObject("data");
            if (d != null) {
                JSONObject im = d.optJSONObject("wbi_img");
                if (im != null) {
                    String a = tailName(im.optString("img_url", ""));
                    String b = tailName(im.optString("sub_url", ""));
                    if (a.length() > 0 && b.length() > 0) {
                        wbiImg = a;
                        wbiSub = b;
                        wbiAt = now;
                        wbi.setKeys(a, b);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        sessionReady = true;
        save();
    }

    private static String tailName(String url) {
        if (url == null) return "";
        int slash = url.lastIndexOf('/');
        String tail = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = tail.lastIndexOf('.');
        return dot > 0 ? tail.substring(0, dot) : tail;
    }

    private static String hmacSha256(String key, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
        byte[] d = mac.doFinal(msg.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    // ------------------------------------------------------------- cookies

    /** All cookies we manage, de-duplicated by name. */
    private static Map<String, String> cookieMap() {
        LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
        put(m, "buvid3", buvid3);
        put(m, "buvid4", buvid4);
        put(m, "b_nut", bNut);
        put(m, "buvid_fp", buvidFp);
        put(m, "_uuid", uuid);
        put(m, "bili_ticket", biliTicket);
        // user pasted / login cookies win over generated ones
        for (String part : userCookie.split(";")) {
            String s = part.trim();
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            String k = s.substring(0, eq).trim();
            String v = s.substring(eq + 1).trim();
            if (k.isEmpty() || v.isEmpty()) continue;
            String low = k.toLowerCase(Locale.US);
            if ("expires".equals(low) || "path".equals(low) || "domain".equals(low)
                    || "max-age".equals(low) || "secure".equals(low)
                    || "httponly".equals(low) || "samesite".equals(low)) continue;
            m.put(k, v);
        }
        return m;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && v.length() > 0) m.put(k, v);
    }

    public static String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookieMap().entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** bili_jct, needed as csrf for every write operation. */
    public static String csrf() {
        String v = cookieMap().get("bili_jct");
        return v == null ? "" : v;
    }

    public static boolean loggedIn() {
        String v = cookieMap().get("SESSDATA");
        return v != null && v.length() > 0;
    }

    public static long mid() {
        String v = cookieMap().get("DedeUserID");
        try {
            return v == null ? 0 : Long.parseLong(v);
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------- requests

    public static Map<String, String> apiHeaders(String referer) {
        Map<String, String> h = Http.browserHeaders();
        h.put("Referer", referer == null || referer.isEmpty() ? Http.REFERER : referer);
        String ck = cookieHeader();
        if (ck.length() > 0) h.put("Cookie", ck);
        return h;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    private static String toQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    private static boolean risk(int code) {
        return code == -412 || code == -352 || code == -509 || code == -799;
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(400L + attempt * 700L);
        } catch (Exception ignored) {
        }
    }

    /**
     * GET a json api with a signed query, full browser headers and automatic
     * risk recovery. Never falls back to an unsigned request on its own: an
     * unsigned call after a -412 only makes the risk engine angrier.
     */
    public static JSONObject api(String url, Map<String, String> params, String referer,
                                 boolean sign) throws Exception {
        Exception last = null;
        boolean riskOnly = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            ensureSession(attempt > 0);
            String qs = (sign && wbi.ready())
                    ? wbi.sign(params, System.currentTimeMillis() / 1000L)
                    : toQuery(params);
            try {
                Http.Resp r = Http.follow(url + "?" + qs, apiHeaders(referer));
                if (r.code == 412 || r.body == null) {
                    last = new ApiException(-412, "请求被拦截(-412)");
                    riskOnly = true;
                    pause(attempt);
                    continue;
                }
                JSONObject jo = new JSONObject(r.body);
                int code = jo.optInt("code", -1);
                if (code == 0) return jo;
                String msg = jo.optString("message");
                if (risk(code)) {
                    last = explain(code, msg);
                    riskOnly = true;
                    pause(attempt);
                    continue;
                }
                if (code == -403 && sign && wbi.ready()) {
                    // stale wbi key, refresh and sign again
                    wbi.setKeys("", "");
                    last = explain(code, msg);
                    pause(attempt);
                    continue;
                }
                throw explain(code, msg);
            } catch (ApiException e) {
                if (risk(e.code)) {
                    last = e;
                    riskOnly = true;
                    pause(attempt);
                } else {
                    throw e;
                }
            } catch (Exception e) {
                last = e;
                pause(attempt);
            }
        }
        if (riskOnly) {
            throw new ApiException(-412, loggedIn()
                    ? "请求被拦截(-412)：已自动重试，请稍后再试"
                    : "请求被拦截(-412)：已自动重试，建议在「B站账号」里授权登录后重试");
        }
        throw last != null ? last : new ApiException(-1, "接口请求失败");
    }

    public static JSONObject api(String url, Map<String, String> params, String referer) throws Exception {
        return api(url, params, referer, true);
    }

    /** POST a form. csrf is injected automatically. no retry (writes aren't idempotent). */
    public static JSONObject postForm(String url, Map<String, String> params, String referer)
            throws Exception {
        ensureSession(false);
        Map<String, String> p = new LinkedHashMap<String, String>(params);
        if (!p.containsKey("csrf") && csrf().length() > 0) p.put("csrf", csrf());
        Map<String, String> h = apiHeaders(referer);
        h.put("Content-Type", "application/x-www-form-urlencoded");
        Http.Resp r = Http.post(url, h, toQuery(p), "application/x-www-form-urlencoded");
        if (r.body == null) throw new ApiException(-1, "empty response");
        JSONObject jo = new JSONObject(r.body);
        int code = jo.optInt("code", -1);
        if (code != 0) throw explain(code, jo.optString("message"));
        return jo;
    }

    private static Exception explain(int code, String msg) {
        switch (code) {
            case -404:
                return new ApiException(code, "视频不存在或已被删除");
            case -403:
                return new ApiException(code, "无权访问（签名过期或会员/地区限制）");
            case -352:
                return new ApiException(code, "触发风控(-352)，请登录后重试");
            case -101:
                return new ApiException(code, "账号未登录(-101)，请先在「B站账号」里授权登录");
            case -111:
                return new ApiException(code, "csrf 校验失败，请重新登录 B 站账号");
            case -509:
            case -799:
                return new ApiException(code, "请求过于频繁，请稍后再试");
            case -412:
                return new ApiException(code, loggedIn()
                        ? "请求被拦截(-412)，请稍后再试"
                        : "请求被拦截(-412)，建议登录 B 站账号后重试");
            default:
                if (msg == null || msg.isEmpty()) msg = "接口错误 " + code;
                return new ApiException(code, msg);
        }
    }

    // ---------------------------------------------------------------- api

    /** Raw "data" object of the view api for this ref (one network call). */
    public static JSONObject view(Ref ref) throws Exception {
        Map<String, String> p = new HashMap<String, String>();
        if (ref.bvid != null && ref.bvid.length() > 0) {
            p.put("bvid", ref.bvid);
        } else {
            p.put("aid", String.valueOf(ref.aid));
        }
        String refUrl = ref.bvid != null && ref.bvid.length() > 0
                ? "https://www.bilibili.com/video/" + ref.bvid + "/" : Http.REFERER;
        // view still accepts unsigned requests; keeping it that way avoids
        // compatibility issues with older mirrors of the endpoint.
        return api(VIEW, p, refUrl, false).getJSONObject("data");
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

    private static Map<String, String> playParams(Track t, String fnval) {
        Map<String, String> p = new HashMap<String, String>();
        p.put("bvid", t.bvid);
        p.put("cid", String.valueOf(t.cid));
        p.put("fnval", fnval);
        p.put("fnver", "0");
        p.put("fourk", "1");
        p.put("otype", "json");
        p.put("platform", "pc");
        // Browser render fingerprint. Web endpoints reject wbi/playurl with
        // -412 when these are missing; logged-in sessions use the switch flag.
        if (loggedIn()) {
            p.put("dm_img_switch", "0");
        } else {
            p.put("dm_img_list", "[]");
            p.put("dm_img_str", "V2ViR0wgMS4wIChPcGVuR0wgRVMgMi4wIENocm9taXVtKQ");
            p.put("dm_cover_img_str", "QU5HTEUgKE5VSUlBTCwgTlVJRElBIFJhdmVuIE1ldGFsICgzMTAwKSwgT3BlbkdMIEVTIDMuMC4yKQ");
            p.put("dm_img_inter", "{\"ds\":[],\"wh\":[0,0],\"of\":[0,0,0]}");
        }
        return p;
    }

    /**
     * Ask bilibili for the audio-only dash stream of this track.
     * Result is put into {@link Track#streamUrl}; backup CDN urls go into
     * {@link Track#streamBackups} so downloaders can retry another host.
     */
    public static String audioUrl(Track t) throws Exception {
        String ref = refererOf(t);
        Exception last = null;
        // 1) wbi-signed endpoint, 2) legacy endpoint (still signed),
        // 3) plain durl stream as a last resort (mp4 container, audio is still playable)
        String[] endpoints = {PLAYURL_WBI, PLAYURL, PLAYURL};
        for (int i = 0; i < endpoints.length; i++) {
            String fnval = (i == 2) ? "0" : "16";
            try {
                JSONObject jo = api(endpoints[i], playParams(t, fnval), ref, true);
                String best = pickStream(jo.getJSONObject("data"), t, fnval);
                if (best != null) {
                    t.streamUrl = best;
                    t.streamAt = System.currentTimeMillis();
                    return best;
                }
                last = new ApiException(-1, "没有取到音频流");
            } catch (Exception e) {
                last = e;
            }
        }
        if (last instanceof ApiException) throw last;
        if (last != null) throw last;
        throw new ApiException(-1, "没有取到音频流");
    }

    private static String pickStream(JSONObject data, Track t, String fnval) throws Exception {
        String best = null;
        int bestRank = 99;
        List<String> backups = new ArrayList<String>();
        JSONObject dash = data.optJSONObject("dash");
        if (dash != null) {
            JSONArray arr = dash.optJSONArray("audio");
            if (arr != null) {
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
                        backups.clear();
                        collectUrls(a, backups);
                    } else if (rank == bestRank) {
                        collectUrls(a, backups);
                    }
                }
            }
        }
        if (best == null) {
            JSONArray durl = data.optJSONArray("durl");
            if (durl != null && durl.length() > 0) {
                JSONObject d0 = durl.getJSONObject(0);
                best = d0.optString("url", "");
                collectUrls(d0, backups);
            }
        }
        if (best == null || best.isEmpty()) return null;
        t.streamBackups = backups;
        return best;
    }

    private static void collectUrls(JSONObject o, List<String> out) {
        if (o == null) return;
        JSONArray bu = o.optJSONArray("backup_url");
        if (bu == null) bu = o.optJSONArray("backupUrl");
        if (bu == null) return;
        for (int i = 0; i < bu.length() && out.size() < 6; i++) {
            String u = bu.optString(i, "");
            if (u.length() > 0 && !out.contains(u)) out.add(u);
        }
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
        Map<String, String> h = Http.browserHeaders();
        h.put("Referer", referer == null ? Http.REFERER : referer);
        h.put("Accept", "*/*");
        String ck = cookieHeader();
        if (ck.length() > 0) h.put("Cookie", ck);
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
