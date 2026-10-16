package com.volcano.bmusic.util;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import com.volcano.bmusic.App;
import com.volcano.bmusic.api.Bili;
import com.volcano.bmusic.net.Http;
import com.volcano.bmusic.model.Track;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Async cover loader with memory + disk cache. */
public class CoverLoader {

    public static final String ACT_COVER = "com.volcano.bmusic.COVER";

    private static CoverLoader s;

    private final LruCache<String, Bitmap> mem;
    private final CopyOnWriteArrayList<String> flying = new CopyOnWriteArrayList<String>();
    private final Context ctx;

    private CoverLoader(Context c) {
        ctx = c.getApplicationContext();
        int maxKb = 16 * 1024;
        mem = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap v) {
                return v.getByteCount() / 1024;
            }
        };
    }

    public static CoverLoader get(Context c) {
        if (s == null) s = new CoverLoader(c);
        return s;
    }

    public Bitmap cachedBitmap(String url) {
        if (url == null || url.isEmpty()) return null;
        Bitmap b = mem.get(url);
        if (b != null) return b;
        File f = Cache.coverFile(ctx, url);
        if (f.exists() && f.length() > 0) {
            Bitmap d = decode(f, 720);
            if (d != null) mem.put(url, d);
            return d;
        }
        return null;
    }

    /** Load remote cover into view; sets nothing when url is empty. */
    public void load(final ImageView v, final String url) {
        if (v == null) return;
        if (url == null || url.isEmpty()) return;
        Object tag = v.getTag();
        if (tag instanceof String && tag.equals(url)) {
            Bitmap b = cachedBitmap(url);
            if (b != null) {
                v.setImageBitmap(b);
                return;
            }
        }
        v.setTag(url);
        Bitmap b = cachedBitmap(url);
        if (b != null) {
            v.setImageBitmap(b);
            return;
        }
        final String key = url;
        App.postNet(new Runnable() {
            @Override
            public void run() {
                Bitmap got = fetch(key);
                if (got != null) {
                    final Bitmap finalB = got;
                    App.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            if (v.getTag() instanceof String && ((String) v.getTag()).equals(url)) {
                                v.setImageBitmap(finalB);
                            }
                        }
                    });
                }
            }
        });
    }

    /** Blocking fetch: disk then network. Call off main thread. */
    public Bitmap fetch(String url) {
        if (url == null || url.isEmpty()) return null;
        Bitmap b = mem.get(url);
        if (b != null) return b;
        File f = Cache.coverFile(ctx, url);
        if (!f.exists() || f.length() == 0) {
            if (flying.contains(url)) return null;
            flying.add(url);
            try {
                Map<String, String> h = new HashMap<String, String>();
                h.put("User-Agent", Http.UA);
                h.put("Referer", Http.REFERER);
                Http.download(url, f, h, null);
            } catch (Exception ignored) {
            } finally {
                flying.remove(url);
            }
        }
        if (f.exists() && f.length() > 0) {
            Bitmap d = decode(f, 720);
            if (d != null) {
                mem.put(url, d);
                Intent i = new Intent(ACT_COVER);
                i.setPackage(App.ctx().getPackageName());
                i.putExtra("url", url);
                App.ctx().sendBroadcast(i);
                return d;
            }
        }
        return null;
    }

    public static Bitmap decode(File f, int maxSide) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int w = o.outWidth;
            int h = o.outHeight;
            int sample = 1;
            int max = Math.max(w, h);
            while (max / sample > maxSide) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Fast box blur used for the blurred player background. */
    public static Bitmap blurred(Bitmap src, int radius) {
        if (src == null) return null;
        try {
            int scale = 8;
            Bitmap small = Bitmap.createScaledBitmap(src,
                    Math.max(1, src.getWidth() / scale),
                    Math.max(1, src.getHeight() / scale), true);
            return fastBlur(small, radius);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap fastBlur(Bitmap src, int radius) {
        if (radius < 1) radius = 1;
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pix = new int[w * h];
        src.getPixels(pix, 0, w, 0, 0, w, h);
        int wm = w - 1;
        int hm = h - 1;
        int wh = w * h;
        int div = radius + radius + 1;
        int[] r = new int[wh];
        int[] g = new int[wh];
        int[] b = new int[wh];
        int rsum, gsum, bsum, x, y, i, p, yp, yi, yw;
        int[] vmin = new int[Math.max(w, h)];
        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        int[] dv = new int[256 * divsum];
        for (i = 0; i < 256 * divsum; i++) dv[i] = (i / divsum);

        yw = yi = 0;
        int[][] stack = new int[div][3];
        int stackpointer;
        int stackstart;
        int[] sir;
        int rbs;
        int r1 = radius + 1;
        int routsum, goutsum, boutsum;
        int rinsum, ginsum, binsum;
        for (y = 0; y < h; y++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            for (i = -radius; i <= radius; i++) {
                p = pix[yi + Math.min(wm, Math.max(i, 0))];
                sir = stack[i + radius];
                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);
                rbs = r1 - Math.abs(i);
                rsum += sir[0] * rbs;
                gsum += sir[1] * rbs;
                bsum += sir[2] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }
            }
            stackpointer = radius;
            for (x = 0; x < w; x++) {
                r[yi] = dv[rsum];
                g[yi] = dv[gsum];
                b[yi] = dv[bsum];
                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;
                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];
                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];
                if (y == 0) {
                    vmin[x] = Math.min(x + radius + 1, wm);
                }
                p = pix[yw + vmin[x]];
                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);
                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];
                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;
                stackpointer = (stackpointer + 1) % div;
                sir = stack[(stackpointer) % div];
                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];
                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];
                yi++;
            }
            yw += w;
        }
        for (x = 0; x < w; x++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            yp = -radius * w;
            for (i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;
                sir = stack[i + radius];
                sir[0] = r[yi];
                sir[1] = g[yi];
                sir[2] = b[yi];
                rbs = r1 - Math.abs(i);
                rsum += r[yi] * rbs;
                gsum += g[yi] * rbs;
                bsum += b[yi] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }
            }
            yi = x;
            stackpointer = radius;
            for (y = 0; y < h; y++) {
                pix[yi] = (0xff000000 & pix[yi]) | (dv[rsum] << 16) | (dv[gsum] << 8) | dv[bsum];
                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;
                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];
                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];
                if (x == 0) vmin[y] = Math.min(y + r1, hm) * w;
                p = x + vmin[y];
                sir[0] = r[p];
                sir[1] = g[p];
                sir[2] = b[p];
                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];
                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;
                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer % div];
                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];
                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];
                yi += w;
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        out.setPixels(pix, 0, w, 0, 0, w, h);
        return out;
    }

    /** Ask the loader to warm up this track's cover in background. */
    public void prefetch(Track t) {
        if (t == null || t.cover == null || t.cover.isEmpty()) return;
        final String url = t.cover;
        App.postNet(new Runnable() {
            @Override
            public void run() {
                fetch(url);
            }
        });
    }
}
