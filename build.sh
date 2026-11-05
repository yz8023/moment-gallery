#!/usr/bin/env bash
# 不使用 Gradle 的极简构建脚本（在此环境中可直接产出可安装的 APK）
# 需要：JDK 17、Android SDK (build-tools 34.0.0 + platforms/android-34)
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

SDK="${ANDROID_HOME:-$HOME/tools/sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
if [ -n "$JAVA_HOME" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi

PKG="com.moment.gallery"
OUT="$ROOT/build"
APP="$ROOT/app/src/main"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/res"

echo "==> 生成带 package 的 manifest"
sed "s|<manifest |<manifest package=\"$PKG\" |" "$APP/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

echo "==> 编译资源"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "==> 链接资源"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$OUT/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  -A "$APP/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code 1 \
  --version-name "1.0" \
  --auto-add-overlay

echo "==> 编译 Java"
find "$APP/java" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
javac --release 11 -nowarn -encoding UTF-8 -cp "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> dex"
"$BT/d8" --release --min-api 24 --lib "$PLATFORM" --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class")

echo "==> 打包 classes.dex"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
python3 - "$OUT/unaligned.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile, shutil, os
apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
shutil.move(apk, tmp)
with zipfile.ZipFile(tmp) as zin, zipfile.ZipFile(apk, "w", zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        if it.filename == "classes.dex":
            continue
        zout.writestr(it, zin.read(it.filename))
    zout.write(dex, "classes.dex")
os.remove(tmp)
PY

echo "==> 对齐 + 签名"
"$BT/zipalign" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
if [ ! -f "$ROOT/debug.keystore" ]; then
  keytool -genkeypair -keystore "$ROOT/debug.keystore" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$ROOT/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/MomentGallery-unsigned-tmp.apk" "$OUT/aligned.apk"
mv "$ROOT/MomentGallery-unsigned-tmp.apk" "$ROOT/MomentGallery.apk"
"$BT/apksigner" verify "$ROOT/MomentGallery.apk"

echo
echo "✅ 完成: $ROOT/MomentGallery.apk"
ls -lh "$ROOT/MomentGallery.apk"
