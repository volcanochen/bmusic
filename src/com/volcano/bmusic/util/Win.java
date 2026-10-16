package com.volcano.bmusic.util;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * Window geometry helpers.
 *
 * In multi-window (split-screen) and freeform (floating window) mode the app
 * window can be resized at any time and no longer matches the physical screen.
 * Everything that adapts the UI has to be measured against the *activity
 * window*, otherwise the content keeps the layout it was inflated with and gets
 * clipped at the new bounds.
 */
public final class Win {

    private Win() {
    }

    /** Window size in dp: index 0 = width, index 1 = height. */
    public static int[] sizeDp(Activity a) {
        float d = a.getResources().getDisplayMetrics().density;
        if (d <= 0f) d = 1f;

        if (Build.VERSION.SDK_INT >= 30) {
            WindowManager wm = a.getWindowManager();
            if (wm != null) {
                Rect b = wm.getCurrentWindowMetrics().getBounds();
                int w = Math.round(b.width() / d);
                int h = Math.round(b.height() / d);
                if (w > 0 && h > 0) return new int[]{w, h};
            }
        }

        // API 24+ multi-window: the activity configuration follows the *window*
        // (not the display), so these already describe what we are drawn into.
        Configuration c = a.getResources().getConfiguration();
        if (c.screenWidthDp > 0 && c.screenHeightDp > 0) {
            return new int[]{c.screenWidthDp, c.screenHeightDp};
        }

        // Last resort (very old / odd devices): the default display.
        DisplayMetrics dm = new DisplayMetrics();
        a.getWindowManager().getDefaultDisplay().getMetrics(dm);
        return new int[]{Math.round(dm.widthPixels / d), Math.round(dm.heightPixels / d)};
    }

    /** True when the window is wider than it is tall (landscape-ish). */
    public static boolean wide(Activity a) {
        int[] s = sizeDp(a);
        return s[0] > s[1];
    }
}
