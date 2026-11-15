package com.volcano.bmusic.util;

import com.volcano.bmusic.model.Track;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 曲库条目的唯一身份 = 它指向的 B 站视频链接（BV 号 + 分 P）。
 *
 * <p>「单独添加」「B站收藏夹导入」「旧版备份恢复」只是添加方式，不构成身份的一部分，
 * 因此这里不保存任何来源/渠道信息。只要两个条目指向同一个分 P 链接，就是同一首歌，
 * 与它们的 id 字符串、添加时间、标题文字无关。
 *
 * <p>正常入库时 {@code id = bvid + "_p" + page}，与 key 一致；老数据/外部备份里
 * 可能出现只有 BV 号、只有 av 号、或 page=0 的 id，key 会把它们归一到同一形式。
 */
public final class TrackKey {

    /** BV 号：前缀大小写 tolerant，后 10 位 base58 大小写敏感，必须原样保留。 */
    private static final Pattern P_BV = Pattern.compile("[Bb][Vv][0-9A-Za-z]{10}");

    private TrackKey() {
    }

    public static String of(Track t) {
        return t == null ? null : of(t.bvid, t.cid, t.page, t.id);
    }

    public static String of(String bvid, long cid, int page, String id) {
        int p = page > 0 ? page : 1;
        String bv = bvOf(bvid);
        if (bv == null) bv = bvOf(id);          // 老 id 里直接带着 BV 号
        if (bv != null) return bv + "_p" + p;   // 最可靠：链接本身就是身份

        if (cid > 0) return "cid_" + cid;       // 没有 BV 号时 cid 也能唯一定位一个分 P
        String s = id == null ? "" : id.trim();
        if (s.isEmpty()) return null;
        int i = s.lastIndexOf("_p");
        if (i > 0 && digits(s.substring(i + 2))) {
            int oldPage = parseInt(s.substring(i + 2), p);
            return s.substring(0, i) + "_p" + (oldPage > 0 ? oldPage : p);
        }
        return "id_" + s;
    }

    private static String bvOf(String s) {
        if (s == null || s.isEmpty()) return null;
        Matcher m = P_BV.matcher(s);
        if (!m.find()) return null;
        String v = m.group();
        return "BV" + v.substring(2);
    }

    private static boolean digits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return def;
        }
    }
}
