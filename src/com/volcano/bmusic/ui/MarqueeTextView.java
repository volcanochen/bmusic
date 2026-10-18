package com.volcano.bmusic.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * 往复式跑马灯 TextView：文字超宽时先向左滚动显示右边的内容，
 * 到头停顿一会，再向右滚回显示左边的内容，如此往复。
 * 文字没超宽时不滚动、保持普通 TextView 行为（是否居中交给布局决定）。
 * <p>
 * 内置 marquee 只会向左循环，满足不了「往复」，所以自己驱动 scrollX。
 * 用 Handler + 系统时钟按真实时间推进（不能用 ValueAnimator——
 * 设备关动画时 animator_duration_scale=0 会把动画瞬间播完）。
 */
public class MarqueeTextView extends TextView {

    /** 滚动速度：约 0.03 px/ms（≈30px/s），缓慢易读。 */
    private static final float SPEED_PX_PER_MS = 0.03f;
    /** 两端停顿时长 ms。 */
    private static final long DWELL_MS = 1500L;
    /** 开始滚动前的静置时长 ms：先让人看清开头，再开始往复。 */
    private static final long START_DELAY_MS = 2500L;
    /** 刷新间隔 ms。 */
    private static final long FRAME_MS = 16L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker;
    /** 动画启动时的文字；同一文字被重复 bind 时不重启。 */
    private CharSequence runningText;
    private long startMs;
    private long cycle;
    private int maxScroll;

    public MarqueeTextView(Context c) {
        super(c);
    }

    public MarqueeTextView(Context c, AttributeSet a) {
        super(c, a);
    }

    public MarqueeTextView(Context c, AttributeSet a, int s) {
        super(c, a, s);
    }

    @Override
    protected void onTextChanged(CharSequence text, int start,
                                 int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        // 复用的行绑定新文字后重新判断是否需要滚动
        scheduleCheck();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // 布局完成后宽高/文字才可靠（attach 早期 getLayout 还是 null，post 会白跑）
        if (changed) startIfNeeded();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnim();
        super.onDetachedFromWindow();
    }

    /** 列表会高频重复 bind 同一行：文字没变且已在滚动就不打断。 */
    private void scheduleCheck() {
        if (ticker != null && android.text.TextUtils.equals(runningText, getText())) return;
        stopAnim();
        post(new Runnable() {
            @Override
            public void run() {
                startIfNeeded();
            }
        });
    }

    private void startIfNeeded() {
        stopAnim();                                     // 每次都重新评估（宽度/文字可能已变）
        if (getWindowVisibility() != VISIBLE || getVisibility() != VISIBLE) return;
        Layout l = getLayout();
        if (l == null || l.getLineCount() == 0) return;
        float textW = l.getLineWidth(0);
        int avail = getWidth() - getPaddingLeft() - getPaddingRight();
        if (avail <= 0 || textW <= avail + 1) return;   // 显示得下，不用滚

        maxScroll = (int) (textW - avail) + 1;
        long scrollMs = (long) (maxScroll / SPEED_PX_PER_MS);
        cycle = START_DELAY_MS + scrollMs * 2 + DWELL_MS * 2;
        startMs = SystemClock.uptimeMillis();
        runningText = getText();

        ticker = new Runnable() {
            @Override
            public void run() {
                if (ticker == null) return;             // 已被 stop
                long t = (SystemClock.uptimeMillis() - startMs) % cycle;
                setScrollX(phase(t));
                invalidate();                           // setScrollX 不触发重绘，手动来
                handler.postDelayed(this, FRAME_MS);
            }
        };
        handler.post(ticker);
    }

    /** 时间→scrollX：静置 → 向左滚 → 停 → 向右滚 → 停。 */
    private int phase(long t) {
        long scrollMs = (cycle - START_DELAY_MS - DWELL_MS * 2) / 2;
        if (t < START_DELAY_MS) {
            return 0;                                   // 开头静置
        }
        t -= START_DELAY_MS;
        if (t < scrollMs) {
            return (int) (maxScroll * t / scrollMs);    // 向左滚出右边内容
        }
        if (t < scrollMs + DWELL_MS) {
            return maxScroll;                           // 停，展示右边
        }
        if (t < scrollMs * 2 + DWELL_MS) {
            return (int) (maxScroll * (1f - (t - scrollMs - DWELL_MS)
                    / (float) scrollMs));               // 向右滚回左边内容
        }
        return 0;                                       // 停，展示左边
    }

    private void stopAnim() {
        if (ticker != null) {
            handler.removeCallbacks(ticker);
            ticker = null;
        }
        if (getScrollX() != 0) {
            setScrollX(0);
            invalidate();
        }
    }
}
