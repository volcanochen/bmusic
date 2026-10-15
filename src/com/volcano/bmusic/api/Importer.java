package com.volcano.bmusic.api;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.widget.Toast;

import com.volcano.bmusic.App;
import com.volcano.bmusic.db.Db;
import com.volcano.bmusic.model.Track;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Parse shared text in background and insert tracks into the library. */
public final class Importer {

    public static final String ACT_ADDED = "com.volcano.bmusic.ADDED";

    public interface Callback {
        void done(List<Track> added, String failMsg);
    }

    public static void add(final Context c, final String text, final Callback cb) {
        App.postNet(new Runnable() {
            @Override
            public void run() {
                final List<Track> added = new ArrayList<Track>();
                String fail = null;
                try {
                    List<Bili.Ref> refs = Bili.extractAll(text);
                    Set<String> seen = new HashSet<String>();
                    if (refs.isEmpty()) {
                        try {
                            refs.add(Bili.resolve(text));
                        } catch (Exception e) {
                            fail = e.getMessage();
                        }
                    }
                    for (Bili.Ref r : refs) {
                        try {
                            Track t = Bili.fetch(r);
                            if (t == null || TextUtils.isEmpty(t.id)) continue;
                            if (seen.contains(t.id)) continue;
                            seen.add(t.id);
                            t.added = System.currentTimeMillis();
                            Db.get(c).upsert(t);
                            added.add(t);
                        } catch (Exception e) {
                            if (fail == null) fail = e.getMessage();
                        }
                    }
                } catch (Exception e) {
                    if (fail == null) fail = e.getMessage();
                }
        Intent i = new Intent(ACT_ADDED);
        i.setPackage(c.getPackageName());
        i.putExtra("n", added.size());
                c.sendBroadcast(i);
                if (cb != null) {
                    final String f = fail;
                    App.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            cb.done(added, f);
                        }
                    });
                }
            }
        });
    }

    public static void toastResult(Context c, List<Track> added, String fail) {
        StringBuilder sb = new StringBuilder();
        if (!added.isEmpty()) {
            sb.append("已添加 ").append(added.size()).append(" 首：").append(added.get(0).title);
            if (added.size() > 1) sb.append(" 等");
        }
        if (fail != null && !fail.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("失败：").append(fail);
        }
        if (sb.length() == 0) sb.append("没有识别到有效的链接");
        Toast.makeText(c, sb.toString(), Toast.LENGTH_LONG).show();
    }
}
