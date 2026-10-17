package com.volcano.bmusic.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;

import com.volcano.bmusic.R;
import com.volcano.bmusic.util.QrDecoder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫码添加：摄像头预览逐帧送 QrDecoder（离线纯 Java 解码），
 * 识别到链接后 setResult 返回 MainActivity 导入。
 * 另提供「从相册识别」入口（decodeImageUri 供 MainActivity 复用）。
 */
public class ScanActivity extends Activity implements SurfaceHolder.Callback {

    public static final String EXTRA_TEXT = "text";
    private static final int REQ_IMG = 5001;
    private static final int REQ_CAM_PERM = 5002;

    private SurfaceView preview;
    private Camera camera;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private volatile boolean finished = false;
    private boolean surfaceReady = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_scan);
        preview = (SurfaceView) findViewById(R.id.scan_preview);
        preview.getHolder().addCallback(this);
        preview.getHolder().setKeepScreenOn(true);
        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_gallery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });
    }

    // ------------------------------------------------------------ camera

    private void openCamera() {
        if (hasCameraPermission() != 0) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM_PERM);
            return;
        }
        try {
            camera = Camera.open();
            Camera.Parameters p = camera.getParameters();
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                if (s.width > 1300) continue;
                if (best == null || Math.abs(s.width - 960) < Math.abs(best.width - 960)) best = s;
            }
            if (best != null) p.setPreviewSize(best.width, best.height);
            try {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } catch (Exception ignored) {
            }
            camera.setParameters(p);
            camera.setPreviewDisplay(preview.getHolder());
            camera.setPreviewCallback(previewCb);
            camera.startPreview();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开摄像头", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private int hasCameraPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) return -1;
        }
        return 0;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_CAM_PERM) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                if (surfaceReady) openCamera();
            } else {
                Toast.makeText(this, "没有摄像头权限，可用「从相册识别」", Toast.LENGTH_LONG).show();
            }
        }
    }

    private final Camera.PreviewCallback previewCb = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(final byte[] data, final Camera cam) {
            if (finished || !busy.compareAndSet(false, true)) return;
            Camera.Size s = cam.getParameters().getPreviewSize();
            final int w = s.width, h = s.height;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        // NV21 的 Y 平面就是灰度，隔行隔列抽样减负
                        int dw = w / 2, dh = h / 2;
                        int[] gray = new int[dw * dh];
                        for (int y = 0; y < dh; y++) {
                            int srcRow = y * 2 * w;
                            int dstRow = y * dw;
                            for (int x = 0; x < dw; x++) {
                                gray[dstRow + x] = data[srcRow + x * 2] & 0xFF;
                            }
                        }
                        String text = QrDecoder.decode(gray, dw, dh);
                        if (text != null && !finished) {
                            finished = true;
                            returnResult(text);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        busy.set(false);
                    }
                }
            });
        }
    };

    private void returnResult(String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent();
                i.putExtra(EXTRA_TEXT, text);
                setResult(RESULT_OK, i);
                finish();
            }
        });
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        openCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        releaseCamera();
    }

    // ------------------------------------------------------------ gallery

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "选择二维码图片"), REQ_IMG);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的图片选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMG || resultCode != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;
        Toast.makeText(this, "识别中…", Toast.LENGTH_SHORT).show();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                String text = decodeImageUri(ScanActivity.this, uri);
                if (text != null && !finished) {
                    finished = true;
                    returnResult(text);
                } else {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(ScanActivity.this,
                                    "未在图片中识别到二维码", Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        finished = true;
        pool.shutdown();
        releaseCamera();
        super.onDestroy();
    }

    // ------------------------------------------------------- image decode

    /**
     * 从图片 Uri 解二维码：按需降采样 → 依次尝试 0/90/180/270° 旋转。
     * 供 ScanActivity 相册识别与 MainActivity 的「从相册识别」复用。
     */
    public static String decodeImageUri(Context c, Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            java.io.InputStream is = c.getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(is, null, o);
            if (is != null) is.close();
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            int side = Math.max(o.outWidth, o.outHeight);
            while (side / (sample * 2) >= 900) sample *= 2;

            for (int rot : new int[]{0, 90, 180, 270}) {
                Bitmap bmp = loadSampled(c, uri, sample, rot);
                if (bmp == null) return null;
                String r = decodeBitmap(bmp);
                bmp.recycle();
                if (r != null) return r;
            }
            // 整体再缩小一档重试（二维码在小图里占比大时更稳）
            for (int rot : new int[]{0, 90, 180, 270}) {
                Bitmap bmp = loadSampled(c, uri, sample * 2, rot);
                if (bmp == null) return null;
                String r = decodeBitmap(bmp);
                bmp.recycle();
                if (r != null) return r;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap loadSampled(Context c, Uri uri, int sample, int rot) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            java.io.InputStream is = c.getContentResolver().openInputStream(uri);
            Bitmap raw = BitmapFactory.decodeStream(is, null, o);
            if (is != null) is.close();
            if (raw == null) return null;
            if (rot == 0) return raw;
            Matrix m = new Matrix();
            m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            if (r != raw) raw.recycle();
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String decodeBitmap(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int[] gray = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            gray[i] = (((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF)) / 3;
        }
        return QrDecoder.decode(gray, w, h);
    }
}
