package com.moment.gallery;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Switch;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主界面。底栏三栏：图片（单张 / 平铺）/ 图集 / 设置。
 */
public class MainActivity extends Activity
        implements ThumbRailView.Listener, PhotoCardView.Listener,
        GridListView.PinchListener, FastScrollerView.Listener {

    private static final int REQ_PERM = 20;

    // 页面
    private View pageViewer, pageGrid, pageAlbums, pageSettings;
    private ImageView navPhotosIcon, navAlbumsIcon, navSettingsIcon;
    private TextView navPhotosText, navAlbumsText, navSettingsText;

    // 看图页
    private PhotoCardView card;
    private ThumbRailView rail;
    private TextView title, timecode, tag, counter, livePill, nowLine, videoBadge;
    private View cardWrap;

    // 平铺页
    private GridListView gridList;
    private FastScrollerView fastScroller;
    private TextView gridTitle, gridCount, timelineLabel, columnsHint;

    // 图集 / 设置
    private LinearLayout albumsList, settingsList;

    // 数据
    private final List<MediaRepo.Photo> photos = new ArrayList<>();
    private final List<Object> gridRows = new ArrayList<>();   // String(日期头) 或 List<Photo>
    private String albumId = MediaRepo.DEMO_ALBUM;
    private String albumName = "示例相册";
    private int index = 0;
    private int loadSeq = 0;
    private int columns = 4;

    // 设置
    private SharedPreferences prefs;
    private boolean autoPlayLive = true;
    private boolean kenBurnsOn = false;
    private boolean hdrOn = true;
    private boolean gridMode = false;

    private MediaRepo.LiveInfo currentLive;
    private boolean videoPlaying = false;

    private float pinchAccum = 1f;
    private int lastFirstPosition = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("moment", MODE_PRIVATE);
        autoPlayLive = prefs.getBoolean("auto_live", true);
        kenBurnsOn = prefs.getBoolean("kenburns", false);
        hdrOn = prefs.getBoolean("hdr", true);
        columns = prefs.getInt("columns", 4);
        gridMode = prefs.getBoolean("grid_mode", false);

        bindViews();
        applyHdrMode();

        card.setListener(this);
        card.setKenBurnsEnabled(kenBurnsOn);
        rail.setListener(this);

        fastScroller.setListener(this);
        gridList.setPinchListener(this);
        gridList.setAdapter(gridAdapter);
        gridList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_FLING) {
                    fastScroller.flash();
                }
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                lastFirstPosition = first;
                updateScrollerFraction();
                showTimelineFor(first);
            }
        });

        navPhotosIcon.setOnClickListener(v -> showPage(0));
        navAlbumsIcon.setOnClickListener(v -> showPage(1));
        navSettingsIcon.setOnClickListener(v -> showPage(2));
        findViewById(R.id.navPhotos).setOnClickListener(v -> showPage(0));
        findViewById(R.id.navAlbums).setOnClickListener(v -> showPage(1));
        findViewById(R.id.navSettings).setOnClickListener(v -> showPage(2));
        findViewById(R.id.btnToGrid).setOnClickListener(v -> setGridMode(true));
        findViewById(R.id.btnToViewer).setOnClickListener(v -> setGridMode(false));
        livePill.setOnClickListener(v -> toggleLive());

        buildSettings();
        buildAlbums();

        applyPhotos(MediaRepo.demoPhotos(), "示例相册", 0);
        showPage(0);
        setGridMode(gridMode);

        if (hasMediaAccess()) {
            loadDeviceAlbums();
        } else {
            requestMediaAccess();
        }
    }

    private void bindViews() {
        pageViewer = findViewById(R.id.pageViewer);
        pageGrid = findViewById(R.id.pageGrid);
        pageAlbums = findViewById(R.id.pageAlbums);
        pageSettings = findViewById(R.id.pageSettings);

        navPhotosIcon = findViewById(R.id.navPhotosIcon);
        navAlbumsIcon = findViewById(R.id.navAlbumsIcon);
        navSettingsIcon = findViewById(R.id.navSettingsIcon);
        navPhotosText = findViewById(R.id.navPhotosText);
        navAlbumsText = findViewById(R.id.navAlbumsText);
        navSettingsText = findViewById(R.id.navSettingsText);

        card = findViewById(R.id.card);
        rail = findViewById(R.id.rail);
        cardWrap = findViewById(R.id.cardWrap);
        title = findViewById(R.id.title);
        timecode = findViewById(R.id.timecode);
        tag = findViewById(R.id.tag);
        counter = findViewById(R.id.counter);
        livePill = findViewById(R.id.livePill);
        nowLine = findViewById(R.id.nowLine);
        videoBadge = findViewById(R.id.videoBadge);

        gridList = findViewById(R.id.gridList);
        fastScroller = findViewById(R.id.fastScroller);
        gridTitle = findViewById(R.id.gridTitle);
        gridCount = findViewById(R.id.gridCount);
        timelineLabel = findViewById(R.id.timelineLabel);
        columnsHint = findViewById(R.id.columnsHint);

        albumsList = findViewById(R.id.albumsList);
        settingsList = findViewById(R.id.settingsList);
    }

    // ------------------------------------------------------------------ HDR

    private void applyHdrMode() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            getWindow().setColorMode(hdrOn ? ActivityInfo.COLOR_MODE_HDR : ActivityInfo.COLOR_MODE_DEFAULT);
            MediaRepo.setWideGamut(hdrOn && getResources().getConfiguration().isScreenWideColorGamut());
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 页面

    private void showPage(int which) {
        pageViewer.setVisibility(which == 0 ? View.VISIBLE : View.GONE);
        pageGrid.setVisibility(which == 0 && gridMode ? View.VISIBLE : View.GONE);
        if (which == 0 && gridMode) pageViewer.setVisibility(View.GONE);
        pageAlbums.setVisibility(which == 1 ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(which == 2 ? View.VISIBLE : View.GONE);

        setNavActive(0, which == 0);
        setNavActive(1, which == 1);
        setNavActive(2, which == 2);
    }

    private void setNavActive(int i, boolean active) {
        ImageView icon = i == 0 ? navPhotosIcon : i == 1 ? navAlbumsIcon : navSettingsIcon;
        TextView text = i == 0 ? navPhotosText : i == 1 ? navAlbumsText : navSettingsText;
        icon.setAlpha(active ? 1f : 0.45f);
        text.setTextColor(active ? 0xFFF0F0F4 : 0xFF6B6B73);
    }

    private void setGridMode(boolean grid) {
        gridMode = grid;
        prefs.edit().putBoolean("grid_mode", grid).apply();
        pageViewer.setVisibility(grid ? View.GONE : View.VISIBLE);
        pageGrid.setVisibility(grid ? View.VISIBLE : View.GONE);
        if (grid) {
            buildGridRows();
            scrollGridToCurrent();
        }
    }

    // ------------------------------------------------------------------ 权限

    private boolean hasMediaAccess() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMediaAccess() {
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"}, REQ_PERM);
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO}, REQ_PERM);
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERM && hasMediaAccess()) loadDeviceAlbums();
    }

    // ------------------------------------------------------------------ 数据

    private void loadDeviceAlbums() {
        new Thread(() -> {
            List<MediaRepo.Album> list = MediaRepo.queryAlbums(this);
            runOnUiThread(() -> {
                buildAlbums();
                if (!list.isEmpty()) {
                    albumId = list.get(0).id;
                    loadAlbumPhotos(list.get(0).id, list.get(0).name);
                }
            });
        }).start();
    }

    private void loadAlbumPhotos(String id, String name) {
        albumId = id;
        albumName = name;
        new Thread(() -> {
            List<MediaRepo.Photo> list = id.equals(MediaRepo.ALL_ALBUM)
                    ? MediaRepo.queryAll(this)
                    : MediaRepo.queryAlbum(this, id);
            runOnUiThread(() -> {
                if (!list.isEmpty()) applyPhotos(list, name, 0);
                buildAlbums();
            });
        }).start();
    }

    private void applyPhotos(List<MediaRepo.Photo> list, String name, int start) {
        albumName = name;
        photos.clear();
        photos.addAll(list);
        index = Math.max(0, Math.min(start, photos.size() - 1));
        rail.setPhotos(photos.size());
        rail.setActive(index, false);
        updateAlbumTexts();
        show(index, false);
        buildGridRows();
    }

    private void updateAlbumTexts() {
        String upper = albumName == null ? "MOMENTS" : albumName.toUpperCase(Locale.getDefault());
        title.setText(upper);
        gridTitle.setText(upper);
        gridCount.setText(String.format(Locale.getDefault(), "%d 张 · %d 列", photos.size(), columns));
    }

    // ------------------------------------------------------------------ 看图

    private void show(final int i, boolean animate) {
        if (photos.isEmpty()) return;
        index = (i + photos.size()) % photos.size();
        final MediaRepo.Photo p = photos.get(index);
        final int seq = ++loadSeq;

        card.stopLive();
        videoPlaying = false;
        currentLive = null;
        livePill.setVisibility(View.GONE);

        final int reqW = card.getWidth() > 0 ? card.getWidth() : getScreenW();
        final int reqH = card.getHeight() > 0 ? card.getHeight() : getScreenH();

        rail.setActive(index, true);
        updateMeta(p);

        MediaRepo.load(this, p, reqW, reqH, (uri, bm) -> {
            if (seq != loadSeq || bm == null) return;
            resizeCard(bm);
            card.resetZoom();
            card.setBitmap(bm, animate);
            prefetchNeighbours();
        });

        if (p.isVideo) {
            videoBadge.setVisibility(View.VISIBLE);
            videoBadge.setText("▶ " + MediaRepo.durationText(p.durationMs));
            return;
        }
        videoBadge.setVisibility(View.GONE);

        new Thread(() -> {
            MediaRepo.LiveInfo li = MediaRepo.detectLive(this, p);
            runOnUiThread(() -> {
                if (seq != loadSeq) return;
                currentLive = li;
                if (li.isLive) {
                    livePill.setVisibility(View.VISIBLE);
                    livePill.setText("实况");
                    if (autoPlayLive) playLiveFor(p, li);
                }
            });
        }).start();
    }

    private void prefetchNeighbours() {
        if (photos.size() < 2) return;
        final int w = card.getWidth() > 0 ? card.getWidth() : getScreenW();
        final int h = card.getHeight() > 0 ? card.getHeight() : getScreenH();
        MediaRepo.prefetch(this, photos.get((index + 1) % photos.size()), w, h);
        MediaRepo.prefetch(this, photos.get((index - 1 + photos.size()) % photos.size()), w, h);
    }

    private int resizeAttempts = 0;

    private void resizeCard(Bitmap bm) {
        View parent = (View) cardWrap.getParent();
        if (cardWrap.getWidth() <= 0 || parent.getHeight() <= 0) {
            if (resizeAttempts++ > 20) return;
            cardWrap.post(() -> resizeCard(bm));
            return;
        }
        resizeAttempts = 0;
        int availW = cardWrap.getWidth();
        int maxH = parent.getHeight();
        float aspect = bm.getWidth() / (float) bm.getHeight();
        int h = Math.round(availW / aspect);
        int minH = Math.round(availW * 0.60f);
        h = Math.max(minH, Math.min(maxH, h));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cardWrap.getLayoutParams();
        if (lp.height != h || lp.gravity != Gravity.CENTER_VERTICAL) {
            lp.height = h;
            lp.gravity = Gravity.CENTER_VERTICAL;
            cardWrap.setLayoutParams(lp);
        }
    }

    private void updateMeta(MediaRepo.Photo p) {
        tag.setText(p.tag == null ? "DAILY LOOK" : p.tag);
        counter.setText(String.format(Locale.getDefault(), "%02d / %02d", index + 1, photos.size()));
        timecode.setText(p.label == null ? "" : p.label);
        nowLine.setText(String.format(Locale.getDefault(), "%s · %d 张 · 双击看详情，双指放大",
                albumName, photos.size()));
    }

    private void next() {
        if (photos.size() < 2) return;
        show(index + 1, false);
        haptic();
    }

    private void prev() {
        if (photos.size() < 2) return;
        show(index - 1, false);
        haptic();
    }

    private void haptic() {
        card.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }

    // ------------------------------------------------------------------ 视频 / 实况

    private void playLiveFor(final MediaRepo.Photo p, final MediaRepo.LiveInfo li) {
        videoPlaying = true;
        livePill.setText("实况 · 播放中");
        new Thread(() -> {
            File f = null;
            try {
                f = MediaRepo.extractLiveVideo(this, p, li);
            } catch (Throwable ignored) {
            }
            final File file = f;
            runOnUiThread(() -> {
                if (file == null) {
                    videoPlaying = false;
                    livePill.setText("实况");
                    return;
                }
                card.playVideo(null, file, true, null);
            });
        }).start();
    }

    private void toggleLive() {
        if (photos.isEmpty()) return;
        MediaRepo.Photo p = photos.get(index);
        if (p.isVideo) {
            toggleVideo(p);
            return;
        }
        if (currentLive == null || !currentLive.isLive) return;
        if (card.isLivePlaying()) {
            card.stopLive();
            videoPlaying = false;
            livePill.setText("实况");
        } else {
            playLiveFor(p, currentLive);
        }
    }

    private void toggleVideo(MediaRepo.Photo p) {
        if (card.isLivePlaying()) {
            card.stopLive();
            videoPlaying = false;
            videoBadge.setText("▶ " + MediaRepo.durationText(p.durationMs));
        } else {
            videoPlaying = true;
            videoBadge.setText("❚❚ " + MediaRepo.durationText(p.durationMs));
            card.playVideo(Uri.parse(p.uri), null, false, null);
        }
    }

    @Override
    public void onLiveFinished() {
        videoPlaying = false;
        livePill.setText("实况");
        if (!photos.isEmpty()) {
            MediaRepo.Photo p = photos.get(index);
            if (p.isVideo) videoBadge.setText("▶ " + MediaRepo.durationText(p.durationMs));
        }
    }

    // ------------------------------------------------------------------ 手势

    @Override
    public void onTap() {
        if (photos.isEmpty()) return;
        MediaRepo.Photo p = photos.get(index);
        if (p.isVideo) {
            toggleVideo(p);
        } else if (currentLive != null && currentLive.isLive) {
            toggleLive();
        }
    }

    @Override
    public void onDoubleTap() {
        if (!photos.isEmpty()) InfoDialog.show(this, photos.get(index));
    }

    @Override
    public void onSwipeLeft() {
        next();
    }

    @Override
    public void onSwipeRight() {
        prev();
    }

    @Override
    public void onZoomChanged(boolean z) {
    }

    @Override
    public void onNeedHiRes() {
        if (photos.isEmpty()) return;
        final MediaRepo.Photo p = photos.get(index);
        if (p.isVideo) return;
        final int rw = p.width > 0 ? p.width : 3000;
        final int rh = p.height > 0 ? p.height : 3000;
        MediaRepo.loadHiRes(this, p.uri, rw, rh, (uri, bm) -> {
            if (bm != null && photos.size() > index && photos.get(index).uri.equals(uri)) {
                card.setHiRes(bm);
            }
        });
    }

    @Override
    public void onIndexPicked(int i) {
        if (i == index) return;
        show(i, false);
        haptic();
    }

    @Override
    public void onThumbNeeded(int i) {
        if (i < 0 || i >= photos.size()) return;
        final int size = dp(40);
        final int idx = i;
        MediaRepo.load(this, photos.get(i), size, size, (u, bm) -> {
            if (bm != null) rail.setThumb(idx, bm);
        });
    }

    // ------------------------------------------------------------------ 平铺网格

    private void buildGridRows() {
        gridRows.clear();
        String lastDate = null;
        List<MediaRepo.Photo> row = new ArrayList<>();
        for (MediaRepo.Photo p : photos) {
            String d = p.label == null ? "" : p.label;
            if (lastDate == null) lastDate = d;
            if (!d.equals(lastDate) && !row.isEmpty()) {
                gridRows.add(row);
                row = new ArrayList<>();
                gridRows.add(d);
                lastDate = d;
            } else if (lastDate.equals(d) && gridRows.isEmpty()) {
                gridRows.add(d);
            }
            row.add(p);
        }
        if (!row.isEmpty()) gridRows.add(row);
        gridAdapter.notifyDataSetChanged();
        updateAlbumTexts();
    }

    /** 找到当前照片在网格里的行，滚过去 */
    private void scrollGridToCurrent() {
        if (photos.isEmpty()) return;
        int photoIdx = index;
        int count = 0;
        for (int i = 0; i < gridRows.size(); i++) {
            Object o = gridRows.get(i);
            if (o instanceof List) {
                List<?> l = (List<?>) o;
                if (photoIdx < count + l.size()) {
                    final int rowIndex = i;
                    gridList.post(() -> gridList.setSelection(rowIndex));
                    return;
                }
                count += l.size();
            }
        }
    }

    private int cellSize() {
        int w = getScreenW() - dp(12) - dp(24);
        int gaps = dp(3) * (columns - 1);
        return Math.max(dp(48), (w - gaps) / columns);
    }

    private final BaseAdapter gridAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return gridRows.size();
        }

        @Override
        public Object getItem(int position) {
            return gridRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return gridRows.get(position) instanceof String ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object item = gridRows.get(position);
            if (item instanceof String) {
                TextView tv = convertView instanceof TextView ? (TextView) convertView : null;
                if (tv == null) {
                    tv = new TextView(MainActivity.this);
                    tv.setTextSize(11f);
                    tv.setLetterSpacing(0.16f);
                    tv.setTextColor(0xFF8A8A90);
                    tv.setPadding(dp(12), dp(14), dp(12), dp(6));
                }
                tv.setText((String) item);
                return tv;
            }
            @SuppressWarnings("unchecked")
            List<MediaRepo.Photo> rowPhotos = (List<MediaRepo.Photo>) item;
            LinearLayout rowView;
            if (convertView instanceof LinearLayout && columns == ((LinearLayout) convertView).getChildCount()) {
                rowView = (LinearLayout) convertView;
            } else {
                rowView = new LinearLayout(MainActivity.this);
                rowView.setOrientation(LinearLayout.HORIZONTAL);
                rowView.setPadding(0, dp(3), 0, 0);
                for (int i = 0; i < columns; i++) {
                    rowView.addView(makeCell());
                }
            }
            int size = cellSize();
            AbsListView.LayoutParams rowLp = new AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT, size);
            rowView.setLayoutParams(rowLp);
            for (int i = 0; i < rowView.getChildCount(); i++) {
                FrameLayout cell = (FrameLayout) rowView.getChildAt(i);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.rightMargin = dp(3);
                cell.setLayoutParams(lp);
                if (i < rowPhotos.size()) {
                    bindCell(cell, rowPhotos.get(i), size);
                    cell.setVisibility(View.VISIBLE);
                } else {
                    cell.setVisibility(View.INVISIBLE);
                }
            }
            return rowView;
        }
    };

    private FrameLayout makeCell() {
        FrameLayout cell = new FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(0xFF17171A);
        cell.addView(iv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        final float r = dp(4);
        cell.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        cell.setClipToOutline(true);

        TextView badge = new TextView(this);
        badge.setTextSize(9f);
        badge.setTextColor(Color.WHITE);
        badge.setBackgroundResource(R.drawable.bg_badge);
        badge.setPadding(dp(5), dp(1), dp(5), dp(1));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.END | Gravity.BOTTOM;
        blp.setMargins(0, 0, dp(5), dp(5));
        blp.rightMargin = dp(5);
        blp.bottomMargin = dp(5);
        cell.addView(badge, blp);
        return cell;
    }

    private void bindCell(FrameLayout cell, final MediaRepo.Photo p, int size) {
        final ImageView iv = (ImageView) cell.getChildAt(0);
        final TextView badge = (TextView) cell.getChildAt(1);
        iv.setImageBitmap(null);
        iv.setTag(p.uri + "#" + size);
        if (p.isVideo) {
            badge.setVisibility(View.VISIBLE);
            badge.setText("▶ " + MediaRepo.durationText(p.durationMs));
        } else {
            badge.setVisibility(View.GONE);
        }
        final String tag = (String) iv.getTag();
        MediaRepo.load(this, p, size, size, (uri, bm) -> {
            if (bm != null && tag.equals(iv.getTag())) iv.setImageBitmap(bm);
        });
        cell.setOnClickListener(v -> {
            index = photos.indexOf(p);
            if (index < 0) index = 0;
            setGridMode(false);
            showPage(0);
            show(index, false);
        });
    }

    // ------------------------------------------------------------------ 捏合改列数 / 滑条

    @Override
    public void onPinch(float scaleFactor) {
        pinchAccum *= scaleFactor;
        if (pinchAccum > 1.28f) {
            pinchAccum = 1f;
            changeColumns(-1);      // 放大手势 -> 列数变少（图更大）
        } else if (pinchAccum < 0.78f) {
            pinchAccum = 1f;
            changeColumns(1);
        }
    }

    @Override
    public void onPinchEnd() {
        pinchAccum = 1f;
        columnsHint.animate().alpha(0f).setDuration(200).withEndAction(() ->
                columnsHint.setVisibility(View.GONE)).start();
    }

    private void changeColumns(int delta) {
        int next = Math.max(2, Math.min(8, columns + delta));
        if (next == columns) return;
        // 改完列数后滚回原来的位置
        columns = next;
        prefs.edit().putInt("columns", columns).apply();
        buildGridRows();
        final int target = Math.max(0, lastFirstPosition);
        gridList.post(() -> gridList.setSelection(Math.min(target, gridRows.size() - 1)));
        columnsHint.setText(columns + " 列");
        columnsHint.setVisibility(View.VISIBLE);
        columnsHint.setAlpha(1f);
        updateAlbumTexts();
    }

    private void updateScrollerFraction() {
        int count = gridAdapter.getCount();
        if (count <= 1) {
            fastScroller.setFraction(0f);
            return;
        }
        fastScroller.setFraction(lastFirstPosition / (float) (count - 1));
    }

    private void showTimelineFor(int firstVisible) {
        for (int i = Math.min(firstVisible, gridRows.size() - 1); i >= 0; i--) {
            Object o = gridRows.get(i);
            if (o instanceof String) {
                timelineLabel.setText((String) o);
                timelineLabel.setVisibility(View.VISIBLE);
                timelineLabel.animate().cancel();
                timelineLabel.animate().alpha(1f).setDuration(120).start();
                timelineLabel.removeCallbacks(hideTimeline);
                timelineLabel.postDelayed(hideTimeline, 1100);
                return;
            }
        }
    }

    private final Runnable hideTimeline = () ->
            timelineLabel.animate().alpha(0f).setDuration(250).withEndAction(() ->
                    timelineLabel.setVisibility(View.GONE)).start();

    @Override
    public void onScrub(float fraction) {
        int count = gridAdapter.getCount();
        if (count == 0) return;
        int pos = Math.round(fraction * (count - 1));
        gridList.setSelection(pos);
    }

    // ------------------------------------------------------------------ 图集页

    private int albumSeq = 0;
    private View allAlbumRow;

    private void buildAlbums() {
        albumsList.removeAllViews();
        addAlbumRow(MediaRepo.DEMO_ALBUM, "示例相册", MediaRepo.demoPhotos().size(),
                MediaRepo.demoPhotos().get(0).uri);
        allAlbumRow = addAlbumRow(MediaRepo.ALL_ALBUM, "全部照片 / 视频", -1, null);

        final int seq = ++albumSeq;
        new Thread(() -> {
            List<MediaRepo.Album> list = MediaRepo.queryAlbums(this);
            List<MediaRepo.Photo> all = MediaRepo.queryAll(this);
            runOnUiThread(() -> {
                if (seq != albumSeq) return;
                if (!all.isEmpty()) setAlbumCover(allAlbumRow, all.get(0));
                for (MediaRepo.Album a : list) {
                    addAlbumRow(a.id, a.name, a.count, a.coverUri);
                }
            });
        }).start();
    }

    private View addAlbumRow(final String id, String name, int count, String coverUri) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(22), dp(9), dp(18), dp(9));
        row.setBackgroundResource(R.drawable.row_bg);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundResource(R.drawable.bg_thumb);
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        final int s = dp(54);
        row.addView(iv, new LinearLayout.LayoutParams(s, s));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(14);

        TextView n = new TextView(this);
        n.setText(name == null ? "相册" : name);
        n.setTextColor(0xFFF0F0F4);
        n.setTextSize(13f);
        n.setMaxLines(1);
        texts.addView(n);

        TextView c = new TextView(this);
        c.setText(count >= 0 ? String.format(Locale.getDefault(), "%d 项", count) : "本机所有图片和视频");
        c.setTextColor(0xFF6B6B73);
        c.setTextSize(10f);
        c.setPadding(0, dp(3), 0, 0);
        texts.addView(c);
        row.addView(texts, lp);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(0xFF4A4A52);
        arrow.setTextSize(18f);
        row.addView(arrow);

        row.setOnClickListener(v -> {
            if (MediaRepo.DEMO_ALBUM.equals(id)) {
                applyPhotos(MediaRepo.demoPhotos(), name, 0);
            } else {
                loadAlbumPhotos(id, name);
            }
            showPage(0);
        });
        albumsList.addView(row);

        View divider = new View(this);
        divider.setBackgroundColor(0x0DFFFFFF);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dl.leftMargin = dp(90);
        albumsList.addView(divider, dl);

        if (coverUri != null) {
            setAlbumCover(row, new MediaRepo.Photo(coverUri, "", ""));
        }
        return row;
    }

    private void setAlbumCover(View row, MediaRepo.Photo p) {
        if (p == null || !(row instanceof LinearLayout)) return;
        View v = ((LinearLayout) row).getChildAt(0);
        if (!(v instanceof ImageView)) return;
        final ImageView iv = (ImageView) v;
        MediaRepo.load(this, p, dp(54), dp(54), (u, bm) -> {
            if (bm != null) iv.setImageBitmap(bm);
        });
    }

    // ------------------------------------------------------------------ 设置页

    private void buildSettings() {
        settingsList.removeAllViews();
        addSwitch("实况照片自动播放", "关掉后只显示静帧，点「实况」标签手动播放", autoPlayLive,
                v -> {
                    autoPlayLive = v;
                    prefs.edit().putBoolean("auto_live", v).apply();
                });
        addSwitch("照片缓慢推近（Ken Burns）", "默认关闭：照片就是一张静止的原图", kenBurnsOn,
                v -> {
                    kenBurnsOn = v;
                    card.setKenBurnsEnabled(v);
                    prefs.edit().putBoolean("kenburns", v).apply();
                });
        addSwitch("HDR / 广色域", "视频走 HDR 直通，照片按广色域解码（需屏幕支持）", hdrOn,
                v -> {
                    hdrOn = v;
                    prefs.edit().putBoolean("hdr", v).apply();
                    applyHdrMode();
                });

        TextView cols = new TextView(this);
        cols.setText(String.format(Locale.getDefault(),
                "平铺列数：%d 列（在平铺页双指捏合即可调整，2~8 列）", columns));
        cols.setTextColor(0xFF8A8A90);
        cols.setTextSize(11.5f);
        cols.setPadding(0, dp(18), 0, dp(6));
        settingsList.addView(cols);

        TextView about = new TextView(this);
        about.setText("时刻 MOMENT 1.0\n本地图库 · 单张 / 平铺 / 时间线 · 实况与视频 · EXIF");
        about.setTextColor(0xFF4A4A52);
        about.setTextSize(10f);
        about.setLineSpacing(dp(3), 1f);
        about.setPadding(0, dp(26), 0, 0);
        settingsList.addView(about);
    }

    private interface SwitchCallback {
        void onChanged(boolean value);
    }

    private void addSwitch(String title, String desc, boolean value, final SwitchCallback cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFE8E8EE);
        t.setTextSize(13f);
        top.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final Switch toggle = new Switch(this);
        toggle.setChecked(value);
        if (Build.VERSION.SDK_INT >= 23) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            toggle.setThumbTintList(new android.content.res.ColorStateList(states,
                    new int[]{0xFF0F0F11, 0xFF8A8A93}));
            toggle.setTrackTintList(new android.content.res.ColorStateList(states,
                    new int[]{0xFFEDEDF1, 0x33FFFFFF}));
        }
        top.addView(toggle);
        box.addView(top);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(0xFF5E5E66);
        d.setTextSize(10f);
        d.setPadding(0, dp(2), 0, 0);
        box.addView(d);

        toggle.setOnCheckedChangeListener((btn, checked) -> cb.onChanged(checked));
        settingsList.addView(box);

        View line = new View(this);
        line.setBackgroundColor(0x0FFFFFFF);
        settingsList.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
    }

    // ------------------------------------------------------------------ 工具

    @Override
    protected void onPause() {
        super.onPause();
        card.stopLive();
        videoPlaying = false;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int getScreenW() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        return m.widthPixels;
    }

    private int getScreenH() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        return m.heightPixels;
    }
}
