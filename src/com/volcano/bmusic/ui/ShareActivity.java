package com.volcano.bmusic.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import com.volcano.bmusic.App;
import com.volcano.bmusic.api.Importer;
import com.volcano.bmusic.db.Db;
import com.volcano.bmusic.model.Track;
import com.volcano.bmusic.player.PlaybackService;
import com.volcano.bmusic.util.PartAsk;

import java.util.ArrayList;
import java.util.List;

/** Receives "share to" intents from Bilibili. */
public class ShareActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final String text = extract();
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "没有收到链接", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        final String[] items = {"立即播放", "添加到曲库", "播放列表中下一首"};
        new AlertDialog.Builder(this)
                .setTitle("BMusic")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final int choice = which;
                        final AlertDialog wait = new AlertDialog.Builder(ShareActivity.this)
                                .setMessage("正在解析...")
                                .setCancelable(false).show();
                        Importer.add(ShareActivity.this, text, new Importer.Chooser() {
                            @Override
                            public boolean askAll(Importer.Candidate cand) {
                                return PartAsk.allOrCurrent(ShareActivity.this, cand);
                            }
                        }, new Importer.Callback() {
                            @Override
                            public void done(List<Track> added, String failMsg) {
                                try {
                                    wait.dismiss();
                                } catch (Exception ignored) {
                                }
                                if (added.isEmpty()) {
                                    Toast.makeText(ShareActivity.this,
                                            "解析失败：" + failMsg, Toast.LENGTH_LONG).show();
                                    finish();
                                    return;
                                }
                                ArrayList<String> ids = new ArrayList<String>();
                                for (Track t : added) ids.add(t.id);
                                for (Track t : Db.get(ShareActivity.this).allTracks()) {
                                    if (!ids.contains(t.id)) ids.add(t.id);
                                }
                                if (choice == 2) {
                                    PlaybackService.append(ShareActivity.this, added.get(0).id);
                                } else if (choice == 0) {
                                    PlaybackService.setQueue(ShareActivity.this, -1, ids, added.get(0).id);
                                }
                                Toast.makeText(ShareActivity.this,
                                        added.size() > 1 ? ("已添加 " + added.size() + " 首到曲库")
                                                : (choice == 0 ? "开始播放" : "已添加到曲库"),
                                        Toast.LENGTH_SHORT).show();
                                finish();
                            }
                        });
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        finish();
                    }
                })
                .show();
    }

    private String extract() {
        Intent i = getIntent();
        if (i == null) return null;
        String t = Intent.ACTION_SEND.equals(i.getAction()) ? i.getStringExtra(Intent.EXTRA_TEXT) : null;
        if (TextUtils.isEmpty(t) && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            t = i.getData().toString();
        }
        if (TextUtils.isEmpty(t) && i.getCharSequenceExtra(Intent.EXTRA_TEXT) != null) {
            t = i.getCharSequenceExtra(Intent.EXTRA_TEXT).toString();
        }
        return t;
    }
}
