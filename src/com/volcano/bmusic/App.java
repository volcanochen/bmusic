package com.volcano.bmusic;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.volcano.bmusic.api.Bili;
import com.volcano.bmusic.api.Importer;
import com.volcano.bmusic.db.Db;
import com.volcano.bmusic.net.Http;
import com.volcano.bmusic.util.Notify;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class App extends Application {

    private static App s;
    private SharedPreferences prefs;
    private Handler ui;
    private ExecutorService netPool;
    private ExecutorService bgPool;

    @Override
    public void onCreate() {
        super.onCreate();
        s = this;
        prefs = getSharedPreferences("bmusic", Context.MODE_PRIVATE);
        ui = new Handler(Looper.getMainLooper());
        netPool = Executors.newFixedThreadPool(3);
        bgPool = Executors.newFixedThreadPool(2);
        Http.init();
        Bili.attach(prefs);
        Notify.ensureChannel(this);
        // 开机后台整理曲库：把「导入的」和「单独添加的」里链接相同的重复行合并掉，
        // 升级到 v3 时 onUpgrade 已做一次，这里兜住手工改库/旧备份恢复的情况。
        postBg(new Runnable() {
            @Override
            public void run() {
                try {
                    int n = Db.get(App.this).dedup();
                    if (n > 0) {
                        Intent i = new Intent(Importer.ACT_ADDED);
                        i.setPackage(getPackageName());
                        i.putExtra("n", 0);
                        sendBroadcast(i);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    public static App get() {
        return s;
    }

    public static Context ctx() {
        return s;
    }

    public static Handler ui() {
        return s.ui;
    }

    public static SharedPreferences prefs() {
        return s.prefs;
    }

    public static void postNet(Runnable r) {
        s.netPool.execute(r);
    }

    public static void postBg(Runnable r) {
        s.bgPool.execute(r);
    }

    public static void runOnUi(Runnable r) {
        s.ui.post(r);
    }

    public static String getCookie() {
        return prefs().getString("cookie", "");
    }

    public static void setCookie(String v) {
        prefs().edit().putString("cookie", v).apply();
        Bili.setUserCookie(v);
    }

    // ---- bilibili account (qr-code login) ---------------------------------

    public static String biliName() {
        return prefs().getString("bili_uname", "");
    }

    public static long biliMid() {
        return prefs().getLong("bili_mid", 0);
    }

    public static void setBiliProfile(long mid, String uname) {
        prefs().edit().putLong("bili_mid", mid).putString("bili_uname", uname).apply();
    }

    public static String refreshToken() {
        return prefs().getString("bili_refresh_token", "");
    }

    public static void setRefreshToken(String v) {
        prefs().edit().putString("bili_refresh_token", v == null ? "" : v).apply();
    }

    public static long favFolderId() {
        return prefs().getLong("bili_fav_id", 0);
    }

    public static void setFavFolderId(long id) {
        prefs().edit().putLong("bili_fav_id", id).apply();
    }

    public static void logoutBili() {
        prefs().edit()
                .remove("bili_mid")
                .remove("bili_uname")
                .remove("bili_refresh_token")
                .remove("bili_fav_id")
                .remove("cookie")
                .apply();
        Bili.setUserCookie("");
        Bili.resetSession();
    }

    public static boolean autoCacheNext() {
        return prefs().getBoolean("auto_cache_next", true);
    }

    public static boolean wifiOnly() {
        return prefs().getBoolean("wifi_only", false);
    }
}
