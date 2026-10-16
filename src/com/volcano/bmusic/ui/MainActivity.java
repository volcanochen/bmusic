package com.volcano.bmusic.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.volcano.bmusic.App;
import com.volcano.bmusic.R;
import com.volcano.bmusic.api.Bili;
import com.volcano.bmusic.api.Importer;
import com.volcano.bmusic.db.Db;
import com.volcano.bmusic.model.Playlist;
import com.volcano.bmusic.model.Track;
import com.volcano.bmusic.player.PlaybackService;
import com.volcano.bmusic.ui.adapter.PlaylistAdapter;
import com.volcano.bmusic.ui.adapter.TrackAdapter;
import com.volcano.bmusic.util.Cache;
import com.volcano.bmusic.util.CoverLoader;
import com.volcano.bmusic.util.Downloader;
import com.volcano.bmusic.util.Notify;
import com.volcano.bmusic.util.PartAsk;
import com.volcano.bmusic.util.Receivers;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private ListView listTracks;
    private ListView listPlaylists;
    private ListView listRecent;
    private TrackAdapter trackAdapter;
    private TrackAdapter recentAdapter;
    private PlaylistAdapter playlistAdapter;
    private EditText search;
    private TextView emptyTracks;
    private TextView emptyPlaylists;
    private TextView emptyRecent;
    private TextView tvCache;
    private View mini;
    private ImageView miniArt;
    private TextView miniTitle;
    private TextView miniSub;
    private ImageButton miniPlay;
    private View pageTracks;
    private View pagePlaylists;
    private View pageMe;
    private ImageView[] tabIcons;
    private TextView[] tabTexts;

    private int page = 0;
    private String filter = "";
    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        bind();
        initTabs();
        initLists();
        initActions();
        askNotify();
        reloadAll();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        String t = null;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            t = i.getData().toString();
        }
        if (t == null && Intent.ACTION_SEND.equals(i.getAction())) {
            t = i.getStringExtra(Intent.EXTRA_TEXT);
        }
        if (!TextUtils.isEmpty(t)) {
            final String text = t;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    doImport(text);
                }
            }, 400);
        }
    }

    private void bind() {
        listTracks = (ListView) findViewById(R.id.list_tracks);
        listPlaylists = (ListView) findViewById(R.id.list_playlists);
        listRecent = (ListView) findViewById(R.id.list_recent);
        search = (EditText) findViewById(R.id.search);
        emptyTracks = (TextView) findViewById(R.id.empty_tracks);
        emptyPlaylists = (TextView) findViewById(R.id.empty_playlists);
        emptyRecent = (TextView) findViewById(R.id.empty_recent);
        tvCache = (TextView) findViewById(R.id.tv_cache);
        pageTracks = findViewById(R.id.page_tracks);
        pagePlaylists = findViewById(R.id.page_playlists);
        pageMe = findViewById(R.id.page_me);
        mini = findViewById(R.id.mini);
        miniArt = (ImageView) findViewById(R.id.mini_art);
        miniTitle = (TextView) findViewById(R.id.mini_title);
        miniSub = (TextView) findViewById(R.id.mini_sub);
        miniPlay = (ImageButton) findViewById(R.id.mini_play);
    }

    private void initTabs() {
        int[][] tabs = {
                {R.id.tab_tracks, R.id.icon_tracks, R.id.page_tracks},
                {R.id.tab_playlists, R.id.icon_playlists, 0},
                {R.id.tab_me, R.id.icon_me, 0}
        };
        tabIcons = new ImageView[]{findViewById(R.id.icon_tracks),
                findViewById(R.id.icon_playlists), findViewById(R.id.icon_me)};
        final TextView t1 = (TextView) ((ViewGroup) findViewById(R.id.tab_tracks)).getChildAt(1);
        final TextView t2 = (TextView) ((ViewGroup) findViewById(R.id.tab_playlists)).getChildAt(1);
        final TextView t3 = (TextView) ((ViewGroup) findViewById(R.id.tab_me)).getChildAt(1);
        tabTexts = new TextView[]{t1, t2, t3};
        View[] pages = {pageTracks, pagePlaylists, pageMe};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            View tab = findViewById(tabs[i][0]);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(idx);
                }
            });
        }
        select(0);
    }

    private void select(int idx) {
        page = idx;
        View[] pages = {pageTracks, pagePlaylists, pageMe};
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            int color = i == idx ? getResources().getColor(R.color.pink)
                    : getResources().getColor(R.color.text_dim);
            tabIcons[i].setColorFilter(color);
            tabTexts[i].setTextColor(color);
        }
        if (idx == 2) refreshCacheSize();
    }

    private void initLists() {
        trackAdapter = new TrackAdapter(this, new TrackAdapter.Listener() {
            @Override
            public void onPlay(List<Track> list, int pos) {
                playFrom(list, pos, -1);
            }
            @Override
            public void onMore(View anchor, Track t) {
                showTrackMenu(anchor, t);
            }
        });
        listTracks.setAdapter(trackAdapter);
        listTracks.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                showTrackMenu(v, trackAdapter.data().get(pos));
                return true;
            }
        });

        recentAdapter = new TrackAdapter(this, new TrackAdapter.Listener() {
            @Override
            public void onPlay(List<Track> list, int pos) {
                playFrom(list, pos, -1);
            }
            @Override
            public void onMore(View anchor, Track t) {
                showTrackMenu(anchor, t);
            }
        });
        listRecent.setAdapter(recentAdapter);
        listRecent.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                showTrackMenu(v, recentAdapter.data().get(pos));
                return true;
            }
        });

        playlistAdapter = new PlaylistAdapter(this);
        listPlaylists.setAdapter(playlistAdapter);
        listPlaylists.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Playlist pl = (Playlist) playlistAdapter.getItem(pos);
                Intent i = new Intent(MainActivity.this, PlaylistActivity.class);
                i.putExtra("pid", pl.id);
                i.putExtra("name", pl.name);
                startActivity(i);
            }
        });
        listPlaylists.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                final Playlist pl = (Playlist) playlistAdapter.getItem(pos);
                String[] items = pl.name.equals("我喜欢的音乐")
                        ? new String[]{"清空歌单"}
                        : new String[]{"重命名", "删除歌单", "清空歌单"};
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(pl.name)
                        .setItems(items, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (!pl.name.equals("我喜欢的音乐")) {
                                    if (which == 0) renamePlaylist(pl);
                                    else if (which == 1) confirmDelPlaylist(pl);
                                    else clearPlaylist(pl);
                                } else {
                                    clearPlaylist(pl);
                                }
                            }
                        }).show();
                return true;
            }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                filter = s.toString().trim();
                reloadTracks();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void initActions() {
        findViewById(R.id.btn_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddDialog();
            }
        });
        findViewById(R.id.btn_new_pl).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNewPlaylistDialog();
            }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.btn_clear_cache).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("清除音频缓存")
                        .setMessage("删除所有已下载的音频文件（曲目信息保留）。确定？")
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                App.postBg(new Runnable() {
                                    @Override
                                    public void run() {
                                        Cache.dropAudio(MainActivity.this);
                                        Db.get(MainActivity.this).execResetCache();
                                        App.runOnUi(new Runnable() {
                                            @Override
                                            public void run() {
                                                Toast.makeText(MainActivity.this, "已清除", Toast.LENGTH_SHORT).show();
                                                reloadAll();
                                            }
                                        });
                                    }
                                });
                            }
                        })
                        .setNegativeButton("取消", null).show();
            }
        });
        mini.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, PlayerActivity.class));
            }
        });
        miniPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlaybackService.toggle(MainActivity.this);
            }
        });
        findViewById(R.id.mini_next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlaybackService.next(MainActivity.this);
            }
        });
    }

    private void askNotify() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
        }
    }

    // ---------------------------------------------------------------- data

    private void reloadAll() {
        reloadTracks();
        reloadRecent();
        reloadPlaylists();
        refreshMini();
    }

    private void reloadTracks() {
        App.postBg(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Track> all = Db.get(MainActivity.this).allTracks();
                if (!TextUtils.isEmpty(filter)) {
                    String f = filter.toLowerCase();
                    ArrayList<Track> keep = new ArrayList<Track>();
                    for (Track t : all) {
                        String s = (t.title == null ? "" : t.title.toLowerCase())
                                + " " + (t.author == null ? "" : t.author.toLowerCase())
                                + " " + t.bvid;
                        if (s.contains(f)) keep.add(t);
                    }
                    all.clear();
                    all.addAll(keep);
                }
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        trackAdapter.setData(all);
                        emptyTracks.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
                        Track cur = currentTrack();
                        if (cur != null) trackAdapter.setPlaying(cur.id, isPlaying());
                    }
                });
            }
        });
    }

    private void reloadRecent() {
        App.postBg(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Track> list = Db.get(MainActivity.this).recent(40);
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        recentAdapter.setData(list);
                        emptyRecent.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        });
    }

    private void reloadPlaylists() {
        App.postBg(new Runnable() {
            @Override
            public void run() {
                final List<Playlist> list = Db.get(MainActivity.this).playlists();
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        playlistAdapter.setData(list);
                        emptyPlaylists.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        });
    }

    private void refreshCacheSize() {
        App.postBg(new Runnable() {
            @Override
            public void run() {
                final long audio = Cache.audioSize(MainActivity.this);
                final long cover = Cache.coverSize(MainActivity.this);
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        tvCache.setText("音频 " + Bili.fmtSize(audio) + " · 封面 " + Bili.fmtSize(cover));
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------- mini bar

    private PlaybackService svc() {
        return PlaybackService.get();
    }

    private Track currentTrack() {
        PlaybackService s = svc();
        if (s != null && s.current() != null) return s.current();
        String id = App.prefs().getString("last_id", null);
        if (id == null) return null;
        Track t = Db.get(this).get(id);
        if (t != null) t.cached = t.hasLocal();
        return t;
    }

    private boolean isPlaying() {
        PlaybackService s = svc();
        return s != null && s.isPlaying();
    }

    private void refreshMini() {
        Track t = currentTrack();
        if (t == null) {
            mini.setVisibility(View.GONE);
            return;
        }
        mini.setVisibility(View.VISIBLE);
        miniTitle.setText(t.title);
        miniSub.setText(t.author);
        boolean playing = isPlaying();
        miniPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        miniArt.setImageResource(R.drawable.ic_music_note);
        CoverLoader.get(this).load(miniArt, t.cover);
        trackAdapter.setPlaying(t.id, playing);
        recentAdapter.setPlaying(t.id, playing);
    }

    // --------------------------------------------------------------- import

    private void showAddDialog() {
        final EditText et = new EditText(this);
        et.setHint(getString(R.string.hint_paste));
        et.setMinLines(3);
        et.setGravity(android.view.Gravity.TOP);
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(0xFF777777);
        et.setTextSize(13);
        String clip = clipboardText();
        if (!TextUtils.isEmpty(clip)) et.setText(clip);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("添加B站音频")
                .setView(et)
                .setPositiveButton("解析添加", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String text = et.getText().toString();
                        doImport(text);
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private String clipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return null;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return null;
            CharSequence c = cd.getItemAt(0).getText();
            return c == null ? null : c.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void doImport(final String text) {
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "请输入链接", Toast.LENGTH_SHORT).show();
            return;
        }
        final AlertDialog wait = new AlertDialog.Builder(this)
                .setMessage("正在解析...")
                .setCancelable(false).show();
        Importer.add(this, text, new Importer.Chooser() {
            @Override
            public boolean askAll(Importer.Candidate cand) {
                return PartAsk.allOrCurrent(MainActivity.this, cand);
            }
        }, new Importer.Callback() {
            @Override
            public void done(List<Track> added, String failMsg) {
                try {
                    wait.dismiss();
                } catch (Exception ignored) {
                }
                Importer.toastResult(MainActivity.this, added, failMsg);
                reloadAll();
                if (!added.isEmpty()) {
                    // play the first one right away if library was empty before
                    String id = App.prefs().getString("last_id", null);
                    if (id == null) {
                        ArrayList<String> ids = new ArrayList<String>();
                        for (Track t : added) ids.add(t.id);
                        PlaybackService.setQueue(MainActivity.this, -1, ids, added.get(0).id);
                    }
                }
            }
        });
    }

    // -------------------------------------------------------------- menus

    private void showTrackMenu(View anchor, final Track t) {
        final String[] items = {"立即播放", "加入播放队列", "缓存到本地", "加入歌单", "删除曲目"};
        new AlertDialog.Builder(this)
                .setTitle(t.title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                PlaybackService.open(MainActivity.this, t.id);
                                handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        refreshMini();
                                    }
                                }, 300);
                                break;
                            case 1:
                                PlaybackService.append(MainActivity.this, t.id);
                                Toast.makeText(MainActivity.this, "已加入队列", Toast.LENGTH_SHORT).show();
                                break;
                            case 2:
                                Downloader.start(MainActivity.this, t);
                                Toast.makeText(MainActivity.this, "开始缓存", Toast.LENGTH_SHORT).show();
                                break;
                            case 3:
                                choosePlaylist(t);
                                break;
                            case 4:
                                confirmDelete(t);
                                break;
                        }
                    }
                }).show();
    }

    private void choosePlaylist(final Track t) {
        App.postBg(new Runnable() {
            @Override
            public void run() {
                final List<Playlist> pls = Db.get(MainActivity.this).playlists();
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        CharSequence[] names = new CharSequence[pls.size() + 1];
                        for (int i = 0; i < pls.size(); i++) names[i] = pls.get(i).name;
                        names[pls.size()] = "新建歌单...";
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("加入歌单")
                                .setItems(names, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        if (w >= pls.size()) {
                                            showNewPlaylistDialogWith(t);
                                        } else {
                                            App.postBg(new Runnable() {
                                                @Override
                                                public void run() {
                                                }
                                            });
                                            addToPlaylist(pls.get(w), t);
                                        }
                                    }
                                }).show();
                    }
                });
            }
        });
    }

    private void addToPlaylist(final Playlist p, final Track t) {
        App.postBg(new Runnable() {
            @Override
            public void run() {
                Db.get(MainActivity.this).addTo(p.id, t.id);
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "已加入「" + p.name + "」", Toast.LENGTH_SHORT).show();
                        reloadPlaylists();
                    }
                });
            }
        });
    }

    private void showNewPlaylistDialog() {
        showNewPlaylistDialogWith(null);
    }

    private void showNewPlaylistDialogWith(final Track also) {
        final EditText et = new EditText(this);
        et.setHint("歌单名称");
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(0xFF777777);
        new AlertDialog.Builder(this)
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
                                final long id = Db.get(MainActivity.this).addPlaylist(name);
                                if (also != null) Db.get(MainActivity.this).addTo(id, also.id);
                                App.runOnUi(new Runnable() {
                                    @Override
                                    public void run() {
                                        reloadPlaylists();
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void renamePlaylist(final Playlist p) {
        final EditText et = new EditText(this);
        et.setText(p.name);
        et.setTextColor(Color.WHITE);
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final String n = et.getText().toString().trim();
                        if (n.isEmpty()) return;
                        App.postBg(new Runnable() {
                            @Override
                            public void run() {
                                Db.get(MainActivity.this).renamePlaylist(p.id, n);
                                App.runOnUi(new Runnable() {
                                    @Override
                                    public void run() {
                                        reloadPlaylists();
                                    }
                                });
                            }
                        });
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void confirmDelPlaylist(final Playlist p) {
        new AlertDialog.Builder(this)
                .setMessage("删除歌单「" + p.name + "」？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        App.postBg(new Runnable() {
                            @Override
                            public void run() {
                                Db.get(MainActivity.this).delPlaylist(p.id);
                                App.runOnUi(new Runnable() {
                                    @Override
                                    public void run() {
                                        reloadPlaylists();
                                    }
                                });
                            }
                        });
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void clearPlaylist(final Playlist p) {
        new AlertDialog.Builder(this)
                .setMessage("清空「" + p.name + "」？（不会删除曲库中的曲目）")
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        App.postBg(new Runnable() {
                            @Override
                            public void run() {
                                Db.get(MainActivity.this).clearPlaylist(p.id);
                                App.runOnUi(new Runnable() {
                                    @Override
                                    public void run() {
                                        reloadPlaylists();
                                    }
                                });
                            }
                        });
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void confirmDelete(final Track t) {
        new AlertDialog.Builder(this)
                .setMessage("从曲库删除「" + t.title + "」？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        App.postBg(new Runnable() {
                            @Override
                            public void run() {
                                java.io.File f = new java.io.File(t.path == null ? "" : t.path);
                                if (t.hasLocal() && f.exists()) f.delete();
                                Db.get(MainActivity.this).deleteTrack(t.id);
                                App.runOnUi(new Runnable() {
                                    @Override
                                    public void run() {
                                        reloadAll();
                                    }
                                });
                            }
                        });
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void playFrom(List<Track> list, int pos, long pid) {
        if (list == null || list.isEmpty() || pos >= list.size()) return;
        ArrayList<String> ids = new ArrayList<String>();
        for (Track t : list) ids.add(t.id);
        PlaybackService.setQueue(this, pid, ids, list.get(pos).id);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshMini();
            }
        }, 400);
    }

    // ----------------------------------------------------------- receiver

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (PlaybackService.ACT_STATE.equals(a)) {
                refreshMini();
                String err = i.getStringExtra("error");
                if (err != null) Toast.makeText(MainActivity.this, err, Toast.LENGTH_LONG).show();
            } else if (Downloader.ACT_DL.equals(a)) {
                String id = i.getStringExtra(Downloader.EXTRA_ID);
                int st = i.getIntExtra(Downloader.EXTRA_STATE, 0);
                if (st == 0) {
                    trackAdapter.setProgress(id, i.getIntExtra(Downloader.EXTRA_PROGRESS, 0));
                } else if (st == 1) {
                    trackAdapter.setProgress(id, null);
                    reloadTracks();
                    refreshMini();
                } else if (st == 2) {
                    trackAdapter.setProgress(id, null);
                    Toast.makeText(MainActivity.this,
                            "缓存失败：" + i.getStringExtra("msg"), Toast.LENGTH_LONG).show();
                }
            } else if (Importer.ACT_ADDED.equals(a)) {
                reloadAll();
            } else if (CoverLoader.ACT_COVER.equals(a)) {
                refreshMini();
            }
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(PlaybackService.ACT_STATE);
        f.addAction(PlaybackService.ACT_PROGRESS);
        f.addAction(Downloader.ACT_DL);
        f.addAction(Importer.ACT_ADDED);
        f.addAction(CoverLoader.ACT_COVER);
        Receivers.registerLocal(this, receiver, f);
        refreshMini();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadAll();
        refreshMini();
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (page != 0) {
            select(0);
            return;
        }
        moveTaskToBack(true);
    }
}
