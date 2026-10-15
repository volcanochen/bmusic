package com.volcano.bmusic;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.volcano.bmusic.api.Bili;
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
        Bili.setUserCookie(prefs.getString("cookie", ""));
        Notify.ensureChannel(this);
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

    public static boolean autoCacheNext() {
        return prefs().getBoolean("auto_cache_next", true);
    }

    public static boolean wifiOnly() {
        return prefs().getBoolean("wifi_only", false);
    }
}
