#!/bin/bash
# 构建脚本（相对路径版，clone 下来直接能编）
set -x
SDK=${ANDROID_SDK:-/opt/android-sdk}
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$HERE
OUT=$SRC/out

rm -rf $OUT
mkdir -p $OUT/classes $OUT/dex

# 0. 桌面图标名 —— 它钉死在 APK 里，装完改不了，所以只能编译期决定。
#    默认「闹钟」；想换：APP_NAME="某某闹钟" bash build.sh
APP_NAME=${APP_NAME:-闹钟}
sed -i "s/android:label=\"[^\"]*\"/android:label=\"$APP_NAME\"/g" $SRC/AndroidManifest.xml

# 1. aapt2 link -> base.apk
$BT/aapt2 link -o $OUT/base.apk -I $PLATFORM --manifest $SRC/AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 34 --version-code 12 --version-name 7.5

# 2. javac
cd $OUT
JCS=$(find $SRC/src -name "*.java")
javac -source 11 -target 11 -classpath $PLATFORM -d $OUT/classes $JCS 2>&1 | head -40

# 3. d8 -> classes.dex
$BT/d8 --lib $PLATFORM --release --output $OUT/dex $(find $OUT/classes -name "*.class")

# 4. zip dex into apk
cp $OUT/dex/classes.dex $OUT/classes.dex
cd $OUT && zip -uj base.apk classes.dex

echo "=== build done ==="
ls -la $OUT/base.apk