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
    private View selBar;
    private TextView tvSel;

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
        selBar = findViewById(R.id.queue_sel_bar);
        tvSel = (TextView) findViewById(R.id.tv_queue_sel);
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
                deleteSelected();
            }
        });
        btnSelect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSelectMode(true);
            }
        });
        findViewById(R.id.btn_queue_sel_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAll();
            }
        });
        findViewById(R.id.btn_queue_sel_inv).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                invertSelected();
            }
        });
        findViewById(R.id.btn_queue_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSelectMode(false);
            }
        });
        load();
    }

    /** 进入/退出多选模式（复用曲库逻辑：选择栏 + 头部删除按钮灰度）。 */
    private void setSelectMode(boolean on) {
        selectMode = on;
        selected.clear();
        adapter.setSelectable(on ? selected : null);
        btnSelect.setVisibility(on ? View.GONE : View.VISIBLE);
        selBar.setVisibility(on ? View.VISIBLE : View.GONE);
        updateSelectionBar();
    }

    private void updateSelectionBar() {
        if (selectMode) {
            tvSel.setText("已选 " + selected.size() + " / " + data.size());
        } else {
            nameView.setText(getString(R.string.current_queue)
                    + "（" + data.size() + "）");
        }
        // 头部垃圾桶 = 删除所选：未勾选时置灰禁用（与曲库一致）
        boolean has = !selected.isEmpty();
        btnClear.setEnabled(has);
        btnClear.setAlpha(has ? 1f : 0.35f);
    }

    /** 勾选模式：全选当前队列。 */
    private void selectAll() {
        if (!selectMode || adapter == null) return;
        for (Track t : adapter.data()) {
            if (t.id != null) selected.add(t.id);
        }
        adapter.setSelectable(selected);
        updateSelectionBar();
    }

    /** 勾选模式：反选（已勾的取消、未勾的选中）。 */
    private void invertSelected() {
        if (!selectMode || adapter == null) return;
        for (Track t : adapter.data()) {
            if (t.id == null) continue;
            if (selected.contains(t.id)) selected.remove(t.id);
            else selected.add(t.id);
        }
        adapter.setSelectable(selected);
        updateSelectionBar();
    }

    private void deleteSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先勾选要删除的曲目", Toast.LENGTH_SHORT).show();
            return;
        }
        final int n = selected.size();
        new android.app.AlertDialog.Builder(this)
                .setMessage("从播放列表移除选中的 " + n + " 首？\n（只移出列表，不从曲库删除）")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        PlaybackService.removeIds(QueueActivity.this,
                                new ArrayList<String>(selected));
                        Toast.makeText(QueueActivity.this,
                                "已移除 " + n + " 首", Toast.LENGTH_SHORT).show();
                        selected.clear();
                        updateSelectionBar();
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
                        TrackActions.confirmDelete(QueueActivity.this, track,
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        // 从曲库删除的同时把队列里的这条也去掉并刷新
                                        ArrayList<String> one = new ArrayList<String>();
                                        one.add(track.id);
                                        PlaybackService.removeIds(QueueActivity.this, one);
                                    }
                                }, "同时会从播放队列中移除，且本地缓存文件一并删除");
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
