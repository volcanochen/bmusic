package com.volcano.bmusic.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.volcano.bmusic.App;
import com.volcano.bmusic.R;
import com.volcano.bmusic.db.Db;
import com.volcano.bmusic.model.Track;
import com.volcano.bmusic.player.PlaybackService;
import com.volcano.bmusic.ui.adapter.TrackAdapter;

import java.util.ArrayList;
import java.util.List;

public class PlaylistActivity extends Activity {

    private long pid;
    private ListView list;
    private TextView empty;
    private TextView nameView;
    private TrackAdapter adapter;
    private ArrayList<Track> data = new ArrayList<Track>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_playlist);
        pid = getIntent().getLongExtra("pid", -1);
        list = (ListView) findViewById(R.id.list);
        empty = (TextView) findViewById(R.id.empty);
        nameView = (TextView) findViewById(R.id.name);
        nameView.setText(getIntent().getStringExtra("name"));
        adapter = new TrackAdapter(this, new TrackAdapter.Listener() {
            @Override
            public void onPlay(List<Track> list, int pos) {
                ArrayList<String> ids = new ArrayList<String>();
                for (Track t : data) ids.add(t.id);
                PlaybackService.setQueue(PlaylistActivity.this, pid, ids, data.get(pos).id);
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
        findViewById(R.id.play_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (data.isEmpty()) return;
                ArrayList<String> ids = new ArrayList<String>();
                for (Track t : data) ids.add(t.id);
                PlaybackService.setQueue(PlaylistActivity.this, pid, ids, data.get(0).id);
            }
        });
        load();
    }

    private void load() {
        App.postBg(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Track> ts = Db.get(PlaylistActivity.this).tracksOf(pid);
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        data = ts;
                        adapter.setData(ts);
                        empty.setVisibility(ts.isEmpty() ? View.VISIBLE : View.GONE);
                        PlaybackService s = PlaybackService.get();
                        if (s != null && s.current() != null) {
                            adapter.setPlaying(s.current().id, s.isPlaying());
                        }
                    }
                });
            }
        });
    }

    private void menu(final Track t) {
        final String[] items = {"立即播放", "上移", "下移", "缓存到本地", "加入歌单",
                "从歌单移除", "删除曲目"};
        TrackActions.showMenu(this, t, items, new TrackActions.ItemHandler() {
            @Override
            public void onItem(Track track, int which) {
                switch (which) {
                    case 0:
                        TrackActions.playNow(PlaylistActivity.this, track);
                        break;
                    case 1:
                        move(track, -1);
                        break;
                    case 2:
                        move(track, 1);
                        break;
                    case 3:
                        TrackActions.cache(PlaylistActivity.this, track);
                        break;
                    case 4:
                        TrackActions.addToPlaylist(PlaylistActivity.this, track);
                        break;
                    case 5:
                        removeFrom(track);
                        break;
                    case 6:
                        TrackActions.confirmDelete(PlaylistActivity.this, track, new Runnable() {
                            @Override
                            public void run() {
                                load();
                            }
                        });
                        break;
                }
            }
        });
    }

    private void removeFrom(final Track t) {
        App.postBg(new Runnable() {
            @Override
            public void run() {
                Db.get(PlaylistActivity.this).removeFrom(pid, t.id);
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                });
            }
        });
    }

    private void move(final Track t, final int delta) {
        App.postBg(new Runnable() {
            @Override
            public void run() {
                ArrayList<Track> copy = new ArrayList<Track>(data);
                int i = -1;
                for (int k = 0; k < copy.size(); k++) {
                    if (copy.get(k).id.equals(t.id)) i = k;
                }
                if (i < 0) return;
                int j = i + delta;
                if (j < 0 || j >= copy.size()) return;
                copy.remove(i);
                copy.add(j, t);
                ArrayList<String> ids = new ArrayList<String>();
                for (Track x : copy) ids.add(x.id);
                Db.get(PlaylistActivity.this).reorder(pid, ids);
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }
}
