package com.volcano.bmusic.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.volcano.bmusic.R;
import com.volcano.bmusic.model.Track;
import com.volcano.bmusic.util.CoverLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TrackAdapter extends BaseAdapter {

    public interface Listener {
        void onPlay(List<Track> list, int pos);
        void onMore(View anchor, Track t);
    }

    private final Context ctx;
    private final List<Track> data = new ArrayList<Track>();
    private final Map<String, Integer> progress = new HashMap<String, Integer>();
    private final Listener listener;
    private String playingId;
    private boolean playing;
    private final boolean showMore;

    public TrackAdapter(Context c, Listener l) {
        this(c, l, true);
    }

    public TrackAdapter(Context c, Listener l, boolean showMore) {
        this.ctx = c;
        this.listener = l;
        this.showMore = showMore;
    }

    public void setData(List<Track> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    public List<Track> data() {
        return data;
    }

    public void setPlaying(String id, boolean isPlaying) {
        playingId = id;
        playing = isPlaying;
        notifyDataSetChanged();
    }

    public void setProgress(String id, Integer pct) {
        if (pct == null) progress.remove(id);
        else progress.put(id, pct);
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
        return position;
    }

    @Override
    public View getView(final int position, View v, ViewGroup parent) {
        Holder h;
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(R.layout.item_track, parent, false);
            h = new Holder();
            h.art = (ImageView) v.findViewById(R.id.art);
            h.title = (TextView) v.findViewById(R.id.title);
            h.sub = (TextView) v.findViewById(R.id.sub);
            h.dur = (TextView) v.findViewById(R.id.dur);
            h.badge = (ImageView) v.findViewById(R.id.badge);
            h.more = (ImageButton) v.findViewById(R.id.more);
            v.setTag(h);
        } else {
            h = (Holder) v.getTag();
        }
        final Track t = data.get(position);
        h.title.setText(t.title == null ? t.bvid : t.title);
        String suffix = "";
        if (t.plays > 0) suffix = "  ·  播过" + t.plays + "次";
        h.sub.setText((t.author == null ? "" : t.author) + suffix);
        h.dur.setText(t.duration > 0 ? Track.fmt(t.duration) : "--:--");
        Integer pct = progress.get(t.id);
        if (pct != null) {
            h.dur.setText(pct + "%");
        }
        h.badge.setVisibility(t.hasLocal() ? View.VISIBLE : View.GONE);
        boolean isCur = t.id != null && t.id.equals(playingId);
        h.title.setTextColor(ctx.getResources().getColor(
                isCur ? R.color.pink : R.color.text));
        h.sub.setTextColor(ctx.getResources().getColor(
                isCur ? R.color.pink : R.color.text_dim));
        h.art.setImageResource(R.drawable.ic_music_note);
        h.art.setTag(null);
        CoverLoader.get(ctx).load(h.art, t.cover);
        if (showMore) {
            h.more.setVisibility(View.VISIBLE);
            h.more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) listener.onMore(v, data.get(position));
                }
            });
        } else {
            h.more.setVisibility(View.GONE);
            h.more.setOnClickListener(null);
        }
        // Row click: handled here instead of ListView.OnItemClickListener
        // because a clickable/focusable descendant (the "more" button) would
        // otherwise suppress the list item click on older devices.
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onPlay(data, position);
            }
        });
        return v;
    }

    private static class Holder {
        ImageView art;
        TextView title;
        TextView sub;
        TextView dur;
        ImageView badge;
        ImageButton more;
    }
}
