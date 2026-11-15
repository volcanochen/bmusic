package com.volcano.bmusic.api;

import com.volcano.bmusic.App;
import com.volcano.bmusic.net.Http;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bilibili web QR-code login. The QR code is scanned and confirmed by the
 * official bilibili App ("通过 B 站 App 授权"), no password ever reaches us.
 * On success the Set-Cookie headers give us SESSDATA / bili_jct / DedeUserID.
 */
public final class BiliAuth {

    public static final int WAITING = 0;
    public static final int SCANNED = 1;
    public static final int EXPIRED = 2;
    public static final int SUCCESS = 3;

    private static final String GENERATE =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main_web";
    private static final String POLL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll";
    private static final String NAV = "https://api.bilibili.com/x/web-interface/nav";

    public static class Qr {
        public String url;
        public String key;
    }

    public static class Account {
        public long mid;
        public String name;
        public String face;

        public Account(long mid, String name, String face) {
            this.mid = mid;
            this.name = name;
            this.face = face;
        }
    }

    public static Qr generate() throws Exception {
        Http.Resp r = Http.follow(GENERATE, Bili.apiHeaders(Http.REFERER));
        if (r.code == 412) throw new Bili.ApiException(-412, "登录二维码获取失败(-412)，请稍后重试");
        if (r.body == null) throw new Bili.ApiException(-1, "登录二维码获取失败");
        JSONObject jo = new JSONObject(r.body);
        if (jo.optInt("code", -1) != 0) {
            throw new Bili.ApiException(jo.optInt("code", -1), jo.optString("message"));
        }
        JSONObject d = jo.optJSONObject("data");
        if (d == null) throw new Bili.ApiException(-1, "登录二维码数据为空");
        Qr q = new Qr();
        q.url = d.optString("url");
        q.key = d.optString("qrcode_key");
        if (q.url.isEmpty() || q.key.isEmpty()) {
            throw new Bili.ApiException(-1, "登录二维码数据不完整");
        }
        return q;
    }

    /** One poll. Call roughly every 2 seconds from a background thread. */
    public static int poll(String key) throws Exception {
        String url = POLL + "?qrcode_key=" + enc(key) + "&source=main_web";
        Http.Resp r = Http.follow(url, Bili.apiHeaders(Http.REFERER));
        if (r.code == 412) throw new Bili.ApiException(-412, "登录状态查询被拦截，请稍后重试");
        if (r.body == null) throw new Bili.ApiException(-1, "登录状态查询失败");
        JSONObject jo = new JSONObject(r.body);
        if (jo.optInt("code", -1) != 0) {
            throw new Bili.ApiException(jo.optInt("code", -1), jo.optString("message"));
        }
        JSONObject d = jo.optJSONObject("data");
        if (d == null) return WAITING;
        int code = d.optInt("code", -1);
        if (code == 0) {
            onSuccess(r, d);
            return SUCCESS;
        }
        if (code == 86090) return SCANNED;
        if (code == 86038 || code == 86039) return EXPIRED;
        // 86101 = not scanned yet, 86091 = waiting for confirmation
        return WAITING;
    }

    private static void onSuccess(Http.Resp r, JSONObject data) {
        LinkedHashMap<String, String> ck = new LinkedHashMap<String, String>();
        // keep any extra cookies the user pasted earlier (but not expired login ones)
        for (String part : App.getCookie().split(";")) {
            String s = part.trim();
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            String k = s.substring(0, eq).trim();
            if (!LOGIN_KEYS.contains(k.toLowerCase(Locale.US))) ck.put(k, s.substring(eq + 1).trim());
        }
        for (String line : r.cookies) {
            String first = line.split(";")[0].trim();
            int eq = first.indexOf('=');
            if (eq <= 0) continue;
            String k = first.substring(0, eq).trim();
            String v = first.substring(eq + 1).trim();
            if (v.isEmpty()) continue;
            if (LOGIN_KEYS.contains(k.toLowerCase(Locale.US))) ck.put(k, v);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : ck.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        App.setCookie(sb.toString());
        App.setRefreshToken(data.optString("refresh_token", ""));
        // the wbi keys must be re-read with the new identity
        Bili.resetSession();
    }

    private static final java.util.Set<String> LOGIN_KEYS = new java.util.HashSet<String>(
            java.util.Arrays.asList("sessdata", "bili_jct", "dedeuserid", "dedeuserid__ckmd5",
                    "sid", "bili_deta", "ac_time_value"));

    /** Verify the stored cookie and remember the nickname / uid. null = not logged in. */
    public static Account verify() throws Exception {
        if (!Bili.loggedIn()) return null;
        Http.Resp r = Http.follow(NAV, Bili.apiHeaders(Http.REFERER));
        if (r.body == null) throw new Bili.ApiException(-1, "账号信息获取失败");
        JSONObject d = new JSONObject(r.body).optJSONObject("data");
        if (d == null || !d.optBoolean("isLogin", false)) return null;
        Account a = new Account(d.optLong("mid"), d.optString("uname"), d.optString("face"));
        App.setBiliProfile(a.mid, a.name);
        return a;
    }

    /** Local logout: drop the credentials stored on this device. */
    public static void logout() {
        App.logoutBili();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }
}
