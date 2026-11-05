package com.moment.gallery;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

/**
 * 左侧竖排圆形缩略图轨道：当前项放大并带白色描边，其余压暗，
 * 可拖动 / 点击跳转，自动居中当前项，上下边缘做了渐隐。
 */
public class ThumbRailView extends View {

    public interface Listener {
        void onIndexPicked(int index);

        void onThumbNeeded(int index);
    }

    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF oval = new RectF();

    private final float density;
    private final float thumbSize;
    private final float activeSize;
    private final float itemHeight;
    private final float padTop = 0f;
    private final int touchSlop;

    private Bitmap[] thumbs = new Bitmap[0];
    private boolean[] requested = new boolean[0];
    private int count = 0;
    private int active = 0;
    private float scroll = 0f;
    private ValueAnimator scrollAnim;

    private float downY, lastY;
    private boolean dragging;

    private Listener listener;
    private Shader fadeTop, fadeBottom;
    private int fadeHeight = -1;
    private final int bgColor = Color.parseColor("#0A0A0B");

    public ThumbRailView(Context c) {
        this(c, null);
    }

    public ThumbRailView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        thumbSize = dp(30);
        activeSize = dp(40);
        itemHeight = dp(50);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1.4f));
        ringPaint.setColor(Color.WHITE);

        placeholderPaint.setColor(Color.parseColor("#1E1E22"));

        dimPaint.setColor(Color.parseColor("#99000000"));

        tickPaint.setColor(Color.WHITE);
        tickPaint.setStrokeWidth(dp(2f));
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    private float dp(float v) {
        return v * density;
    }

    /** 缩略图圆点在屏幕上的中心点，用来做「飞向缩略图」的过场 */
    public void getThumbAnchor(int index, float[] outScreen) {
        if (outScreen == null || outScreen.length < 2) return;
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        outScreen[0] = loc[0] + getWidth() / 2f + dp(4);
        // 当前项会被居中，所以它的落点就是轨道竖直中心；其它项按当前滚动位置算
        outScreen[1] = (index == active)
                ? loc[1] + getHeight() / 2f
                : loc[1] + index * itemHeight + itemHeight / 2f - scroll + padTop;
    }

    /** 缩略图尺寸（过场终点的视觉大小） */
    public float getAnchorSize() {
        return activeSize;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setPhotos(int n) {
        count = n;
        thumbs = new Bitmap[n];
        requested = new boolean[n];
        active = 0;
        scroll = 0f;
        invalidate();
    }

    public void setThumb(int index, Bitmap bm) {
        if (index >= 0 && index < count) {
            thumbs[index] = bm;
            postInvalidateOnAnimation();   // 合并同一帧内的多次刷新
        }
    }

    public void setActive(int index, boolean animate) {
        if (count == 0) return;
        active = Math.max(0, Math.min(count - 1, index));
        centerOn(active, animate);
        invalidate();
    }

    private float maxScroll() {
        return Math.max(0f, count * itemHeight - getHeight() + padTop * 2);
    }

    private int centerAttempts = 0;

    private void centerOn(final int index, boolean animate) {
        if (getHeight() == 0) {
            if (centerAttempts++ > 20) return;
            post(() -> centerOn(index, animate));
            return;
        }
        centerAttempts = 0;
        float target = index * itemHeight + itemHeight / 2f - getHeight() / 2f + padTop;
        target = Math.max(-padTop, Math.min(maxScroll() - padTop, target));
        if (scrollAnim != null) scrollAnim.cancel();
        if (!animate) {
            scroll = target;
            invalidate();
            return;
        }
        scrollAnim = ValueAnimator.ofFloat(scroll, target);
        scrollAnim.setDuration(420);
        scrollAnim.setInterpolator(new DecelerateInterpolator(2f));
        scrollAnim.addUpdateListener(anim -> {
            scroll = (float) anim.getAnimatedValue();
            invalidate();
        });
        scrollAnim.start();
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        if (count == 0 || getHeight() == 0) return;
        float cx = getWidth() / 2f + dp(4);
        float radiusActive = activeSize / 2f;

        for (int i = 0; i < count; i++) {
            float cy = i * itemHeight + itemHeight / 2f - scroll + padTop;
            if (cy < -itemHeight || cy > getHeight() + itemHeight) continue;
            boolean isActive = i == active;
            float r = isActive ? radiusActive : thumbSize / 2f;

            if (isActive) {
                canvas.drawCircle(cx, cy, r + dp(3f), ringPaint);
            }

            oval.set(cx - r, cy - r, cx + r, cy + r);
            canvas.save();
            clip.reset();
            clip.addCircle(cx, cy, r, Path.Direction.CW);
            canvas.clipPath(clip);

            Bitmap bm = thumbs[i];
            if (bm != null && !bm.isRecycled()) {
                float scale = Math.max(2 * r / bm.getWidth(), 2 * r / bm.getHeight());
                float w = bm.getWidth() * scale;
                float h = bm.getHeight() * scale;
                canvas.drawBitmap(bm, cx - w / 2f, cy - h / 2f, bmpPaint);
            } else {
                canvas.drawRect(oval, placeholderPaint);
                if (listener != null && !requested[i]) {
                    requested[i] = true;
                    final int idx = i;
                    post(() -> listener.onThumbNeeded(idx));
                }
            }
            if (!isActive) {
                canvas.drawCircle(cx, cy, r, dimPaint);
            }
            canvas.restore();
        }

        // 当前项左侧的小白条（播放头）
        float activeCy = active * itemHeight + itemHeight / 2f - scroll + padTop;
        if (activeCy > -itemHeight && activeCy < getHeight() + itemHeight) {
            canvas.drawLine(dp(2f), activeCy - dp(7f), dp(2f), activeCy + dp(7f), tickPaint);
        }

        // 上下渐隐（shader 缓存，避免每帧 new）
        if (fadeTop == null || fadeHeight != getHeight()) {
            fadeHeight = getHeight();
            fadeTop = new LinearGradient(0, 0, 0, dp(54), bgColor, 0x000A0A0B, Shader.TileMode.CLAMP);
            fadeBottom = new LinearGradient(0, fadeHeight - dp(54), 0, fadeHeight,
                    0x000A0A0B, bgColor, Shader.TileMode.CLAMP);
        }
        fadePaint.setShader(fadeTop);
        canvas.drawRect(0, 0, getWidth(), dp(54), fadePaint);
        fadePaint.setShader(fadeBottom);
        canvas.drawRect(0, fadeHeight - dp(54), getWidth(), fadeHeight, fadePaint);
        fadePaint.setShader(null);
    }

    // ------------------------------------------------------------------ 触摸

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        centerOn(active, false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = lastY = e.getY();
                dragging = false;
                if (scrollAnim != null) scrollAnim.cancel();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float y = e.getY();
                float dy = y - lastY;
                lastY = y;
                if (!dragging && Math.abs(y - downY) > touchSlop) dragging = true;
                if (dragging) {
                    scroll -= dy;
                    scroll = Math.max(-padTop, Math.min(maxScroll() - padTop, scroll));
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) {
                    int idx = (int) ((e.getY() + scroll - padTop) / itemHeight);
                    if (idx >= 0 && idx < count && listener != null) {
                        listener.onIndexPicked(idx);
                    }
                } else {
                    centerOn(active, true);
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }
}
