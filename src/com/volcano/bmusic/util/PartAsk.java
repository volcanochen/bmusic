package com.volcano.bmusic.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

import com.volcano.bmusic.App;
import com.volcano.bmusic.api.Importer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Blocking "all parts or just this part" prompt.
 *
 * The import runs on a background thread while the dialog can only be shown on
 * the ui thread, so this posts the dialog and waits on a latch. Cancelling or
 * timing out means "only the shared part" — the safe default.
 */
public final class PartAsk {

    private static final long TIMEOUT_MS = 60000L;

    private PartAsk() {
    }

    /** @return true when the user picked every part. */
    public static boolean allOrCurrent(final Context c, final Importer.Candidate cand) {
        if (cand == null || cand.pageCount <= 1) return false;
        final int page = cand.page < 1 ? 1 : cand.page;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean all = new AtomicBoolean(false);
        App.runOnUi(new Runnable() {
            @Override
            public void run() {
                try {
                    if (c instanceof Activity && ((Activity) c).isFinishing()) {
                        latch.countDown();
                        return;
                    }
                    String title = (cand.title == null || cand.title.isEmpty())
                            ? "该视频" : cand.title;
                    new AlertDialog.Builder(c)
                            .setTitle(title)
                            .setMessage("该视频共 " + cand.pageCount + " 个分P，你分享的是第 " + page + " P")
                            .setPositiveButton("添加全部分P", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    all.set(true);
                                    latch.countDown();
                                }
                            })
                            .setNegativeButton("只添加第 " + page + " P", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    all.set(false);
                                    latch.countDown();
                                }
                            })
                            .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                @Override
                                public void onCancel(DialogInterface dialog) {
                                    all.set(false);
                                    latch.countDown();
                                }
                            })
                            .show();
                } catch (Exception e) {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return all.get();
    }
}
