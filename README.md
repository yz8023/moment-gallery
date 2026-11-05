# 时刻 MOMENT · 安卓本地图库（图片 + 视频）

> **直接下载**
> - 安装包（APK）：[`MomentGallery.apk`](./MomentGallery.apk) · 1.4 MB · 已签名 · Android 7.0+
> - 源码打包：[`moment-gallery-source-v6.zip`](./moment-gallery-source-v6.zip) · 3.0 MB
> - 界面预览（浏览器打开）：[`docs/preview-v6.html`](./docs/preview-v6.html)
> - 版本页：[Releases](https://github.com/yz8023/moment-gallery/releases/latest)

黑底、字母间距标题、左侧竖排圆形缩略图、中央大圆角卡片。
**打开就是原图**：没有自动轮播、没有播放/暂停/下一张、没有进度条。自己滑、自己点，双击看 EXIF，双指放大。

这一版（v6）把 App 变成三栏：**图片（单张看图 / 平铺相册）· 图集 · 设置**，并补上了 HDR 与视频。

- 安装包：`MomentGallery.apk`（1.4 MB，已签名，minSdk 24 / Android 7.0+，target 34）
- 界面参考图：`screenshot.png`（四屏：看图 / 平铺 / 图集 / 设置）
- 仓库：https://github.com/yz8023/moment-gallery
- 交互预览（浏览器直接打开）：`docs/preview-v6.html`（平铺那一屏下面有个滑块，拖动就是「双指捏合改列数」的效果）

---

## 一、装到手机上

1. 把 `MomentGallery.apk` 传到手机，点开安装，允许「未知来源」这一次。
   - 数据线：`adb install -r MomentGallery.apk`
2. 首次启动申请**照片权限**（Android 13+ 是「照片和视频」，14 可只授权部分）。
   拒绝也能用：先跑内置的**示例相册**，之后在底栏「图集」里随时重选。

## 二、怎么用

底栏固定三栏：**图片 / 图集 / 设置**。

| 操作 | 效果 |
| --- | --- |
| 底栏「图片」 | 进入看图模式（单张大卡片） |
| 看图页右上角 ▦ / 平铺页右上角 ▣ | 切「单张」↔「平铺」两种排版，下次启动记住 |
| 平铺页**双指捏合** | 改「一行几列」，2~8 列，居中闪一下「N 列」；松手后停在那里，下次启动记住 |
| 平铺页**时间线** | 按拍摄日期分组，顶部浮一条日期胶囊；滚动时随当前日期更新 |
| 平铺页右侧**滑条** | 平时完全不可见；只在**快速滑动**（惯性甩动）时淡入 0.9 秒后自动消失；滑块可以按住拖动跳转 |
| 视频格子 / 视频大图 | 右下角带 ▶ 与时长的角标（`0:24` / `1:42`）；点一下就地播放，再点暂停，播完回封面 |
| 左右拖拽画面 | 上一张 / 下一张（放大状态下是平移图片） |
| 点左侧圆点 / 拖圆点列 | 跳到那张 / 快速翻缩略图 |
| **双击画面** | 照片详情（完整 EXIF） |
| **双指捏合**（大图） | 放大缩小（最大 8×），放大后单指拖动 |
| 单击画面 | 实况照片：播放 / 暂停它自带的动态；视频：播放 / 暂停 |
| 底栏「设置」 | 实况自动播放、缓慢推近、HDR/广色域、当前列数 |

**没有**：自动轮播、播放暂停按钮、上一张下一张按钮、进度条 —— 这些是幻灯片/播放器的东西，这里不做。

## 三、v6 这一轮做了什么

| 你的要求 | 处理 |
| --- | --- |
| ① HDR，图片和视频都要 | 窗口按 `ActivityInfo.COLOR_MODE_HDR` 开色彩模式（API 26+，屏幕支持宽色域才开）；照片解码走 `inPreferredColorSpace = Display P3`，HDR 视频由系统 tone-map 直通显示；设置里可以关掉 |
| ② 普通相册排版：图片平铺 + 时间线 + 捏合改列数 | 新增平铺页：`GridListView`（自绘双指捏合，双指时不吃列表手势）+ 日期分组头（`dateAdded` 分组）+ 顶部浮动的日期胶囊 |
| ③ 右侧滑条：半透明、按住出现滑块、只在快速滑动时显示 | 新增 `FastScrollerView`：平时 alpha=0 且**不吃触摸**；`SCROLL_STATE_FLING` 时 `flash()` 淡入，0.9s 后淡出；按住可拖动跳转 |
| ④ 视频标记时长 | `MediaStore.Files` 同时查图片和视频，`MediaMetadataRetriever` 读时长，格子和卡片都显示 `▶ m:ss` / `h:mm:ss` |
| ⑤ 底栏三栏 | 图片（单张/平铺）/ 图集 / 设置，选中项图标与文字提亮 |

上一轮（仍然是现在的行为，没退回去）：

| 你的反馈 | 处理 |
| --- | --- |
| 进入时还在放大 | 删掉「从小图放大飞入」的过场。**直接上屏**，换图只做 160ms 纯透明度交叉淡入（无缩放、无位移、无模糊） |
| 为什么下面有一个暂停下一张 | 底部播放条整块删除，只留一行 `相册名 · N 张 · 双击看详情，双指放大` |
| 别自动翻页 | 看哪张停哪张 |
| 流畅度 | 大图一次读进内存再解析；首屏只解到卡片 1.4 倍尺寸，放大时再单独加载高清；缩略图流式解码 + RGB_565；缩略图轨道缓存 shader；前后各预取一张 |

## 四、保留的能力

- **不裁切**（大图）：按原始比例完整显示，卡片高度随照片比例自适应（`h = 卡宽/比例`，限制在 `0.6×卡宽` 与容器高之间）
- **完整 EXIF**：双击弹出面板，含文件名、分辨率、大小、格式、相册、拍摄时间、相机、镜头、光圈、快门、ISO、焦距、等效焦距、曝光补偿、白平衡、闪光灯、测光、方向、GPS、处理软件，以及是否实况图
- **缩放**：最大 8×；到原始像素 1:1 之前是真无损，超过 1:1 是插值；放大过 1.7× 自动换更清晰的素材（上限约 800 万像素，防 OOM）
- **实况图**：识别 Motion Photo / MicroVideo（XMP 优先，读不到再扫文件尾部找内嵌 MP4），静音贴图播放；设置里可开关自动播放
- **视频**：点一下就地播放（`MediaPlayer` + `TextureView`，无控制条），播完自动回封面并复位角标

## 五、想调什么，改哪里

| 想改的东西 | 位置 |
| --- | --- |
| 换图淡入时长（160ms） | `PhotoCardView.java` → `FADE` |
| 最大放大倍数（8×）/ 高清切换阈值（1.7×） | `ZoomImageView.java` → `MAX_ZOOM` / `HI_RES_THRESHOLD` |
| 平铺默认列数（4）/ 列数范围（2~8）/ 捏合触发阈值（1.28×） | `MainActivity.java` → `columns` / `changeColumns()` / `onPinch()` |
| 滑条淡入时长（140ms）、停留时间（900ms） | `FastScrollerView.java` → `flash()` / `fadeIn()` |
| 解码像素上限（800 万）、首屏解码倍数（1.4×） | `MediaRepo.java` → `MAX_PIXELS` / `maxW = reqW * 1.4f` |
| 卡片高度下限 | `MainActivity.resizeCard()` → `availW * 0.60f` |
| 标题字号/字母间距、配色、圆角 | `res/layout/activity_main.xml`、`res/values/colors.xml`、`res/drawable/bg_card.xml` |

## 六、代码结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/moment/gallery/
│   ├── MainActivity.java     三个页面：看图（卡片+缩略图轨道+实况/视频）/ 平铺（网格+时间线+列数）/ 图集 / 设置
│   ├── MediaRepo.java        MediaStore 查询（图片+视频）/ 示例相册 / 解码线程池 / LruCache / EXIF 旋转 / 视频封面与时长 / 实况识别
│   ├── PhotoCardView.java    大图卡片：双层交叉淡入 + 实况/视频叠加播放
│   ├── ZoomImageView.java    缩放 / 拖动 / 单双击 / 滑动
│   ├── ThumbRailView.java    左侧圆形缩略图轨道（自绘）
│   ├── GridListView.java     平铺列表（双指捏合只改列数，不误滚）
│   ├── FastScrollerView.java 右侧快速滚动条（平时透明且不吃触摸）
│   ├── InfoDialog.java       照片详情（完整 EXIF）
│   └── AlbumsActivity.java   旧的独立相册页（底栏「图集」已覆盖它的功能，保留不影响使用）
├── res/                      布局、颜色、主题、矢量图标、启动图标
└── assets/demo/              内置示例照片（5 张）
```

**零第三方依赖**（不用 AndroidX / Compose），只用 framework 原生 API，所以包只有 1.4 MB。

## 七、重新编译

**A. Android Studio**：打开 `moment-gallery` 目录 → 按提示生成 Gradle Wrapper → Run。

**B. 不用 Gradle**（本环境就是这么出包的）：需要 JDK 17 + Android SDK（build-tools 34.0.0、platforms android-34）

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./build.sh          # 产出 ./MomentGallery.apk
```

## 说明与已知限制

- **没有在真机上验证过**（本环境无真机、无模拟器/KVM）。编译、资源、签名、dex、布局 id 与代码引用都校验过，手感和个别机型行为需要你实测反馈。
- **HDR**：窗口色彩模式 + 广色域解码 + 视频直通。真正的 HDR 增益图（Apple/部分安卓的 HDR 照片）在旧 API 上没有公开解码入口，这类照片会按广色域（Display P3）显示，不会发灰但也不会比屏幕更亮；Android 14+ 的 `ImageDecoder` 增益图 API 还可以再接一层。
- **平铺格的缩略图是居中裁成方形**（相册墙的常规做法，快且整齐）；点进大图仍是完整不裁切。
- 实况图识别：Google 规范优先，其次扫尾部内嵌 MP4；华为/OPPO 等私有「动态照片」可能识别不到——告诉我机型和系统版本可以加解析。
