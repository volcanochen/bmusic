package com.volcano.bmusic.api;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.widget.Toast;

import com.volcano.bmusic.App;
import com.volcano.bmusic.db.Db;
import com.volcano.bmusic.model.Track;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Parse shared text in background and insert tracks into the library. */
public final class Importer {

    public static final String ACT_ADDED = "com.volcano.bmusic.ADDED";

    /** One resolved video, waiting for the user's multi-part decision. */
    public static class Candidate {
        public String bvid;
        public long aid;
        /** part the shared link pointed at */
        public int page = 1;
        /** total parts of the video */
        public int pageCount = 1;
        public String title = "";
        public String author = "";
        public String cover = "";
    }

    public interface Callback {
        void done(List<Track> added, String failMsg);
    }

    /**
     * Asked when a shared video has more than one part.
     * Runs on a background thread, so the implementation is expected to show
     * something on the ui thread and block until the user answered.
     *
     * @return true to add every part, false for only the shared part
     */
    public interface Chooser {
        boolean askAll(Candidate cand);
    }

    public static void add(final Context c, final String text, final Callback cb) {
        add(c, text, null, cb);
    }

    public static void add(final Context c, final String text, final Chooser chooser, final Callback cb) {
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
                            // one view call per video, reused for every part
                            JSONObject data = Bili.view(r);
                            int pc = Bili.pageCount(data);
                            List<Bili.Ref> targets = new ArrayList<Bili.Ref>();
                            if (pc > 1 && chooser != null) {
                                boolean all = chooser.askAll(candidate(data, r, pc));
                                if (all) {
                                    for (int i = 1; i <= pc; i++) targets.add(withPage(r, i));
                                } else {
                                    targets.add(r);
                                }
                            } else {
                                targets.add(r);
                            }
                            for (Bili.Ref tr : targets) {
                                try {
                                    Track t = Bili.fetch(tr, data);
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

    private static Candidate candidate(JSONObject data, Bili.Ref r, int pageCount) {
        Candidate cd = new Candidate();
        cd.bvid = (r.bvid != null && r.bvid.length() > 0) ? r.bvid : data.optString("bvid");
        cd.aid = r.aid;
        cd.page = r.page < 1 ? 1 : r.page;
        cd.pageCount = pageCount;
        cd.title = data.optString("title");
        JSONObject owner = data.optJSONObject("owner");
        cd.author = owner != null ? owner.optString("name") : "";
        cd.cover = data.optString("pic");
        return cd;
    }

    private static Bili.Ref withPage(Bili.Ref r, int page) {
        Bili.Ref n = new Bili.Ref();
        n.bvid = r.bvid;
        n.aid = r.aid;
        n.page = page;
        return n;
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
