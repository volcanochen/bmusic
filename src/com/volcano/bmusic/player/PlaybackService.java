package com.volcano.bmusic.player;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.volcano.bmusic.App;
import com.volcano.bmusic.R;
import com.volcano.bmusic.api.Bili;
import com.volcano.bmusic.db.Db;
import com.volcano.bmusic.model.Track;
import com.volcano.bmusic.ui.MainActivity;
import com.volcano.bmusic.util.CoverLoader;
import com.volcano.bmusic.util.Downloader;
import com.volcano.bmusic.util.Notify;
import com.volcano.bmusic.util.Receivers;

import java.util.ArrayList;
import java.util.Map;
import java.util.Random;

/** Background audio playback engine. */
public class PlaybackService extends Service implements AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "BMusic";

    public static final String A_TOGGLE = "bm.a.toggle";
    public static final String A_PLAY = "bm.a.play";
    public static final String A_PAUSE = "bm.a.pause";
    public static final String A_NEXT = "bm.a.next";
    public static final String A_PREV = "bm.a.prev";
    public static final String A_STOP = "bm.a.stop";
    public static final String A_SEEK = "bm.a.seek";
    public static final String A_APPEND = "bm.a.append";
    public static final String A_OPEN = "bm.a.open";
    public static final String A_QUEUE = "bm.a.queue";
    public static final String A_MODE = "bm.a.mode";

    public static final String ACT_STATE = "com.volcano.bmusic.STATE";
    public static final String ACT_PROGRESS = "com.volcano.bmusic.PROGRESS";

    public static final int MODE_LOOP = 0;
    public static final int MODE_SINGLE = 1;
    public static final int MODE_SHUFFLE = 2;

    private static PlaybackService s;

    private MediaPlayer mp;
    private MediaSession session;
    private AudioManager audio;
    private Handler handler;
    private Bitmap artCache;

    private Track cur;
    private ArrayList<String> queueIds = new ArrayList<String>();
    private int index = -1;
    private long queuePid = -1;
    private int mode = MODE_LOOP;
    private boolean playing = false;
    private boolean loading = false;
    private int lastError = 0;
    private int retry = 0;

    private final Random rnd = new Random();

    public static PlaybackService get() {
        return s;
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        s = this;
        handler = new Handler(Looper.getMainLooper());
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mode = App.prefs().getInt("mode", MODE_LOOP);
        Notify.ensureChannel(this);
        initPlayer();
        initSession();
        Receivers.registerLocal(this, noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        startForegroundSafe();
        restore();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String a = intent.getAction();
            if (A_TOGGLE.equals(a)) {
                toggle();
            } else if (A_PLAY.equals(a)) {
                play();
            } else if (A_PAUSE.equals(a)) {
                pause();
            } else if (A_NEXT.equals(a)) {
                next(false);
            } else if (A_PREV.equals(a)) {
                prev();
            } else if (A_STOP.equals(a)) {
                stopSelf0();
            } else if (A_SEEK.equals(a)) {
                int pos = intent.getIntExtra("pos", 0);
                seek(pos);
            } else if (A_OPEN.equals(a)) {
                String id = intent.getStringExtra("id");
                boolean auto = intent.getBooleanExtra("auto", true);
                openById(id, auto);
            } else if (A_QUEUE.equals(a)) {
                ArrayList<String> ids = intent.getStringArrayListExtra("ids");
                long pid = intent.getLongExtra("pid", -1);
                String startTid = intent.getStringExtra("start");
                boolean auto = intent.getBooleanExtra("auto", true);
                setQueue(pid, ids, startTid, auto);
            } else if (A_MODE.equals(a)) {
                switchMode();
            } else if (A_APPEND.equals(a)) {
                String id = intent.getStringExtra("id");
                if (id != null && !queueIds.contains(id)) queueIds.add(id);
                if (cur == null && !queueIds.isEmpty()) openIndex(index < 0 ? 0 : index, true);
                saveQueue();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(noisy);
        } catch (Exception ignored) {
        }
        try {
            audio.abandonAudioFocus(this);
        } catch (Exception ignored) {
        }
        releasePlayer();
        if (session != null) session.release();
        if (cur != null) App.prefs().edit().putString("last_id", cur.id).apply();
        s = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void initPlayer() {
        if (mp == null) {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setOnPreparedListener(prepared);
            mp.setOnCompletionListener(completed);
            mp.setOnErrorListener(errorListener);
            mp.setOnBufferingUpdateListener(null);
        }
    }

    private void releasePlayer() {
        if (mp != null) {
            try {
                mp.stop();
            } catch (Exception ignored) {
            }
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
        playing = false;
        loading = false;
    }

    private void initSession() {
        session = new MediaSession(this, "bmusic");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                next(false);
            }

            @Override
            public void onSkipToPrevious() {
                prev();
            }

            @Override
            public void onSeekTo(long pos) {
                seek((int) pos);
            }

            @Override
            public void onStop() {
                stopSelf0();
            }
        });
        Intent launch = new Intent(Intent.ACTION_VIEW, null, this, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        session.setSessionActivity(PendingIntent.getActivity(this, 1, launch, piFlags()));
        session.setActive(true);
    }

    private static int piFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pause();
            }
        }
    };

    // -------------------------------------------------------------- helpers

    private void startForegroundSafe() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForeground(Notify.ID, buildNotify());
            } else {
                startForeground(Notify.ID, buildNotify());
            }
        } catch (Exception e) {
            Log.e(TAG, "fg fail", e);
        }
    }

    private void restore() {
        String id = App.prefs().getString("last_id", null);
        long pid = App.prefs().getLong("queue_pid", -1);
        int idx = App.prefs().getInt("queue_index", -1);
        if (id != null) {
            ArrayList<String> ids;
            if (pid > 0) {
                ArrayList<Track> ts = Db.get(this).tracksOf(pid);
                ids = new ArrayList<String>();
                for (Track t : ts) ids.add(t.id);
            } else {
                ArrayList<Track> ts = Db.get(this).allTracks();
                ids = new ArrayList<String>();
                for (Track t : ts) ids.add(t.id);
            }
            queueIds = ids;
            queuePid = pid;
            cur = Db.get(this).get(id);
            if (cur != null) {
                index = queueIds.indexOf(id);
                if (index < 0 && idx >= 0) index = idx;
                broadcastState();
            }
        }
    }

    private void saveQueue() {
        App.prefs().edit()
                .putLong("queue_pid", queuePid)
                .putInt("queue_index", index)
                .putInt("mode", mode)
                .apply();
    }

    // ------------------------------------------------------------ playback

    public void setQueue(long pid, ArrayList<String> ids, String startId, boolean autoplay) {
        queuePid = pid;
        if (ids != null) queueIds = new ArrayList<String>(ids);
        index = startId == null ? 0 : queueIds.indexOf(startId);
        if (index < 0) index = 0;
        saveQueue();
        openIndex(index, autoplay);
    }

    public void openById(String id, boolean autoplay) {
        int i = queueIds.indexOf(id);
        if (i < 0) {
            queueIds = new ArrayList<String>();
            ArrayList<Track> all = Db.get(this).allTracks();
            for (Track t : all) queueIds.add(t.id);
            queuePid = -1;
            i = queueIds.indexOf(id);
        }
        if (i < 0) return;
        index = i;
        saveQueue();
        openIndex(index, autoplay);
    }

    private void openIndex(int i, boolean autoplay) {
        if (queueIds.isEmpty()) return;
        if (i < 0) i = 0;
        if (i >= queueIds.size()) i = queueIds.size() - 1;
        index = i;
        final String id = queueIds.get(i);
        Track t = Db.get(this).get(id);
        if (t == null) return;
        cur = t;
        App.prefs().edit().putString("last_id", id).apply();
        retry = 0;
        loading = true;
        playing = false;
        broadcastState();
        refreshNotify();
        requestFocus(new Runnable() {
            @Override
            public void run() {
                startSource(id);
            }
        });
    }

    private void requestFocus(final Runnable then) {
        try {
            int r = audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            if (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                then.run();
            } else {
                then.run();
            }
        } catch (Exception e) {
            then.run();
        }
    }

    private void startSource(final String id) {
        Track t = cur;
        if (t == null || !t.id.equals(id)) return;
        initPlayer();
        try {
            mp.reset();
        } catch (Exception e) {
            releasePlayer();
            initPlayer();
        }
        try {
            if (t.hasLocal()) {
                mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
                mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
                mp.setDataSource(t.path);
                mp.prepareAsync();
            } else {
                App.postNet(new Runnable() {
                    @Override
                    public void run() {
                        Track t2 = Db.get(PlaybackService.this).get(id);
                        if (t2 == null) return;
                        String url = null;
                        String err = null;
                        try {
                            url = (t2.streamUrl != null
                                    && System.currentTimeMillis() - t2.streamAt < 20 * 60 * 1000)
                                    ? t2.streamUrl : Bili.audioUrl(t2);
                        } catch (Exception e) {
                            err = e.getMessage();
                        }
                        final String fUrl = url;
                        final String fErr = err;
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (cur == null || !cur.id.equals(id)) return;
                                if (fUrl == null) {
                                    broadcastError(fErr);
                                    loading = false;
                                    playing = false;
                                    broadcastState();
                                    return;
                                }
                                try {
                                    Map<String, String> h = Bili.mediaHeaders(Bili.refererOf(cur));
                                    mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
                                    mp.setDataSource(getApplicationContext(), Uri.parse(fUrl), h);
                                    mp.prepareAsync();
                                } catch (Exception e) {
                                    Log.e(TAG, "setDataSource", e);
                                    broadcastError(e.getMessage());
                                    loading = false;
                                    broadcastState();
                                }
                            }
                        });
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "startSource", e);
            broadcastError(e.getMessage());
            loading = false;
            broadcastState();
        }
    }

    private final MediaPlayer.OnPreparedListener prepared = new MediaPlayer.OnPreparedListener() {
        @Override
        public void onPrepared(MediaPlayer m) {
            loading = false;
            retry = 0;
            if (cur != null && cur.id != null) {
                try {
                    int d = m.getDuration();
                    if (d > 0 && (cur.duration <= 0 || Math.abs(d - cur.duration) > 1000)) {
                        cur.duration = d;
                        Db.get(PlaybackService.this).setDuration(cur.id, d);
                    }
                } catch (Exception ignored) {
                }
            }
            doPlay();
            broadcastState();
            updateMetadata();
            refreshNotify();
            if (cur != null) {
                Db.get(PlaybackService.this).touchPlay(cur.id);
                CoverLoader.get(PlaybackService.this).prefetch(cur);
                maybeCacheNext();
            }
        }
    };

    private final MediaPlayer.OnCompletionListener completed = new MediaPlayer.OnCompletionListener() {
        @Override
        public void onCompletion(MediaPlayer m) {
            if (mode == MODE_SINGLE) {
                try {
                    m.seekTo(0);
                    m.start();
                    playing = true;
                    broadcastState();
                    return;
                } catch (Exception ignored) {
                }
            }
            playing = false;
            next(true);
        }
    };

    private final MediaPlayer.OnErrorListener errorListener = new MediaPlayer.OnErrorListener() {
        @Override
        public boolean onError(MediaPlayer m, int what, int extra) {
            Log.e(TAG, "player error " + what + "/" + extra);
            lastError = what;
            if (cur != null && !cur.hasLocal() && retry < 1) {
                retry++;
                cur.streamUrl = null;
                startSource(cur.id);
                return true;
            }
            loading = false;
            playing = false;
            broadcastError("播放失败 (" + what + ")");
            broadcastState();
            refreshNotify();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!playing) next(true);
                }
            }, 1500);
            return true;
        }
    };

    public void play() {
        if (cur == null && !queueIds.isEmpty()) {
            openIndex(index < 0 ? 0 : index, true);
            return;
        }
        if (loading) return;
        requestFocus(new Runnable() {
            @Override
            public void run() {
                doPlay();
            }
        });
    }

    private void doPlay() {
        if (mp == null) return;
        try {
            if (!playing) {
                mp.start();
                playing = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "start", e);
            playing = false;
        }
        broadcastState();
        refreshNotify();
        updateMetadata();
        tick();
    }

    public void pause() {
        if (mp != null && playing) {
            try {
                mp.pause();
            } catch (Exception ignored) {
            }
        }
        playing = false;
        broadcastState();
        refreshNotify();
        updateMetadata();
        try {
            audio.abandonAudioFocus(this);
        } catch (Exception ignored) {
        }
    }

    public void toggle() {
        if (playing) pause();
        else play();
    }

    public void next(boolean auto) {
        if (queueIds.isEmpty()) return;
        int n;
        if (mode == MODE_SHUFFLE && queueIds.size() > 1) {
            do {
                n = rnd.nextInt(queueIds.size());
            } while (n == index);
        } else {
            n = index + 1;
            if (n >= queueIds.size()) n = 0;
        }
        index = n;
        saveQueue();
        openIndex(n, true);
    }

    public void prev() {
        if (queueIds.isEmpty()) return;
        if (position() > 4000) {
            seek(0);
            return;
        }
        int n = index - 1;
        if (n < 0) n = queueIds.size() - 1;
        index = n;
        saveQueue();
        openIndex(n, true);
    }

    public void seek(int pos) {
        if (mp == null) return;
        try {
            int d = mp.getDuration();
            if (d > 0 && pos > d) pos = d;
            mp.seekTo(pos);
            updateMetadata();
            broadcastProgress();
        } catch (Exception ignored) {
        }
    }

    public void switchMode() {
        mode = (mode + 1) % 3;
        App.prefs().edit().putInt("mode", mode).apply();
        broadcastState();
    }

    private void stopSelf0() {
        pause();
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                stopForeground(true);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {
        }
        s = null;
        stopSelf();
    }

    private void maybeCacheNext() {
        if (!App.autoCacheNext() || queueIds.isEmpty()) return;
        if (mode == MODE_SINGLE) return;
        int n = index + 1;
        if (n >= queueIds.size()) {
            if (mode == MODE_SHUFFLE) return;
            n = 0;
        }
        if (n == index) return;
        final Track nt = Db.get(this).get(queueIds.get(n));
        if (nt == null || nt.hasLocal()) return;
        Downloader.start(this, nt);
    }

    // ------------------------------------------------------------- getters

    public Track current() {
        return cur;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isLoading() {
        return loading;
    }

    public int mode() {
        return mode;
    }

    public int position() {
        try {
            return mp == null ? 0 : mp.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    public int duration() {
        try {
            int d = mp == null ? 0 : mp.getDuration();
            if (d > 0) return d;
        } catch (Exception ignored) {
        }
        return cur == null ? 0 : (int) cur.duration;
    }

    public ArrayList<String> queue() {
        return queueIds;
    }

    // ------------------------------------------------------------ broadcast

    private boolean ticking = false;

    private void tick() {
        if (ticking) return;
        ticking = true;
        handler.postDelayed(ticker, 500);
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            ticking = false;
            broadcastProgress();
            if (Build.VERSION.SDK_INT >= 21 && session != null && false) {
                // reserved
            }
            if (playing) tick();
        }
    };

    private void broadcastProgress() {
        Intent i = new Intent(ACT_PROGRESS);
        i.setPackage(getPackageName());
        i.putExtra("playing", playing);
        i.putExtra("pos", position());
        i.putExtra("dur", duration());
        sendBroadcast(i);
    }

    private void broadcastState() {
        Intent i = new Intent(ACT_STATE);
        i.setPackage(getPackageName());
        i.putExtra("playing", playing);
        i.putExtra("loading", loading);
        i.putExtra("mode", mode);
        if (cur != null) {
            i.putExtra("id", cur.id);
            i.putExtra("title", cur.title);
            i.putExtra("author", cur.author);
            i.putExtra("cover", cur.cover);
            i.putExtra("dur", cur.duration);
            i.putExtra("cached", cur.hasLocal());
        }
        i.putExtra("pos", position());
        sendBroadcast(i);
        updateMetadata();
    }

    private void broadcastError(String msg) {
        Intent i = new Intent(ACT_STATE);
        i.setPackage(getPackageName());
        i.putExtra("playing", false);
        i.putExtra("loading", false);
        i.putExtra("error", msg == null ? "未知错误" : msg);
        i.putExtra("mode", mode);
        if (cur != null) {
            i.putExtra("id", cur.id);
            i.putExtra("title", cur.title);
            i.putExtra("author", cur.author);
            i.putExtra("cover", cur.cover);
        }
        sendBroadcast(i);
    }

    private void updateMetadata() {
        if (session == null || Build.VERSION.SDK_INT < 21) return;
        if (cur == null) return;
        MediaMetadata.Builder mb = new MediaMetadata.Builder();
        mb.putString(MediaMetadata.METADATA_KEY_TITLE, cur.title);
        mb.putString(MediaMetadata.METADATA_KEY_ARTIST, cur.author == null ? "" : cur.author);
        mb.putLong(MediaMetadata.METADATA_KEY_DURATION, duration());
        if (artCache != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artCache);
        session.setMetadata(mb.build());
        PlaybackState.Builder pb = new PlaybackState.Builder();
        int st = playing ? PlaybackState.STATE_PLAYING
                : (loading ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PAUSED);
        long acts = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_STOP;
        pb.setState(st, position(), playing ? 1f : 0f, System.currentTimeMillis());
        pb.setActions(acts);
        session.setPlaybackState(pb.build());
    }

    // ---------------------------------------------------------- notification

    private Notification buildNotify() {
        String title = cur == null ? "未播放" : (cur.title == null ? "未知" : cur.title);
        String sub = cur == null ? "BMusic" : (cur.author == null ? "BMusic" : cur.author);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            nb = new Notification.Builder(this, Notify.CHANNEL);
        } else {
            nb = new Notification.Builder(this);
        }
        nb.setSmallIcon(R.drawable.ic_notify);
        nb.setContentTitle(title);
        nb.setContentText(sub);
        nb.setSubText(playing ? "正在播放" : (loading ? "缓冲中" : "已暂停"));
        nb.setOngoing(playing);
        nb.setPriority(Notification.PRIORITY_LOW);
        nb.setCategory(Notification.CATEGORY_TRANSPORT);
        nb.setShowWhen(false);
        nb.setVisibility(Notification.VISIBILITY_PUBLIC);
        Intent content = new Intent(Intent.ACTION_VIEW, null, this, MainActivity.class);
        content.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        nb.setContentIntent(PendingIntent.getActivity(this, 3, content, piFlags()));
        Intent del = new Intent(this, PlaybackService.class);
        del.setAction(A_STOP);
        nb.setDeleteIntent(PendingIntent.getService(this, 4, del, piFlags()));

        nb.addAction(R.drawable.ic_prev, "上一首", pi(A_PREV, 1));
        int playIcon = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        nb.addAction(playIcon, playing ? "暂停" : "播放", pi(A_TOGGLE, 2));
        nb.addAction(R.drawable.ic_next, "下一首", pi(A_NEXT, 3));
        try {
            Notification.MediaStyle style = new Notification.MediaStyle();
            if (session != null) style.setMediaSession(session.getSessionToken());
            style.setShowActionsInCompactView(0, 1, 2);
            nb.setStyle(style);
        } catch (Throwable ignored) {
        }
        if (artCache != null) {
            nb.setLargeIcon(artCache);
        }
        return nb.build();
    }

    private PendingIntent pi(String action, int code) {
        Intent i = new Intent(this, PlaybackService.class);
        i.setAction(action);
        return PendingIntent.getService(this, code, i, piFlags());
    }

    /** Called from UI after the cover bitmap becomes available. */
    public void updateArt(String url) {
        if (cur == null || cur.cover == null || !cur.cover.equals(url)) return;
        App.postBg(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = CoverLoader.get(PlaybackService.this).cachedBitmap(cur.cover);
                if (b == null) return;
                artCache = b;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            android.app.NotificationManager nm =
                                    (android.app.NotificationManager)
                                            getSystemService(Context.NOTIFICATION_SERVICE);
                            nm.notify(Notify.ID, buildNotify());
                        } catch (Exception ignored) {
                        }
                        updateMetadata();
                    }
                });
            }
        });
    }

    private void refreshNotify() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(Notify.ID, buildNotify());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------- static helpers

    public static void toggle(Context c) {
        act(c, A_TOGGLE);
    }

    public static void next(Context c) {
        act(c, A_NEXT);
    }

    public static void prev(Context c) {
        act(c, A_PREV);
    }

    public static void stop(Context c) {
        act(c, A_STOP);
    }

    public static void open(Context c, String id) {
        Intent i = new Intent(c, PlaybackService.class);
        i.setAction(A_OPEN);
        i.putExtra("id", id);
        i.putExtra("auto", true);
        start(c, i);
    }

    public static void setQueue(Context c, long pid, ArrayList<String> ids, String startId) {
        Intent i = new Intent(c, PlaybackService.class);
        i.setAction(A_QUEUE);
        i.putExtra("pid", pid);
        i.putStringArrayListExtra("ids", ids);
        i.putExtra("start", startId);
        i.putExtra("auto", true);
        start(c, i);
    }

    private static void act(Context c, String action) {
        Intent i = new Intent(c, PlaybackService.class);
        i.setAction(action);
        start(c, i);
    }

    public static void append(Context c, String id) {
        Intent i = new Intent(c, PlaybackService.class);
        i.setAction(A_APPEND);
        i.putExtra("id", id);
        start(c, i);
    }

    private static void start(Context c, Intent i) {
        Log.i(TAG, "start command " + i.getAction());
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "startForegroundService failed", e);
            try {
                c.startService(i);
            } catch (Exception e2) {
                Log.e(TAG, "startService failed too", e2);
            }
        }
    }
}
