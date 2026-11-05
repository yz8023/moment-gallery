package com.moment.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.OverScroller;

/**
 * 可缩放的大图：整张图完整显示（不裁切，fit 居中），支持双指缩放 / 拖动 / 单双击 / 左右滑动。
 * 最大放大倍数默认 8 倍（相当于把原图像素放大到 1:1 之后还能再放大一点点），
 * 缩放倍数超过 {@link #HI_RES_THRESHOLD} 时回调宿主去换一张更高分辨率的位图。
 */
public class ZoomImageView extends ImageView {

    public interface Listener {
        void onTap();

        void onDoubleTap();

        void onSwipeLeft();

        void onSwipeRight();

        /** 是否处于放大状态（用来暂停自动轮播之类） */
        void onZoomChanged(boolean zoomed);

        /** 放大到需要更高清素材的程度 */
        void onNeedHiRes();
    }

    private static final float MAX_ZOOM = 8f;
    private static final float HI_RES_THRESHOLD = 1.7f;
    private static final float SNAP_BACK = 1.02f;

    private final Matrix baseMatrix = new Matrix();
    private final Matrix drawMatrix = new Matrix();
    private final float[] tmp = new float[9];
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;
    private final OverScroller scroller;
    private final int touchSlop;

    private float zoom = 1f;            // 相对 fit 的倍数
    private float lastX, lastY, downX, downY;
    private boolean dragging, pinching, moved, swipeHandled;
    private boolean hiResAsked;
    private Bitmap bitmap;
    private Listener listener;

    public ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        scroller = new OverScroller(c);

        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float factor = d.getScaleFactor();
                float target = Math.max(1f, Math.min(MAX_ZOOM, zoom * factor));
                factor = target / zoom;
                if (Math.abs(factor - 1f) < 0.0001f) return true;
                zoom = target;
                drawMatrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                constrain();
                setImageMatrix(drawMatrix);
                if (zoom > SNAP_BACK) notifyZoom(true);
                if (zoom > HI_RES_THRESHOLD && !hiResAsked) {
                    hiResAsked = true;
                    if (listener != null) listener.onNeedHiRes();
                }
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                pinching = false;
                if (zoom <= SNAP_BACK) {
                    zoom = 1f;
                    drawMatrix.set(baseMatrix);
                    setImageMatrix(drawMatrix);
                    notifyZoom(false);
                }
            }
        });

        tapDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (listener != null) listener.onDoubleTap();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null || zoom > SNAP_BACK || swipeHandled) return false;
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > touchSlop * 2 && Math.abs(vx) > Math.abs(vy)) {
                    swipeHandled = true;
                    if (dx < 0) {
                        if (listener != null) listener.onSwipeLeft();
                    } else {
                        if (listener != null) listener.onSwipeRight();
                    }
                    return true;
                }
                return false;
            }
        });
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 当前位图在视图坐标系里的实际显示矩形（用来对齐实况视频） */
    public void getBitmapRect(android.graphics.RectF out) {
        if (out == null) return;
        if (bitmap == null || bitmap.isRecycled()) {
            out.set(0, 0, getWidth(), getHeight());
            return;
        }
        android.graphics.Matrix m = new android.graphics.Matrix(getImageMatrix());
        out.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        m.mapRect(out);
    }

    public boolean isZoomed() {
        return zoom > SNAP_BACK;
    }

    public void resetZoom() {
        zoom = 1f;
        hiResAsked = false;
        drawMatrix.set(baseMatrix);
        setImageMatrix(drawMatrix);
        notifyZoom(false);
    }

    public void setBitmapCompat(Bitmap bm, boolean keepZoom) {
        float keep = keepZoom ? zoom : 1f;
        bitmap = bm;
        super.setImageBitmap(bm);
        if (!keepZoom) hiResAsked = false;
        if (getWidth() > 0 && getHeight() > 0) {
            buildBase();
            if (keep > SNAP_BACK) {
                zoom = Math.min(MAX_ZOOM, keep);
                drawMatrix.set(baseMatrix);
                float s = zoom;
                drawMatrix.postScale(s, s, getWidth() / 2f, getHeight() / 2f);
                constrain();
                setImageMatrix(drawMatrix);
            }
        }
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        setBitmapCompat(bm, false);
    }

    @Override
    public void setImageDrawable(Drawable d) {
        bitmap = null;
        super.setImageDrawable(d);
        if (getWidth() > 0 && getHeight() > 0) {
            buildBase();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        buildBase();
    }

    private void buildBase() {
        baseMatrix.reset();
        if (bitmap == null || bitmap.isRecycled() || getWidth() == 0 || getHeight() == 0) {
            drawMatrix.reset();
            setImageMatrix(drawMatrix);
            return;
        }
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        float vw = getWidth() - getPaddingLeft() - getPaddingRight();
        float vh = getHeight() - getPaddingTop() - getPaddingBottom();
        float s = Math.min(vw / bw, vh / bh);
        float dx = getPaddingLeft() + (vw - bw * s) / 2f;
        float dy = getPaddingTop() + (vh - bh * s) / 2f;
        baseMatrix.setScale(s, s);
        baseMatrix.postTranslate(dx, dy);

        if (zoom <= SNAP_BACK) {
            drawMatrix.set(baseMatrix);
        } else {
            // 换图 / 换分辨率时保持当前倍数
            drawMatrix.set(baseMatrix);
            drawMatrix.postScale(zoom, zoom, getWidth() / 2f, getHeight() / 2f);
            constrain();
        }
        setImageMatrix(drawMatrix);
    }

    /** 把画面限制在视图内：比视图大就允许拖动但不能拖出边，比视图小就居中。 */
    private void constrain() {
        if (bitmap == null || bitmap.isRecycled()) return;
        drawMatrix.getValues(tmp);
        float curScale = tmp[Matrix.MSCALE_X];
        if (curScale <= 0) return;
        float imgW = bitmap.getWidth() * curScale;
        float imgH = bitmap.getHeight() * curScale;
        float vw = getWidth() - getPaddingLeft() - getPaddingRight();
        float vh = getHeight() - getPaddingTop() - getPaddingBottom();

        float tx = tmp[Matrix.MTRANS_X];
        float ty = tmp[Matrix.MTRANS_Y];
        float dx, dy;
        if (imgW <= vw) {
            dx = (vw - imgW) / 2f + getPaddingLeft() - tx;
        } else {
            dx = Math.min(getPaddingLeft(), Math.max(vw - imgW + getPaddingLeft(), tx)) - tx;
        }
        if (imgH <= vh) {
            dy = (vh - imgH) / 2f + getPaddingTop() - ty;
        } else {
            dy = Math.min(getPaddingTop(), Math.max(vh - imgH + getPaddingTop(), ty)) - ty;
        }
        drawMatrix.postTranslate(dx, dy);
    }

    private void notifyZoom(boolean zoomed) {
        if (listener != null) listener.onZoomChanged(zoomed);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        tapDetector.onTouchEvent(ev);

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = downX = ev.getX();
                lastY = downY = ev.getY();
                dragging = false;
                moved = false;
                swipeHandled = false;
                pinching = ev.getPointerCount() > 1;
                if (!scroller.isFinished()) scroller.abortAnimation();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                pinching = true;
                moved = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (ev.getPointerCount() > 1) pinching = true;
                if (!pinching && isZoomed()) {
                    float x = ev.getX();
                    float y = ev.getY();
                    float dx = x - lastX;
                    float dy = y - lastY;
                    if (!dragging && Math.abs(dx) + Math.abs(dy) > touchSlop) dragging = true;
                    if (dragging) {
                        drawMatrix.postTranslate(dx, dy);
                        constrain();
                        setImageMatrix(drawMatrix);
                        moved = true;
                    }
                    lastX = x;
                    lastY = y;
                } else {
                    lastX = ev.getX();
                    lastY = ev.getY();
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                pinching = ev.getPointerCount() > 2;
                break;
            case MotionEvent.ACTION_UP:
                // 慢速拖动也能切图（手势不靠 fling 速度）
                if (!isZoomed() && !pinching && !swipeHandled) {
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (Math.abs(dx) > getWidth() * 0.16f && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        swipeHandled = true;
                        if (dx < 0) {
                            if (listener != null) listener.onSwipeLeft();
                        } else {
                            if (listener != null) listener.onSwipeRight();
                        }
                    }
                }
                dragging = false;
                pinching = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                pinching = false;
                return true;
        }
        return super.onTouchEvent(ev);
    }
}
