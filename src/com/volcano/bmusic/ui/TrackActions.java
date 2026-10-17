package com.volcano.bmusic.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.volcano.bmusic.App;
import com.volcano.bmusic.db.Db;
import com.volcano.bmusic.model.Playlist;
import com.volcano.bmusic.model.Track;
import com.volcano.bmusic.player.PlaybackService;
import com.volcano.bmusic.util.Downloader;

import java.util.List;

/**
 * Song operations shared by the library, the playlists, the queue and the
 * player, so every list offers the same actions on a track.
 */
public final class TrackActions {

    public interface ItemHandler {
        /** Called with the index of the tapped item, in the same order it was passed in. */
        void onItem(Track t, int which);
    }

    /** Sink for "user picked a playlist" — used for both single and batch adds. */
    public interface PickListener {
        void onPick(long pid, String name);
    }

    /** The standard library-style actions, in this order. */
    public static final String[] LIBRARY_ITEMS =
            {"立即播放", "加入播放队列", "缓存到本地", "加入歌单", "返回 B 站", "删除曲目"};

    private TrackActions() {
    }

    /** Show a track action sheet built from the given item list. */
    public static void showMenu(Activity a, final Track t, String[] items, final ItemHandler h) {
        new AlertDialog.Builder(a)
                .setTitle(t.title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        h.onItem(t, which);
                    }
                }).show();
    }

    /** 加入当前播放列表（不存在时插到当前曲目之后）并立即播放。 */
    public static void playNow(Activity a, Track t) {
        if (t == null) return;
        PlaybackService.playNow(a, t.id);
        Toast.makeText(a, "立即播放：" + t.title, Toast.LENGTH_SHORT).show();
    }

    /** 追加到当前播放队列尾部。 */
    public static void enqueue(Activity a, Track t) {
        if (t == null) return;
        PlaybackService.append(a, t.id);
        Toast.makeText(a, "已加入队列", Toast.LENGTH_SHORT).show();
    }

    /**
     * 跳回 B 站该曲目的视频页面（多 P 视频定位到对应分 P）。
     * <p>
     * 优先直接拉起 B 站 App：先用 {@code setPackage()} 指定包名的 https 链接（App
     * Links 由 B 站注册），再退到 {@code bilibili://video/<bvid>} scheme。
     * 这样就不会先跳浏览器、再由浏览器弹「是否打开 App」。
     * 都没有才退回系统选择器（走浏览器）。
     * <p>
     * 注意：{@link Track#page} 直接来自分享链接的 {@code ?p=}，本身就是 1 起算的，
     * 所以这里原样使用，不要再加 1。
     */
    public static void openBili(Activity a, Track t) {
        if (t == null || t.bvid == null || t.bvid.isEmpty()) {
            Toast.makeText(a, "缺少 BV 号，无法跳转", Toast.LENGTH_SHORT).show();
            return;
        }
        final int p = t.page > 0 ? t.page : 1;
        final String url = "https://www.bilibili.com/video/" + t.bvid
                + (p > 1 ? ("?p=" + p) : "");
        final android.content.pm.PackageManager pm = a.getPackageManager();
        final String[] pkgs = {
                "tv.danmaku.bili",           // 官方版
                "tv.danmaku.bilibilihd",     // HD 版
                "com.bilibili.app.in",       // 国际版
                "com.bilibili.app.blue",     // 概念版
        };
        for (String pkg : pkgs) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.setPackage(pkg);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                if (pm.resolveActivity(i, 0) != null) {
                    a.startActivity(i);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        try {
            Intent s = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("bilibili://video/" + t.bvid + "?p=" + p));
            if (pm.resolveActivity(s, 0) != null) {
                a.startActivity(s);
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(a, "没有可打开网页的应用", Toast.LENGTH_LONG).show();
        }
    }

    public static void cache(Activity a, Track t) {
        if (t == null) return;
        if (t.hasLocal()) {
            Toast.makeText(a, "已缓存到本地", Toast.LENGTH_SHORT).show();
            return;
        }
        Downloader.start(a, t);
        Toast.makeText(a, "开始缓存", Toast.LENGTH_SHORT).show();
    }

    /**
     * 弹出歌单选择器，末项固定为「新建歌单...」。
     * 单曲与批量共用这一份，保证两处行为一致。
     */
    public static void pickPlaylist(final Activity a, final String title, final PickListener cb) {
        App.postBg(new Runnable() {
            @Override
            public void run() {
                final List<Playlist> pls = Db.get(a).playlists();
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        CharSequence[] names = new CharSequence[pls.size() + 1];
                        for (int i = 0; i < pls.size(); i++) names[i] = pls.get(i).name;
                        names[pls.size()] = "新建歌单...";
                        new AlertDialog.Builder(a)
                                .setTitle(title)
                                .setItems(names, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        if (w >= pls.size()) {
                                            newPlaylist(a, null, cb);
                                        } else {
                                            cb.onPick(pls.get(w).id, pls.get(w).name);
                                        }
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        });
    }

    /** 单曲加入歌单：与批量走同一套选择器。 */
    public static void addToPlaylist(final Activity a, final Track t) {
        if (t == null) return;
        pickPlaylist(a, "加入歌单", new PickListener() {
            @Override
            public void onPick(long pid, String name) {
                addTo(a, pid, name, t);
            }
        });
    }

    private static void addTo(final Activity a, final long pid, final String pname, final Track t) {
        App.postBg(new Runnable() {
            @Override
            public void run() {
                Db.get(a).addTo(pid, t.id);
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(a, "已加入「" + pname + "」", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /**
     * 新建歌单，建好后回传 pid 给调用方（批量加入用）；
     * {@code also != null} 时顺手把这首也放进去。
     */
    public static void newPlaylist(final Activity a, final Track also) {
        newPlaylist(a, also, null);
    }

    private static void newPlaylist(final Activity a, final Track also, final PickListener cb) {
        final android.widget.EditText et = new android.widget.EditText(a);
        et.setHint("歌单名称");
        et.setTextColor(android.graphics.Color.WHITE);
        et.setHintTextColor(0xFF777777);
        new AlertDialog.Builder(a)
                .setTitle("新建歌单")
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final String name = et.getText().toString().trim();
                        if (name.isEmpty()) return;
                        App.postBg(new Runnable() {
                            @Override
                            public void run() {
                                final long id = Db.get(a).addPlaylist(name);
                                if (also != null) Db.get(a).addTo(id, also.id);
                                App.runOnUi(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (cb != null) {
                                            cb.onPick(id, name);
                                        } else {
                                            Toast.makeText(a, "已创建「" + name + "」",
                                                    Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    /**
     * Remove the track from the library (and its cached audio file).
     *
     * @param extraTip 追加到确认文案里的后果说明，例如「同时会从曲库删除」，
     *                 用于在非曲库页面（队列 / 歌单）防止误删；可传 null。
     */
    public static void confirmDelete(final Activity a, final Track t, final Runnable after,
                                     final String extraTip) {
        String msg = "从曲库删除「" + t.title + "」？";
        if (extraTip != null && extraTip.length() > 0) msg += "\n" + extraTip;
        new AlertDialog.Builder(a)
                .setMessage(msg)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        App.postBg(new Runnable() {
                            @Override
                            public void run() {
                                java.io.File f = new java.io.File(t.path == null ? "" : t.path);
                                if (t.hasLocal() && f.exists()) f.delete();
                                Db.get(a).deleteTrack(t.id);
                                App.runOnUi(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(a, "已删除", Toast.LENGTH_SHORT).show();
                                        if (after != null) after.run();
                                    }
                                });
                            }
                        });
                    }
                }).setNegativeButton("取消", null).show();
    }

    /** Remove the track from the library (and its cached audio file). */
    public static void confirmDelete(final Activity a, final Track t, final Runnable after) {
        confirmDelete(a, t, after, null);
    }
}
