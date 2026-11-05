package com.moment.gallery;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.ExifInterface;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 双击弹出的「照片详情」面板：文件信息 + 完整 EXIF。 */
public final class InfoDialog {

    private InfoDialog() {
    }

    public static void show(final Activity act, final MediaRepo.Photo p) {
        final float d = act.getResources().getDisplayMetrics().density;

        final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF141417);
        root.setPadding(dp(d, 22), dp(d, 20), dp(d, 22), dp(d, 26));

        TextView title = new TextView(act);
        title.setText("照片详情");
        title.setTextColor(0xFFF5F5F7);
        title.setTextSize(15f);
        title.setLetterSpacing(0.12f);
        root.addView(title);

        TextView fileName = new TextView(act);
        fileName.setText(p.displayName == null ? p.uri : p.displayName);
        fileName.setTextColor(0xFF6B6B73);
        fileName.setTextSize(10f);
        fileName.setPadding(0, dp(d, 5), 0, dp(d, 12));
        root.addView(fileName);

        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        addRow(act, list, "读取中…", "");

        ScrollView scroll = new ScrollView(act);
        scroll.addView(root);
        dialog.setContentView(scroll);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            w.setAttributes(lp);
        }

        root.setOnClickListener(v -> dialog.dismiss());
        dialog.show();

        new Thread(() -> {
            final List<String[]> rows = collect(act, p);
            act.runOnUiThread(() -> {
                list.removeAllViews();
                for (String[] r : rows) addRow(act, list, r[0], r[1]);
            });
        }).start();
    }

    private static List<String[]> collect(Activity act, MediaRepo.Photo p) {
        List<String[]> rows = new ArrayList<>();
        try {
            long size = MediaRepo.sizeOf(act, p);
            if (p.width > 0 && p.height > 0) rows.add(new String[]{"分辨率", p.width + " × " + p.height});
            if (size > 0) rows.add(new String[]{"文件大小", readableSize(size)});
            if (p.mime != null) rows.add(new String[]{"格式", p.mime});
            if (p.bucketName != null) rows.add(new String[]{"所在相册", p.bucketName});
            if (p.dateAdded > 0) {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
                rows.add(new String[]{"添加到相册", f.format(new Date(p.dateAdded * 1000L))});
            }

            ExifInterface exif = null;
            InputStream in = null;
            try {
                if (p.uri.startsWith(MediaRepo.DEMO_PREFIX)) {
                    in = act.getAssets().open("demo/" + p.uri.substring(MediaRepo.DEMO_PREFIX.length()));
                } else {
                    in = act.getContentResolver().openInputStream(android.net.Uri.parse(p.uri));
                }
                if (in != null) exif = new ExifInterface(in);
            } catch (Throwable ignored) {
            } finally {
                if (in != null) try {
                    in.close();
                } catch (Exception ignored) {
                }
            }

            if (exif != null) {
                addIf(rows, "拍摄时间", join(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                        exif.getAttribute(ExifInterface.TAG_DATETIME)));
                String make = exif.getAttribute(ExifInterface.TAG_MAKE);
                String model = exif.getAttribute(ExifInterface.TAG_MODEL);
                addIf(rows, "相机", join(make, model));
                addIf(rows, "镜头", exif.getAttribute("LensModel"));
                addIf(rows, "光圈", fmt(exif.getAttribute(ExifInterface.TAG_F_NUMBER), "f/"));
                addIf(rows, "快门", fmtExposure(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)));
                String iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS);
                if (iso == null) iso = exif.getAttribute("PhotographicSensitivity");
                addIf(rows, "ISO", iso);
                addIf(rows, "焦距", fmt(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH), ""));
                addIf(rows, "等效焦距", fmt(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM), ""));
                addIf(rows, "曝光补偿", exif.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE));
                addIf(rows, "白平衡", exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE));
                addIf(rows, "闪光灯", exif.getAttribute(ExifInterface.TAG_FLASH));
                addIf(rows, "测光模式", exif.getAttribute(ExifInterface.TAG_METERING_MODE));
                addIf(rows, "方向", orientationText(
                        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)));

                float[] latLong = new float[2];
                if (exif.getLatLong(latLong)) {
                    rows.add(new String[]{"位置", String.format(Locale.US, "%.6f, %.6f",
                            latLong[0], latLong[1])});
                }
                String soft = exif.getAttribute(ExifInterface.TAG_SOFTWARE);
                addIf(rows, "处理软件", soft);
            }

            MediaRepo.LiveInfo live = MediaRepo.detectLive(act, p);
            if (live.isLive) {
                rows.add(new String[]{"实况照片", "是 · 内嵌视频 "
                        + readableSize(live.videoLength)});
            }
        } catch (Throwable ignored) {
        }
        if (rows.isEmpty()) rows.add(new String[]{"信息", "这张图片没有可读的 EXIF"});
        return rows;
    }

    private static String fmt(String v, String prefix) {
        if (v == null || v.trim().isEmpty()) return null;
        try {
            if (v.contains("/")) {
                String[] ab = v.split("/");
                double val = Double.parseDouble(ab[0]) / Double.parseDouble(ab[1]);
                return prefix + trimNum(val);
            }
        } catch (Throwable ignored) {
        }
        return prefix + v + (prefix.equals("f/") ? "" : "");
    }

    private static String fmtExposure(String v) {
        if (v == null || v.trim().isEmpty()) return null;
        try {
            if (v.contains("/")) {
                String[] ab = v.split("/");
                double val = Double.parseDouble(ab[0]) / Double.parseDouble(ab[1]);
                if (val > 0 && val < 1) return "1/" + Math.round(1 / val) + " s";
                return trimNum(val) + " s";
            }
            return trimNum(Double.parseDouble(v)) + " s";
        } catch (Throwable t) {
            return v;
        }
    }

    private static String trimNum(double d) {
        if (Math.abs(d - Math.rint(d)) < 0.01) return String.valueOf((long) Math.rint(d));
        return String.format(Locale.US, "%.1f", d);
    }

    private static String orientationText(int o) {
        switch (o) {
            case 3:
                return "旋转 180°";
            case 6:
                return "旋转 90°";
            case 8:
                return "旋转 270°";
            default:
                return "正常";
        }
    }

    private static void addIf(List<String[]> rows, String k, String v) {
        if (v != null && !v.trim().isEmpty() && !"null".equals(v)) {
            rows.add(new String[]{k, v.trim()});
        }
    }

    private static String join(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        if (x.isEmpty()) return y.isEmpty() ? null : y;
        if (y.isEmpty()) return x;
        return x.contains(y) ? x : x + " " + y;
    }

    private static String readableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        return String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f);
    }

    private static void addRow(Activity act, LinearLayout parent, String key, String value) {
        float d = act.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(d, 9), 0, dp(d, 9));

        TextView k = new TextView(act);
        k.setText(key);
        k.setTextColor(0xFF6B6B73);
        k.setTextSize(11.5f);
        k.setLetterSpacing(0.06f);
        row.addView(k, new LinearLayout.LayoutParams(dp(d, 92),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView v = new TextView(act);
        v.setText(value == null ? "" : value);
        v.setTextColor(0xFFE8E8EE);
        v.setTextSize(12f);
        v.setLineSpacing(dp(d, 2), 1f);
        row.addView(v, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);

        View line = new View(act);
        line.setBackgroundColor(0x0FFFFFFF);
        parent.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
    }

    private static int dp(float d, int v) {
        return Math.round(v * d);
    }
}
