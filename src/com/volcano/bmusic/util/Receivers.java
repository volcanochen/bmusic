package com.volcano.bmusic.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;

/**
 * Version-safe receiver registration.
 *
 * On Android 13 (API 33) and above, {@link Context#registerReceiver} requires
 * an explicit RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED flag for any receiver
 * that is not registered exclusively for system broadcasts, otherwise it throws
 * a SecurityException. All of our receivers only listen for in-app broadcasts
 * (and, in the service, a system broadcast), so RECEIVER_NOT_EXPORTED is correct.
 */
public final class Receivers {
    private Receivers() {
    }

    /** Register {@code r} for in-app broadcasts only (never exported). */
    public static void registerLocal(Context c, BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) {
            // Constant is inlined at compile time; this branch only runs on API 33+.
            c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            c.registerReceiver(r, f);
        }
    }
}
