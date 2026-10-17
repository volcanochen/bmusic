package com.volcano.bmusic.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.Layout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.ViewGroup;
import android.widget.Toast;
import android.widget.Button;
import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;

import com.volcano.bmusic.App;
import com.volcano.bmusic.R;
import com.volcano.bmusic.db.Db;
import com.volcano.bmusic.lyric.Lyric;
import com.volcano.bmusic.lyric.NeteaseLyric;
import com.volcano.bmusic.model.Playlist;
import com.volcano.bmusic.model.Track;
import com.volcano.bmusic.player.PlaybackService;
import com.volcano.bmusic.util.CoverLoader;
import com.volcano.bmusic.util.Downloader;
import com.volcano.bmusic.util.Receivers;
import com.volcano.bmusic.util.Win;

import java.util.ArrayList;
import java.util.List;

public class PlayerActivity extends Activity {

    private ImageView art;
    private ImageView bg;
    private TextView title;
    private TextView author;
    private TextView tCur;
    private TextView tDur;
    private SeekBar seek;
    private ImageButton btnPlay;
    private ImageButton btnPrev;
    private ImageButton btnNext;
    private ImageButton btnMode;
    private ImageButton btnFav;
    private TextView hint;
    private TextView pageBadge;
    private Button btnCache;
    private Button btnQueue;
    private Button btnBili;
    // Original leading icons of the action buttons, so they can be restored
    // when the window grows again.
    private Drawable cacheIcon, plIcon, lyricIcon, queueIcon, biliIcon;

    // vertical volume control
    private VolumeBar volBar;
    private TextView volPct;
    private AudioManager am;
    private int volMax = 1;

    // lyrics
    private Button btnLyric;
    private FrameLayout lyricPanel;
    private EditText lyricQuery;
    private Button lyricSearchBtn;
    private ImageButton lyricClose;
    private LinearLayout lyricLines;
    private ScrollView lyricScroll;
    private TextView lyricStatus;
    private Lyric lyric;
    private int lastLyricIdx = -1;
    private String lastLyricKey = null;

    private boolean dragging = false;
    private final Handler handler = new Handler();
    private Track cur;

    // Window-shape state: which layout is currently inflated and whether the
    // lyrics sheet is open, so both can be restored after a re-inflate.
    private boolean landLayout = false;
    private boolean lyricOpen = false;

    // Guaranteed UI refresh: poll the service directly every 500ms so the
    // play/pause icon and progress bar always track playback, even if a
    // broadcast is missed.
    private final Runnable progressPoller = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (PlaybackService.get() == null) PlaybackService.wake(this);
        landLayout = Win.wide(this);
        setContentView(landLayout ? R.layout.activity_player_land : R.layout.activity_player);
        bind();
        actions();
        applyWindowSize();
        refresh();
    }

    /* ---------------- window shape: split-screen / freeform / rotation ---------------- */

    /**
     * Called whenever the size (or aspect ratio) of our window changed:
     * rotation, entering / leaving split-screen or freeform, and dragging the
     * divider or a floating window handle. We re-pick the layout and re-scale
     * the content so everything stays inside the new bounds.
     */
    private void relayoutForWindow() {
        boolean wide = Win.wide(this);
        if (wide != landLayout) {
            landLayout = wide;
            if (lyricPanel != null) {
                lyricOpen = lyricPanel.getVisibility() == View.VISIBLE;
            }
            setContentView(landLayout ? R.layout.activity_player_land : R.layout.activity_player);
            bind();
            actions();
            if (lyricOpen && lyricPanel != null) {
                lyricPanel.setVisibility(View.VISIBLE);
                if (lyric != null) renderLyric(lyric);
            }
        }
        applyWindowSize();
        refresh();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        relayoutForWindow();
    }

    @Override
    public void onMultiWindowModeChanged(boolean inMultiWindowMode) {
        super.onMultiWindowModeChanged(inMultiWindowMode);
        relayoutForWindow();
    }

    /** Scale the content to the current window size (dp based, ratio aware). */
    private void applyWindowSize() {
        int[] s = Win.sizeDp(this);
        int wDp = s[0];
        int hDp = s[1];
        float d = getResources().getDisplayMetrics().density;
        if (d <= 0f) d = 1f;
        boolean compact = hDp < 660;
        boolean tiny = hDp < 470;

        // Cover art: never let it eat more than a third of a short window.
        View artBox = findViewById(R.id.art_box);
        if (artBox != null) {
            int size = (int) Math.min(wDp * 0.62f, hDp * 0.30f);
            size = Math.max(96, Math.min(260, size));
            setSize(artBox, size, size);
        }

        // Vertical volume bar must fit the window height too.
        if (volBar != null) {
            int vh = (int) Math.min(190f, hDp * 0.30f);
            vh = Math.max(84, vh);
            setSize(volBar, 44, vh);
        }

        // Compact windows: shrink the transport controls so they still fit.
        if (btnPlay != null) {
            int big = compact ? 56 : 72;
            setSize(btnPlay, big, big);
            btnPlay.setPadding((int) (big * 0.28f * d), (int) (big * 0.28f * d),
                    (int) (big * 0.28f * d), (int) (big * 0.28f * d));
        }
        if (btnPrev != null) setSize(btnPrev, compact ? 44 : 52, compact ? 44 : 52);
        if (btnNext != null) setSize(btnNext, compact ? 44 : 52, compact ? 44 : 52);
        if (btnMode != null) setSize(btnMode, compact ? 40 : 48, compact ? 40 : 48);
        if (btnFav != null) setSize(btnFav, compact ? 40 : 48, compact ? 40 : 48);
        View backBtn = findViewById(R.id.back);
        if (backBtn != null) setHeight(backBtn, compact ? 34 : 38);
        if (btnCache != null) setHeight(btnCache, compact ? 34 : 38);
        View addPl = findViewById(R.id.btn_add_pl);
        if (addPl != null) setHeight(addPl, compact ? 34 : 38);
        if (btnLyric != null) setHeight(btnLyric, compact ? 34 : 38);
        if (btnQueue != null) setHeight(btnQueue, compact ? 34 : 38);
        if (btnBili != null) setHeight(btnBili, compact ? 34 : 38);

        // Narrow windows: forbid wrapping and drop the leading icons when the
        // three action labels would otherwise be clipped.
        tuneActionButtons(compact);

        // Tighten the vertical rhythm so the whole column still fits a short window.
        setTopMargin(findViewById(R.id.row_seek), tiny ? 8 : (compact ? 14 : -1));
        setTopMargin(findViewById(R.id.row_ctrl), tiny ? 6 : (compact ? 10 : -1));
        setTopMargin(findViewById(R.id.row_bottom), tiny ? 8 : (compact ? 12 : -1));
        View root = findViewById(R.id.player_root);   // landscape only
        if (root != null) setPadding(root, tiny ? 6 : (compact ? 10 : 16));
        if (title != null) title.setTextSize(tiny ? 16 : (landLayout ? 19 : 18));
        if (author != null) author.setTextSize(tiny ? 11 : 13);

        // 底部状态行已按需求移除：不再显示「已缓存 / 在线播放」等文字
        if (hint != null) hint.setVisibility(View.GONE);
    }

    /**
     * Keep the action labels readable in a cramped window. Icons are never
     * dropped; when a label does not fit we shrink its font (down to 9sp)
     * based on real measured text width, so system font scale is respected.
     */
    private void tuneActionButtons(boolean compact) {
        final View addPl = findViewById(R.id.btn_add_pl);
        tuneButton(btnCache, cacheIcon, false, compact);
        tuneButton((Button) addPl, plIcon, false, compact);
        tuneButton(btnLyric, lyricIcon, false, compact);
        tuneButton(btnQueue, queueIcon, false, compact);
        tuneButton(btnBili, biliIcon, false, compact);
        final View row = findViewById(R.id.row_bottom);
        if (row == null) return;
        // 双层 post：第一层可能在首次遍历前出队，第二层保证落在布局完成之后
        row.post(new Runnable() {
            @Override
            public void run() {
                row.post(new Runnable() {
                    @Override
                    public void run() {
                        fitButton((Button) findViewById(R.id.back));
                        fitButton(btnCache);
                        fitButton((Button) findViewById(R.id.btn_bili));
                        fitButton((Button) addPl);
                        fitButton(btnLyric);
                        fitButton(btnQueue);
                    }
                });
            }
        });
    }

    /**
     * Shrink a button's font (12sp -> 9sp, 0.5sp steps) until the text plus
     * its leading icon fits the measured button width. Never touches icons.
     */
    private void fitButton(Button b) {
        if (b == null || b.getWidth() <= 0) return;
        Drawable[] ds = b.getCompoundDrawables();
        Drawable d = (ds == null || ds.length == 0) ? null : ds[0];
        int fixed = (d != null ? d.getIntrinsicWidth() + b.getCompoundDrawablePadding() : 0)
                + b.getPaddingLeft() + b.getPaddingRight();
        int avail = b.getWidth() - fixed;
        if (avail <= 0) return;
        String t = b.getText().toString();
        TextPaint tmp = new TextPaint(b.getPaint());
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        float chosen = 9f;
        for (float s = 12f; s >= 9f; s -= 0.5f) {
            tmp.setTextSize(s * scaled);
            chosen = s;
            if (tmp.measureText(t) <= avail) break;
        }
        b.setTextSize(chosen); // SP 单位，走 TextView 正规路径触发重新排版
    }

    private void tuneButton(Button b, Drawable icon, boolean iconless, boolean smallText) {
        if (b == null) return;
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setTextSize(smallText ? 11 : 12);
        b.setCompoundDrawables(iconless ? null : icon, null, null, null);
    }

    /** Set a child's top margin in dp (-1 = keep the value from the layout). */
    private void setTopMargin(View v, int dp) {
        if (v == null || dp < 0) return;
        float d = getResources().getDisplayMetrics().density;
        if (d <= 0f) d = 1f;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof LinearLayout.LayoutParams)) return;
        ((LinearLayout.LayoutParams) lp).topMargin = Math.round(dp * d);
        v.setLayoutParams(lp);
    }

    /** Set a view's uniform padding in dp. */
    private void setPadding(View v, int dp) {
        float d = getResources().getDisplayMetrics().density;
        if (d <= 0f) d = 1f;
        int px = Math.round(dp * d);
        v.setPadding(px, px, px, px);
    }

    /** Set a view's height in dp, leaving its width untouched. */
    private void setHeight(View v, int hDp) {
        float d = getResources().getDisplayMetrics().density;
        if (d <= 0f) d = 1f;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return;
        lp.height = Math.round(hDp * d);
        v.setLayoutParams(lp);
    }

    /** Set a view's size in dp, keeping whatever layout params type it has. */
    private void setSize(View v, int wDp, int hDp) {
        float d = getResources().getDisplayMetrics().density;
        if (d <= 0f) d = 1f;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return;
        lp.width = wDp < 0 ? wDp : Math.round(wDp * d);
        lp.height = hDp < 0 ? hDp : Math.round(hDp * d);
        v.setLayoutParams(lp);
    }

    private void bind() {
        art = (ImageView) findViewById(R.id.art);
        bg = (ImageView) findViewById(R.id.bg);
        title = (TextView) findViewById(R.id.title);
        author = (TextView) findViewById(R.id.author);
        tCur = (TextView) findViewById(R.id.t_cur);
        tDur = (TextView) findViewById(R.id.t_dur);
        seek = (SeekBar) findViewById(R.id.seek);
        btnPlay = (ImageButton) findViewById(R.id.btn_play);
        btnPrev = (ImageButton) findViewById(R.id.btn_prev);
        btnNext = (ImageButton) findViewById(R.id.btn_next);
        btnMode = (ImageButton) findViewById(R.id.btn_mode);
        btnFav = (ImageButton) findViewById(R.id.btn_fav);
        hint = (TextView) findViewById(R.id.hint);
        pageBadge = (TextView) findViewById(R.id.n_badge);
        btnCache = (Button) findViewById(R.id.btn_cache);
        btnQueue = (Button) findViewById(R.id.btn_queue);
        btnBili = (Button) findViewById(R.id.btn_bili);
        volBar = (VolumeBar) findViewById(R.id.vol_bar);
        volPct = (TextView) findViewById(R.id.vol_pct);
        btnLyric = (Button) findViewById(R.id.btn_lyric);
        lyricPanel = (FrameLayout) findViewById(R.id.lyric_panel);
        lyricQuery = (EditText) findViewById(R.id.lyric_query);
        lyricSearchBtn = (Button) findViewById(R.id.lyric_search);
        lyricClose = (ImageButton) findViewById(R.id.lyric_close);
        lyricLines = (LinearLayout) findViewById(R.id.lyric_lines);
        lyricScroll = (ScrollView) findViewById(R.id.lyric_scroll);
        lyricStatus = (TextView) findViewById(R.id.lyric_status);
        View addPl = findViewById(R.id.btn_add_pl);
        cacheIcon = firstDrawable(btnCache);
        plIcon = firstDrawable(addPl);
        lyricIcon = firstDrawable(btnLyric);
        queueIcon = firstDrawable(btnQueue);
        biliIcon = firstDrawable(btnBili);
        initVolume();
    }

    /** Leading compound drawable of a TextView (null when there is none). */
    private static Drawable firstDrawable(View v) {
        if (!(v instanceof TextView)) return null;
        Drawable[] ds = ((TextView) v).getCompoundDrawables();
        return ds == null || ds.length == 0 ? null : ds[0];
    }

    private void actions() {
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlaybackService.toggle(PlayerActivity.this);
            }
        });
        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlaybackService.prev(PlayerActivity.this);
            }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlaybackService.next(PlayerActivity.this);
            }
        });
        btnMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlaybackService svc = PlaybackService.get();
                if (svc != null) svc.switchMode();
                refresh();
            }
        });
        btnFav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFav();
            }
        });
        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 回到主界面并定位到「曲库」页，保持入口逻辑一致
                Intent i = new Intent(PlayerActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                i.putExtra("page", 0);
                startActivity(i);
                finish();
            }
        });
        // 曲库入口按钮显示曲库总数：「曲库 · N」
        final android.widget.Button libBtn = (android.widget.Button) findViewById(R.id.back);
        if (libBtn != null) {
            App.postBg(new Runnable() {
                @Override
                public void run() {
                    final int n = Db.get(PlayerActivity.this).allTracks().size();
                    App.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            libBtn.setText("曲库 · " + n);
                        }
                    });
                }
            });
        }
        findViewById(R.id.btn_queue).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQueue();
            }
        });
        btnCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cur == null) return;
                if (cur.hasLocal()) {
                    Toast.makeText(PlayerActivity.this, "已缓存到本地", Toast.LENGTH_SHORT).show();
                    return;
                }
                Downloader.start(PlayerActivity.this, cur);
            }
        });
        findViewById(R.id.btn_add_pl).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choosePlaylist();
            }
        });
        if (btnLyric != null) {
            btnLyric.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleLyricPanel();
                }
            });
        }
        if (lyricClose != null) {
            lyricClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (lyricPanel != null) lyricPanel.setVisibility(View.GONE);
                    lyricOpen = false;
                }
            });
        }
        if (lyricSearchBtn != null) {
            lyricSearchBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    manualSearch();
                }
            });
        }
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    PlaybackService svc = PlaybackService.get();
                    int dur = svc != null ? svc.duration() : 0;
                    tCur.setText(Track.fmt(dur * progress / 1000L));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                PlaybackService svc = PlaybackService.get();
                if (svc == null) return;
                int dur = svc.duration();
                if (dur > 0) svc.seek((int) (dur * (long) sb.getProgress() / 1000L));
            }
        });
    }

    private void refresh() {
        PlaybackService svc = PlaybackService.get();
        Track t = svc != null ? svc.current() : null;
        if (t == null) {
            String id = App.prefs().getString("last_id", null);
            if (id != null) t = Db.get(this).get(id);
        }
        cur = t;
        if (cur != null && !cur.id.equals(lastLyricKey)) {
            fetchLyricFor(cur);
        }
        if (btnBili != null) {
            btnBili.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    TrackActions.openBili(PlayerActivity.this, cur);
                }
            });
        }
        if (btnQueue != null) {
            int n = svc != null ? svc.queue().size() : 0;
            btnQueue.setText(n > 0
                    ? getString(R.string.current_queue) + "（" + n + "）"
                    : getString(R.string.current_queue));
        }
        if (t == null) {
            title.setText("未在播放");
            author.setText("");
            hint.setText("从曲库选择一首开始播放");
            btnPlay.setImageResource(R.drawable.ic_play);
            return;
        }
        title.setText(t.title);
        author.setText(t.author == null ? "" : t.author);
        pageBadge.setText("P" + t.page);
        boolean playing = svc != null && svc.isPlaying();
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (svc != null) {
            int m = svc.mode();
            btnMode.setImageResource(m == PlaybackService.MODE_SINGLE ? R.drawable.ic_repeat_one
                    : (m == PlaybackService.MODE_SHUFFLE ? R.drawable.ic_shuffle : R.drawable.ic_repeat));
            int dur = svc.duration();
            int pos = svc.position();
            tDur.setText(Track.fmt(dur));
            tCur.setText(Track.fmt(pos));
            if (!dragging && dur > 0) {
                seek.setProgress((int) ((long) pos * 1000L / dur));
            }
            String mode = m == PlaybackService.MODE_SINGLE ? "单曲循环"
                    : (m == PlaybackService.MODE_SHUFFLE ? "随机播放" : "列表循环");
            String status = t.hasLocal() ? "已缓存 · " + mode : "在线播放 · " + mode;
            if (svc.isLoading()) status = "缓冲中...";
            hint.setText(status);
        } else {
            hint.setText(t.hasLocal() ? "已缓存" : "在线播放");
        }
        // Reconcile cache button (e.g. re-open player while downloading)
        if (cur != null && cur.hasLocal()) {
            updateCacheBtn(1, 100, 0);
        } else if (cur != null && Downloader.busy(cur.id)) {
            int[] p = Downloader.progress(cur.id);
            if (p != null) updateCacheBtn(0, p[0], p[1]);
            else updateCacheBtn(3, 0, 0);
        } else {
            resetCacheBtn();
        }
        App.postBg(new Runnable() {
            @Override
            public void run() {
                final long favId = Db.get(PlayerActivity.this).favId();
                final boolean fav = cur != null && Db.get(PlayerActivity.this).inPlaylist(favId, cur.id);
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        btnFav.setImageResource(fav ? R.drawable.ic_fav : R.drawable.ic_fav_border);
                        btnFav.setColorFilter(getResources().getColor(fav ? R.color.pink : R.color.text_sub));
                    }
                });
            }
        });
        loadArt(t);
    }

    private void loadArt(final Track t) {
        Bitmap cached = CoverLoader.get(this).cachedBitmap(t.cover);
        if (cached != null) {
            art.setImageBitmap(cached);
            bg.setImageBitmap(CoverLoader.blurred(cached, 12));
        } else {
            App.postBg(new Runnable() {
                @Override
                public void run() {
                    final Bitmap b = CoverLoader.get(PlayerActivity.this).fetch(t.cover);
                    if (b == null) return;
                    final Bitmap blur = CoverLoader.blurred(b, 12);
                    App.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            if (cur == null || cur != t) return;
                            art.setImageBitmap(b);
                            if (blur != null) bg.setImageBitmap(blur);
                            PlaybackService svc = PlaybackService.get();
                            if (svc != null) svc.updateArt(t.cover);
                        }
                    });
                }
            });
        }
    }

    private void toggleFav() {
        if (cur == null) return;
        final Track t = cur;
        App.postBg(new Runnable() {
            @Override
            public void run() {
                long fid = Db.get(PlayerActivity.this).favId();
                boolean has = Db.get(PlayerActivity.this).inPlaylist(fid, t.id);
                if (has) Db.get(PlayerActivity.this).removeFrom(fid, t.id);
                else Db.get(PlayerActivity.this).addTo(fid, t.id);
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PlayerActivity.this, has ? "已取消喜欢" : "已加入我喜欢的音乐",
                                Toast.LENGTH_SHORT).show();
                        refresh();
                    }
                });
            }
        });
    }

    private void choosePlaylist() {
        if (cur == null) return;
        App.postBg(new Runnable() {
            @Override
            public void run() {
                final List<Playlist> pls = Db.get(PlayerActivity.this).playlists();
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        CharSequence[] names = new CharSequence[pls.size()];
                        for (int i = 0; i < pls.size(); i++) names[i] = pls.get(i).name;
                        new AlertDialog.Builder(PlayerActivity.this)
                                .setTitle("加入歌单")
                                .setItems(names, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        App.postBg(new Runnable() {
                                            @Override
                                            public void run() {
                                                Db.get(PlayerActivity.this).addTo(pls.get(w).id, cur.id);
                                                App.runOnUi(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        Toast.makeText(PlayerActivity.this, "已加入",
                                                                Toast.LENGTH_SHORT).show();
                                                    }
                                                });
                                            }
                                        });
                                    }
                                }).show();
                    }
                });
            }
        });
    }

    /** Open the queue screen (a full list, laid out like the library). */
    private void showQueue() {
        startActivity(new Intent(PlayerActivity.this, QueueActivity.class));
    }

    /* ---------------- volume (vertical bar: display + control) ---------------- */

    private void initVolume() {
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            volMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (volMax <= 0) volMax = 1;
            syncVolume(am.getStreamVolume(AudioManager.STREAM_MUSIC));
        }
        if (volBar != null) {
            volBar.setOnVolumeChangeListener(new VolumeBar.OnVolumeChangeListener() {
                @Override
                public void onVolume(float level) {
                    if (am == null) return;
                    int v = Math.round(level * volMax);
                    if (v < 0) v = 0;
                    if (v > volMax) v = volMax;
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                    syncVolume(v);
                }
            });
        }
    }

    private void syncVolume(int v) {
        if (volBar != null && volMax > 0) volBar.setLevel((float) v / volMax);
        if (volPct != null) volPct.setText(v + "/" + volMax);
    }

    /** Keep the bar in sync with hardware-key / system volume changes. */
    private void syncVolumeFromSystem() {
        if (am == null) return;
        syncVolume(am.getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    /* ---------------- lyrics ---------------- */

    /** "artist + song name" with part numbers / ids stripped — used to pre-fill. */
    private static String suggestQuery(Track t) {
        if (t == null) return "";
        // 已经识别出歌手/歌名时直接用，比从标题里猜准得多
        String artist = t.artist == null ? "" : t.artist.trim();
        String song = t.song == null ? "" : t.song.trim();
        if (!song.isEmpty()) {
            if (artist.isEmpty() || song.toLowerCase().contains(artist.toLowerCase())) return song;
            return artist + " " + song;
        }
        String q = NeteaseLyric.suggestQuery(t.title, t.author);
        if (q.isEmpty()) q = t.title == null ? "" : t.title;
        return q;
    }

    private void toggleLyricPanel() {
        if (lyricPanel == null) return;
        if (lyricPanel.getVisibility() == View.VISIBLE) {
            lyricPanel.setVisibility(View.GONE);
            lyricOpen = false;
            return;
        }
        lyricPanel.setVisibility(View.VISIBLE);
        lyricOpen = true;
        if (lyricQuery != null) {
            String curQ = lyricQuery.getText() == null ? "" : lyricQuery.getText().toString().trim();
            if (curQ.isEmpty() && cur != null) {
                lyricQuery.setText(suggestQuery(cur));
            }
        }
        if (lyric != null) {
            renderLyric(lyric);
        } else if (cur != null && !cur.id.equals(lastLyricKey)) {
            fetchLyricFor(cur);
        } else if (lyricStatus != null) {
            lyricStatus.setText(getString(R.string.lyric_loading));
        }
    }

    /** Auto-search lyrics for a track (cached by track id, runs on a bg thread). */
    private void fetchLyricFor(final Track t) {
        if (t == null) return;
        lastLyricKey = t.id;
        lastLyricIdx = -1;
        lyric = null;
        String q = suggestQuery(t);
        if (lyricQuery != null) lyricQuery.setText(q);
        if (lyricLines != null) lyricLines.removeAllViews();
        if (lyricStatus != null && lyricPanel != null
                && lyricPanel.getVisibility() == View.VISIBLE) {
            lyricStatus.setText(getString(R.string.lyric_loading));
        }
        NeteaseLyric.getLyric(this, t.id, q, new NeteaseLyric.LyricCallback() {
            @Override
            public void onLyric(Lyric ly, boolean fromCache) {
                if (cur == null || t == null || !t.id.equals(cur.id)) return;
                lyric = ly;
                if (lyricPanel != null && lyricPanel.getVisibility() == View.VISIBLE) {
                    renderLyric(ly);
                }
            }

            @Override
            public void onError(String msg) {
                if (lyricStatus != null && lyricPanel != null
                        && lyricPanel.getVisibility() == View.VISIBLE) {
                    lyricStatus.setText(getString(R.string.lyric_not_found) + "：" + msg);
                }
            }
        });
    }

    private void renderLyric(Lyric ly) {
        if (lyricLines == null) return;
        lyricLines.removeAllViews();
        if (ly != null) {
            for (Lyric.Line l : ly.lines) {
                TextView tv = new TextView(PlayerActivity.this);
                tv.setText(l.text);
                tv.setTextColor(getResources().getColor(R.color.text_sub));
                tv.setTextSize(15);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(8, 10, 8, 10);
                lyricLines.addView(tv);
            }
        }
        lastLyricIdx = -1;
        if (lyricStatus != null) {
            lyricStatus.setText(ly == null || ly.lines.isEmpty()
                        ? getString(R.string.lyric_not_found) : "");
        }
        updateLyricHighlight();
    }

    /** Highlight + auto-scroll the line matching the current playback position. */
    private void updateLyricHighlight() {
        if (lyric == null || lyricLines == null || lyricScroll == null) return;
        if (lyricPanel == null || lyricPanel.getVisibility() != View.VISIBLE) return;
        int n = lyricLines.getChildCount();
        if (n == 0) return;
        PlaybackService svc = PlaybackService.get();
        long pos = svc != null ? svc.position() : 0;
        int idx = lyric.indexAt(pos);
        if (idx == lastLyricIdx) return;
        if (lastLyricIdx >= 0 && lastLyricIdx < n) {
            TextView old = (TextView) lyricLines.getChildAt(lastLyricIdx);
            old.setTextColor(getResources().getColor(R.color.text_sub));
            old.setTextSize(15);
        }
        if (idx >= 0 && idx < n) {
            TextView nv = (TextView) lyricLines.getChildAt(idx);
            nv.setTextColor(getResources().getColor(R.color.accent));
            nv.setTextSize(17);
            final int top = nv.getTop();
            final int off = lyricScroll.getHeight() / 3;
            lyricScroll.post(new Runnable() {
                @Override
                public void run() {
                    lyricScroll.smoothScrollTo(0, Math.max(0, top - off));
                }
            });
        }
        lastLyricIdx = idx;
    }

    /** Manual lyrics search: search by keyword, then pick a candidate. */
    private void manualSearch() {
        if (lyricQuery == null) return;
        String q = lyricQuery.getText() == null ? "" : lyricQuery.getText().toString().trim();
        if (q.isEmpty() && cur != null) q = suggestQuery(cur);
        if (q.isEmpty()) return;
        if (lyricStatus != null) lyricStatus.setText(getString(R.string.lyric_loading));
        NeteaseLyric.search(q, new NeteaseLyric.SearchCallback() {
            @Override
            public void onSongs(final List<NeteaseLyric.Song> songs) {
                CharSequence[] names = new CharSequence[songs.size()];
                for (int i = 0; i < songs.size(); i++) {
                    NeteaseLyric.Song s = songs.get(i);
                    names[i] = s.name + (s.artist == null || s.artist.isEmpty() ? "" : " - " + s.artist);
                }
                new AlertDialog.Builder(PlayerActivity.this)
                        .setTitle(R.string.lyrics)
                        .setItems(names, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                NeteaseLyric.Song s = songs.get(w);
                                if (lyricStatus != null) {
                                    lyricStatus.setText(getString(R.string.lyric_loading));
                                }
                                NeteaseLyric.lyricById(PlayerActivity.this,
                                        (cur != null ? cur.id : "man") + "_" + s.id, s.id,
                                        new NeteaseLyric.LyricCallback() {
                                            @Override
                                            public void onLyric(Lyric ly, boolean fromCache) {
                                                lyric = ly;
                                                renderLyric(ly);
                                            }

                                            @Override
                                            public void onError(String msg) {
                                                if (lyricStatus != null) {
                                                    lyricStatus.setText(
                                                            getString(R.string.lyric_not_found)
                                                                    + "：" + msg);
                                                }
                                            }
                                        });
                            }
                        }).show();
            }

            @Override
            public void onError(String msg) {
                if (lyricStatus != null) {
                    lyricStatus.setText(getString(R.string.lyric_not_found) + "：" + msg);
                }
            }
        });
    }

    private void updateProgress() {
        PlaybackService svc = PlaybackService.get();
        if (svc == null) return;
        boolean playing = svc.isPlaying();
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        int dur = svc.duration();
        int pos = svc.position();
        tDur.setText(Track.fmt(dur));
        tCur.setText(Track.fmt(pos));
        if (!dragging && dur > 0) {
            seek.setProgress((int) ((long) pos * 1000L / dur));
        }
        if (cur != null) {
            if (svc.isLoading()) {
                hint.setText("缓冲中...");
            } else {
                int m = svc.mode();
                String mode = m == PlaybackService.MODE_SINGLE ? "单曲循环"
                        : (m == PlaybackService.MODE_SHUFFLE ? "随机播放" : "列表循环");
                hint.setText((cur.hasLocal() ? "已缓存 · " : "在线播放 · ") + mode);
            }
        }
        syncVolumeFromSystem();
        updateLyricHighlight();
    }

    /** Update the cache button to reflect download state + progress + speed. */
    private void updateCacheBtn(int state, int pct, long speed) {
        if (btnCache == null) return;
        switch (state) {
            case 1: // done / already cached
                btnCache.setText("已缓存 ✓");
                btnCache.setTextColor(getResources().getColor(R.color.accent));
                btnCache.setBackgroundResource(R.drawable.ripple_round);
                break;
            case 2: // failed
                btnCache.setText("缓存失败 · 点击重试");
                btnCache.setTextColor(getResources().getColor(R.color.pink));
                btnCache.setBackgroundResource(R.drawable.ripple_round);
                break;
            case 3: // waiting / preparing
                btnCache.setText("准备缓存…");
                btnCache.setTextColor(getResources().getColor(R.color.accent));
                btnCache.setBackgroundResource(R.drawable.ripple_round);
                break;
            default: // 0 = downloading
                btnCache.setText("缓存中 " + pct + "%  ·  " + fmtSpeed(speed));
                btnCache.setTextColor(getResources().getColor(android.R.color.white));
                btnCache.setBackground(makeCacheBg(pct));
                break;
        }
    }

    /** Reset the cache button to its idle "cache to local" state. */
    private void resetCacheBtn() {
        if (btnCache == null) return;
        btnCache.setText("缓存到本地");
        btnCache.setTextColor(getResources().getColor(R.color.text_sub));
        btnCache.setBackgroundResource(R.drawable.ripple_round);
    }

    /** Rounded progress fill used as the cache button background while downloading. */
    private Drawable makeCacheBg(int pct) {
        int accent = getResources().getColor(R.color.accent);
        int r = (accent >> 16) & 0xff, g = (accent >> 8) & 0xff, b = accent & 0xff;
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable base = new GradientDrawable();
        base.setColor(Color.argb(60, r, g, b));
        base.setCornerRadius(19 * d);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(accent);
        fill.setCornerRadius(19 * d);
        ClipDrawable clip = new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        clip.setLevel(Math.max(1, pct) * 100);
        return new LayerDrawable(new Drawable[]{base, clip});
    }

    private static String fmtSpeed(long bps) {
        if (bps <= 0) return "0 KB/s";
        if (bps >= 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.2f MB/s", bps / 1048576.0);
        }
        if (bps >= 1024) return (bps / 1024) + " KB/s";
        return bps + " B/s";
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (PlaybackService.ACT_STATE.equals(a)) {
                refresh();
                String err = i.getStringExtra("error");
                if (err != null) {
                    hint.setText("错误：" + err);
                    Toast.makeText(PlayerActivity.this, err, Toast.LENGTH_LONG).show();
                }
            } else if (PlaybackService.ACT_PROGRESS.equals(a)) {
                int pos = i.getIntExtra("pos", 0);
                int dur = i.getIntExtra("dur", 0);
                tCur.setText(Track.fmt(pos));
                tDur.setText(Track.fmt(dur));
                if (!dragging && dur > 0) seek.setProgress((int) ((long) pos * 1000L / dur));
            } else if (Downloader.ACT_DL.equals(a)) {
                if (cur != null && cur.id.equals(i.getStringExtra(Downloader.EXTRA_ID))) {
                    int st = i.getIntExtra(Downloader.EXTRA_STATE, 0);
                    int pct = i.getIntExtra(Downloader.EXTRA_PROGRESS, 0);
                    long spd = i.getLongExtra(Downloader.EXTRA_SPEED, 0);
                    if (st == 1) {
                        cur.cached = true;
                        cur.path = com.volcano.bmusic.util.Cache.audioFile(PlayerActivity.this, cur)
                                .getAbsolutePath();
                        updateCacheBtn(1, 100, 0);
                        Toast.makeText(PlayerActivity.this, "缓存完成", Toast.LENGTH_SHORT).show();
                        refresh();
                    } else if (st == 0) {
                        updateCacheBtn(0, pct, spd);
                    } else if (st == 2) {
                        updateCacheBtn(2, 0, 0);
                        String msg = i.getStringExtra("msg");
                        hint.setText("缓存失败" + (msg != null ? "：" + msg : ""));
                    } else if (st == 3) {
                        updateCacheBtn(3, 0, 0);
                    }
                }
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
        Receivers.registerLocal(this, receiver, f);
        refresh();
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        handler.removeCallbacks(progressPoller);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, 200);
        handler.removeCallbacks(progressPoller);
        handler.postDelayed(progressPoller, 300);
    }
}
