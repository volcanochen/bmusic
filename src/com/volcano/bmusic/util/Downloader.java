package com.volcano.bmusic.util;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.volcano.bmusic.App;
import com.volcano.bmusic.api.Bili;
import com.volcano.bmusic.db.Db;
import com.volcano.bmusic.model.Track;
import com.volcano.bmusic.net.Http;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sequential audio cache downloader. */
public class Downloader {

    public static final String ACT_DL = "com.volcano.bmusic.DL";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_STATE = "state"; // 0 progress, 1 done, 2 fail, 3 waiting
    public static final String EXTRA_PROGRESS = "progress";

    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final Set<String> running = new HashSet<String>();
    private static final Set<String> stopped = new HashSet<String>();

    public static synchronized boolean busy(String id) {
        return running.contains(id);
    }

    public static void cancel(String id) {
        synchronized (Downloader.class) {
            stopped.add(id);
        }
    }

    public static void start(final Context c, final Track t) {
        if (t == null) return;
        if (t.hasLocal()) {
            broadcast(c, t.id, 1, 100);
            return;
        }
        synchronized (Downloader.class) {
            if (running.contains(t.id)) return;
            running.add(t.id);
            stopped.remove(t.id);
        }
        broadcast(c, t.id, 3, 0);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                File dst = Cache.audioFile(c, t);
                try {
                    if (App.wifiOnly() && !onWifi(c)) {
                        throw new Exception("当前限制仅WiFi下载");
                    }
                    String url;
                    try {
                        url = (t.streamUrl != null && System.currentTimeMillis() - t.streamAt < 20 * 60 * 1000)
                                ? t.streamUrl : Bili.audioUrl(t);
                    } catch (Exception e) {
                        throw new Exception("取音频地址失败：" + e.getMessage());
                    }
                    final long[] last = {0};
                    Http.download(url, dst, Bili.mediaHeaders(Bili.refererOf(t)), new Http.Progress() {
                        @Override
                        public void on(long done, long total) {
                            int pct = total > 0 ? (int) (done * 100 / total) : 0;
                            if (pct != last[0]) {
                                last[0] = pct;
                                broadcast(c, t.id, 0, pct);
                            }
                        }
                    });
                    if (dst.exists() && dst.length() > 8 * 1024) {
                        Db.get(c).setCache(t.id, true, dst.getAbsolutePath());
                        synchronized (Downloader.class) {
                            running.remove(t.id);
                        }
                        broadcast(c, t.id, 1, 100);
                    } else {
                        throw new Exception("下载文件过小");
                    }
                } catch (Exception e) {
                    try {
                        dst.delete();
                    } catch (Exception ignored) {
                    }
                    synchronized (Downloader.class) {
                        running.remove(t.id);
                        stopped.remove(t.id);
                    }
                    Intent i = new Intent(ACT_DL);
                    i.putExtra(EXTRA_ID, t.id);
                    i.putExtra(EXTRA_STATE, 2);
                    i.putExtra(EXTRA_PROGRESS, 0);
                    i.putExtra("msg", e.getMessage());
                    c.sendBroadcast(i);
                }
            }
        });
    }

    private static boolean onWifi(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Exception e) {
            return false;
        }
    }

    private static void broadcast(Context c, String id, int state, int progress) {
        Intent i = new Intent(ACT_DL);
        i.putExtra(EXTRA_ID, id);
        i.putExtra(EXTRA_STATE, state);
        i.putExtra(EXTRA_PROGRESS, progress);
        c.sendBroadcast(i);
    }
}
