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
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
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
import android.widget.Toast;

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
    /** 回到 App 时：若上次是因音频焦点被抢而中断，则从断点续播 */
    public static final String A_RESUME = "bm.a.resume";
    public static final String A_APPEND = "bm.a.append";
    public static final String A_APPEND_IDS = "bm.a.append_ids";
    public static final String A_OPEN = "bm.a.open";
    public static final String A_QUEUE = "bm.a.queue";
    public static final String A_MODE = "bm.a.mode";
    /** add to the current queue (right after the playing track) and play it now */
    public static final String A_PLAY_NOW = "bm.a.playnow";
    /** replace the queue list without restarting what is already playing */
    public static final String A_QUEUE_KEEP = "bm.a.qkeep";
    /** edit the queue: remove (op 0) or move (op -1 / +1) one entry */
    public static final String A_QUEUE_EDIT = "bm.a.qedit";
    public static final String A_QUEUE_CLEAR = "bm.a.qclear";
    public static final String A_QUEUE_RM_IDS = "bm.a.qrids";

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
    private boolean mpReady = false;
    private boolean startWhenReady = true;
    private int pendingSeek = -1;
    private int lastError = 0;
    private int retry = 0;
    /** 离线播放轮询计数：连续跳过的未缓存曲目数（跑完一轮队列就停） */
    private int offlineSkip = 0;

    // ---- 音频焦点中断 / 断点续播 -----------------------------------------
    /** API 26+ 焦点请求：复用同一实例才能正确 abandon，并接收 delayed gain */
    private AudioFocusRequest focusRequest;
    /** 因别的 App（如 B 站视频）抢占焦点而暂停，焦点回来时要自动续播 */
    private boolean resumeOnFocusGain;
    /** 焦点请求被延迟（对方仍占着焦点）时暂存的动作，GAIN 后再执行 */
    private Runnable pendingFocusAction;
    /** 被短暂 duck（降低音量）中 */
    private boolean ducked;
    /** 服务被重建后要恢复到的断点（毫秒），-1 表示没有 */
    private int resumePos = -1;
    /** 进度轮询计数，每 10 次（约 5 秒）落盘一次播放位置 */
    private int tickCount;

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
                prev(intent.getBooleanExtra("force", false));
            } else if (A_RESUME.equals(a)) {
                // 回到 App 的兜底：仅当上次确实是「被别的 App 打断」才续播
                if (!playing && resumeOnFocusGain) {
                    resumeOnFocusGain = false;
                    play();
                }
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
            } else if (A_PLAY_NOW.equals(a)) {
                insertAndPlay(intent.getStringExtra("id"));
            } else if (A_QUEUE_KEEP.equals(a)) {
                ArrayList<String> qids = intent.getStringArrayListExtra("ids");
                replaceQueue(intent.getLongExtra("pid", -1), qids);
            } else if (A_QUEUE_EDIT.equals(a)) {
                editQueue(intent.getStringExtra("id"), intent.getIntExtra("op", 0));
            } else if (A_QUEUE_CLEAR.equals(a)) {
                clearQueue();
            } else if (A_QUEUE_RM_IDS.equals(a)) {
                removeIds(intent.getStringArrayListExtra("ids"));
            } else if (A_APPEND_IDS.equals(a)) {
                ArrayList<String> ids = intent.getStringArrayListExtra("ids");
                if (ids != null && !ids.isEmpty()) {
                    boolean wasIdle = (cur == null);
                    for (String id : ids) {
                        if (id != null && !queueIds.contains(id)) queueIds.add(id);
                    }
                    if (wasIdle) {
                        String first = ids.get(0);
                        if (queueIds.contains(first)) {
                            index = queueIds.indexOf(first);
                            Track t0 = Db.get(this).get(first);
                            if (t0 != null) {
                                cur = t0;
                                App.prefs().edit().putString("last_id", first).apply();
                                retry = 0;
                                loading = true;
                                startWhenReady = false; // 只加载不自动播
                                broadcastState();
                                refreshNotify();
                                requestFocus(new Runnable() {
                                    @Override
                                    public void run() {
                                        startSource(cur.id);
                                    }
                                });
                            }
                        }
                    }
                    saveQueue();
                }
        } else if (A_APPEND.equals(a)) {
            String id = intent.getStringExtra("id");
            if (id == null) return START_STICKY;
            boolean wasIdle = (cur == null);
            if (!queueIds.contains(id)) queueIds.add(id);
            if (wasIdle) {
                index = queueIds.indexOf(id);
                Track t = Db.get(this).get(id);
                if (t != null) {
                    cur = t;
                    App.prefs().edit().putString("last_id", id).apply();
                    retry = 0;
                    loading = true;
                    startWhenReady = false; // load the track but do NOT auto-play
                    broadcastState();
                    refreshNotify();
                    requestFocus(new Runnable() {
                        @Override
                        public void run() {
                            startSource(id);
                        }
                    });
                }
            }
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
        savePosition();
        abandonFocus();
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
        mpReady = false;
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
        // 进程被回收后，把上次中断时的播放位置带回来（仅当还是同一首）
        String posId = App.prefs().getString("last_pos_id", null);
        int pos = App.prefs().getInt("last_pos", -1);
        if (id != null && id.equals(posId) && pos > 0) resumePos = pos;
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

    /** Id of the playlist the current queue came from (-1 for "all tracks"). */
    public long queueId() {
        return queuePid;
    }

    /** Ids of the tracks currently in the queue, in play order. */
    public ArrayList<String> queueTrackIds() {
        return queueIds;
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
        offlineSkip = 0;
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

    /**
     * "立即播放": make sure the track is in the current queue (inserting it
     * right after the playing one when it is not), then start it.
     */
    private void insertAndPlay(String id) {
        if (id == null) return;
        int i = queueIds.indexOf(id);
        if (i < 0) {
            int at = index + 1;
            if (at < 0 || at > queueIds.size()) at = queueIds.size();
            queueIds.add(at, id);
            i = at;
        }
        index = i;
        saveQueue();
        openIndex(i, true);
    }

    /** Swap the whole queue list but keep the currently playing track playing. */
    private void replaceQueue(long pid, ArrayList<String> ids) {
        queuePid = pid;
        if (ids != null) queueIds = new ArrayList<String>(ids);
        String keep = cur != null ? cur.id : null;
        if (keep != null) {
            int i = queueIds.indexOf(keep);
            if (i >= 0) index = i;
        }
        if (index < 0 || index >= queueIds.size()) index = 0;
        saveQueue();
        broadcastState();
    }

    /** Remove (op 0) or move (op -1 / +1) one entry of the queue. */
    private void editQueue(String id, int op) {
        if (id == null) return;
        int i = queueIds.indexOf(id);
        if (i < 0) return;
        String curId = (index >= 0 && index < queueIds.size()) ? queueIds.get(index) : null;
        if (op == 0) {
            queueIds.remove(i);
        } else {
            int j = i + op;
            if (j < 0 || j >= queueIds.size()) return;
            queueIds.remove(i);
            queueIds.add(j, id);
        }
        index = curId != null ? queueIds.indexOf(curId) : 0;
        if (index < 0) index = 0;
        saveQueue();
        broadcastState();
    }

    /**
     * 清空整个队列：暂停播放并清掉全部条目（保留当前曲目信息用于展示，
     * 但队列空了之后切歌按钮自然失效）。
     */
    private void clearQueue() {
        if (queueIds.isEmpty() && cur == null) return;
        pause();
        queueIds.clear();
        index = 0;
        saveQueue();
        broadcastState();
    }

    /** 批量移除队列里的若干曲目（多选删除）。 */
    private void removeIds(java.util.ArrayList<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        String curId = (index >= 0 && index < queueIds.size()) ? queueIds.get(index) : null;
        queueIds.removeAll(ids);
        if (queueIds.isEmpty()) {
            pause();
            index = 0;
        } else {
            index = curId != null ? queueIds.indexOf(curId) : 0;
            if (index < 0) index = 0;
        }
        saveQueue();
        broadcastState();
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
        // 换歌 = 放弃旧断点
        App.prefs().edit().putString("last_id", id)
                .remove("last_pos").remove("last_pos_id").apply();
        resumePos = -1;
        retry = 0;
        loading = true;
        startWhenReady = autoplay;
        playing = false;
        autoCache(t);
        broadcastState();
        refreshNotify();
        requestFocus(new Runnable() {
            @Override
            public void run() {
                startSource(id);
            }
        });
    }

    /**
     * 自动开启缓存：开始播放一首未缓存的曲目时就地排队下载，
     * 不需要手动点「缓存到本地」。受「仅WiFi缓存」设置约束。
     */
    private void autoCache(Track t) {
        if (t == null || t.hasLocal()) return;
        if (!com.volcano.bmusic.util.Downloader.autoAllowed(this)) return;
        com.volcano.bmusic.util.Downloader.start(this, t);
    }

    /**
     * 申请音频焦点后再执行播放动作。
     * API 26+ 用 AudioFocusRequest 并开启 delayed gain：若此刻焦点被别的 App
     * （B 站视频、导航语音等）占着，不立刻出声，而是把动作挂起，等对方释放焦点
     * 时由 onAudioFocusChange(GAIN) 执行，从而实现「中断后能续回来」。
     */
    private void requestFocus(final Runnable then) {
        try {
            int r;
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusRequest == null) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build();
                    focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(attrs)
                            .setWillPauseWhenDucked(false) // 自己 duck，别让系统代我们暂停
                            .setAcceptsDelayedFocusGain(true)
                            .setOnAudioFocusChangeListener(this, handler)
                            .build();
                }
                r = audio.requestAudioFocus(focusRequest);
            } else {
                r = audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            }
            if (r == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
                Log.i(TAG, "audio focus delayed, waiting for GAIN");
                pendingFocusAction = then;
                resumeOnFocusGain = true;
                return;
            }
            pendingFocusAction = null;
            then.run();
        } catch (Exception e) {
            Log.e(TAG, "requestFocus", e);
            then.run();
        }
    }

    /** 只有用户主动暂停 / 停止 / 服务销毁时才放弃焦点，否则收不到回来的 GAIN。 */
    private void abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
            } else {
                audio.abandonAudioFocus(this);
            }
        } catch (Exception ignored) {
        }
        pendingFocusAction = null;
    }

    /** 短暂失去焦点但允许 duck 时压低音量，GAIN 后恢复。 */
    private void duck(boolean on) {
        if (mp == null) return;
        try {
            if (on && !ducked) {
                ducked = true;
                mp.setVolume(0.25f, 0.25f);
            } else if (!on && ducked) {
                ducked = false;
                mp.setVolume(1f, 1f);
            }
        } catch (Exception ignored) {
        }
    }

    /** 记住断点：被动中断、主动暂停、服务被回收时都能从原位置续播。 */
    private void savePosition() {
        try {
            if (cur == null) return;
            int p = position();
            if (p <= 0) return;
            App.prefs().edit()
                    .putString("last_pos_id", cur.id)
                    .putInt("last_pos", p)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------- offline skip

    private boolean isOffline() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni == null || !ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    /** 跳过当前未缓存曲目；整轮队列都跳过则停下。 */
    private void skipOffline() {
        offlineSkip++;
        if (offlineSkip >= queueIds.size()) {
            offlineSkip = 0;
            pause();
            toast("整个列表都没有缓存，已停止播放");
        } else {
            next(true);
        }
    }

    private void toast(final String s) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(PlaybackService.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startSource(final String id) {
        Track t = cur;
        if (t == null || !t.id.equals(id)) return;
        // 无网络且没缓存：提示后直接跳到下一首（整轮都没有就停）
        if (!t.hasLocal() && isOffline()) {
            toast("无网络，「" + t.playTitle() + "」未缓存，已跳过");
            skipOffline();
            return;
        }
        initPlayer();
        try {
            mp.reset();
        } catch (Exception e) {
            releasePlayer();
            initPlayer();
        }
        mpReady = false;
        // 服务重建后从中断处继续；正常切歌则从 0 开始
        if (resumePos >= 0 && t.id.equals(id)) {
            pendingSeek = resumePos;
            resumePos = -1;
        } else {
            pendingSeek = -1;
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
            offlineSkip = 0; // 成功起播，离线跳过计数归零
            mpReady = true;
            if (pendingSeek >= 0) {
                try {
                    m.seekTo(pendingSeek);
                } catch (Exception ignored) {
                }
                pendingSeek = -1;
            }
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
            if (startWhenReady) doPlay();
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
            if (mode == MODE_SINGLE && mpReady) {
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
            // -38 == operation invoked in an illegal state (e.g. seek while
            // still preparing, or a stray call right after reset). This is
            // transient and almost always recoverable, so just swallow it
            // instead of showing "播放失败" and skipping the track.
            if (what == -38 || extra == -38) {
                return true;
            }
            // 无网络时重试在线流没有意义：直接跳到下一首有缓存的
            if (cur != null && !cur.hasLocal() && isOffline()) {
                toast("无网络，「" + cur.playTitle() + "」未缓存，已跳过");
                skipOffline();
                return true;
            }
            lastError = what;
            if (cur != null && !cur.hasLocal() && retry < 3) {
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
        if (cur != null && (mp == null || !mpReady)) {
            // Player not prepared yet (e.g. right after an app restart, or the
            // track was only enqueued without autoplay): (re)prepare the current
            // track and play it once ready.
            loading = true;
            startWhenReady = true;
            broadcastState();
            requestFocus(new Runnable() {
                @Override
                public void run() {
                    startSource(cur.id);
                }
            });
            return;
        }
        requestFocus(new Runnable() {
            @Override
            public void run() {
                doPlay();
            }
        });
    }

    private void doPlay() {
        if (mp == null || !mpReady) return;
        // 播放器已就绪也要复核：本地文件可能已被清理（离线时无法重取流）
        if (cur != null && !cur.hasLocal() && isOffline()) {
            toast("无网络，「" + cur.playTitle() + "」未缓存，已跳过");
            skipOffline();
            return;
        }
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

    /** 用户主动暂停（按钮 / 拔耳机 / 停止）：放弃焦点，焦点回来也不自动续播。 */
    public void pause() {
        pauseInternal(true);
    }

    /** 被别的 App 抢占焦点而暂停：保留焦点注册，等 GAIN 自动从断点续播。 */
    private void pauseForFocus() {
        pauseInternal(false);
    }

    private void pauseInternal(boolean userInitiated) {
        if (mp != null && mpReady && playing) {
            try {
                mp.pause();
            } catch (Exception ignored) {
            }
        }
        playing = false;
        savePosition();
        duck(false);
        if (userInitiated) {
            resumeOnFocusGain = false;
            abandonFocus();
        }
        broadcastState();
        refreshNotify();
        updateMetadata();
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
        prev(false);
    }

    /**
     * @param force true 时无条件切上一首（桌面小组件用）；false 时若已播放超过
     *              4 秒则先回到本曲开头（通知栏 / 耳机按键的常见行为）。
     */
    public void prev(boolean force) {
        if (queueIds.isEmpty()) return;
        if (!force && position() > 4000) {
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
        if (!mpReady) {
            // remember and apply after prepare completes
            pendingSeek = pos;
            return;
        }
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
            if (mp == null || !mpReady) return 0;
            return mp.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    public int duration() {
        try {
            if (mp != null && mpReady) {
                int d = mp.getDuration();
                if (d > 0) return d;
            }
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
            // 每 10 个 tick（约 5 秒）落盘一次进度，进程被杀也能续播
            if (playing && ++tickCount % 10 == 0) savePosition();
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
        Log.i(TAG, "audioFocus change=" + focusChange + " playing=" + playing
                + " resume=" + resumeOnFocusGain);
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // 长期被占用（切去 B 站看视频）。注意不能 abandonAudioFocus：
                // 一旦放弃注册，对方释放焦点时就不会再回调 GAIN，也就永远续不回来。
                if (playing) resumeOnFocusGain = true;
                pauseForFocus();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // 短暂占用（语音、通知音）：结束后系统会给 GAIN，自动续播
                if (playing) resumeOnFocusGain = true;
                pauseForFocus();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                duck(true);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
                duck(false);
                if (pendingFocusAction != null) {
                    // 之前请求焦点时对方正占着，现在终于可以执行那次切歌/播放
                    Runnable r = pendingFocusAction;
                    pendingFocusAction = null;
                    resumeOnFocusGain = false;
                    r.run();
                } else if (resumeOnFocusGain) {
                    resumeOnFocusGain = false;
                    // MediaPlayer 仍停在暂停位置，start() 即为断点续播
                    Log.i(TAG, "resume playback after focus gain, pos=" + position());
                    play();
                }
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------- static helpers

    /**
     * 从别的 App（视频等）切回 BMusic 时调用：仅当上次是被音频焦点打断、
     * 且服务还活着时，重新申请焦点并从断点续播。服务没启动 / 用户主动暂停过
     * 都不会触发，避免「一打开 App 就自动出声」。
     */
    public static void resumeIfInterrupted(Context c) {
        PlaybackService svc = get();
        if (svc == null || svc.playing || !svc.resumeOnFocusGain) return;
        act(c, A_RESUME);
    }

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

    /** 立即播放：加入当前播放列表并马上播这首（已在列表中则直接切过去）。 */
    public static void playNow(Context c, String id) {
        Intent i = new Intent(c, PlaybackService.class);
        i.setAction(A_PLAY_NOW);
        i.putExtra("id", id);
        start(c, i);
    }

    /** 替换队列内容，但不打断正在播放的曲目。 */
    public static void replaceQueue(Context c, long pid, ArrayList<String> ids) {
        Intent i = new Intent(c, PlaybackService.class);
        i.setAction(A_QUEUE_KEEP);
        i.putExtra("pid", pid);
        i.putStringArrayListExtra("ids", ids);
        start(c, i);
    }

    /** 队列编辑：op = 0 移除，-1 上移，+1 下移。 */
    public static void editQueue(Context c, String id, int op) {
        Intent i = new Intent(c, PlaybackService.class);
        i.setAction(A_QUEUE_EDIT);
        i.putExtra("id", id);
        i.putExtra("op", op);
        start(c, i);
    }

    /** 清空整个播放队列。 */
    public static void clearQueue(Context c) {
        act(c, A_QUEUE_CLEAR);
    }

    /** 批量移除队列中的曲目（多选删除）。 */
    public static void removeIds(Context c, ArrayList<String> ids) {
        Intent i = new Intent(c, PlaybackService.class);
        i.setAction(A_QUEUE_RM_IDS);
        i.putStringArrayListExtra("ids", ids);
        start(c, i);
    }

    /**
     * 唤醒服务（不改变播放状态）：进程冷启动后 UI 先于服务跑起来时，
     * 用它让服务 onCreate -> restore() 把上次的队列/曲目恢复出来。
     * 无 action 的 intent 在 onStartCommand 里不会命中任何分支。
     */
    public static void wake(Context c) {
        Intent i = new Intent(c, PlaybackService.class);
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

    /** 批量加入播放队列（按顺序追加，不打断当前播放）。 */
    public static void appendAll(Context c, ArrayList<String> ids) {
        Intent i = new Intent(c, PlaybackService.class);
        i.setAction(A_APPEND_IDS);
        i.putStringArrayListExtra("ids", ids);
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
