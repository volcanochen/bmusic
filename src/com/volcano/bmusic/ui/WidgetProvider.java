package com.volcano.bmusic.ui;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.TypedValue;
import android.widget.RemoteViews;

import com.volcano.bmusic.R;
import com.volcano.bmusic.model.Track;
import com.volcano.bmusic.player.PlaybackService;

/**
 * 2x2 桌面小组件：左「上一首」/ 中「播放暂停」/ 右「下一首」+ 歌名/歌手两行信息。
 * 左右两个控件用 weight 撑满剩余宽度，形成大点击热区（AppWidget 的 RemoteViews
 * 不支持触摸手势，左右滑动由点击左右热区替代）；点文字或空白处进入播放页。
 * 图标与文字由服务的 ACT_STATE 广播实时同步；文字按长度分档缩放，
 * 保证每行至少 8 个字不出现省略号。
 */
public class WidgetProvider extends AppWidgetProvider {

    private static int piFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    /**
     * 把桌面点击转成服务命令。O+ 必须走前台服务启动，否则桌面点击会被系统拦截
     * （Background start not allowed）；O- 无此限制，用普通 getService。
     * 每个 action 用不同 requestCode，避免 PendingIntent.FLAG_UPDATE_CURRENT 互相覆盖。
     */
    private static PendingIntent actionPi(Context c, String action, int code, boolean forcePrev) {
        Intent i = new Intent(c, PlaybackService.class);
        i.setAction(action);
        if (forcePrev) i.putExtra("force", true);
        if (Build.VERSION.SDK_INT >= 26) {
            return PendingIntent.getForegroundService(c, code, i, piFlags());
        }
        return PendingIntent.getService(c, code, i, piFlags());
    }

    private static PendingIntent togglePi(Context c) {
        return actionPi(c, PlaybackService.A_TOGGLE, 11, false);
    }

    private static PendingIntent prevPi(Context c) {
        // 小组件上点「上一首」应当真的切歌，而不是像通知栏那样 >4s 回到开头
        return actionPi(c, PlaybackService.A_PREV, 13, true);
    }

    private static PendingIntent nextPi(Context c) {
        return actionPi(c, PlaybackService.A_NEXT, 14, false);
    }

    private static PendingIntent openPi(Context c) {
        Intent i = new Intent(c, PlayerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 12, i, piFlags());
    }

    private static RemoteViews build(Context c) {
        PlaybackService svc = PlaybackService.get();
        boolean playing = svc != null && svc.isPlaying();
        Track cur = svc != null ? svc.current() : null;
        String title = cur != null ? cur.playTitle() : c.getString(R.string.widget_not_playing);
        String sub = cur != null ? cur.subTitle() : c.getString(R.string.app_name);

        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_play);
        rv.setOnClickPendingIntent(R.id.widget_root, openPi(c));
        rv.setOnClickPendingIntent(R.id.widget_toggle, togglePi(c));
        rv.setOnClickPendingIntent(R.id.widget_prev, prevPi(c));
        rv.setOnClickPendingIntent(R.id.widget_next, nextPi(c));
        rv.setImageViewResource(R.id.widget_toggle,
                playing ? R.drawable.ic_pause : R.drawable.ic_play);
        rv.setTextViewText(R.id.widget_title, title);
        rv.setTextViewText(R.id.widget_sub, sub);
        // 字号自适应：保证 8 个全角字符能完整显示，不出现 "..."
        fitText(rv, R.id.widget_title, title, 15f, 9f);
        fitText(rv, R.id.widget_sub, sub, 12f, 8f);
        return rv;
    }

    /** 按文本长度给 RemoteViews 里的 TextView 选字号。 */
    private static void fitText(RemoteViews rv, int id, String s, float max, float min) {
        int len = s == null ? 0 : s.length();
        float sp;
        if (len <= 6) sp = max;
        else if (len <= 8) sp = max - 1f;
        else if (len <= 10) sp = max - 2f;
        else if (len <= 12) sp = min + 2f;
        else if (len <= 15) sp = min + 1f;
        else sp = min;
        rv.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sp);
    }

    private static void refreshAll(Context c) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(c, WidgetProvider.class));
        for (int id : ids) mgr.updateAppWidget(id, build(c));
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, build(context));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String a = intent == null ? null : intent.getAction();
        if (PlaybackService.ACT_STATE.equals(a)) {
            refreshAll(context);
        }
    }
}
