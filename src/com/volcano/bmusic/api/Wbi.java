package com.volcano.bmusic.api;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bilibili wbi request signature. */
public final class Wbi {

    private static final int[] TABLE = new int[]{
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private String rawKey = "";
    private long keyAt = 0;

    public boolean ready() {
        return rawKey.length() == 64;
    }

    public void setKeys(String imgKey, String subKey) {
        if (imgKey == null || subKey == null) return;
        this.rawKey = imgKey + subKey;
        this.keyAt = System.currentTimeMillis();
    }

    public boolean stale() {
        return !ready() || (System.currentTimeMillis() - keyAt > 4 * 60 * 60 * 1000L);
    }

    private String mixinKey() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(rawKey.charAt(TABLE[i]));
        }
        return sb.toString();
    }

    /** Returns the signed query string (param=value&...&w_rid=xxx&wts=xxx). */
    public String sign(Map<String, String> src, long nowSec) {
        Map<String, String> params = new TreeMap<String, String>(src);
        params.put("wts", String.valueOf(nowSec));
        List<String> keys = new ArrayList<String>(params.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            String v = params.get(k);
            if (v == null) v = "";
            v = filter(v);
            if (i > 0) sb.append('&');
            sb.append(enc(k)).append('=').append(enc(v));
        }
        String base = sb.toString();
        String wrid = md5(base + mixinKey());
        return base + "&w_rid=" + wrid;
    }

    private static String filter(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '!' || c == '\'' || c == '(' || c == ')' || c == '*') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private static String enc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (keep) {
                sb.append(c);
            } else {
                byte[] b;
                try {
                    b = String.valueOf(c).getBytes("UTF-8");
                } catch (Exception e) {
                    b = new byte[]{(byte) c};
                }
                for (byte one : b) {
                    sb.append('%').append(HEX[(one >> 4) & 0xF]).append(HEX[one & 0xF]);
                }
            }
        }
        return sb.toString();
    }

    public static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
