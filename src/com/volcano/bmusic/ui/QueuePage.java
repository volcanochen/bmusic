package com.volcano.bmusic.ui;

import android.app.Activity;
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

import java.util.ArrayList;

/**
 * 播放列表页（嵌入主界面，与曲库/歌单同级的 tab 页）。
 * 原为独立 QueueActivity，v1.0.18 合并进主界面；逻辑不变：
 * 与曲库同样的行样式 + 多选（全选/反选/删除所选，复用曲库交互）。
 */
public class QueuePage {

    private final Activity host;
    private final ListView list;
    private final TextView empty;
    private final TextView nameView;
    private final TrackAdapter adapter;
    private final ArrayList<Track> data = new ArrayList<Track>();
    /** 多选模式：非 null 时行点击 = 勾选。 */
    private final java.util.HashSet<String> selected = new java.util.HashSet<String>();
    private boolean selectMode = false;
    private final View btnSelect;
    private final View btnClear;
    private final View selBar;
    private final TextView tvSel;

    private static final String[] ITEMS = {"立即播放", "上移", "下移", "缓存到本地",
            "加入歌单", "返回 B 站", "从队列移除", "删除曲目"};

    public QueuePage(Activity host) {
        this.host = host;
        list = (ListView) host.findViewById(R.id.list);
        empty = (TextView) host.findViewById(R.id.empty);
        nameView = (TextView) host.findViewById(R.id.name);
        btnSelect = host.findViewById(R.id.btn_select);
        btnClear = host.findViewById(R.id.btn_clear);
        selBar = host.findViewById(R.id.queue_sel_bar);
        tvSel = (TextView) host.findViewById(R.id.tv_queue_sel);

        adapter = new TrackAdapter(host, new TrackAdapter.Listener() {
            @Override
            public void onPlay(java.util.List<Track> l, int pos) {
                if (selectMode) {
                    adapter.toggleSelected(l.get(pos).id);
                    updateSelectionBar();
                    return;
                }
                PlaybackService.open(host, l.get(pos).id);
            }

            @Override
            public void onMore(View anchor, Track t) {
                menu(t);
            }
        });
        list.setAdapter(adapter);

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
        host.findViewById(R.id.btn_queue_sel_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAll();
            }
        });
        host.findViewById(R.id.btn_queue_sel_inv).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                invertSelected();
            }
        });
        host.findViewById(R.id.btn_queue_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSelectMode(false);
            }
        });

        PlaybackService svc = PlaybackService.get();
        if (svc == null && App.prefs().getString("last_id", null) != null) {
            PlaybackService.wake(host);
        }
    }

    /** 页面被切到前台时刷新队列。 */
    public void refresh() {
        load();
    }

    /** 播放状态广播（ACT_STATE）。 */
    public void onStateChanged() {
        load();
    }

    /** 下载进度广播（Downloader.ACT_DL）。st：0=进行中 1=完成 2=失败。 */
    public void onDownload(String id, int st, int progress) {
        if (st == 0) {
            adapter.setProgress(id, progress);
        } else {
            adapter.setProgress(id, null);
            load();
        }
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
            nameView.setText(host.getString(R.string.current_queue)
                    + "（" + data.size() + "）");
        }
        // 头部垃圾桶 = 删除所选：未勾选时置灰禁用（与曲库一致）
        boolean has = !selected.isEmpty();
        btnClear.setEnabled(has);
        btnClear.setAlpha(has ? 1f : 0.35f);
    }

    /** 勾选模式：全选当前队列。 */
    private void selectAll() {
        if (!selectMode) return;
        for (Track t : data) {
            if (t.id != null) selected.add(t.id);
        }
        adapter.setSelectable(selected);
        updateSelectionBar();
    }

    /** 勾选模式：反选（已勾的取消、未勾的选中）。 */
    private void invertSelected() {
        if (!selectMode) return;
        for (Track t : data) {
            if (t.id == null) continue;
            if (selected.contains(t.id)) selected.remove(t.id);
            else selected.add(t.id);
        }
        adapter.setSelectable(selected);
        updateSelectionBar();
    }

    private void deleteSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(host, "请先勾选要删除的曲目", Toast.LENGTH_SHORT).show();
            return;
        }
        final int n = selected.size();
        new android.app.AlertDialog.Builder(host)
                .setMessage("从播放列表移除选中的 " + n + " 首？\n（只移出列表，不从曲库删除）")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        PlaybackService.removeIds(host, new ArrayList<String>(selected));
                        Toast.makeText(host, "已移除 " + n + " 首", Toast.LENGTH_SHORT).show();
                        selected.clear();
                        updateSelectionBar();
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void load() {
        PlaybackService svc = PlaybackService.get();
        if (svc == null) {
            // 进程冷启动后服务可能还没起：先唤醒让 restore() 恢复队列
            PlaybackService.wake(host);
            svc = PlaybackService.get();
        }
        final ArrayList<String> ids = svc == null
                ? new ArrayList<String>() : new ArrayList<String>(svc.queue());
        App.postBg(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Track> ts = new ArrayList<Track>();
                for (String id : ids) {
                    Track t = Db.get(host).get(id);
                    if (t != null) ts.add(t);
                }
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        data.clear();
                        data.addAll(ts);
                        adapter.setData(ts);
                        empty.setVisibility(ts.isEmpty() ? View.VISIBLE : View.GONE);
                        updateSelectionBar();
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
        TrackActions.showMenu(host, t, ITEMS, new TrackActions.ItemHandler() {
            @Override
            public void onItem(Track track, int which) {
                switch (which) {
                    case 0:
                        TrackActions.playNow(host, track);
                        break;
                    case 1:
                        PlaybackService.editQueue(host, track.id, -1);
                        Toast.makeText(host, "已上移", Toast.LENGTH_SHORT).show();
                        break;
                    case 2:
                        PlaybackService.editQueue(host, track.id, 1);
                        Toast.makeText(host, "已下移", Toast.LENGTH_SHORT).show();
                        break;
                    case 3:
                        TrackActions.cache(host, track);
                        break;
                    case 4:
                        TrackActions.addToPlaylist(host, track);
                        break;
                    case 5:
                        TrackActions.openBili(host, track);
                        break;
                    case 6:
                        PlaybackService.editQueue(host, track.id, 0);
                        Toast.makeText(host, "已从队列移除", Toast.LENGTH_SHORT).show();
                        break;
                    case 7:
                        TrackActions.confirmDelete(host, track,
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        // 从曲库删除的同时把队列里的这条也去掉并刷新
                                        ArrayList<String> one = new ArrayList<String>();
                                        one.add(track.id);
                                        PlaybackService.removeIds(host, one);
                                    }
                                }, "同时会从播放列表中移除，且本地缓存文件一并删除");
                        break;
                }
            }
        });
    }
}
