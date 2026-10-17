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
    /** 多选模式：非 null 时行点击 = 勾选。 */
    private java.util.HashSet<String> selected = new java.util.HashSet<String>();
    private boolean selectMode = false;
    private View btnSelect;
    private View btnClear;
    private View btnDeleteSel;
    private View btnCancelSel;

    private static final String[] ITEMS = {"立即播放", "上移", "下移", "缓存到本地",
            "加入歌单", "返回 B 站", "从队列移除", "删除曲目"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_queue);
        list = (ListView) findViewById(R.id.list);
        empty = (TextView) findViewById(R.id.empty);
        nameView = (TextView) findViewById(R.id.name);
        btnSelect = findViewById(R.id.btn_select);
        btnClear = findViewById(R.id.btn_clear);
        btnDeleteSel = findViewById(R.id.btn_delete_sel);
        btnCancelSel = findViewById(R.id.btn_cancel_sel);
        adapter = new TrackAdapter(this, new TrackAdapter.Listener() {
            @Override
            public void onPlay(List<Track> l, int pos) {
                if (selectMode) {
                    adapter.toggleSelected(l.get(pos).id);
                    updateSelectionBar();
                    return;
                }
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
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });
        btnSelect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSelectMode(true);
            }
        });
        btnCancelSel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSelectMode(false);
            }
        });
        btnDeleteSel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteSelected();
            }
        });
        load();
    }

    /** 进入/退出多选模式。 */
    private void setSelectMode(boolean on) {
        selectMode = on;
        selected.clear();
        adapter.setSelectable(on ? selected : null);
        btnSelect.setVisibility(on ? View.GONE : View.VISIBLE);
        btnClear.setVisibility(on ? View.GONE : View.VISIBLE);
        btnDeleteSel.setVisibility(on ? View.VISIBLE : View.GONE);
        btnCancelSel.setVisibility(on ? View.VISIBLE : View.GONE);
        updateSelectionBar();
    }

    private void updateSelectionBar() {
        if (selectMode) {
            nameView.setText("已选 " + selected.size() + " / " + data.size());
            btnDeleteSel.setEnabled(!selected.isEmpty());
            btnDeleteSel.setAlpha(selected.isEmpty() ? 0.4f : 1f);
        } else {
            nameView.setText(getString(R.string.current_queue)
                    + "（" + data.size() + "）");
        }
    }

    private void deleteSelected() {
        if (selected.isEmpty()) return;
        final int n = selected.size();
        new android.app.AlertDialog.Builder(this)
                .setMessage("从队列删除选中的 " + n + " 首？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        PlaybackService.removeIds(QueueActivity.this,
                                new ArrayList<String>(selected));
                        Toast.makeText(QueueActivity.this,
                                "已删除 " + n + " 首", Toast.LENGTH_SHORT).show();
                        setSelectMode(false);
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void confirmClear() {
        new android.app.AlertDialog.Builder(this)
                .setMessage("清空整个播放队列（" + data.size() + " 首）？播放将停止。")
                .setPositiveButton("清空", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        PlaybackService.clearQueue(QueueActivity.this);
                        Toast.makeText(QueueActivity.this, "队列已清空", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null).show();
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
                        if (selectMode) {
                            updateSelectionBar();
                        } else {
                            nameView.setText(getString(R.string.current_queue)
                                    + "（" + ts.size() + "）");
                        }
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
                        TrackActions.confirmDelete(QueueActivity.this, track, new Runnable() {
                            @Override
                            public void run() {
                                // 从曲库删除的同时把队列里的这条也去掉并刷新
                                ArrayList<String> one = new ArrayList<String>();
                                one.add(track.id);
                                PlaybackService.removeIds(QueueActivity.this, one);
                            }
                        });
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
