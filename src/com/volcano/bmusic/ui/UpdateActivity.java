package com.volcano.bmusic.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.volcano.bmusic.R;
import com.volcano.bmusic.util.ApkUpdater;

/**
 * "发现新版本" 介绍页：并列展示当前版本与最新版本、更新内容，
 * 提供「开始下载」拉起系统下载器并安装。由 MainActivity 检查到新版本后打开。
 */
public class UpdateActivity extends Activity {

    public static final String EXTRA_CUR = "cur";
    public static final String EXTRA_NEW = "new";
    public static final String EXTRA_NOTES = "notes";
    public static final String EXTRA_URL = "url";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_update);

        String cur = getIntent().getStringExtra(EXTRA_CUR);
        String newv = getIntent().getStringExtra(EXTRA_NEW);
        String notes = getIntent().getStringExtra(EXTRA_NOTES);
        final String url = getIntent().getStringExtra(EXTRA_URL);

        ((TextView) findViewById(R.id.old_ver)).setText(cur == null ? "" : cur);
        ((TextView) findViewById(R.id.new_ver)).setText(newv == null ? "" : newv);
        TextView notesView = (TextView) findViewById(R.id.notes);
        if (TextUtils.isEmpty(notes)) {
            notesView.setText("（暂无更新说明）");
        } else {
            // 把 "\n" / "；" 规整成可读的多行说明
            notesView.setText(notes.replace("\\n", "\n").replace("；", "；\n"));
        }

        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_later).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        ((Button) findViewById(R.id.btn_download)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TextUtils.isEmpty(url)) {
                    Toast.makeText(UpdateActivity.this, "更新地址无效", Toast.LENGTH_SHORT).show();
                    return;
                }
                ApkUpdater.download(UpdateActivity.this, url);
                finish();
            }
        });
    }
}
