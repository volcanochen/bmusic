package com.volcano.bmusic.util;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

/**
 * QR 解码入口：内部使用 ZXing core 的 QR 解码子集（Apache License 2.0，
 * 源码 vendored 在 src/com/google/zxing/，tag zxing-3.3.3，仅含 QR 解码所需类）。
 * <p>
 * 旧的自研解码器只支持 v1-10 且识别率不足，已替换；对外签名保持不变——
 * 传入 0-255 灰度 int[]，返回文本或 null。依次尝试：
 * 全局直方图二值化 → 局部自适应(Hybrid) → 反色（白底黑码 / 深底白码都覆盖）。
 * <p>
 * 纯 Java（无 android.* import）：仍可在 JVM 上直接单测。
 */
public final class QrDecoder {

    private QrDecoder() {
    }

    /** @return 解码文本；识别失败返回 null。 */
    public static String decode(int[] gray, int w, int h) {
        if (gray == null || w < 21 || h < 21 || w * h > 2500000) return null;
        try {
            LuminanceSource src = new GraySource(gray, w, h);
            String r = run(new BinaryBitmap(new GlobalHistogramBinarizer(src)));
            if (r == null) r = run(new BinaryBitmap(new HybridBinarizer(src)));
            if (r == null) r = run(new BinaryBitmap(new GlobalHistogramBinarizer(src.invert())));
            if (r == null) r = run(new BinaryBitmap(new HybridBinarizer(src.invert())));
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String run(BinaryBitmap bm) {
        try {
            return new QRCodeReader().decode(bm).getText();
        } catch (ReaderException e) {
            return null;
        }
    }

    /** 把项目里现成的 0-255 灰度 int[] 适配成 ZXing 的 LuminanceSource。 */
    private static final class GraySource extends LuminanceSource {
        private final int[] gray;

        GraySource(int[] gray, int w, int h) {
            super(w, h);
            this.gray = gray;
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            int w = getWidth();
            if (row == null || row.length < w) row = new byte[w];
            int off = y * w;
            for (int x = 0; x < w; x++) row[x] = (byte) gray[off + x];
            return row;
        }

        @Override
        public byte[] getMatrix() {
            int w = getWidth(), h = getHeight();
            byte[] m = new byte[w * h];
            for (int i = 0; i < m.length; i++) m[i] = (byte) gray[i];
            return m;
        }

        @Override
        public boolean isCropSupported() {
            return true;
        }

        @Override
        public LuminanceSource crop(int left, int top, int cw, int ch) {
            int w = getWidth();
            int[] g = new int[cw * ch];
            for (int y = 0; y < ch; y++) {
                System.arraycopy(gray, (top + y) * w + left, g, y * cw, cw);
            }
            return new GraySource(g, cw, ch);
        }
    }
}
