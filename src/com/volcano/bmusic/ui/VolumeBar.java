package com.volcano.bmusic.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Vertical volume bar. Doubles as the volume display (filled portion = current
 * level) and the control (drag up/down to change). Level is 0..1.
 */
public class VolumeBar extends View {

    public interface OnVolumeChangeListener {
        void onVolume(float level);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float level = 0.5f;
    private OnVolumeChangeListener listener;

    public VolumeBar(Context c) {
        super(c);
        init();
    }

    public VolumeBar(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public VolumeBar(Context c, AttributeSet a, int s) {
        super(c, a, s);
        init();
    }

    private void init() {
        trackPaint.setColor(0x55F2F4FA);
        fillPaint.setColor(0xFFFB7299);
        thumbPaint.setColor(0xFFF2F4FA);
    }

    public void setLevel(float l) {
        level = Math.max(0f, Math.min(1f, l));
        invalidate();
    }

    public float getLevel() {
        return level;
    }

    public void setOnVolumeChangeListener(OnVolumeChangeListener l) {
        listener = l;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        int w = getWidth();
        int h = getHeight();
        int trackW = (int) (6 * d);
        int top = (int) (14 * d);
        int bottom = h - top;
        if (bottom <= top) return;
        float r = trackW / 2f;
        float left = (w - trackW) / 2f;
        RectF tr = new RectF(left, top, left + trackW, bottom);
        canvas.drawRoundRect(tr, r, r, trackPaint);
        int fh = (int) ((bottom - top) * level);
        if (fh > 0) {
            RectF fr = new RectF(left, bottom - fh, left + trackW, bottom);
            canvas.drawRoundRect(fr, r, r, fillPaint);
        }
        float ty = bottom - fh;
        canvas.drawCircle(w / 2f, ty, r + 3 * d, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getAction();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_UP) {
            float d = getResources().getDisplayMetrics().density;
            int top = (int) (14 * d);
            int bottom = getHeight() - top;
            if (bottom > top) {
                float l = (bottom - e.getY()) / (bottom - top);
                l = Math.max(0f, Math.min(1f, l));
                setLevel(l);
                if (listener != null) listener.onVolume(l);
            }
            return true;
        }
        return super.onTouchEvent(e);
    }
}
