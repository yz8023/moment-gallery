package com.moment.gallery;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 右侧快速滚动条：平时透明；快速滑动 / 按住时淡入，
 * 一条半透明轨道 + 一个更亮的滑块；按住滑块可以拖动跳转。
 */
public class FastScrollerView extends View {

    public interface Listener {
        void onScrub(float fraction);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;

    private float fraction = 0f;
    private boolean dragging;
    private boolean visible;
    private Listener listener;

    private final Runnable hideTask = () -> {
        if (!dragging) fadeOut();
    };

    public FastScrollerView(Context c) {
        this(c, null);
    }

    public FastScrollerView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        trackPaint.setColor(0x33FFFFFF);
        thumbPaint.setColor(0xCCFFFFFF);
        setAlpha(0f);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setFraction(float f) {
        fraction = Math.max(0f, Math.min(1f, f));
        invalidate();
    }

    /** 快速滑动时调用：淡入并在一段时间后自动淡出 */
    public void flash() {
        handler.removeCallbacks(hideTask);
        fadeIn();
        handler.postDelayed(hideTask, 900);
    }

    private void fadeIn() {
        if (!visible) {
            visible = true;
            animate().alpha(1f).setDuration(140).start();
        }
    }

    private void fadeOut() {
        visible = false;
        animate().alpha(0f).setDuration(220).start();
    }

    private float trackLeft() {
        return getWidth() - dp(7);
    }

    private float thumbHeight() {
        return dp(46);
    }

    private float thumbTop() {
        float h = getHeight() - dp(56) - thumbHeight();
        return dp(28) + (h * fraction);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = trackLeft();
        float top = dp(28);
        float bottom = getHeight() - dp(28);

        // 轨道
        rect.set(cx - dp(1.5f), top, cx + dp(1.5f), bottom);
        canvas.drawRoundRect(rect, dp(2), dp(2), trackPaint);

        // 滑块（按住时更亮更宽）
        float ty = thumbTop();
        rect.set(cx - dp(dragging ? 3.5f : 2.5f), ty,
                cx + dp(dragging ? 3.5f : 2.5f), ty + thumbHeight());
        canvas.drawRoundRect(rect, dp(6), dp(6), thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // 平时完全透明时不吃触摸，右边缘的点击/滑动照常交给列表
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && !visible && getAlpha() < 0.05f) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                handler.removeCallbacks(hideTask);
                fadeIn();
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                scrubTo(e.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                scrubTo(e.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                invalidate();
                handler.removeCallbacks(hideTask);
                handler.postDelayed(hideTask, 700);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void scrubTo(float y) {
        float top = dp(28);
        float bottom = getHeight() - dp(28) - thumbHeight();
        float f = (y - top) / Math.max(1f, bottom - top);
        fraction = Math.max(0f, Math.min(1f, f));
        invalidate();
        if (listener != null) listener.onScrub(fraction);
    }

    private float dp(float v) {
        return v * density;
    }
}
