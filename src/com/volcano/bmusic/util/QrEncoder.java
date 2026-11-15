package com.volcano.bmusic.util;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;

import java.util.Arrays;

/** Render a QR code bitmap with the vendored ZXing encoder (no libraries). */
public final class QrEncoder {

    private static final int QUIET = 4;

    /** @param px desired bitmap size in pixels (at least one pixel per module) */
    public static Bitmap bitmap(String content, int px) throws WriterException {
        QRCode qr = Encoder.encode(content, ErrorCorrectionLevel.M);
        ByteMatrix m = qr.getMatrix();
        int n = m.getWidth();
        int cells = n + QUIET * 2;
        int size = Math.max(cells, px);
        int cell = size / cells;
        int offset = (size - cell * cells) / 2;
        int[] pix = new int[size * size];
        Arrays.fill(pix, Color.WHITE);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (m.get(x, y) != 1) continue;
                int left = offset + (x + QUIET) * cell;
                int top = offset + (y + QUIET) * cell;
                for (int dy = 0; dy < cell; dy++) {
                    int row = (top + dy) * size;
                    for (int dx = 0; dx < cell; dx++) {
                        pix[row + left + dx] = Color.BLACK;
                    }
                }
            }
        }
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        b.setPixels(pix, 0, size, 0, 0, size, size);
        return b;
    }
}
