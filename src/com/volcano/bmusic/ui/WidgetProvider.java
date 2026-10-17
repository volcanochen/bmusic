package com.volcano.bmusic.ui;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

import com.volcano.bmusic.R;
import com.volcano.bmusic.player.PlaybackService;

/**
 * 2x2 桌面小组件：一个大号播放/暂停圆钮。
 * 点圆钮切换播放/暂停；点圆钮以外的空间（边框与四角）进入播放页。
 * 图标状态由服务的 ACT_STATE 广播实时同步（正在播显示暂停图标）。
 */
public class WidgetProvider extends AppWidgetProvider {

    private static int piFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    private static PendingIntent togglePi(Context c) {
        Intent i = new Intent(c, PlaybackService.class);
        i.setAction(PlaybackService.A_TOGGLE);
        // O+ 必须走前台服务启动，否则桌面点击会被系统拦截
        //（Background start not allowed）；O- 无此限制，用普通 getService。
        if (Build.VERSION.SDK_INT >= 26) {
            return PendingIntent.getForegroundService(c, 11, i, piFlags());
        }
        return PendingIntent.getService(c, 11, i, piFlags());
    }

    private static PendingIntent openPi(Context c) {
        Intent i = new Intent(c, PlayerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 12, i, piFlags());
    }

    private static RemoteViews build(Context c) {
        PlaybackService svc = PlaybackService.get();
        boolean playing = svc != null && svc.isPlaying();
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_play);
        rv.setOnClickPendingIntent(R.id.widget_root, openPi(c));
        rv.setOnClickPendingIntent(R.id.widget_toggle, togglePi(c));
        rv.setImageViewResource(R.id.widget_toggle,
                playing ? R.drawable.ic_pause : R.drawable.ic_play);
        return rv;
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
