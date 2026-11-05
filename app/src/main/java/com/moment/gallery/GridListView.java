package com.moment.gallery;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ListView;

/**
 * 平铺网格用的 ListView：双指捏合时把缩放系数交给宿主去改变「一行几列」，
 * 捏合过程中不滚动列表；单指行为跟普通列表一致。
 */
public class GridListView extends ListView {

    public interface PinchListener {
        void onPinch(float scaleFactor);

        void onPinchEnd();
    }

    private final ScaleGestureDetector detector;
    private PinchListener pinchListener;
    private boolean pinching;

    public GridListView(Context c) {
        this(c, null);
    }

    public GridListView(Context c, AttributeSet a) {
        super(c, a);
        setVerticalScrollBarEnabled(false);
        setClipToPadding(true);   // 顶部留白区不被瓦片覆盖
        setDivider(null);
        setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        detector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (pinchListener != null) pinchListener.onPinch(d.getScaleFactor());
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                pinching = false;
                if (pinchListener != null) pinchListener.onPinchEnd();
            }
        });
    }

    public void setPinchListener(PinchListener l) {
        pinchListener = l;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 从单指切到双指时，先给列表发个 CANCEL，避免它带着半截手势继续滚
        if (!pinching && ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            MotionEvent cancel = MotionEvent.obtain(ev);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            pinching = true;
        }
        detector.onTouchEvent(ev);
        if (pinching) {
            if (ev.getActionMasked() == MotionEvent.ACTION_UP
                    || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                pinching = false;
            }
            return true;    // 双指时只处理缩放
        }
        return super.dispatchTouchEvent(ev);
    }
}
