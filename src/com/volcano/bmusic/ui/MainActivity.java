package com.volcano.bmusic.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.pm.PackageManager;
import android.content.ActivityNotFoundException;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import android.net.Uri;

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
import com.volcano.bmusic.util.MetaResolver;
import com.volcano.bmusic.util.Notify;
import com.volcano.bmusic.util.PartAsk;
import com.volcano.bmusic.util.Receivers;
import com.volcano.bmusic.util.Win;
import com.volcano.bmusic.net.Http;
import com.volcano.bmusic.ui.UpdateActivity;
import com.volcano.bmusic.util.ApkUpdater;

import org.json.JSONObject;

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
    private View miniWrap;
    private View miniProg;
    private ProgressBar miniLoad;
    private Button btnPlayFilter;
    private TextView tvFilterChip;
    /** 曲库筛选：按歌手 / 专辑（null = 不筛）。 */
    private String filterArtist;
    private String filterAlbum;
    private ImageView miniArt;
    private TextView miniTitle;
    private TextView miniSub;
    private ImageButton miniPlay;

    // last values shown on the mini bar, so we know when something changed and
    // the change deserves an animation
    private String miniId;
    private String miniSubText;
    private Boolean miniPlaying;
    private String miniError;
    private View pageTracks;
    private View pageQueue;
    private QueuePage queuePage;
    private View pagePlaylists;
    private View pageRecent;
    private View pageMe;
    private ImageView[] tabIcons;
    private TextView[] tabTexts;

    private int page = 0;
    private String filter = "";
    private final Handler handler = new Handler();

    // 曲库多选
    private java.util.HashSet<String> libSelected = new java.util.HashSet<String>();
    private boolean libSelectMode = false;
    private View btnLibSelect;
    private View btnLibQueueSel;
    private View btnLibPlSel;
    private View btnLibClear;
    private View btnLibSelAll;
    private View btnLibSelInv;
    private View btnLibCancel;
    private View libSelBar;
    private TextView tvLibSel;
    private TextView tvUpdate;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        bind();
        queuePage = new QueuePage(this);
        initTabs();
        initLists();
        initActions();
        askNotify();
        reloadAll();
        applyWindowSize();
        handleIntent(getIntent());
    }

    /* --------- window shape: split-screen / freeform / rotation --------- */

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyWindowSize();
    }

    @Override
    public void onMultiWindowModeChanged(boolean inMultiWindowMode) {
        super.onMultiWindowModeChanged(inMultiWindowMode);
        applyWindowSize();
    }

    /** Re-fit the chrome (bottom nav, mini player, recent list) to the window. */
    private void applyWindowSize() {
        float d = getResources().getDisplayMetrics().density;
        if (d <= 0f) d = 1f;
        int[] s = Win.sizeDp(this);
        int hDp = s[1];
        boolean compact = hDp < 560;

        View nav = findViewById(R.id.nav);
        if (nav != null) {
            ViewGroup.LayoutParams lp = nav.getLayoutParams();
            lp.height = Math.round((compact ? 44 : 54) * d);
            nav.setLayoutParams(lp);
        }
        if (mini != null) {
            ViewGroup.LayoutParams lp = mini.getLayoutParams();
            lp.height = Math.round((compact ? 52 : 60) * d);
            mini.setLayoutParams(lp);
        }
        // The "recent" list has a fixed height; scale it with the window so it
        // never pushes the rest of the page off a short (split / floating) window.
        if (listRecent != null) {
            int hh = (int) (hDp * 0.34f);
            hh = Math.max(110, Math.min(240, hh));
            ViewGroup.LayoutParams lp = listRecent.getLayoutParams();
            lp.height = Math.round(hh * d);
            listRecent.setLayoutParams(lp);
        }
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
        // 播放页「曲库/播放列表」按钮带回的目标页（0=曲库 2=播放列表）
        if (i.hasExtra("page")) {
            int p = i.getIntExtra("page", 0);
            if (p < 0) p = 0;
            if (p > 4) p = 4;
            select(p);
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
        pageQueue = findViewById(R.id.page_queue);
        pageRecent = findViewById(R.id.page_recent);
        pageMe = findViewById(R.id.page_me);
        mini = findViewById(R.id.mini);
        miniWrap = findViewById(R.id.mini_wrap);
        miniProg = findViewById(R.id.mini_prog);
        miniLoad = (ProgressBar) findViewById(R.id.mini_load);
        btnPlayFilter = (Button) findViewById(R.id.btn_play_filter);
        miniArt = (ImageView) findViewById(R.id.mini_art);
        miniTitle = (TextView) findViewById(R.id.mini_title);
        miniSub = (TextView) findViewById(R.id.mini_sub);
        miniPlay = (ImageButton) findViewById(R.id.mini_play);
        btnLibSelect = findViewById(R.id.btn_lib_select);
        btnLibClear = findViewById(R.id.btn_lib_clear);
        btnLibSelAll = findViewById(R.id.btn_lib_sel_all);
        btnLibSelInv = findViewById(R.id.btn_lib_sel_inv);
        btnLibCancel = findViewById(R.id.btn_lib_cancel_sel);
        btnLibQueueSel = findViewById(R.id.btn_lib_queue_sel);
        btnLibPlSel = findViewById(R.id.btn_lib_pl_sel);
        libSelBar = findViewById(R.id.lib_sel_bar);
        tvLibSel = (TextView) findViewById(R.id.tv_lib_sel);
        tvUpdate = (TextView) findViewById(R.id.tv_update);
        tvFilterChip = (TextView) findViewById(R.id.tv_filter_chip);
        View btnFilter = findViewById(R.id.btn_filter);
        if (btnFilter != null) {
            btnFilter.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showFilterDialog();
                }
            });
        }
        if (tvFilterChip != null) {
            tvFilterChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    filterArtist = null;
                    filterAlbum = null;
                    updateFilterChip();
                    reloadTracks();
                }
            });
        }
        if (tvUpdate != null) {
            String v = currentVersionName();
            if (!TextUtils.isEmpty(v)) tvUpdate.setText("当前版本 v" + v);
        }
    }

    private void initTabs() {
        int[][] tabs = {
                {R.id.tab_tracks, R.id.icon_tracks},
                {R.id.tab_playlists, R.id.icon_playlists},
                {R.id.tab_queue, R.id.icon_queue},
                {R.id.tab_recent, R.id.icon_recent},
                {R.id.tab_me, R.id.icon_me}
        };
        tabIcons = new ImageView[]{findViewById(R.id.icon_tracks),
                findViewById(R.id.icon_playlists), findViewById(R.id.icon_queue),
                findViewById(R.id.icon_recent), findViewById(R.id.icon_me)};
        tabTexts = new TextView[]{
                (TextView) ((ViewGroup) findViewById(R.id.tab_tracks)).getChildAt(1),
                (TextView) ((ViewGroup) findViewById(R.id.tab_playlists)).getChildAt(1),
                (TextView) ((ViewGroup) findViewById(R.id.tab_queue)).getChildAt(1),
                (TextView) ((ViewGroup) findViewById(R.id.tab_recent)).getChildAt(1),
                (TextView) ((ViewGroup) findViewById(R.id.tab_me)).getChildAt(1)};
        for (int i = 0; i < 5; i++) {
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
        View[] pages = {pageTracks, pagePlaylists, pageQueue, pageRecent, pageMe};
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            int color = i == idx ? getResources().getColor(R.color.pink)
                    : getResources().getColor(R.color.text_dim);
            tabIcons[i].setColorFilter(color);
            tabTexts[i].setTextColor(color);
        }
        if (idx == 2) {
            queuePage.refresh();        // 播放列表页切到前台时拉最新队列
        }
        if (idx == 4) refreshCacheSize();
    }

    private void initLists() {
        trackAdapter = new TrackAdapter(this, new TrackAdapter.Listener() {
            @Override
            public void onPlay(List<Track> list, int pos) {
                if (libSelectMode) {
                    trackAdapter.toggleSelected(list.get(pos).id);
                    updateLibSelCount();
                    return;
                }
                PlaybackService.append(MainActivity.this, list.get(pos).id);
                Toast.makeText(MainActivity.this, "已加入队列", Toast.LENGTH_SHORT).show();
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
                PlaybackService.append(MainActivity.this, list.get(pos).id);
                Toast.makeText(MainActivity.this, "已加入队列", Toast.LENGTH_SHORT).show();
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
                showAddSourceDialog();
            }
        });
        // 曲库：多选 / 批量删除 / 清空
        if (btnLibSelect != null) {
            btnLibSelect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleLibSelectMode();
                }
            });
        }
        if (btnLibCancel != null) {
            btnLibCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    exitLibSelectMode();
                }
            });
        }
        if (btnLibSelAll != null) {
            btnLibSelAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectAllTracks();
                }
            });
        }
        if (btnLibSelInv != null) {
            btnLibSelInv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    invertSelectedTracks();
                }
            });
        }
        if (btnLibQueueSel != null) {
            btnLibQueueSel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    queueSelectedTracks();
                }
            });
        }
        if (btnLibPlSel != null) {
            btnLibPlSel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    addSelectedToPlaylist();
                }
            });
        }
        if (btnLibClear != null) {
            btnLibClear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    deleteSelectedTracks();
                }
            });
            // 初始无勾选 → 禁用并置灰（进入勾选模式且勾选后才会亮起）
            btnLibClear.setEnabled(false);
            btnLibClear.setAlpha(0.35f);
        }
        if (btnPlayFilter != null) {
            btnPlayFilter.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playFiltered();
                }
            });
        }
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
        View btnUpdate = findViewById(R.id.btn_update);
        if (btnUpdate != null) {
            btnUpdate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    checkUpdate();
                }
            });
        }
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
        // 数据备份 / 恢复 / 打开缓存文件夹
        View btnBackup = findViewById(R.id.btn_backup);
        if (btnBackup != null) {
            btnBackup.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    doBackup();
                }
            });
        }
        View btnRestore = findViewById(R.id.btn_restore);
        if (btnRestore != null) {
            btnRestore.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("*/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    try {
                        startActivityForResult(
                                Intent.createChooser(i, "选择备份 JSON 文件"), REQ_RESTORE);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(MainActivity.this,
                                "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
        View btnOpenCache = findViewById(R.id.btn_open_cache);
        if (btnOpenCache != null) {
            btnOpenCache.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    com.volcano.bmusic.util.Backup.openFolder(MainActivity.this);
                }
            });
            TextView tvPath = (TextView) findViewById(R.id.tv_cache_path);
            if (tvPath != null) {
                tvPath.setText(com.volcano.bmusic.util.Cache.audioDir(this).getAbsolutePath());
            }
        }
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

    // ------------------------------------------------------------- backup

    private void doBackup() {
        if (android.os.Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_WRITE_PERM);
            return;
        }
        Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show();
        App.postBg(new Runnable() {
            @Override
            public void run() {
                String json = com.volcano.bmusic.util.Backup.export(MainActivity.this);
                String path = null, err = null;
                if (json != null) {
                    try {
                        path = com.volcano.bmusic.util.Backup.write(MainActivity.this, json);
                    } catch (Exception e) {
                        err = e.getMessage();
                    }
                }
                final String fPath = path, fErr = err;
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        if (fPath != null) {
                            Toast.makeText(MainActivity.this,
                                    "已备份到 " + fPath, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "备份失败：" + fErr, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_WRITE_PERM) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                doBackup();
            } else {
                Toast.makeText(this, "没有存储权限，无法备份", Toast.LENGTH_LONG).show();
            }
        }
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
                                + " " + (t.song == null ? "" : t.song.toLowerCase())
                                + " " + (t.artist == null ? "" : t.artist.toLowerCase())
                                + " " + (t.album == null ? "" : t.album.toLowerCase())
                                + " " + (t.author == null ? "" : t.author.toLowerCase())
                                + " " + t.bvid;
                        if (s.contains(f)) keep.add(t);
                    }
                    all.clear();
                    all.addAll(keep);
                }
                // 歌手 / 专辑筛选（与搜索叠加）
                if (filterArtist != null || filterAlbum != null) {
                    ArrayList<Track> keep = new ArrayList<Track>();
                    for (Track t : all) {
                        if (filterArtist != null
                                && (t.artist == null || !filterArtist.equalsIgnoreCase(t.artist))) {
                            continue;
                        }
                        if (filterAlbum != null
                                && (t.album == null || !filterAlbum.equalsIgnoreCase(t.album))) {
                            continue;
                        }
                        keep.add(t);
                    }
                    all.clear();
                    all.addAll(keep);
                }
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        trackAdapter.setData(all);
                        emptyTracks.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
                        if (btnPlayFilter != null) {
                            btnPlayFilter.setText(all.isEmpty()
                                    ? "播放全部" : "播放 " + all.size() + " 首");
                        }
                        Track cur = currentTrack();
                        if (cur != null) trackAdapter.setPlaying(cur.id, isPlaying());
                    }
                });
            }
        });
    }

    // ------------------------------------------------------- 曲库筛选

    /** 第一步：选按歌手还是按专辑；第二步列出可选值。 */
    private void showFilterDialog() {
        final boolean active = filterArtist != null || filterAlbum != null;
        final String[] items = active
                ? new String[]{"按歌手筛选", "按专辑筛选", "清除筛选"}
                : new String[]{"按歌手筛选", "按专辑筛选"};
        new AlertDialog.Builder(this)
                .setTitle("筛选曲库")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            pickValue(true);
                        } else if (which == 1) {
                            pickValue(false);
                        } else {
                            filterArtist = null;
                            filterAlbum = null;
                            updateFilterChip();
                            reloadTracks();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickValue(final boolean byArtist) {
        App.postBg(new Runnable() {
            @Override
            public void run() {
                ArrayList<Track> all = Db.get(MainActivity.this).allTracks();
                final ArrayList<String> vals = new ArrayList<String>();
                final ArrayList<Integer> cnt = new ArrayList<Integer>();
                for (Track t : all) {
                    String v = byArtist ? t.artist : t.album;
                    if (v == null || v.trim().isEmpty()) continue;
                    int i = vals.indexOf(v);
                    if (i < 0) {
                        vals.add(v);
                        cnt.add(1);
                    } else {
                        cnt.set(i, cnt.get(i) + 1);
                    }
                }
                if (vals.isEmpty()) {
                    App.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "还没有识别到" + (byArtist ? "歌手" : "专辑") + "信息",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                // 曲目多的排前面
                for (int i = 0; i < vals.size() - 1; i++) {
                    for (int j = i + 1; j < vals.size(); j++) {
                        if (cnt.get(j) > cnt.get(i)) {
                            String tv = vals.get(i);
                            vals.set(i, vals.get(j));
                            vals.set(j, tv);
                            Integer tc = cnt.get(i);
                            cnt.set(i, cnt.get(j));
                            cnt.set(j, tc);
                        }
                    }
                }
                final String[] names = new String[vals.size()];
                for (int i = 0; i < vals.size(); i++) {
                    names[i] = vals.get(i) + "（" + cnt.get(i) + " 首）";
                }
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(byArtist ? "选择歌手" : "选择专辑")
                                .setItems(names, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        if (byArtist) {
                                            filterArtist = vals.get(which);
                                            filterAlbum = null;
                                        } else {
                                            filterAlbum = vals.get(which);
                                            filterArtist = null;
                                        }
                                        updateFilterChip();
                                        reloadTracks();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        });
    }

    private void updateFilterChip() {
        if (tvFilterChip == null) return;
        String s = null;
        if (filterArtist != null) s = "歌手：" + filterArtist;
        else if (filterAlbum != null) s = "专辑：" + filterAlbum;
        if (s == null) {
            tvFilterChip.setVisibility(View.GONE);
        } else {
            tvFilterChip.setText(s + "  ✕");
            tvFilterChip.setVisibility(View.VISIBLE);
        }
    }

    private void reloadRecent() {        App.postBg(new Runnable() {
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
                final PlaybackService s = PlaybackService.get();
                final long curPid = s == null ? -1 : s.queueId();
                final boolean pl = s != null && s.isPlaying();
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        playlistAdapter.setData(list);
                        playlistAdapter.setPlaying(curPid, pl);
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

    /**
     * Repaint the mini bar. Every event the user should notice — a new track,
     * play/pause, buffering, an error — gets an animation instead of an
     * instant silent swap.
     */
    private void refreshMini() {
        Track t = currentTrack();
        if (t == null) {
            if (miniWrap != null) miniWrap.setVisibility(View.GONE);
            miniId = null;
            miniSubText = null;
            miniPlaying = null;
            return;
        }
        PlaybackService s = svc();
        boolean playing = isPlaying();
        boolean loading = s != null && s.isLoading();

        String state;
        int stateColor;
        if (miniError != null) {
            state = "播放失败";
            stateColor = R.color.pink;
        } else if (loading) {
            state = "缓冲中…";
            stateColor = R.color.orange;
        } else if (playing) {
            state = "播放中";
            stateColor = R.color.pink;
        } else {
            state = "已暂停";
            stateColor = R.color.text_dim;
        }

        // first appearance: slide + fade the whole bar in
        if (miniWrap.getVisibility() != View.VISIBLE) {
            miniWrap.setVisibility(View.VISIBLE);
            float d = getResources().getDisplayMetrics().density;
            if (d <= 0f) d = 1f;
            miniWrap.setAlpha(0f);
            miniWrap.setTranslationY(26f * d);
            miniWrap.animate().alpha(1f).translationY(0f).setDuration(280).start();
            miniId = null;
            miniSubText = null;
            miniPlaying = null;
        }

        // new track: swap the title with a cross-fade and pulse the cover
        if (!t.id.equals(miniId)) {
            miniId = t.id;
            swapText(miniTitle, t.title == null ? "" : t.title);
            pulse(miniArt);
            flashMini();
            miniArt.setImageResource(R.drawable.ic_music_note);
            CoverLoader.get(this).load(miniArt, t.cover);
        }

        // status line: "UP主 · 播放中" with the status part highlighted
        String sub = t.author == null ? "" : t.author;
        if (sub.length() > 0) sub += " · ";
        sub += state;
        if (!sub.equals(miniSubText)) {
            miniSubText = sub;
            swapText(miniSub, colored(sub, state, stateColor));
        }

        // play / pause: change the icon and pulse it
        if (miniPlaying == null || miniPlaying.booleanValue() != playing) {
            miniPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            if (miniPlaying != null) pulse(miniPlay);
            miniPlaying = Boolean.valueOf(playing);
        }
        if (miniLoad != null) {
            miniLoad.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (s != null) updateMiniProgress(s.position(), s.duration());
        trackAdapter.setPlaying(t.id, playing);
        recentAdapter.setPlaying(t.id, playing);
    }

    /** Cross-fade + slide a text change, so the swap is easy to notice. */
    private void swapText(final TextView tv, final CharSequence next) {
        if (tv.getText() != null && tv.getText().toString().equals(next.toString())) {
            tv.setText(next);
            return;
        }
        tv.animate().cancel();
        tv.animate().alpha(0.1f).translationY(-6f).setDuration(110)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        tv.setText(next);
                        tv.setTranslationY(7f);
                        tv.animate().alpha(1f).translationY(0f).setDuration(200).start();
                    }
                }).start();
    }

    /** Colour just the trailing status word of the mini subtitle. */
    private CharSequence colored(String whole, String tail, int colorRes) {
        SpannableString ss = new SpannableString(whole);
        int at = whole.lastIndexOf(tail);
        if (at >= 0) {
            ss.setSpan(new ForegroundColorSpan(getResources().getColor(colorRes)),
                    at, whole.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    /** Short scale bounce, used for the play button and the cover art. */
    private void pulse(final View v) {
        if (v == null) return;
        v.animate().cancel();
        v.animate().scaleX(1.22f).scaleY(1.22f).setDuration(110)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                    }
                }).start();
    }

    /** Brief dim-then-bright flash of the whole bar when the track changes. */
    private void flashMini() {
        if (mini == null) return;
        mini.setAlpha(0.45f);
        mini.animate().alpha(1f).setDuration(340).start();
    }

    /** Thin progress line along the bottom edge of the mini bar. */
    private void updateMiniProgress(int pos, int dur) {
        if (miniProg == null || miniWrap == null) return;
        int w = miniWrap.getWidth();
        if (w <= 0 || dur <= 0) {
            setWidth(miniProg, 0);
            return;
        }
        int pw = (int) ((long) w * pos / dur);
        if (pw < 0) pw = 0;
        if (pw > w) pw = w;
        setWidth(miniProg, pw);
    }

    private static void setWidth(View v, int px) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return;
        lp.width = px;
        v.setLayoutParams(lp);
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
        TrackActions.showMenu(this, t, TrackActions.LIBRARY_ITEMS, new TrackActions.ItemHandler() {
            @Override
            public void onItem(Track track, int which) {
                switch (which) {
                    case 0:
                        TrackActions.playNow(MainActivity.this, track);
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                refreshMini();
                            }
                        }, 400);
                        break;
                    case 1:
                        TrackActions.enqueue(MainActivity.this, track);
                        break;
                    case 2:
                        TrackActions.cache(MainActivity.this, track);
                        break;
                    case 3:
                        TrackActions.addToPlaylist(MainActivity.this, track);
                        break;
                    case 4:
                        TrackActions.openBili(MainActivity.this, track);
                        break;
                    case 5:
                        TrackActions.confirmDelete(MainActivity.this, track, new Runnable() {
                            @Override
                            public void run() {
                                reloadAll();
                            }
                        });
                        break;
                }
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

    /**
     * 曲库播放选项：把当前筛选范围里的全部曲目导入播放队列并播放。
     * 若正在播放的曲目就在筛选结果里，就接着播这首，不打断；否则从第一首开始。
     */
    private void playFiltered() {
        ArrayList<Track> list = new ArrayList<Track>(trackAdapter.data());
        if (list.isEmpty()) {
            Toast.makeText(this, "没有可播放的曲目", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<String> ids = new ArrayList<String>();
        for (Track t : list) ids.add(t.id);
        Track cur = currentTrack();
        String startId = null;
        if (cur != null) {
            for (Track t : list) {
                if (t.id.equals(cur.id)) {
                    startId = cur.id;
                    break;
                }
            }
        }
        if (startId == null) startId = list.get(0).id;
        boolean keep = isPlaying() && cur != null && startId.equals(cur.id);
        if (keep) {
            PlaybackService.replaceQueue(this, -1, ids);
            Toast.makeText(this, "已导入 " + ids.size() + " 首，继续播放当前曲目",
                    Toast.LENGTH_SHORT).show();
        } else {
            PlaybackService.setQueue(this, -1, ids, startId);
            Toast.makeText(this, "已导入 " + ids.size() + " 首并开始播放",
                    Toast.LENGTH_SHORT).show();
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshMini();
            }
        }, 400);
    }

    // ---------------------------------------------------------- app update

    private static final String UPDATE_INFO_URL =
            "https://raw.githubusercontent.com/volcanochen/bmusic/main/release/version.json";
    private static final String UPDATE_APK_URL =
            "https://raw.githubusercontent.com/volcanochen/bmusic/main/release/bmusic.apk";

    private String currentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private int currentVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 检查更新：拉 release/version.json 比对版本号，有新版打开介绍页。 */
    private void checkUpdate() {
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
        final int curCode = currentVersionCode();
        App.postNet(new Runnable() {
            @Override
            public void run() {
                String body = null, err = null;
                try {
                    Http.Resp r = Http.get(UPDATE_INFO_URL, null);
                    if (r.ok()) body = r.body;
                    else err = "HTTP " + r.code;
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String fBody = body, fErr = err;
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        if (fBody == null) {
                            Toast.makeText(MainActivity.this,
                                    "检查更新失败：" + fErr, Toast.LENGTH_LONG).show();
                            return;
                        }
                        try {
                            JSONObject o = new JSONObject(fBody);
                            int newCode = o.optInt("versionCode", 0);
                            String newName = o.optString("versionName", "");
                            String notes = o.optString("notes", "");
                            String url = o.optString("url", UPDATE_APK_URL);
                            if (newCode > curCode) {
                                Intent i = new Intent(MainActivity.this, UpdateActivity.class);
                                i.putExtra(UpdateActivity.EXTRA_CUR, currentVersionName());
                                i.putExtra(UpdateActivity.EXTRA_NEW, newName);
                                i.putExtra(UpdateActivity.EXTRA_NOTES, notes);
                                i.putExtra(UpdateActivity.EXTRA_URL, url);
                                startActivity(i);
                            } else {
                                Toast.makeText(MainActivity.this,
                                        "已是最新版本 v" + currentVersionName(),
                                        Toast.LENGTH_LONG).show();
                                if (tvUpdate != null) {
                                    tvUpdate.setText("已是最新 v" + currentVersionName());
                                }
                            }
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this,
                                    "解析更新信息失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    // -------------------------------------------------------- scan / import

    private static final int REQ_SCAN = 9001;
    private static final int REQ_IMG = 9002;
    private static final int REQ_RESTORE = 9003;
    private static final int REQ_WRITE_PERM = 78;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_RESTORE) {
            Uri ruri = data.getData();
            if (ruri == null) return;
            String json = null;
            try {
                java.io.InputStream is = getContentResolver().openInputStream(ruri);
                json = Http.readAll(is);
                is.close();
            } catch (Exception e) {
                json = null;
            }
            final String fjson = json;
            if (TextUtils.isEmpty(fjson)) {
                Toast.makeText(this, "读取备份文件失败", Toast.LENGTH_LONG).show();
                return;
            }
            App.postBg(new Runnable() {
                @Override
                public void run() {
                    final int n = com.volcano.bmusic.util.Backup.restore(MainActivity.this, fjson);
                    App.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            if (n < 0) {
                                Toast.makeText(MainActivity.this,
                                        "不是有效的 BMusic 备份文件", Toast.LENGTH_LONG).show();
                            } else {
                                reloadAll();
                                Toast.makeText(MainActivity.this,
                                        n > 0 ? ("已导入 " + n + " 首曲目")
                                              : "备份内容已存在，未新增曲目",
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            });
            return;
        }
        String text = data.getStringExtra(ScanActivity.EXTRA_TEXT);
        if (TextUtils.isEmpty(text) && requestCode == REQ_IMG) {
            Uri uri = data.getData();
            if (uri != null) {
                Toast.makeText(this, "识别中…", Toast.LENGTH_SHORT).show();
                text = ScanActivity.decodeImageUri(this, uri);
            }
        }
        if (TextUtils.isEmpty(text)) {
            if (requestCode == REQ_SCAN || requestCode == REQ_IMG) {
                Toast.makeText(this, R.string.scan_no_qr, Toast.LENGTH_LONG).show();
            }
            return;
        }
        doImport(text);
    }

    /** 「+」来源菜单：粘贴链接 / 扫码 / 相册识别 / 手动输入。 */
    private void showAddSourceDialog() {
        final String[] items = {
                getString(R.string.menu_paste),
                getString(R.string.menu_scan),
                getString(R.string.menu_pick_image),
                getString(R.string.menu_manual)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            String clip = clipboardText();
                            if (TextUtils.isEmpty(clip)) {
                                Toast.makeText(MainActivity.this,
                                        "剪贴板是空的，请先在B站分享时「复制链接」",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            doImport(clip);
                        } else if (which == 1) {
                            startActivityForResult(new Intent(MainActivity.this,
                                    ScanActivity.class), REQ_SCAN);
                        } else if (which == 2) {
                            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                            i.setType("image/*");
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            try {
                                startActivityForResult(
                                        Intent.createChooser(i, "选择二维码图片"), REQ_IMG);
                            } catch (ActivityNotFoundException e) {
                                Toast.makeText(MainActivity.this,
                                        "没有可用的图片选择器", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            showAddDialog();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ----------------------------------------------------- library multi-op

    private void updateLibSelCount() {
        if (tvLibSel == null || trackAdapter == null) return;
        tvLibSel.setText(getString(R.string.lib_selected,
                libSelected.size(), trackAdapter.getCount()));
        boolean has = !libSelected.isEmpty();
        if (btnLibQueueSel != null) {
            btnLibQueueSel.setVisibility(has ? View.VISIBLE : View.GONE);
        }
        if (btnLibPlSel != null) {
            btnLibPlSel.setVisibility(has ? View.VISIBLE : View.GONE);
        }
        if (btnLibCancel != null) {
            btnLibCancel.setVisibility(has ? View.VISIBLE : View.GONE);
        }
        // 删除按钮（头部垃圾桶）：未勾选时禁用并置灰，有勾选时正常亮起
        if (btnLibClear != null) {
            btnLibClear.setEnabled(has);
            btnLibClear.setAlpha(has ? 1f : 0.35f);
        }
    }

    private void toggleLibSelectMode() {
        libSelectMode = !libSelectMode;
        if (libSelectMode) libSelected.clear();
        trackAdapter.setSelectable(libSelectMode ? libSelected : null);
        libSelBar.setVisibility(libSelectMode ? View.VISIBLE : View.GONE);
        updateLibSelCount();
    }

    private void exitLibSelectMode() {
        if (!libSelectMode) return;
        libSelectMode = false;
        libSelected.clear();
        trackAdapter.setSelectable(null);
        libSelBar.setVisibility(View.GONE);
    }

    private void deleteSelectedTracks() {
        if (libSelected.isEmpty()) {
            Toast.makeText(this, "请先勾选要删除的曲目", Toast.LENGTH_SHORT).show();
            return;
        }
        final ArrayList<String> ids = new ArrayList<String>(libSelected);
        new AlertDialog.Builder(this)
                .setTitle("删除所选")
                .setMessage("删除勾选的 " + ids.size() + " 首曲目？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        App.postBg(new Runnable() {
                            @Override
                            public void run() {
                                for (String id : ids) Db.get(MainActivity.this).deleteTrack(id);
                                libSelected.clear();
                                PlaybackService.removeIds(MainActivity.this, ids);
                                App.runOnUi(new Runnable() {
                                    @Override
                                    public void run() {
                                        updateLibSelCount();
                                        reloadAll();
                                        Toast.makeText(MainActivity.this,
                                                "已删除", Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 勾选曲目按顺序批量加入播放队列（不打断当前播放）。 */
    private void queueSelectedTracks() {
        if (libSelected.isEmpty()) {
            Toast.makeText(this, "请先勾选曲目", Toast.LENGTH_SHORT).show();
            return;
        }
        final ArrayList<String> ids = new ArrayList<String>(libSelected);
        PlaybackService.appendAll(this, ids);
        exitLibSelectMode();
        Toast.makeText(this, "已加入队列 " + ids.size() + " 首", Toast.LENGTH_SHORT).show();
    }

    /** 勾选曲目批量加入歌单：与单曲共用同一套选择器（含「新建歌单」）。 */
    private void addSelectedToPlaylist() {
        if (libSelected.isEmpty()) {
            Toast.makeText(this, "请先勾选曲目", Toast.LENGTH_SHORT).show();
            return;
        }
        final int n = libSelected.size();
        TrackActions.pickPlaylist(this, "加入歌单（" + n + " 首）",
                new TrackActions.PickListener() {
                    @Override
                    public void onPick(final long pid, final String pname) {
                        final ArrayList<String> ids = new ArrayList<String>(libSelected);
                        App.postBg(new Runnable() {
                            @Override
                            public void run() {
                                for (String tid : ids) {
                                    Db.get(MainActivity.this).addTo(pid, tid);
                                }
                                libSelected.clear();
                                App.runOnUi(new Runnable() {
                                    @Override
                                    public void run() {
                                        exitLibSelectMode();
                                        reloadPlaylists();
                                        Toast.makeText(MainActivity.this,
                                                "已加入歌单「" + pname + "」" + ids.size() + " 首",
                                                Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        });
                    }
                });
    }

    /** 勾选模式：全选当前列表所有曲目。 */
    private void selectAllTracks() {
        if (!libSelectMode || trackAdapter == null) return;
        for (Track t : trackAdapter.data()) {
            if (t.id != null) libSelected.add(t.id);
        }
        trackAdapter.setSelectable(libSelected);
        updateLibSelCount();
    }

    /** 勾选模式：反选（已勾的取消、未勾的选中）。 */
    private void invertSelectedTracks() {
        if (!libSelectMode || trackAdapter == null) return;
        for (Track t : trackAdapter.data()) {
            if (t.id == null) continue;
            if (libSelected.contains(t.id)) libSelected.remove(t.id);
            else libSelected.add(t.id);
        }
        trackAdapter.setSelectable(libSelected);
        updateLibSelCount();
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
                miniError = i.getStringExtra("error");
                refreshMini();
                reloadPlaylists();
                queuePage.onStateChanged();
                if (miniError != null) {                    Toast.makeText(MainActivity.this, miniError, Toast.LENGTH_LONG).show();
                }
            } else if (PlaybackService.ACT_PROGRESS.equals(a)) {
                updateMiniProgress(i.getIntExtra("pos", 0), i.getIntExtra("dur", 0));
            } else if (MetaResolver.ACT_META.equals(a)) {
                // 歌手/专辑识别完成：刷新曲库与歌单（列表可能正在显示旧标题）
                reloadTracks();
                reloadRecent();
            } else if (Downloader.ACT_DL.equals(a)) {
                String id = i.getStringExtra(Downloader.EXTRA_ID);
                int st = i.getIntExtra(Downloader.EXTRA_STATE, 0);
                queuePage.onDownload(id, st, i.getIntExtra(Downloader.EXTRA_PROGRESS, 0));
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
        f.addAction(MetaResolver.ACT_META);
        f.addAction(CoverLoader.ACT_COVER);
        Receivers.registerLocal(this, receiver, f);
        // 进程冷启动后服务可能不在（迷你条展示的是持久化状态）：唤醒它，
        // 让 restore() 恢复队列，播放页/队列页/通知栏状态才能对上。
        if (PlaybackService.get() == null
                && App.prefs().getString("last_id", null) != null) {
            PlaybackService.wake(this);
        }
        // 一次性补齐老曲目的歌手/专辑信息（每个安装只跑一次）
        MetaResolver.backfill(this);
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
