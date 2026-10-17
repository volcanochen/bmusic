package com.volcano.bmusic.util;

import android.content.Context;

import com.volcano.bmusic.db.Db;
import com.volcano.bmusic.lyric.NeteaseLyric;
import com.volcano.bmusic.model.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 从 B 站标题 + 分 P 名里推断「歌名 / 歌手 / 专辑」。
 * <p>
 * 两步走：
 * <ol>
 *   <li>{@link #parseLocal} 纯本地规则，导入时立即执行（不联网、不阻塞），
 *       处理「【歌手】歌名」「歌手 - 歌名」「01. 歌名」这类常见写法；</li>
 *   <li>{@link #enqueue} 联网用 QQ 音乐搜索接口补**专辑**（真实发行专辑名），
 *       走单线程队列，批量导入时不会同时发一堆请求；结果写库并广播刷新。
 *       （改用 QQ 音乐原因：网易云搜索返回大量翻唱，专辑字段常是合集名；
 *       百度百科则有 JS 反爬校验，纯 HTTP 拿不到。）</li>
 * </ol>
 */
public final class MetaResolver {

    /** 元信息更新完成（UI 收到后刷新列表）。 */
    public static final String ACT_META = "com.volcano.bmusic.META";

    /** 一批标题里常见的噪声词，判断括号内容是不是歌手/专辑时用。 */
    private static final String[] NOISE = {
            "mv", "pv", "live", "官方", "高清", "超清", "无损", "完整版", "中字", "字幕",
            "翻唱", "现场", "合集", "搬运", "纯音乐", "伴奏", "动态歌词", "歌词版", "4k", "8k",
            "1080p", "720p", "hq", "hires", "hi-res", "60fps", "audition", "audio", "visualizer",
            "performance", "practice", "teaser", "预告", "花絮", "直拍", "混剪", "amv", "mad",
    };

    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor();

    private MetaResolver() {
    }

    // ------------------------------------------------------------- local

    /**
     * 本地规则推断，幂等：已有歌名/歌手的不覆盖。
     * 只用标题（多 P 时是分 P 名）+ 合集标题 + UP 主名，不联网。
     */
    public static void parseLocal(Track t) {
        parseLocal(t, false);
    }

    /**
     * @param overwrite true 时用标题重新推导并覆盖已有值（清掉错误的联网结果），
     *                  false 时只填空字段。
     */
    public static void parseLocal(Track t, boolean overwrite) {
        if (t == null) return;
        String part = safe(t.title);
        String whole = safe(t.srcTitle);
        String author = NeteaseLyric.cleanAuthor(t.author);

        // 分 P 名可能只是「01」「P1」这种，那就以合集标题为准
        if (part.length() < 2 || isNumberOnly(part)) part = whole;
        if (part.isEmpty()) part = whole;
        if (part.isEmpty()) return;

        // 先去掉合集曲目序号「0123. 」「12、」「12:」，并记住它是否存在
        String stripped = stripSeq(part);
        boolean numbered = !stripped.equals(part);
        String base = stripped.isEmpty() ? part : stripped;

        String song = "";
        String singer = "";
        String album = "";

        // 1) 《歌名》优先 —— 书名号里几乎总是歌名，外面的短词是歌手
        String bookSong = firstBook(base);
        if (bookSong != null) {
            song = bookSong;
            String outside = base.replaceAll("《[^》]*》", " ").trim();
            String cand = cleanArtist(outside);
            if (looksLikeArtist(cand)) singer = cand;
        } else {
            // 2) 「A - B」：优先用 UP 主、序号判断哪边是歌手
            int dash = indexOfSeparator(base);
            if (dash > 0) {
                String left = base.substring(0, dash).trim();
                String right = base.substring(dash + sepLen(base, dash)).trim();
                boolean leftIsAuthor = !author.isEmpty() && norm(left).equals(norm(author));
                boolean rightIsAuthor = !author.isEmpty() && norm(right).equals(norm(author));
                if (leftIsAuthor) {
                    // UP 主在左 → 右边是歌名
                    singer = left;
                    song = right;
                } else if (rightIsAuthor) {
                    // UP 主在右 → 左边是歌名
                    singer = right;
                    song = left;
                } else if (numbered) {
                    // 合集惯例：序号. 歌名 - 歌手（歌手在右）
                    song = left;
                    singer = right;
                } else {
                    // 普通「歌手 - 歌名」（歌手在左）
                    singer = left;
                    song = right;
                }
            } else {
                song = base;
                singer = author;
            }
        }

        // 3) 括号里的歌手（【林火火】/（林火火翻唱））—— 仅在没歌手或只是 UP 主兜底时用
        if (singer.isEmpty() || (!author.isEmpty() && norm(singer).equals(norm(author)))) {
            for (String tag : brackets(base)) {
                String ct = cleanArtist(tag.replace("翻唱", " ")
                        .replace("cover", " ").replace("Cover", " "));
                if (!ct.isEmpty() && looksLikeArtist(ct)) {
                    singer = ct;
                    break;
                }
            }
        }

        // 4) 专辑：只认明确标注的「专辑：xxx」
        if (album.isEmpty()) {
            for (String tag : brackets(base)) {
                if (tag.contains("专辑")) {
                    String v = tag.replace("专辑", "").replace(":", "").replace("：", "").trim();
                    if (v.length() > 0) album = v;
                }
            }
        }

        // 5) 清理噪声。歌名兜底保证非空
        song = denoise(song);
        if (song.isEmpty()) song = denoise(base);
        if (song.isEmpty()) song = base;
        singer = cleanArtist(singer);
        if (singer.isEmpty()) singer = author;

        if (overwrite) {
            if (!song.isEmpty()) t.song = song;
            if (!singer.isEmpty()) t.artist = singer;
            // 专辑：只在解析到明确信息时覆盖，避免清掉之前联网补到的正确专辑
            if (!album.isEmpty()) t.album = album;
        } else {
            if (t.song == null || t.song.isEmpty()) t.song = song;
            if (t.artist == null || t.artist.isEmpty()) t.artist = singer;
            if (t.album == null || t.album.isEmpty()) t.album = album;
        }
        if (t.srcTitle == null || t.srcTitle.isEmpty()) t.srcTitle = whole;
    }

    /** 去掉合集里的曲目序号前缀「0123. 」「12、」「12:」「12．」。 */
    private static String stripSeq(String s) {
        if (s == null) return "";
        return s.replaceAll("^\\s*\\d{1,4}\\s*[.、．:：]\\s*", "").trim();
    }

    /**
     * 清理歌手名：去括号标签/噪声、剥离尾部「歌曲/音乐/合集/精选…」、
     * 把「A _ B」这种多歌手分隔符统一成「A / B」。
     */
    private static String cleanArtist(String s) {
        if (s == null) return "";
        String t = denoise(s);
        // 反复剥掉尾部描述词（denoise 会先删「合集」，这里再收拾「歌曲」「音乐」等）
        t = t.replaceAll("((歌曲|音乐|合集|精选|歌单|无损|品质|音质|高音质|珍藏|典藏|全收录|收录)\\s*)+$", "")
                .trim();
        t = t.replaceAll("\\s*_\\s*", " / ");
        t = t.replaceAll("\\s*/\\s*", " / ");
        t = t.replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^[/\\s]+|[/\\s]+$", "").trim();
        return t;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean isNumberOnly(String s) {
        return s.replaceAll("[\\s0-9.、．:：\\-Pp第集期话章节曲篇]", "").isEmpty();
    }

    private static int indexOfSeparator(String s) {
        String[] seps = {" - ", " – ", " — ", "-", "—"};
        int best = -1;
        for (String sep : seps) {
            int i = s.indexOf(sep);
            if (i > 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private static int sepLen(String s, int at) {
        if (s.startsWith(" ", at)) return 3;          // " - "
        if (s.startsWith("–", at) || s.startsWith("—", at)) return 1;
        return 1;
    }

    /** 《》里的第一个内容（歌名）。 */
    private static String firstBook(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("《([^》]{1,40})》").matcher(s);
        if (m.find()) {
            String v = m.group(1).trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    /** 第一个左括号的位置（任意括号类型），没有返回 -1。 */
    private static int firstBracketIndex(String s) {
        if (s == null) return -1;
        int best = -1;
        for (char ch : new char[]{'《', '【', '[', '（', '(', '「'}) {
            int i = s.indexOf(ch);
            if (i > 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    /** 括号里的内容（【】[]（）()《》「」）。 */    private static List<String> brackets(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[【\\[（(《「]([^】\\]）)》」]{1,30})[】\\]）)》」]")
                .matcher(s);
        while (m.find()) {
            String v = m.group(1).trim();
            if (v.length() > 0) out.add(v);
        }
        return out;
    }

    /** 去掉括号标签、噪声词、编号、多余空白。 */
    private static String denoise(String s) {
        if (s == null) return "";
        String t = s;
        t = t.replaceAll("[【\\[（(《「][^】\\]）)》」]*[】\\]）)》」]", " ");
        String low = t.toLowerCase();
        for (String n : NOISE) {
            if (low.contains(n)) {
                t = t.replaceAll("(?i)" + java.util.regex.Pattern.quote(n), " ");
                low = t.toLowerCase();
            }
        }
        t = t.replaceAll("(?i)\\b(feat|ft|prod|remix|ver|version|official)\\b\\.?", " ");
        // 结尾的 Video / MV / Audio 是载体不是歌名
        t = t.replaceAll("(?i)[\\s\\-_/|]*\\b(music\\s+)?(video|mv|audio|lyric)\\s*$", " ");
        t = t.replaceAll("[|｜/\\\\]+", " ");
        t = NeteaseLyric.stripNumbering(t);
        t = NeteaseLyric.stripLeadingNumber(t);
        t = t.replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^[-–—:：.、]+|[-–—:：.、]+$", "").trim();
        return t;
    }

    /** 像不像歌手名：短、没有噪声词、不是纯数字、不是「歌名」式长句。 */
    private static boolean looksLikeArtist(String s) {
        if (s == null) return false;
        String v = s.trim();
        if (v.length() < 1 || v.length() > 24) return false;
        String low = v.toLowerCase();
        for (String n : NOISE) {
            if (low.contains(n)) return false;
        }
        if (v.matches("^[\\d\\s.、]+$")) return false;
        // 含「的」「了」这类虚词的多半是标题不是人名
        return !(v.length() > 8 && (v.contains("的") || v.contains("了") || v.contains("我们")));
    }

    // ----------------------------------------------------------- online

    /** 联网校准（单线程队列，避免批量导入时并发打接口）。 */
    public static void enqueue(final Context c, final Track t) {
        if (c == null || t == null) return;
        final Context app = c.getApplicationContext();
        QUEUE.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    enrich(app, t);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * 一次性补齐历史曲目：老版本导入的歌只有 B 站标题，没有歌手/专辑。
     * 每个安装只跑一次（pref 记标记），逐个本地解析 + 联网补专辑。
     * v5：清空网易云时代可能错误的专辑，重推标题后用 QQ 音乐重新补齐。
     */
    public static void backfill(final Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        if (com.volcano.bmusic.App.prefs().getBoolean("meta_backfill_v5", false)) return;
        com.volcano.bmusic.App.prefs().edit().putBoolean("meta_backfill_v5", true).apply();
        QUEUE.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Track> all = Db.get(app).allTracks();
                    int changed = 0;
                    for (Track t : all) {
                        // 先清专辑（可能来自网易云的错误结果），再用标题重推歌名/歌手；
                        // 标题里明确写了「专辑：xxx」的会在此恢复
                        t.album = "";
                        parseLocal(t, true);
                        Db.get(app).setMeta(t.id, t.song, t.artist, t.album);
                        changed++;
                    }
                    if (changed > 0) {
                        android.content.Intent i = new android.content.Intent(ACT_META);
                        i.setPackage(app.getPackageName());
                        i.putExtra("n", changed);
                        app.sendBroadcast(i);
                    }
                    // 联网补专辑：节流 400ms，连续 3 次请求失败（疑似限流）即停
                    int fail = 0;
                    for (Track t : all) {
                        if (t.album != null && !t.album.trim().isEmpty()) continue;
                        int r = enrich(app, t);
                        if (r < 0) {
                            fail++;
                            if (fail >= 3) break;
                        } else {
                            fail = 0;
                        }
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException ignored) {
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * 联网补专辑：查询 QQ 音乐，只取真实发行专辑名。绝不改歌名/歌手。
     * 候选必须「歌名一致 + 歌手对得上」才采纳，否则宁可留空（列表显示 Unknown），
     * 也不张冠李戴。网易云搜索翻唱太多且专辑常是合集名，已弃用。
     *
     * @return 1 补到专辑；0 没匹配到；-1 请求失败（疑似限流）
     */
    public static int enrich(Context c, Track t) {
        if (c == null || t == null) return 0;
        String song = safe(t.song);
        String artist = safe(t.artist);
        if (song.isEmpty()) return 0;
        // 已有专辑就不必再请求
        if (t.album != null && !t.album.trim().isEmpty()) return 0;

        String album = qqAlbum(song, artist);
        if (album == null) return -1;                       // 请求失败 / 限流
        if (album.isEmpty()) return 0;                      // 没匹配到

        t.album = album;
        Db.get(c).setMeta(t.id, t.song, t.artist, t.album);
        android.content.Intent i = new android.content.Intent(ACT_META);
        i.setPackage(c.getPackageName());
        i.putExtra("id", t.id);
        c.sendBroadcast(i);
        return 1;
    }

    /**
     * QQ 音乐搜索取专辑。@return 专辑名；null=请求失败；""=没匹配到。
     */
    private static String qqAlbum(String song, String artist) {
        String ns = norm(song);
        String na = norm(artist);
        String query = (!artist.isEmpty() && !ns.contains(na)) ? (artist + " " + song) : song;
        org.json.JSONObject o = qqSearch(query);
        if (o == null) return null;
        String hit = pickAlbum(o, song, artist);
        if (hit == null && !query.equals(song)) {
            o = qqSearch(song);
            if (o == null) return null;
            hit = pickAlbum(o, song, artist);
        }
        return hit == null ? "" : hit;
    }

    /** 从搜索结果里挑专辑：歌名完全一致优先直接采纳，包含匹配取第一个过门控的。 */
    private static String pickAlbum(org.json.JSONObject o, String song, String artist) {
        if (o == null) return null;
        org.json.JSONObject data = o.optJSONObject("data");
        if (data == null) return null;
        org.json.JSONObject sj = data.optJSONObject("song");
        if (sj == null) return null;
        org.json.JSONArray list = sj.optJSONArray("list");
        if (list == null) return null;
        String best = null;
        for (int i = 0; i < list.length(); i++) {
            org.json.JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            String name = s.optString("songname");
            String alb = s.optString("albumname").trim();
            if (alb.isEmpty()) continue;
            if (!songMatches(song, name)) continue;
            StringBuilder sb = new StringBuilder();
            org.json.JSONArray arts = s.optJSONArray("singer");
            if (arts != null) {
                for (int j = 0; j < arts.length(); j++) {
                    if (j > 0) sb.append("/");
                    sb.append(arts.optJSONObject(j).optString("name"));
                }
            }
            if (!artist.isEmpty() && !artistMatches(artist, sb.toString())) continue;
            if (norm(song).equals(norm(name))) return alb;   // 完全一致：直接采纳
            if (best == null) best = alb;                    // 包含匹配：取第一个
        }
        return best;
    }

    /** QQ 音乐公开搜索接口（无需签名）。失败返回 null。 */
    private static org.json.JSONObject qqSearch(String query) {
        try {
            String url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w="
                    + java.net.URLEncoder.encode(query, "UTF-8") + "&format=json&n=8";
            java.util.Map<String, String> h = new java.util.HashMap<String, String>();
            h.put("Referer", "https://y.qq.com/");
            com.volcano.bmusic.net.Http.Resp r =
                    com.volcano.bmusic.net.Http.get(url, h);
            if (!r.ok() || r.body == null || r.body.trim().isEmpty()) return null;
            return new org.json.JSONObject(r.body);
        } catch (Exception e) {
            return null;
        }
    }

    /** 歌名一致判定：短名（<3 字）必须完全一致，长名允许包含（如多加「现场版」）。 */
    private static boolean songMatches(String want, String got) {
        String a = norm(want);
        String b = norm(got);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        return a.length() >= 3 && b.contains(a);
    }

    private static boolean same(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    /** 歌手比对：忽略大小写/空格，允许「A2O」匹配「A2O」「A2O_LITE」等多位歌手串。 */
    private static boolean artistMatches(String want, String got) {
        String a = norm(want);
        String b = norm(got);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (b.contains(a) || a.contains(b)) return true;
        // 多位歌手用 / 分隔时逐个比
        String[] parts = (got == null ? "" : got).split("[/、,&]");
        for (String p : parts) {
            String x = norm(p);
            if (!x.isEmpty() && (x.contains(a) || a.contains(x))) return true;
        }
        return false;
    }

    /** 字符重合度打分（忽略空格/大小写/标点）。 */
    private static int score(String a, String b) {
        String x = norm(a);
        String y = norm(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        if (x.equals(y)) return x.length() * 2;
        if (y.contains(x) || x.contains(y)) return Math.min(x.length(), y.length()) + 1;
        java.util.Set<Character> set = new java.util.HashSet<Character>();
        for (int i = 0; i < y.length(); i++) set.add(y.charAt(i));
        int hit = 0;
        for (int i = 0; i < x.length(); i++) {
            if (set.contains(x.charAt(i))) hit++;
        }
        return hit;
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[\\s\\p{Punct}，。！？、：；（）【】《》「」·]", "");
    }
}
