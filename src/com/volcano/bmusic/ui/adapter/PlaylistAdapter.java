package com.volcano.bmusic.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.volcano.bmusic.R;
import com.volcano.bmusic.model.Playlist;

import java.util.ArrayList;
import java.util.List;

public class PlaylistAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<Playlist> data = new ArrayList<Playlist>();
    /** Id of the playlist the current queue came from; -1 = none. */
    private long playingPid = -1;
    private boolean playing;

    public PlaylistAdapter(Context c) {
        ctx = c;
    }

    /** Mark which playlist is currently being played (pass -1 to clear). */
    public void setPlaying(long pid, boolean isPlaying) {
        playingPid = pid;
        playing = isPlaying;
        notifyDataSetChanged();
    }

    public void setData(List<Playlist> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Object getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return data.get(position).id;
    }

    @Override
    public View getView(int position, View v, ViewGroup parent) {
        Holder h;
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(R.layout.item_playlist, parent, false);
            h = new Holder();
            h.name = (TextView) v.findViewById(R.id.name);
            h.count = (TextView) v.findViewById(R.id.count);
            h.extra = (TextView) v.findViewById(R.id.extra);
            v.setTag(h);
        } else {
            h = (Holder) v.getTag();
        }
        Playlist p = data.get(position);
        boolean isCur = p.id == playingPid;
        h.name.setText(p.name);
        h.count.setText(p.count + " 首");
        h.extra.setText(isCur ? (playing ? "正在播放" : "已暂停") : "");
        h.name.setTextColor(ctx.getResources().getColor(isCur ? R.color.pink : R.color.text));
        h.extra.setTextColor(ctx.getResources().getColor(R.color.pink));
        return v;
    }

    private static class Holder {
        TextView name;
        TextView count;
        TextView extra;
    }
}
