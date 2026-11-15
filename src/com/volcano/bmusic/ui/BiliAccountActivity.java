package com.volcano.bmusic.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.volcano.bmusic.App;
import com.volcano.bmusic.R;
import com.volcano.bmusic.api.Bili;
import com.volcano.bmusic.api.BiliAuth;
import com.volcano.bmusic.api.BiliFav;
import com.volcano.bmusic.util.QrEncoder;

/** QR-code login + "bmusic" favourite folder import. */
public class BiliAccountActivity extends Activity {

    /** Set by the "+" menu: start the folder import as soon as the account is ready. */
    public static final String EXTRA_AUTO_IMPORT = "auto_import";

    private TextView tvAccount, tvQrHint, tvFolder, tvProgress;
    private ImageView ivQr;
    private Button btnLogout, btnImport, btnRefreshQr, btnOpenApp;
    private View qrBox, folderBox, loginBox;

    private volatile boolean alive;
    /** bumped whenever a new QR code is generated, invalidating old pollers */
    private volatile long pollGen;
    /** the auth URL behind the current QR code; handed to the Bilibili app for one-tap auth */
    private volatile String qrUrl;
    /** a "+" menu import pending on login; cleared as soon as it starts */
    private boolean pendingAutoImport;
    /** QR bitmap size adapted to the current screen height (landscape needs a smaller one) */
    private int qrSidePx = 720;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_bili_account);
        alive = true;
        // Only a fresh creation honours the flag: a rotation must not start a second import.
        pendingAutoImport = b == null && getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_AUTO_IMPORT, false);
        tvAccount = (TextView) findViewById(R.id.tv_account);
        tvQrHint = (TextView) findViewById(R.id.tv_qr_hint);
        tvFolder = (TextView) findViewById(R.id.tv_folder);
        tvProgress = (TextView) findViewById(R.id.tv_progress);
        ivQr = (ImageView) findViewById(R.id.iv_qr);
        btnLogout = (Button) findViewById(R.id.btn_logout);
        btnImport = (Button) findViewById(R.id.btn_import);
        btnRefreshQr = (Button) findViewById(R.id.btn_refresh_qr);
        btnOpenApp = (Button) findViewById(R.id.btn_open_app);
        qrBox = findViewById(R.id.qr_box);
        folderBox = findViewById(R.id.folder_box);
        // landscape has a separate login card; portrait keeps the button inside qr_box
        loginBox = findViewById(R.id.login_box);
        fitQrSize();

        btnOpenApp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openInBiliApp();
            }
        });

        ((ImageButton) findViewById(R.id.back)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btnRefreshQr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                generateQr();
            }
        });
        btnLogout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(BiliAccountActivity.this)
                        .setTitle("退出登录")
                        .setMessage("清除本机保存的 B 站登录凭证？曲库和缓存不会受影响。")
                        .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                pollGen++;
                                BiliAuth.logout();
                                refresh();
                                Toast.makeText(BiliAccountActivity.this, "已退出登录", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        btnImport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doImport();
            }
        });
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The user confirms authorization inside the Bilibili app. The poller notices it within
        // ~2s, but refresh immediately when we regain focus so the UI never looks stale.
        if (Bili.loggedIn() != (qrBox.getVisibility() == View.GONE)) refresh();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        pollGen++;
        super.onDestroy();
    }

    /** Keep the whole QR code visible without scrolling on short/landscape screens. */
    private void fitQrSize() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int sideDp;
        if (dm.widthPixels > dm.heightPixels) {
            // layout-land puts the QR into its own column: no vertical squeeze at all
            sideDp = 150;
        } else {
            int availDp = (int) (dm.heightPixels / dm.density);
            sideDp = Math.min(240, Math.max(120, availDp - 265));
        }
        qrSidePx = (int) (sideDp * dm.density);
        ViewGroup.LayoutParams lp = ivQr.getLayoutParams();
        lp.width = qrSidePx;
        lp.height = qrSidePx;
        ivQr.setLayoutParams(lp);
    }

    /**
     * One-tap login: hand the QR auth URL to the Bilibili app's own web container.
     * The signed-in account is already known there, so the user only taps "确认".
     * No OAuth client_id / SDK is required; the QR poller picks up the result.
     */
    private void openInBiliApp() {
        final String url = qrUrl;
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "二维码还在生成，请稍候再试", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri deep = Uri.parse("bilibili://forward?-Btarget=" + Uri.encode(url));
        Intent i = new Intent(Intent.ACTION_VIEW, deep);
        i.setPackage("tv.danmaku.bili");
        try {
            startActivity(i);
            hint("在 B 站 App 点「确认」后返回，登录会自动完成");
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        // Some builds / forks expose the scheme without a fixed package name.
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, deep));
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        // No Bilibili app: the browser can still confirm if it is already signed in.
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        Toast.makeText(this, "未找到 B 站 App，请用另一台设备扫描下方二维码", Toast.LENGTH_LONG).show();
    }

    private void refresh() {
        final boolean logged = Bili.loggedIn();
        String name = App.biliName();
        tvAccount.setText(logged
                ? (name.isEmpty() ? "已登录 B 站账号" : "已登录：" + name)
                : "未登录");
        btnLogout.setVisibility(logged ? View.VISIBLE : View.GONE);
        btnOpenApp.setVisibility(logged ? View.GONE : View.VISIBLE);
        if (loginBox != null) loginBox.setVisibility(logged ? View.GONE : View.VISIBLE);
        qrBox.setVisibility(logged ? View.GONE : View.VISIBLE);
        folderBox.setVisibility(logged ? View.VISIBLE : View.GONE);
        if (logged) {
            qrUrl = null;
            if (pendingAutoImport) {
                // "+" → import: don't make the user press the button a second time
                pendingAutoImport = false;
                tvFolder.setText("正在从 bmusic 收藏夹导入...");
                doImport();
            } else {
                loadFolder();
            }
        } else {
            generateQr();
        }
    }

    // ------------------------------------------------------------ qr login

    private void generateQr() {
        final long gen = ++pollGen;
        qrUrl = null;
        hint("正在生成二维码...");
        App.postNet(new Runnable() {
            @Override
            public void run() {
                try {
                    final BiliAuth.Qr q = BiliAuth.generate();
                    final Bitmap bmp = QrEncoder.bitmap(q.url, qrSidePx);
                    App.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            if (!alive || gen != pollGen) return;
                            qrUrl = q.url;
                            ivQr.setImageBitmap(bmp);
                            hint("点上方按钮跳到 B 站 App 授权，或用另一台设备扫码");
                            startPoll(q.key, gen);
                        }
                    });
                } catch (final Exception e) {
                    App.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            if (!alive || gen != pollGen) return;
                            hint("二维码生成失败：" + msg(e));
                        }
                    });
                }
            }
        });
    }

    private void startPoll(final String key, final long gen) {
        App.postNet(new Runnable() {
            @Override
            public void run() {
                int fails = 0;
                for (int i = 0; i < 90; i++) {
                    if (!alive || gen != pollGen || Bili.loggedIn()) return;
                    try {
                        int st = BiliAuth.poll(key);
                        fails = 0;
                        if (st == BiliAuth.SUCCESS) {
                            App.runOnUi(new Runnable() {
                                @Override
                                public void run() {
                                    if (!alive || gen != pollGen) return;
                                    Toast.makeText(BiliAccountActivity.this, "登录成功", Toast.LENGTH_SHORT).show();
                                    refresh();
                                }
                            });
                            return;
                        }
                        if (st == BiliAuth.EXPIRED) {
                            hint("二维码已过期，点击「刷新二维码」重试");
                            return;
                        }
                        if (st == BiliAuth.SCANNED) hint("已扫码，请在 B 站 App 上点「确认」");
                        else hint("点上方按钮跳到 B 站 App 授权，或用另一台设备扫码");
                    } catch (final Exception e) {
                        if (++fails >= 3) {
                            hint("登录状态查询失败：" + msg(e));
                            return;
                        }
                    }
                    try {
                        Thread.sleep(2000);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void hint(final String s) {
        App.runOnUi(new Runnable() {
            @Override
            public void run() {
                if (alive) tvQrHint.setText(s);
            }
        });
    }

    // ------------------------------------------------------------- folder

    private void loadFolder() {
        App.postNet(new Runnable() {
            @Override
            public void run() {
                try {
                    final BiliAuth.Account a = BiliAuth.verify();
                    final BiliFav.Folder f = BiliFav.ensureFolder();
                    App.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            if (!alive) return;
                            if (a != null && a.name != null && !a.name.isEmpty()) {
                                tvAccount.setText("已登录：" + a.name);
                            }
                            tvFolder.setText("已就绪 · " + BiliFav.folderLabel(f));
                        }
                    });
                } catch (final Exception e) {
                    App.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            if (alive) tvFolder.setText("收藏夹读取失败：" + msg(e));
                        }
                    });
                }
            }
        });
    }

    private void doImport() {
        if (!Bili.loggedIn()) {
            Toast.makeText(this, "请先授权登录 B 站账号", Toast.LENGTH_SHORT).show();
            return;
        }
        final Context c = getApplicationContext();
        btnImport.setEnabled(false);
        tvProgress.setText("正在读取 bmusic 收藏夹...");
        App.postNet(new Runnable() {
            @Override
            public void run() {
                try {
                    final BiliFav.Folder f = BiliFav.ensureFolder();
                    final BiliFav.Result r = BiliFav.importAll(c, f.id, new BiliFav.Progress() {
                        @Override
                        public void on(final int done, final int total, final String title) {
                            App.runOnUi(new Runnable() {
                                @Override
                                public void run() {
                                    if (alive) tvProgress.setText("导入中 " + done + "/" + total + " · " + title);
                                }
                            });
                        }
                    });
                    App.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            if (!alive) return;
                            btnImport.setEnabled(true);
                            tvProgress.setText(BiliFav.summary(r));
                            tvFolder.setText("已就绪 · " + BiliFav.folderLabel(f));
                            Toast.makeText(BiliAccountActivity.this,
                                    BiliFav.summary(r), Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    App.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            if (!alive) return;
                            btnImport.setEnabled(true);
                            tvProgress.setText("导入失败：" + msg(e));
                            Toast.makeText(BiliAccountActivity.this,
                                    "导入失败：" + msg(e), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private static String msg(Exception e) {
        String s = e.getMessage();
        return s == null || s.isEmpty() ? "未知错误" : s;
    }
}
