package com.volcano.bmusic.ui;

import android.Manifest;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.volcano.bmusic.App;
import com.volcano.bmusic.R;
import com.volcano.bmusic.api.Bili;
import com.volcano.bmusic.util.Cache;
import com.volcano.bmusic.util.Downloader;

public class SettingsActivity extends Activity {

    private EditText cookie;
    private Switch swAuto;
    private Switch swWifi;
    private TextView tvUsage;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        cookie = (EditText) findViewById(R.id.cookie);
        swAuto = (Switch) findViewById(R.id.sw_auto);
        swWifi = (Switch) findViewById(R.id.sw_wifi);
        tvUsage = (TextView) findViewById(R.id.tv_usage);
        cookie.setText(App.getCookie());
        swAuto.setChecked(App.autoCacheNext());
        swWifi.setChecked(App.wifiOnly());

        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                finish();
            }
        });
        swAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                App.prefs().edit().putBoolean("auto_cache_next", checked).apply();
            }
        });
        swWifi.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                App.prefs().edit().putBoolean("wifi_only", checked).apply();
            }
        });
        ((Button) findViewById(R.id.btn_clear_audio)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                App.postBg(new Runnable() {
                    @Override
                    public void run() {
                        Cache.dropAudio(SettingsActivity.this);
                        com.volcano.bmusic.db.Db.get(SettingsActivity.this).execResetCache();
                        App.runOnUi(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(SettingsActivity.this, "已清除音频缓存",
                                        Toast.LENGTH_SHORT).show();
                                usage();
                            }
                        });
                    }
                });
            }
        });
        ((Button) findViewById(R.id.btn_clear_cover)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                App.postBg(new Runnable() {
                    @Override
                    public void run() {
                        Cache.dropCovers(SettingsActivity.this);
                        App.runOnUi(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(SettingsActivity.this, "已清除封面缓存",
                                        Toast.LENGTH_SHORT).show();
                                usage();
                            }
                        });
                    }
                });
            }
        });
        findViewById(R.id.btn_notify).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT < 33) {
                    Toast.makeText(SettingsActivity.this, "系统无需额外授权", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 78);
                } else {
                    Toast.makeText(SettingsActivity.this, "已授权", Toast.LENGTH_SHORT).show();
                }
            }
        });
        usage();
    }

    private void usage() {
        App.postBg(new Runnable() {
            @Override
            public void run() {
                final long a = Cache.audioSize(SettingsActivity.this);
                final long c = Cache.coverSize(SettingsActivity.this);
                App.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        tvUsage.setText("音频 " + Bili.fmtSize(a) + " · 封面 " + Bili.fmtSize(c));
                    }
                });
            }
        });
    }

    private void save() {
        String c = cookie.getText().toString().trim();
        App.setCookie(c);
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        save();
        super.onBackPressed();
    }
}
