#!/usr/bin/env bash
set -e

if [ -z "$ANDROID_NDK_HOME" ]; then
    export ANDROID_NDK_HOME="/home/cc/Android/Sdk/ndk/30.0.16248370"
    echo "使用預設 ANDROID_NDK_HOME=$ANDROID_NDK_HOME"
fi

export PATH="$HOME/.local/share/gem/ruby/3.4.0/bin:$PATH"
export JAVA_HOME="/opt/android-studio/jbr"
unset ANDROID_PREFS_ROOT

echo "==> 1. 編譯 mruby 與生成引擎 C 陣列..."
./build_mruby.sh

echo "==> 2. 編譯 Android APK..."
./gradlew assembleDebug

echo "==> 3. 安裝到已連接的手機..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "🎉 全部完成！請至手機設定中啟用「小蝦米輸入法」。"
