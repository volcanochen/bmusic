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

    public PlaylistAdapter(Context c) {
        ctx = c;
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
        h.name.setText(p.name);
        h.count.setText(p.count + " 首");
        h.extra.setText("");
        return v;
    }

    private static class Holder {
        TextView name;
        TextView count;
        TextView extra;
    }
}
