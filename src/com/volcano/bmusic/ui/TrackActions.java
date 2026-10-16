package com.volcano.bmusic.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
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

    /** The standard library-style actions, in this order. */
    public static final String[] LIBRARY_ITEMS =
            {"立即播放", "加入播放队列", "缓存到本地", "加入歌单", "删除曲目"};

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

    public static void cache(Activity a, Track t) {
        if (t == null) return;
        if (t.hasLocal()) {
            Toast.makeText(a, "已缓存到本地", Toast.LENGTH_SHORT).show();
            return;
        }
        Downloader.start(a, t);
        Toast.makeText(a, "开始缓存", Toast.LENGTH_SHORT).show();
    }

    /** Ask which playlist to add the track to (with a "new playlist" entry). */
    public static void addToPlaylist(final Activity a, final Track t) {
        if (t == null) return;
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
                                .setTitle("加入歌单")
                                .setItems(names, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        if (w >= pls.size()) {
                                            newPlaylist(a, t);
                                        } else {
                                            addTo(a, pls.get(w), t);
                                        }
                                    }
                                }).show();
                    }
                });
            }
        });
    }

    private static void addTo(final Activity a, final Playlist p, final Track t) {
        App.postBg(new Runnable() {
            @Override
            public void run() {
                Db.get(a).addTo(p.id, t.id);
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(a, "已加入「" + p.name + "」", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /** Create a playlist and (optionally) drop a track into it right away. */
    public static void newPlaylist(final Activity a, final Track also) {
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
                                        Toast.makeText(a, "已创建「" + name + "」",
                                                Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    /** Remove the track from the library (and its cached audio file). */
    public static void confirmDelete(final Activity a, final Track t, final Runnable after) {
        new AlertDialog.Builder(a)
                .setMessage("从曲库删除「" + t.title + "」？")
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
}
