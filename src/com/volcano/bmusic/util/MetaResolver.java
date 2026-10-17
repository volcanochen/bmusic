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
 *   <li>{@link #enqueue} 联网用网易云搜索接口校准（歌手/专辑/规范歌名），
 *       走单线程队列，批量导入时不会同时发一堆请求；结果写库并广播刷新。</li>
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
        // 分 P 名可能只是「01」「P1」这种，那就以合集标题为准
        String base = part.length() >= 2 && !isNumberOnly(part) ? part : whole;
        if (base.isEmpty()) base = whole;
        if (base.isEmpty()) return;

        // 1) 《歌名》优先 —— 书名号里几乎总是歌名，外面的短词是歌手
        String singer = "";
        String song = "";
        String bookSong = firstBook(base);
        if (bookSong != null) {
            song = bookSong;
            String outside = base.replaceAll("《[^》]*》", " ").trim();
            int d0 = indexOfSeparator(outside);
            String cand = denoise(d0 > 0 ? outside.substring(0, d0) : outside);
            if (cand.isEmpty()) cand = denoise(outside);
            if (looksLikeArtist(cand)) singer = cand;
        } else {
            // 2) 「歌手 - 歌名」/「歌名 - 歌手」
            int dash = indexOfSeparator(base);
            if (dash > 0) {
                String left = base.substring(0, dash).trim();
                String right = base.substring(dash + sepLen(base, dash)).trim();
                if (looksLikeArtist(left) && !looksLikeArtist(right)) {
                    singer = left;
                    song = right;
                } else if (looksLikeArtist(right) && !looksLikeArtist(left)) {
                    singer = right;
                    song = left;
                } else {
                    song = right;
                    singer = left;
                }
            } else {
                song = base;
            }
        }

        // 2) 【xx】/《xx》 括号内容：短且无噪声 → 歌手；含「专辑」→ 专辑
        String album = "";
        List<String> tags = brackets(song);
        for (String tag : tags) {
            if (tag.contains("专辑")) {
                String v = tag.replace("专辑", "").replace(":", "").replace("：", "").trim();
                if (v.length() > 0) album = v;
            } else if (singer.isEmpty() && looksLikeArtist(tag)) {
                singer = tag;
            }
        }
        if (album.isEmpty()) {
            for (String tag : brackets(base)) {
                if (tag.contains("专辑")) {
                    String v = tag.replace("专辑", "").replace(":", "").replace("：", "").trim();
                    if (v.length() > 0) album = v;
                }
            }
        }

        // 3) 去掉括号标签、MV/官方等噪声和曲目编号，得到干净歌名
        song = denoise(song);
        singer = denoise(singer);

        // 4) 歌手还空着：合集标题里找（《》前面的短词最可能是歌手），再退回 UP 主
        if (singer.isEmpty() && !whole.isEmpty() && !whole.equals(base)) {
            int bi = firstBracketIndex(whole);
            if (bi > 0) {
                String head = denoise(whole.substring(0, bi));
                if (looksLikeArtist(head)) singer = head;
            }
            if (singer.isEmpty()) {
                int d2 = indexOfSeparator(whole);
                if (d2 > 0) {
                    String left = whole.substring(0, d2).trim();
                    if (looksLikeArtist(left)) singer = denoise(left);
                }
            }
            if (singer.isEmpty()) {
                for (String tag : brackets(whole)) {
                    if (looksLikeArtist(tag)) {
                        singer = denoise(tag);
                        break;
                    }
                }
            }
        }
        if (singer.isEmpty()) singer = NeteaseLyric.cleanAuthor(t.author);

        if (overwrite) {
            if (!song.isEmpty()) t.song = song;
            if (!singer.isEmpty()) t.artist = singer;
            t.album = album;
        } else {
            if (t.song == null || t.song.isEmpty()) t.song = song;
            if (t.artist == null || t.artist.isEmpty()) t.artist = singer;
            if (t.album == null || t.album.isEmpty()) t.album = album;
        }
        if (t.srcTitle == null || t.srcTitle.isEmpty()) t.srcTitle = whole;
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
     * 每个安装只跑一次（pref 记标记），逐个本地解析 + 联网校准。
     */
    public static void backfill(final Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        if (com.volcano.bmusic.App.prefs().getBoolean("meta_backfill_v3", false)) return;
        com.volcano.bmusic.App.prefs().edit().putBoolean("meta_backfill_v3", true).apply();
        QUEUE.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Track> all = Db.get(app).allTracks();
                    int changed = 0;
                    for (Track t : all) {
                        // 用标题重新推导，覆盖掉之前可能匹错的联网结果
                        parseLocal(t, true);
                        Db.get(app).setMeta(t.id, t.song, t.artist, t.album);
                        changed++;
                        try {
                            enrich(app, t);
                        } catch (Exception ignored) {
                        }
                    }
                    if (changed > 0) {
                        android.content.Intent i = new android.content.Intent(ACT_META);
                        i.setPackage(app.getPackageName());
                        i.putExtra("n", changed);
                        app.sendBroadcast(i);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * 用网易云搜索校准：以「歌手 + 歌名」搜，取字符重合度最高的候选，
     * 补全歌手/专辑，并把歌名换成规范写法（只在明显更匹配时替换）。
     */
    public static void enrich(Context c, Track t) {
        String song = safe(t.song);
        String artist = safe(t.artist);
        if (song.isEmpty() && safe(t.title).isEmpty()) return;

        String query = song;
        if (!artist.isEmpty() && !song.toLowerCase().contains(artist.toLowerCase())) {
            query = artist + " " + song;
        }
        if (query.isEmpty()) query = safe(t.title);

        List<NeteaseLyric.Song> hits = NeteaseLyric.searchNow(query);
        if (hits == null || hits.isEmpty()) hits = NeteaseLyric.searchNow(song);
        if (hits == null || hits.isEmpty()) return;

        NeteaseLyric.Song best = null;
        int bestScore = 0;
        for (NeteaseLyric.Song s : hits) {
            int nameScore = score(song, s.name);
            boolean artistHit = !artist.isEmpty() && artistMatches(artist, s.artist);
            // 已知歌手时，候选必须歌手对得上，否则至少歌名要完全包含 ——
            // 否则很容易被同名翻唱 / 合集带跑（专辑、歌手全错）。
            if (!artist.isEmpty() && !artistHit && nameScore < song.length()) continue;
            int sc = nameScore + (artistHit ? 6 : 0);
            if (sc > bestScore) {
                bestScore = sc;
                best = s;
            }
        }
        // 要求至少一半字符命中，避免把无关歌曲的歌手安上来
        if (best == null || bestScore < Math.max(2, song.length() / 2)) return;

        String newSong = t.song;
        String newArtist = t.artist;
        String newAlbum = t.album;
        if (best.name != null && !best.name.trim().isEmpty()
                && score(song, best.name) >= score(song, song)) {
            newSong = best.name.trim();
        }
        if (t.artist == null || t.artist.isEmpty() || bestScore >= song.length()) {
            if (best.artist != null && !best.artist.trim().isEmpty()) newArtist = best.artist.trim();
        }
        if ((t.album == null || t.album.isEmpty())
                && best.album != null && !best.album.trim().isEmpty()) {
            newAlbum = best.album.trim();
        }
        if (same(newSong, t.song) && same(newArtist, t.artist) && same(newAlbum, t.album)) {
            return;
        }
        t.song = newSong;
        t.artist = newArtist;
        t.album = newAlbum;
        Db.get(c).setMeta(t.id, newSong, newArtist, newAlbum);
        android.content.Intent i = new android.content.Intent(ACT_META);
        i.setPackage(c.getPackageName());
        i.putExtra("id", t.id);
        c.sendBroadcast(i);
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
