package com.moment.gallery;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** 相册选择页：深色列表，显示封面、相册名与照片数量。 */
public class AlbumsActivity extends Activity {

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0A0B);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 顶部栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(26), dp(20), dp(10));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setPadding(dp(10), dp(10), dp(10), dp(10));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView t = new TextView(this);
        t.setText("相册");
        t.setTextColor(0xFFF5F5F7);
        t.setTextSize(17f);
        t.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(4);
        bar.addView(t, tp);
        column.addView(bar);

        TextView hint = new TextView(this);
        hint.setText("选择要播放的相册 · 本机所有图片");
        hint.setTextColor(0xFF5E5E66);
        hint.setTextSize(10f);
        hint.setLetterSpacing(0.2f);
        hint.setPadding(dp(24), dp(2), dp(24), dp(14));
        column.addView(hint);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(30));
        scroll.addView(list);
        column.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        buildRows();
    }

    private void buildRows() {
        addRow(MediaRepo.DEMO_ALBUM, "示例相册", MediaRepo.demoPhotos().size(), "asset://demo/p1.jpg");
        final View allRow = addRow(MediaRepo.ALL_ALBUM, "全部照片", -1, null);

        new Thread(() -> {
            List<MediaRepo.Album> albums = MediaRepo.queryAlbums(this);
            List<MediaRepo.Photo> all = MediaRepo.queryAll(this);
            runOnUiThread(() -> {
                if (!all.isEmpty()) {
                    // 全部照片用最新一张做封面
                    setRowCover(allRow, all.get(0).uri);
                }
                for (MediaRepo.Album a : albums) {
                    addRow(a.id, a.name, a.count, a.coverUri);
                }
            });
        }).start();
    }

    private View addRow(final String id, String name, int count, String coverUri) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), dp(9), dp(20), dp(9));
        row.setBackgroundResource(R.drawable.row_bg);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundResource(R.drawable.bg_thumb);
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                float r = dp(14);
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        row.addView(iv, new LinearLayout.LayoutParams(dp(58), dp(58)));
        iv.setTag(coverUri);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(14);

        TextView n = new TextView(this);
        n.setText(name == null ? "相册" : name);
        n.setTextColor(0xFFF0F0F4);
        n.setTextSize(13.5f);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(n);

        TextView c = new TextView(this);
        c.setText(count >= 0 ? String.format(Locale.getDefault(), "%d 张照片", count) : "本机所有图片");
        c.setTextColor(0xFF6B6B73);
        c.setTextSize(10f);
        c.setLetterSpacing(0.14f);
        c.setPadding(0, dp(3), 0, 0);
        texts.addView(c);
        row.addView(texts, lp);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(0xFF4A4A52);
        arrow.setTextSize(18f);
        row.addView(arrow);

        row.setOnClickListener(v -> {
            Intent data = new Intent();
            data.putExtra("albumId", id);
            data.putExtra("albumName", name);
            setResult(RESULT_OK, data);
            finish();
        });

        list.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(0x0DFFFFFF);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dl.leftMargin = dp(96);
        list.addView(divider, dl);

        if (coverUri != null) setRowCover(row, coverUri);
        return row;
    }

    private void setRowCover(View row, String uri) {
        if (!(row instanceof LinearLayout)) return;
        View v = ((LinearLayout) row).getChildAt(0);
        if (!(v instanceof ImageView)) return;
        final ImageView iv = (ImageView) v;
        if (uri == null) return;
        MediaRepo.load(this, uri, dp(58), dp(58), (u, bm) -> {
            if (bm != null) iv.setImageBitmap(bm);
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
