package com.moment.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import java.io.File;

/**
 * 大图卡片（看图，不是幻灯片）。
 *
 * · 照片按原始比例完整显示（fit，不裁切），显示的就是这张图本身，没有任何进入放大动画；
 * · 换图只是一次很短的交叉淡入（160ms，无缩放无位移）；
 * · 支持双指缩放 / 拖动 / 单双击 / 左右滑动；
 * · 可播放实况图（Motion Photo）尾部的小视频，视频贴合照片实际显示区域。
 */
public class PhotoCardView extends FrameLayout {

    public interface Listener {
        void onTap();

        void onDoubleTap();

        void onSwipeLeft();

        void onSwipeRight();

        void onZoomChanged(boolean zoomed);

        void onNeedHiRes();

        void onLiveFinished();
    }

    private static final long FADE = 160L;

    private final float density;
    private final Layer layerA;
    private final Layer layerB;
    private Layer front;

    private Listener listener;

    // 可选的缓慢推近（默认关闭）
    private boolean kenBurns = false;
    private long kenBurnsDuration = 6000L;

    // 实况图
    private MediaPlayer player;
    private TextureView videoView;
    private boolean livePlaying;

    public PhotoCardView(Context c) {
        this(c, null);
    }

    public PhotoCardView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        layerA = new Layer(c);
        layerB = new Layer(c);
        addView(layerA);
        addView(layerB);
        layerA.img.setAlpha(0f);
        layerB.img.setAlpha(0f);
        front = layerA;
    }

    public void setListener(Listener l) {
        this.listener = l;
        layerA.bind(this);
        layerB.bind(this);
    }

    public void setKenBurnsEnabled(boolean on) {
        kenBurns = on;
        if (!on && front != null) {
            front.animate().cancel();
            front.setScaleX(1f);
            front.setScaleY(1f);
        }
    }

    public void setKenBurnsDuration(long ms) {
        kenBurnsDuration = ms;
    }

    public boolean isZoomed() {
        return front != null && front.img.isZoomed();
    }

    public void resetZoom() {
        layerA.img.resetZoom();
        layerB.img.resetZoom();
    }

    public void setHiRes(Bitmap bm) {
        if (bm == null || front == null) return;
        front.img.setBitmapCompat(bm, true);
    }

    /** 直接上屏；animate=true 时两个图层做一次 160ms 的交叉淡入（只有透明度，没有缩放/位移）。 */
    public void setBitmap(Bitmap bm, boolean animate) {
        if (bm == null) return;
        stopLive();

        final Layer in = front == layerA ? layerB : layerA;
        final Layer out = front;
        front = in;

        in.animate().cancel();
        out.animate().cancel();
        in.img.setBitmapCompat(bm, false);
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.setTranslationX(0f);
        in.setTranslationY(0f);

        if (!animate || out.img.getDrawable() == null) {
            in.img.setAlpha(1f);
            out.img.setAlpha(0f);
            out.img.setImageDrawable(null);
            in.bringToFront();
            startKenBurns(in);
            return;
        }

        in.img.setAlpha(0f);
        in.bringToFront();
        in.img.animate().alpha(1f).setDuration(FADE).start();
        out.img.animate().alpha(0f).setDuration(FADE).withEndAction(() -> out.img.setImageDrawable(null)).start();
        postDelayed(this::startKenBurnsIfNeeded, FADE);
    }

    private void startKenBurnsIfNeeded() {
        startKenBurns(front);
    }

    private void startKenBurns(final Layer layer) {
        if (!kenBurns || layer == null) {
            if (layer != null) {
                layer.setScaleX(1f);
                layer.setScaleY(1f);
            }
            return;
        }
        layer.setPivotX(getWidth() / 2f);
        layer.setPivotY(getHeight() / 2f);
        layer.setScaleX(1f);
        layer.setScaleY(1f);
        layer.animate()
                .scaleX(1.06f)
                .scaleY(1.06f)
                .setDuration(kenBurnsDuration)
                .setInterpolator(new LinearInterpolator())
                .start();
    }

    // ------------------------------------------------------------------ 实况图

    public boolean isLivePlaying() {
        return livePlaying;
    }

    /** 播放实况图内嵌视频（静音） */
    public void playLive(final File file, final Runnable onPrepared) {
        playVideo(null, file, true, onPrepared);
    }

    /** 播放视频：file 优先（实况图抽出来的），否则用 uri（相册里的视频） */
    public void playVideo(final android.net.Uri uri, final File file, final boolean muted,
                          final Runnable onPrepared) {
        stopLive();
        if ((file == null || !file.exists()) && uri == null) {
            if (listener != null) listener.onLiveFinished();
            return;
        }
        final Layer layer = front;
        if (layer.video == null) {
            TextureView tv = new TextureView(getContext());
            tv.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            tv.setAlpha(0f);
            layer.addView(tv);
            layer.video = tv;
        }
        videoView = layer.video;
        livePlaying = true;

        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                startPlayer(uri, file, muted, st, onPrepared);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });

        if (videoView.isAvailable()) {
            startPlayer(uri, file, muted, videoView.getSurfaceTexture(), onPrepared);
        }
    }

    private void startPlayer(android.net.Uri uri, File file, boolean muted, SurfaceTexture st,
                             final Runnable onPrepared) {
        if (st == null || !livePlaying || player != null) return;
        try {
            player = new MediaPlayer();
            player.setSurface(new Surface(st));
            if (file != null && file.exists()) {
                player.setDataSource(file.getPath());
            } else {
                player.setDataSource(getContext(), uri);
            }
            player.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
            player.setLooping(false);
            player.setOnPreparedListener(mp -> {
                if (!livePlaying) return;
                applyVideoFit(mp.getVideoWidth(), mp.getVideoHeight());
                mp.start();
                if (videoView != null) videoView.animate().alpha(1f).setDuration(160).start();
                if (onPrepared != null) onPrepared.run();
            });
            player.setOnCompletionListener(mp -> {
                if (videoView != null) videoView.animate().alpha(0f).setDuration(200).start();
                livePlaying = false;
                if (listener != null) listener.onLiveFinished();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                livePlaying = false;
                if (listener != null) listener.onLiveFinished();
                return true;
            });
            player.prepareAsync();
        } catch (Throwable t) {
            livePlaying = false;
            if (listener != null) listener.onLiveFinished();
        }
    }

    /** 视频按比例贴合「照片实际显示区域」，不拉伸、不裁切 */
    private void applyVideoFit(int vw, int vh) {
        if (videoView == null || vw <= 0 || vh <= 0) return;
        int w = videoView.getWidth();
        int h = videoView.getHeight();
        if (w == 0 || h == 0) return;

        RectF target = new RectF(0, 0, w, h);
        if (front != null) front.img.getBitmapRect(target);

        float scale = Math.min(target.width() / vw, target.height() / vh);
        float dx = target.left + (target.width() - vw * scale) / 2f;
        float dy = target.top + (target.height() - vh * scale) / 2f;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);
        videoView.setTransform(m);
    }

    public void stopLive() {
        livePlaying = false;
        if (player != null) {
            try {
                player.stop();
            } catch (Throwable ignored) {
            }
            try {
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
        if (videoView != null) {
            videoView.setAlpha(0f);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopLive();
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ 图层

    private final class Layer extends FrameLayout {
        final ZoomImageView img;
        TextureView video;

        Layer(Context c) {
            super(c);
            img = new ZoomImageView(c);
            img.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            addView(img);
            final float radius = 22f * density / 2f;
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            setClipToOutline(true);
        }

        void bind(final PhotoCardView host) {
            img.setListener(new ZoomImageView.Listener() {
                @Override
                public void onTap() {
                    if (host.listener != null && isFront()) host.listener.onTap();
                }

                @Override
                public void onDoubleTap() {
                    if (host.listener != null && isFront()) host.listener.onDoubleTap();
                }

                @Override
                public void onSwipeLeft() {
                    if (host.listener != null && isFront()) host.listener.onSwipeLeft();
                }

                @Override
                public void onSwipeRight() {
                    if (host.listener != null && isFront()) host.listener.onSwipeRight();
                }

                @Override
                public void onZoomChanged(boolean zoomed) {
                    if (host.listener != null && isFront()) host.listener.onZoomChanged(zoomed);
                }

                @Override
                public void onNeedHiRes() {
                    if (host.listener != null && isFront()) host.listener.onNeedHiRes();
                }
            });
        }

        boolean isFront() {
            return PhotoCardView.this.front == this;
        }
    }
}
