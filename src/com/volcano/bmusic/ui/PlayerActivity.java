package com.volcano.bmusic.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.volcano.bmusic.App;
import com.volcano.bmusic.R;
import com.volcano.bmusic.db.Db;
import com.volcano.bmusic.model.Playlist;
import com.volcano.bmusic.model.Track;
import com.volcano.bmusic.player.PlaybackService;
import com.volcano.bmusic.util.CoverLoader;
import com.volcano.bmusic.util.Downloader;

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

    private boolean dragging = false;
    private final Handler handler = new Handler();
    private Track cur;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_player);
        bind();
        actions();
        refresh();
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
                onBackPressed();
            }
        });
        findViewById(R.id.btn_queue).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQueue();
            }
        });
        findViewById(R.id.btn_cache).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cur == null) return;
                if (cur.hasLocal()) {
                    Toast.makeText(PlayerActivity.this, "已缓存到本地", Toast.LENGTH_SHORT).show();
                    return;
                }
                Downloader.start(PlayerActivity.this, cur);
                Toast.makeText(PlayerActivity.this, "开始缓存...", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_add_pl).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choosePlaylist();
            }
        });
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

    private void showQueue() {
        PlaybackService svc = PlaybackService.get();
        if (svc == null) {
            Toast.makeText(this, "播放服务未运行", Toast.LENGTH_SHORT).show();
            return;
        }
        final ArrayList<String> ids = new ArrayList<String>(svc.queue());
        App.postBg(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Track> ts = new ArrayList<Track>();
                for (String id : ids) {
                    Track t = Db.get(PlayerActivity.this).get(id);
                    if (t != null) ts.add(t);
                }
                CharSequence[] names = new CharSequence[ts.size()];
                for (int i = 0; i < ts.size(); i++) names[i] = (i + 1) + ". " + ts.get(i).title;
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        new AlertDialog.Builder(PlayerActivity.this)
                                .setTitle("播放队列（" + ts.size() + "首）")
                                .setItems(names, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        PlaybackService.open(PlayerActivity.this, ts.get(w).id);
                                    }
                                }).show();
                    }
                });
            }
        });
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
                    if (st == 1) {
                        cur.cached = true;
                        cur.path = com.volcano.bmusic.util.Cache.audioFile(PlayerActivity.this, cur)
                                .getAbsolutePath();
                        Toast.makeText(PlayerActivity.this, "缓存完成", Toast.LENGTH_SHORT).show();
                        refresh();
                    } else {
                        hint.setText("缓存中 " + i.getIntExtra(Downloader.EXTRA_PROGRESS, 0) + "%");
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
        registerReceiver(receiver, f);
        refresh();
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
    protected void onResume() {
        super.onResume();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, 200);
    }
}
