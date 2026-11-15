package com.volcano.bmusic.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Minimal http helper built on HttpURLConnection (no external deps). */
public final class Http {

    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    public static final String REFERER = "https://www.bilibili.com/";

    private static volatile boolean ready = false;

    public static void init() {
        if (ready) return;
        synchronized (Http.class) {
            if (ready) return;
            try {
                CookieManager cm = new CookieManager();
                cm.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
                CookieHandler.setDefault(cm);
            } catch (Throwable ignored) {
            }
            ready = true;
        }
    }

    public static class Resp {
        public int code;
        public String body;
        public String location;
        /** lower-case header name -> first value */
        public Map<String, String> headers = new HashMap<String, String>();
        /** every Set-Cookie line, in order (login needs all of them) */
        public List<String> cookies = new ArrayList<String>();

        public boolean ok() {
            return code >= 200 && code < 300;
        }

        public String header(String key) {
            return headers.get(key.toLowerCase(Locale.US));
        }
    }

    public static Map<String, String> baseHeaders() {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("Referer", REFERER);
        h.put("Accept-Language", "zh-CN,zh;q=0.9");
        return h;
    }

    /**
     * Browser-like headers for bilibili api calls. The B-station risk engine
     * checks the consistency of UA / sec-ch-ua / Sec-Fetch-*; a bare UA with
     * none of these is one of the main reasons for -412.
     */
    public static Map<String, String> browserHeaders() {
        Map<String, String> h = baseHeaders();
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Origin", "https://www.bilibili.com");
        h.put("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"");
        h.put("sec-ch-ua-mobile", "?0");
        h.put("sec-ch-ua-platform", "\"Windows\"");
        h.put("Sec-Fetch-Dest", "empty");
        h.put("Sec-Fetch-Mode", "cors");
        h.put("Sec-Fetch-Site", "same-site");
        return h;
    }

    /** GET, no redirect following (Location is returned). */
    public static Resp get(String url, Map<String, String> headers) throws IOException {
        return doGet(url, headers, false);
    }

    /** GET with automatic redirect following. */
    public static Resp follow(String url, Map<String, String> headers) throws IOException {
        return doGet(url, headers, true);
    }

    /** POST a raw body. Used by the login/refresh and web-ticket endpoints. */
    public static Resp post(String url, Map<String, String> headers, String body, String contentType)
            throws IOException {
        init();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        apply(c, headers);
        if (contentType != null && contentType.length() > 0) {
            c.setRequestProperty("Content-Type", contentType);
        }
        if (body != null && body.length() > 0) {
            OutputStream os = c.getOutputStream();
            try {
                os.write(body.getBytes("UTF-8"));
            } finally {
                os.close();
            }
        }
        try {
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    private static Resp doGet(String url, Map<String, String> headers, boolean followRedirect)
            throws IOException {
        init();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(followRedirect);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        apply(c, headers);
        try {
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    private static void apply(HttpURLConnection c, Map<String, String> headers) {
        c.setRequestProperty("User-Agent", UA);
        if (headers == null) return;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
        }
    }

    private static Resp read(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        Resp r = new Resp();
        r.code = code;
        r.location = c.getHeaderField("Location");
        Map<String, List<String>> raw = c.getHeaderFields();
        if (raw != null) {
            for (Map.Entry<String, List<String>> e : raw.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                String key = e.getKey().toLowerCase(Locale.US);
                r.headers.put(key, e.getValue().get(0));
                if ("set-cookie".equals(key)) r.cookies.addAll(e.getValue());
            }
        }
        String enc = c.getHeaderField("Content-Encoding");
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return r;
        try {
            InputStream src = ("gzip".equalsIgnoreCase(enc)) ? new GZIPInputStream(in) : in;
            r.body = readAll(src);
        } finally {
            in.close();
        }
        return r;
    }

    public static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    public static interface Progress {
        void on(long done, long total);
    }

    /** Download url to dst. Returns bytes written. Throws on failure. */
    public static long download(String url, File dst, Map<String, String> headers, Progress cb) throws IOException {
        init();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        apply(c, headers);
        int code = c.getResponseCode();
        if (code >= 400) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        long total = 0;
        try {
            total = Long.parseLong(c.getHeaderField("Content-Length"));
        } catch (Exception ignored) {
        }
        InputStream in = new BufferedInputStream(c.getInputStream(), 32768);
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        OutputStream out = new FileOutputStream(tmp, false);
        long acc = 0;
        long last = 0;
        try {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                acc += n;
                if (cb != null && System.currentTimeMillis() - last > 250) {
                    last = System.currentTimeMillis();
                    cb.on(acc, total);
                }
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
            c.disconnect();
        }
        if (tmp.exists() && tmp.length() > 0) {
            if (dst.exists()) dst.delete();
            tmp.renameTo(dst);
        }
        if (cb != null) cb.on(acc, total);
        return acc;
    }
}
