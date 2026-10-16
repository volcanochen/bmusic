package com.volcano.bmusic.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
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
import com.volcano.bmusic.util.Win;

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
        miniWrap = findViewById(R.id.mini_wrap);
        miniProg = findViewById(R.id.mini_prog);
        miniLoad = (ProgressBar) findViewById(R.id.mini_load);
        btnPlayFilter = (Button) findViewById(R.id.btn_play_filter);
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
                showAddDialog();
            }
        });
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
                if (miniError != null) {
                    Toast.makeText(MainActivity.this, miniError, Toast.LENGTH_LONG).show();
                }
            } else if (PlaybackService.ACT_PROGRESS.equals(a)) {
                updateMiniProgress(i.getIntExtra("pos", 0), i.getIntExtra("dur", 0));
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
        // 进程冷启动后服务可能不在（迷你条展示的是持久化状态）：唤醒它，
        // 让 restore() 恢复队列，播放页/队列页/通知栏状态才能对上。
        if (PlaybackService.get() == null
                && App.prefs().getString("last_id", null) != null) {
            PlaybackService.wake(this);
        }
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
