package com.volcano.bmusic.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.volcano.bmusic.App;
import com.volcano.bmusic.R;
import com.volcano.bmusic.db.Db;
import com.volcano.bmusic.model.Track;
import com.volcano.bmusic.player.PlaybackService;
import com.volcano.bmusic.ui.adapter.TrackAdapter;
import com.volcano.bmusic.util.Downloader;
import com.volcano.bmusic.util.Receivers;

import java.util.ArrayList;
import java.util.List;

/**
 * The playback queue, laid out exactly like the library (same rows, same
 * actions) with the currently playing track highlighted.
 */
public class QueueActivity extends Activity {

    private ListView list;
    private TextView empty;
    private TextView nameView;
    private TrackAdapter adapter;
    private ArrayList<Track> data = new ArrayList<Track>();

    private static final String[] ITEMS = {"立即播放", "上移", "下移", "缓存到本地",
            "加入歌单", "返回 B 站", "从队列移除", "删除曲目"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_queue);
        list = (ListView) findViewById(R.id.list);
        empty = (TextView) findViewById(R.id.empty);
        nameView = (TextView) findViewById(R.id.name);
        adapter = new TrackAdapter(this, new TrackAdapter.Listener() {
            @Override
            public void onPlay(List<Track> l, int pos) {
                PlaybackService.open(QueueActivity.this, l.get(pos).id);
            }

            @Override
            public void onMore(View anchor, Track t) {
                menu(t);
            }
        });
        list.setAdapter(adapter);
        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        load();
    }

    private void load() {
        PlaybackService svc = PlaybackService.get();
        if (svc == null) {
            // 进程冷启动后服务可能还没起（迷你条/播放页展示的是持久化状态）:
            // 先唤醒服务让 restore() 恢复队列，同进程 startService 会同步完成 onCreate。
            PlaybackService.wake(this);
            svc = PlaybackService.get();
        }
        final ArrayList<String> ids = svc == null
                ? new ArrayList<String>() : new ArrayList<String>(svc.queue());
        if (svc == null) {
            Toast.makeText(this, "播放服务未连接", Toast.LENGTH_SHORT).show();
        }
        App.postBg(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Track> ts = new ArrayList<Track>();
                for (String id : ids) {
                    Track t = Db.get(QueueActivity.this).get(id);
                    if (t != null) ts.add(t);
                }
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        data = ts;
                        adapter.setData(ts);
                        empty.setVisibility(ts.isEmpty() ? View.VISIBLE : View.GONE);
                        nameView.setText(getString(R.string.current_queue)
                                + "（" + ts.size() + "）");
                        markPlaying();
                    }
                });
            }
        });
    }

    /** Highlight the track that is currently loaded / playing. */
    private void markPlaying() {
        PlaybackService svc = PlaybackService.get();
        if (svc != null && svc.current() != null) {
            adapter.setPlaying(svc.current().id, svc.isPlaying());
            return;
        }
        String id = App.prefs().getString("last_id", null);
        if (id != null) adapter.setPlaying(id, false);
    }

    private void menu(final Track t) {
        TrackActions.showMenu(this, t, ITEMS, new TrackActions.ItemHandler() {
            @Override
            public void onItem(Track track, int which) {
                switch (which) {
                    case 0:
                        TrackActions.playNow(QueueActivity.this, track);
                        break;
                    case 1:
                        PlaybackService.editQueue(QueueActivity.this, track.id, -1);
                        Toast.makeText(QueueActivity.this, "已上移", Toast.LENGTH_SHORT).show();
                        break;
                    case 2:
                        PlaybackService.editQueue(QueueActivity.this, track.id, 1);
                        Toast.makeText(QueueActivity.this, "已下移", Toast.LENGTH_SHORT).show();
                        break;
                    case 3:
                        TrackActions.cache(QueueActivity.this, track);
                        break;
                    case 4:
                        TrackActions.addToPlaylist(QueueActivity.this, track);
                        break;
                    case 5:
                        TrackActions.openBili(QueueActivity.this, track);
                        break;
                    case 6:
                        PlaybackService.editQueue(QueueActivity.this, track.id, 0);
                        Toast.makeText(QueueActivity.this, "已从队列移除", Toast.LENGTH_SHORT).show();
                        break;
                    case 7:
                        TrackActions.confirmDelete(QueueActivity.this, track, null);
                        break;
                }
            }
        });
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (PlaybackService.ACT_STATE.equals(a)) {
                load();
            } else if (Downloader.ACT_DL.equals(a)) {
                String id = i.getStringExtra(Downloader.EXTRA_ID);
                int st = i.getIntExtra(Downloader.EXTRA_STATE, 0);
                if (st == 0) {
                    adapter.setProgress(id, i.getIntExtra(Downloader.EXTRA_PROGRESS, 0));
                } else {
                    adapter.setProgress(id, null);
                    load();
                }
            }
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(PlaybackService.ACT_STATE);
        f.addAction(Downloader.ACT_DL);
        Receivers.registerLocal(this, receiver, f);
        load();
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
        load();
    }
}
