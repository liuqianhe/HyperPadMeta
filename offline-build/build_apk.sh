#!/usr/bin/env bash
# HyperPadMeta 离线构建脚本（不需要 Gradle、不需要联网）
#
# 仓库主流程仍是 Gradle（app 模块，Kotlin）。本目录收录的是设备上实际安装/验证过的那一版
# 纯 Java 实现 + 手工构建链，用于「装不了 AGP 依赖 / 无外网 maven」时复现出包。
#
# 用法：
#   ./build_apk.sh                              # 产物 build/HyperPadMeta-HyperOS3.apk
#   ANDROID_JAR=... BT=... JAVA_HOME=... ./build_apk.sh
#
# 依赖：Android SDK build-tools（aapt2/d8/zipalign/apksigner）、JDK 17、Git Bash（cygpath）。

set -e

HBM="$(cd "$(dirname "$0")" && pwd)"
HB="$(cygpath -m "$HBM" 2>/dev/null || echo "$HBM")"

ANDROID_HOME="${ANDROID_HOME:-C:/Paths/Android_Path}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_HOME/platforms/android-36/android.jar}"
BT="${BT:-$ANDROID_HOME/build-tools/36.1.0}"
JAVA_HOME="${JAVA_HOME:-C:/Paths/java_jdk/jdk17}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
JAR="$JAVA_HOME/bin/jar"
export JAVA_HOME
KEYTOOL="$JAVA_HOME/bin/keytool"

KS="${KS:-$HB/mykey.jks}"
OUT="$HB/build/HyperPadMeta-HyperOS3.apk"

echo "== 1. aapt2 编译资源 =="
rm -rf "$HB/out" "$HB/build"
mkdir -p "$HB/out" "$HB/build/gen"
"$BT/aapt2" compile -o "$HB/build/res.zip" --dir "$HB/res"
ls -l "$HB/build/res.zip"

echo "== 2. aapt2 link（同时生成 R.java 供设置页引用）=="
"$BT/aapt2" link -o "$HB/build/base.apk" -I "$ANDROID_JAR" \
  --manifest "$HB/AndroidManifest.xml" --java "$HB/build/gen" "$HB/build/res.zip"
ls -l "$HB/build/base.apk"

echo "== 3. 编译 Java（Xposed 桩仅编译期，不打入 DEX）=="
"$JAVAC" -encoding UTF-8 -cp "$ANDROID_JAR" -d "$HB/out" \
  $(find "$HB/src" -name '*.java') $(find "$HB/build/gen" -name '*.java')
find "$HB/out" -name '*.class' | sed "s#$HB/##" | head -20

echo "== 4. d8 打包（仅 pub/*，排除 Xposed 桩 de/robv；经 cmd 调 d8.bat）=="
mkdir -p "$HB/build/dexout"
D8_BAT="${BT//\//\\}\\d8.bat"
cmd //c "$D8_BAT" --min-api 30 --lib "$ANDROID_JAR" --classpath "$HB/out" \
  --output "$HB/build/dexout" $(find "$HB/out/pub" -name '*.class')
cp "$HB/build/dexout/classes.dex" "$HB/build/classes.dex"
ls -l "$HB/build/classes.dex"

echo "== 5. 塞入 classes.dex 与 assets/xposed_init（store 不压缩）=="
mkdir -p "$HB/build/assets"
cp "$HB/assets/xposed_init" "$HB/build/assets/xposed_init"
cd "$HB/build" && "$JAR" uf0 base.apk classes.dex assets/xposed_init
echo "base.apk 条目：" && "$JAR" tf "$HB/build/base.apk" | tail -6

echo "== 6. zipalign =="
"$BT/zipalign" -p 4 "$HB/build/base.apk" "$HB/build/aligned.apk"
ls -l "$HB/build/aligned.apk"

echo "== 7. 签名（无 keystore 则生成；不要在仓库里提交 jks）=="
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -v -keystore "$KS" -alias mp -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=HyperPadMeta" >/dev/null 2>&1
fi
"$JAVA" -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" "$HB/build/aligned.apk"

echo "== 8. 校验 =="
"$JAVA" -jar "$BT/lib/apksigner.jar" verify --print-certs "$OUT" 2>&1 | head -6
ls -l "$OUT"
