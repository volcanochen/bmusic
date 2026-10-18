package com.volcano.bmusic.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.TextView;

import com.volcano.bmusic.R;

/**
 * 往复式跑马灯容器（HorizontalScrollView 方案，开源社区验证过的经典做法）：
 * 内部包一个 wrap_content 的 TextView——
 *  • 文字短：内容比视口窄，靠 FrameLayout 的 gravity 水平居中，天然居中；
 *  • 文字长：内容比视口宽，按真实时间驱动 HorizontalScrollView.scrollTo
 *    「向左滚出右边 → 停 → 向右滚回左边 → 停」，往复循环。
 * 滚动发生在滚动容器上而不是 TextView 上，完全不走 TextView 内部的
 * singleLine/gravity 渲染路径，各机型行为一致。
 * <p>
 * 用法：XML 里把 TextView 作为本容器的唯一子元素（保留原 id，代码无感）；
 * 文字变化通过子 TextView 的 TextWatcher 感知，自动重新评估。
 * 用 Handler + SystemClock 按真实时间推进（设备关动画时
 * animator_duration_scale=0 会把 ValueAnimator 瞬间播完，不能用）。
 */
public class MarqueeView extends HorizontalScrollView {

    /** 滚动速度：约 0.03 px/ms（≈30px/s），缓慢易读。 */
    private static final float SPEED_PX_PER_MS = 0.03f;
    /** 两端停顿时长 ms。 */
    private static final long DWELL_MS = 1500L;
    /** 开始滚动前的静置时长 ms：先让人看清开头。 */
    private static final long START_DELAY_MS = 2500L;
    /** 刷新间隔 ms。 */
    private static final long FRAME_MS = 16L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView tv;
    private Runnable ticker;
    /** 动画启动时的文字；同一文字被重复 bind 时不重启。 */
    private CharSequence runningText;
    /** 启动评估时内层的宽度；宽度变了（旋转/字号/复用换字）也要重评。 */
    private int runningContent;
    private long startMs;
    private long cycle;
    private int maxScroll;
    /** true = 放得下也靠左（列表行）；false = 放得下居中（播放页）。XML alignStart。 */
    private boolean alignStart;

    public MarqueeView(Context c) {
        super(c);
        init();
    }

    public MarqueeView(Context c, AttributeSet a) {
        super(c, a);
        init();
        android.content.res.TypedArray ta = c.obtainStyledAttributes(a, R.styleable.MarqueeView);
        alignStart = ta.getBoolean(R.styleable.MarqueeView_alignStart, false);
        ta.recycle();
    }

    public MarqueeView(Context c, AttributeSet a, int s) {
        super(c, a, s);
        init();
    }

    private void init() {
        setHorizontalScrollBarEnabled(false);
        setClipToPadding(true);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() > 0 && getChildAt(0) instanceof TextView) {
            tv = (TextView) getChildAt(0);
        } else {
            tv = new TextView(getContext());
            addView(tv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));
        }
        tv.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c2) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c2) {
                scheduleCheck();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
    }

    private TextView tv() {
        if (tv == null) onFinishInflate();
        return tv;
    }

    /** 透传：设置文字（内层 TextView）。 */
    public void setText(CharSequence cs) {
        tv().setText(cs);
    }

    /** 透传：当前文字。 */
    public CharSequence getText() {
        return tv().getText();
    }

    /** 内层 TextView（需要设置字号/颜色/图标等细粒度样式时用）。 */
    public TextView textView() {
        return tv();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // 布局完成后宽高才可靠；尺寸变化（旋转/分屏/字号）后重新评估
        if (changed) scheduleCheck();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnim();
        super.onDetachedFromWindow();
    }

    /**
     * 不消费触摸：HorizontalScrollView 默认会吃掉整个触摸序列，
     * 包在列表行里会让行点击（item click）失效。跑马灯是自动滚动，
     * 不需要手势，把触摸还给外层（列表行 / 迷你条）。
     * 播放页按钮的点击在内层 TextView（clickable=true）上，不受影响。
     */
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return false;
    }

    /**
     * 列表会高频重复 bind 同一行：文字没变、宽度也没变且已在滚动就不打断。
     * （宽度必须一起比：布局尺寸变化后旧评估作废，要重新判断居中/滚动。）
     */
    private void scheduleCheck() {
        if (ticker != null) {
            TextView t = tv();
            if (t != null && android.text.TextUtils.equals(runningText, t.getText())
                    && t.getWidth() == runningContent) {
                return;
            }
        }
        stopAnim();
        post(new Runnable() {
            @Override
            public void run() {
                startIfNeeded();
            }
        });
    }

    private void startIfNeeded() {
        stopAnim();                                     // 每次都重新评估
        TextView t = tv();
        if (!isShown() || t == null) return;
        CharSequence cur = t.getText();
        // ListView 复用行时，本方法可能跑在新文字的布局完成之前——
        // 此时 t.getLayout() 还是上一条内容的旧布局、getWidth() 是旧宽度，
        // 拿旧宽度判断会「该居中的误判成溢出（左对齐）/反之」，且误判后
        // runningText 已更新、防重入守卫会拦掉重评，错误状态固化。
        // 所以必须等内层用当前文字完成一次布局后再评估。
        if (t.getLayout() == null
                || !android.text.TextUtils.equals(t.getLayout().getText(), cur)) {
            post(new Runnable() {
                @Override
                public void run() {
                    startIfNeeded();
                }
            });
            return;
        }
        int avail = getWidth() - getPaddingLeft() - getPaddingRight();
        int content = t.getWidth();     // getWidth() 已含自身左右 padding
        if (avail <= 0 || content <= 0) {
            post(new Runnable() {                       // 内层还没量完，再等一轮
                @Override
                public void run() {
                    startIfNeeded();
                }
            });
            return;
        }
        if (content <= avail + 1) {
            scrollTo(0, 0);                             // 显示得下：gravity 居中或靠左
            setChildGravity(!alignStart);
            return;
        }
        setChildGravity(false);                         // 溢出：左对齐，滚出右边内容
        runningContent = content;
        maxScroll = content - avail + 1;
        long scrollMs = (long) (maxScroll / SPEED_PX_PER_MS);
        cycle = START_DELAY_MS + scrollMs * 2 + DWELL_MS * 2;
        startMs = SystemClock.uptimeMillis();
        runningText = t.getText();

        ticker = new Runnable() {
            @Override
            public void run() {
                if (ticker == null) return;             // 已被 stop
                long el = SystemClock.uptimeMillis() - startMs;
                long p = el % cycle;
                scrollTo(phase(p), 0);                  // HSV.scrollTo 自带重绘
                handler.postDelayed(this, FRAME_MS);
            }
        };
        handler.post(ticker);
    }

    /** 时间→scrollX：静置 → 向左滚 → 停 → 向右滚 → 停。 */
    private int phase(long t) {
        long scrollMs = (cycle - START_DELAY_MS - DWELL_MS * 2) / 2;
        if (t < START_DELAY_MS) return 0;               // 开头静置
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

    /**
     * 溢出滚动时子视图必须 start 对齐（gravity 居中会把内容推出可滚范围）。
     * 直接对比 LayoutParams 的实际 gravity：不依赖记忆字段——
     * XML 默认 layout_gravity 只有 center_vertical（水平靠左），
     * 若用「以为的状态」做守卫，初次居中会被误判为已居中而跳过，
     * 表现为列表里是否居中取决于该 View 的复用历史。
     */
    private void setChildGravity(boolean center) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tv().getLayoutParams();
        int want = center ? Gravity.CENTER
                : (Gravity.START | Gravity.CENTER_VERTICAL);
        if (lp.gravity == want) return;
        lp.gravity = want;
        tv().setLayoutParams(lp);
    }

    private void stopAnim() {
        if (ticker != null) {
            handler.removeCallbacks(ticker);
            ticker = null;
        }
        if (getScrollX() != 0) {
            scrollTo(0, 0);
        }
    }
}
