package com.volcano.bmusic.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.volcano.bmusic.R;

/** 独立的「关于」介绍页：应用说明、版本、开源地址（长按复制）。 */
public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_about);
        TextView version = (TextView) findViewById(R.id.version);
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            version.setText("v" + v);
        } catch (Exception e) {
            version.setText("");
        }
        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        TextView repo = (TextView) findViewById(R.id.repo);
        repo.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                try {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("bmusic",
                            "https://github.com/volcanochen/bmusic"));
                    Toast.makeText(AboutActivity.this, "链接已复制", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        repo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TextUtils.isEmpty(repo.getText())) return;
                Toast.makeText(AboutActivity.this,
                        "长按可复制链接", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
