package com.volcano.bmusic.util;

import android.annotation.TargetApi;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

public final class Notify {

    public static final String CHANNEL = "bmusic_play";
    public static final int ID = 10086;

    private static boolean made = false;

    public static synchronized void ensureChannel(Context c) {
        if (made || Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "播放控制", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("正在播放的音频");
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
        made = true;
    }

    @TargetApi(Build.VERSION_CODES.O)
    public static boolean enabled(Context c) {
        if (Build.VERSION.SDK_INT < 33) return true;
        try {
            return c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return true;
        }
    }
}
