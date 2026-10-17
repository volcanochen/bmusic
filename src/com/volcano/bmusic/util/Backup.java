package com.volcano.bmusic.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import com.volcano.bmusic.db.Db;
import com.volcano.bmusic.model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 数据备份/恢复：曲库、歌单、最近播放导出为 JSON（不含音频文件本体）。
 * 导出写到公共 Download 目录（API29+ 走 MediaStore，无需权限）。
 * 恢复是"合并"语义：已存在的曲目/歌单不覆盖。
 */
public final class Backup {

    private Backup() {
    }

    /** 生成备份 JSON，失败返回 null。 */
    public static String export(Context c) {
        Db db = Db.get(c);
        try {
            JSONObject o = new JSONObject();
            o.put("app", "bmusic");
            o.put("version", 1);
            o.put("exported", System.currentTimeMillis());

            JSONArray tracks = new JSONArray();
            for (Track t : db.allTracks()) {
                JSONObject t2 = new JSONObject();
                t2.put("id", t.id);
                t2.put("bvid", t.bvid);
                t2.put("cid", t.cid);
                t2.put("page", t.page);
                t2.put("title", t.title);
                t2.put("author", t.author);
                t2.put("song", t.song);
                t2.put("artist", t.artist);
                t2.put("album", t.album);
                t2.put("srcTitle", t.srcTitle);
                t2.put("cover", t.cover);
                t2.put("duration", t.duration);
                t2.put("added", t.added);
                t2.put("lastPlayed", t.lastPlayed);
                t2.put("plays", t.plays);
                t2.put("fav", t.fav);
                tracks.put(t2);
            }
            o.put("tracks", tracks);

            JSONArray pls = new JSONArray();
            for (Object[] p : db.playlistsFull()) {
                long pid = (Long) p[0];
                JSONObject p2 = new JSONObject();
                p2.put("name", p[1]);
                p2.put("created", p[2]);
                p2.put("kind", p[3]);
                JSONArray tids = new JSONArray();
                for (Track t : db.tracksOf(pid)) tids.put(t.id);
                p2.put("tracks", tids);
                pls.put(p2);
            }
            o.put("playlists", pls);

            JSONArray his = new JSONArray();
            for (Object[] h : db.historyAll()) {
                his.put(new JSONObject()
                        .put("tid", (String) h[0])
                        .put("played", (Long) h[1]));
            }
            o.put("history", his);
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 写到公共 Download 目录，返回用于展示的路径。 */
    public static String write(Context c, String json) throws Exception {
        String name = "bmusic_backup_"
                + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date())
                + ".json";
        byte[] data = json.getBytes("UTF-8");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
            Uri uri = c.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("insert failed");
            OutputStream os = c.getContentResolver().openOutputStream(uri);
            try {
                os.write(data);
            } finally {
                os.close();
            }
            return "下载/" + name;
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            dir.mkdirs();
            File f = new File(dir, name);
            FileOutputStream fo = new FileOutputStream(f);
            try {
                fo.write(data);
            } finally {
                fo.close();
            }
            return f.getAbsolutePath();
        }
    }

    /**
     * 从备份 JSON 恢复（合并语义）：已存在的曲目/歌单不覆盖。
     * 返回新导入的曲目数，-1 表示解析失败。
     */
    public static int restore(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!"bmusic".equals(o.optString("app"))) return -1;
            Db db = Db.get(c);
            int imported = 0;

            JSONArray tracks = o.optJSONArray("tracks");
            if (tracks != null) {
                for (int i = 0; i < tracks.length(); i++) {
                    JSONObject t2 = tracks.getJSONObject(i);
                    String id = t2.optString("id", null);
                    if (id == null || db.get(id) != null) continue;
                    Track t = new Track(id, t2.optString("bvid", ""),
                            t2.optLong("cid"), t2.optInt("page"));
                    t.title = t2.optString("title", null);
                    t.author = t2.optString("author", null);
                    t.cover = t2.optString("cover", null);
                    t.duration = t2.optLong("duration");
                    t.added = t2.optLong("added", System.currentTimeMillis());
                    t.lastPlayed = t2.optLong("lastPlayed", 0);
                    t.plays = t2.optInt("plays");
                    t.fav = t2.optBoolean("fav");
                    // 备份不含音频文件本体，恢复后一律视为未缓存
                    t.cached = false;
                    t.path = null;
                    db.upsert(t);
                    imported++;
                }
            }

            JSONArray pls = o.optJSONArray("playlists");
            if (pls != null) {
                java.util.ArrayList<Object[]> existing = db.playlistsFull();
                for (int i = 0; i < pls.length(); i++) {
                    JSONObject p = pls.getJSONObject(i);
                    String name = p.optString("name", "");
                    if (name.isEmpty()) continue;
                    long pid = -1;
                    for (Object[] e : existing) {
                        if (name.equals((String) e[1])) {
                            pid = (Long) e[0];
                            break;
                        }
                    }
                    if (pid < 0) pid = db.addPlaylist(name);
                    JSONArray tids = p.optJSONArray("tracks");
                    if (tids == null) continue;
                    java.util.ArrayList<String> in = new java.util.ArrayList<String>();
                    for (Track t : db.tracksOf(pid)) in.add(t.id);
                    for (int j = 0; j < tids.length(); j++) {
                        String tid = tids.optString(j, null);
                        if (tid == null || in.contains(tid)) continue;
                        db.addTo(pid, tid);
                        in.add(tid);
                    }
                }
            }

            JSONArray his = o.optJSONArray("history");
            if (his != null) {
                for (int i = 0; i < his.length(); i++) {
                    JSONObject h = his.getJSONObject(i);
                    String tid = h.optString("tid", null);
                    if (tid == null || db.get(tid) == null) continue;
                    db.insertHistory(tid, h.optLong("played"));
                }
            }
            return imported;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 用文件管理器打开缓存音频文件夹；打不开则弹窗展示路径。 */
    public static void openFolder(final Activity a) {
        final File dir = Cache.audioDir(a);
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(dir), "resource/folder");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.fromFile(dir), "application/x-directory");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
            new AlertDialog.Builder(a)
                    .setTitle("缓存文件夹")
                    .setMessage("未找到可用的文件管理器。\n缓存音频保存在：\n" + dir.getAbsolutePath())
                    .setPositiveButton("复制路径", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            try {
                                android.content.ClipboardManager cm =
                                        (android.content.ClipboardManager)
                                                a.getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                        "path", dir.getAbsolutePath()));
                                Toast.makeText(a, "路径已复制", Toast.LENGTH_SHORT).show();
                            } catch (Exception ignored) {
                            }
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        }
    }
}
