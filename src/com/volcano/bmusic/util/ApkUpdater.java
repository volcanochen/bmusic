package com.volcano.bmusic.util;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.widget.Toast;

/**
 * Download the latest APK via the system DownloadManager and, once finished,
 * launch the package installer. Shared by the "我的" update button and the
 * dedicated update page so the logic exists in exactly one place.
 */
public final class ApkUpdater {

    private static final Handler handler = new Handler();
    private static long updateDlId = -1;

    private ApkUpdater() {
    }

    /** Enqueue the APK download and watch it to completion. */
    public static void download(Activity a, String url) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(a, "更新地址无效", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("BMusic 更新");
            req.setDescription("正在下载最新版本…");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setMimeType("application/vnd.android.package-archive");
            DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            updateDlId = dm.enqueue(req);
            Toast.makeText(a, "开始下载最新版本，完成后点击通知安装", Toast.LENGTH_SHORT).show();
            poll(a, dm);
        } catch (Exception e) {
            Toast.makeText(a, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void poll(final Activity a, final DownloadManager dm) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (updateDlId == -1 || a.isFinishing()) return;
                Cursor c = dm.query(new DownloadManager.Query().setFilterById(updateDlId));
                boolean done = false;
                if (c != null && c.moveToFirst()) {
                    int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (st == DownloadManager.STATUS_SUCCESSFUL) {
                        done = true;
                        Uri uri = dm.getUriForDownloadedFile(updateDlId);
                        updateDlId = -1;
                        install(a, uri);
                    } else if (st == DownloadManager.STATUS_FAILED) {
                        done = true;
                        updateDlId = -1;
                        Toast.makeText(a, "下载失败，请检查网络后重试", Toast.LENGTH_LONG).show();
                    }
                }
                if (c != null) c.close();
                if (!done && !a.isFinishing()) poll(a, dm);
            }
        }, 1500);
    }

    /** Launch the system installer for the given (DownloadManager) uri. */
    public static void install(Context c, Uri uri) {
        if (uri == null) {
            Toast.makeText(c, "下载文件不可用", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(c,
                    "请在系统设置里允许 BMusic「安装未知应用」后重试", Toast.LENGTH_LONG).show();
        }
    }
}
