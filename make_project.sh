#!/usr/bin/env bash
set -e
ROOT=mruby-ime
rm -rf "$ROOT"
mkdir -p "$ROOT"/app/src/main/{cpp,java/com/example/mrubyime,assets,res/{xml,layout,values}}

# ---------- Gradle ----------
cat > "$ROOT/settings.gradle.kts" <<'EOF'
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "BoshiamyIME"
include(":app")
EOF

cat > "$ROOT/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
}
EOF

cat > "$ROOT/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
EOF

cat > "$ROOT/app/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.mrubyime"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.mrubyime"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
EOF

# ---------- Manifest / Resources ----------
cat > "$ROOT/app/src/main/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="@string/app_name"
                 android:theme="@android:style/Theme.DeviceDefault">
        <service android:name=".BoshiamyIME"
                 android:label="@string/ime_name"
                 android:permission="android.permission.BIND_INPUT_METHOD"
                 android:exported="true">
            <intent-filter>
                <action android:name="android.view.InputMethod" />
            </intent-filter>
            <meta-data android:name="android.view.im" android:resource="@xml/method" />
        </service>
    </application>
</manifest>
EOF

cat > "$ROOT/app/src/main/res/xml/method.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<input-method xmlns:android="http://schemas.android.com/apk/res/android">
    <subtype android:label="@string/ime_name" android:imeSubtypeMode="keyboard" />
</input-method>
EOF

cat > "$ROOT/app/src/main/res/values/strings.xml" <<'EOF'
<resources>
    <string name="app_name">Boshiamy mruby</string>
    <string name="ime_name">嘸蝦米 mruby</string>
</resources>
EOF

cat > "$ROOT/app/src/main/res/layout/keyboard_view.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:orientation="vertical"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="#222222">

    <HorizontalScrollView android:layout_width="match_parent" android:layout_height="48dp">
        <LinearLayout android:id="@+id/candidate_bar"
            android:orientation="horizontal"
            android:layout_width="wrap_content" android:layout_height="match_parent" />
    </HorizontalScrollView>

    <TextView android:id="@+id/buffer_view"
        android:layout_width="match_parent" android:layout_height="32dp"
        android:textColor="#FFFFFF" android:gravity="center_vertical"
        android:paddingStart="16dp" android:text="" />

    <LinearLayout android:id="@+id/key_rows"
        android:orientation="vertical"
        android:layout_width="match_parent" android:layout_height="wrap_content" />
</LinearLayout>
EOF

# ---------- Kotlin ----------
cat > "$ROOT/app/src/main/java/com/example/mrubyime/BoshiamyJNI.kt" <<'EOF'
package com.example.mrubyime

object BoshiamyJNI {
    init { System.loadLibrary("boshiamy") }
    external fun init(dictContent: String)
    external fun input(key: Char): Array<String>
    external fun select(index: Int): String?
    external fun selkeys(): String
    external fun buffer(): String
    external fun reset()
}
EOF

cat > "$ROOT/app/src/main/java/com/example/mrubyime/BoshiamyIME.kt" <<'EOF'
package com.example.mrubyime

import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class BoshiamyIME : InputMethodService() {
    private var candidates: Array<String> = emptyArray()
    private var selkeys: String = "0123456789"
    private lateinit var candidateBar: LinearLayout
    private lateinit var bufferView: TextView
    private lateinit var keyRows: LinearLayout

    override fun onCreate() {
        super.onCreate()
        val dict = assets.open("boshiamy.txt").bufferedReader().use { it.readText() }
        BoshiamyJNI.init(dict)
        selkeys = BoshiamyJNI.selkeys()
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        candidateBar = view.findViewById(R.id.candidate_bar)
        bufferView = view.findViewById(R.id.buffer_view)
        keyRows = view.findViewById(R.id.key_rows)
        buildKeyboard()
        return view
    }

    private fun buildKeyboard() {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm,.", "1234567890", "[]'")
        rows.forEach { row ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            row.forEach { ch ->
                val btn = TextView(this).apply {
                    text = ch.toString()
                    textSize = 18f
                    setPadding(28, 28, 28, 28)
                    setTextColor(0xFFFFFFFF.toInt())
                    setOnClickListener { onKeyPressed(ch) }
                }
                r.addView(btn)
            }
            keyRows.addView(r)
        }
    }

    private fun onKeyPressed(ch: Char) {
        if (candidates.isNotEmpty()) {
            val idx = selkeys.indexOf(ch)
            if (idx >= 0 && idx < candidates.size) {
                commit(candidates[idx]); return
            }
        }
        candidates = BoshiamyJNI.input(ch)
        bufferView.text = BoshiamyJNI.buffer()
        renderCandidates()
    }

    private fun renderCandidates() {
        candidateBar.removeAllViews()
        candidates.forEachIndexed { i, c ->
            val label = if (i < selkeys.length) selkeys[i].toString() else ""
            val tv = TextView(this).apply {
                text = "$label:$c"
                setPadding(16, 8, 16, 8)
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener { commit(c) }
            }
            candidateBar.addView(tv)
        }
    }

    private fun commit(text: String) {
        currentInputConnection.commitText(text, 1)
        BoshiamyJNI.reset()
        candidates = emptyArray()
        bufferView.text = ""
        renderCandidates()
    }
}
EOF

# ---------- C / CMake ----------
cat > "$ROOT/app/src/main/cpp/CMakeLists.txt" <<'EOF'
cmake_minimum_required(VERSION 3.22)
project(boshiamy LANGUAGES C)

# 指向你本机 mruby 源码目录（build_mruby.sh 会编译到这里）
set(MRUBY_ROOT ${CMAKE_SOURCE_DIR}/../../../../../mruby)

add_library(mruby STATIC IMPORTED)
set_target_properties(mruby PROPERTIES
    IMPORTED_LOCATION ${MRUBY_ROOT}/build/android-arm64-v8a/lib/libmruby.a)

add_library(boshiamy SHARED boshiamy_jni.c engine_irep.c)
target_include_directories(boshiamy PRIVATE ${MRUBY_ROOT}/include)
target_link_libraries(boshiamy mruby log)
EOF

cat > "$ROOT/app/src/main/cpp/boshiamy_jni.c" <<'EOF'
#include <jni.h>
#include <mruby.h>
#include <mruby/compile.h>
#include <mruby/array.h>
#include <mruby/string.h>

extern const uint8_t engine_irep[];
static mrb_state *mrb;
static mrb_value engine;

JNIEXPORT void JNICALL
Java_com_example_boshiamy_BoshiamyJNI_init(JNIEnv *env, jobject thiz, jstring dict) {
    mrb = mrb_open();
    mrb_load_irep(mrb, engine_irep);
    engine = mrb_funcall(mrb, mrb_top_self(mrb), "BoshiamyEngine", 0);
    const char *c = (*env)->GetStringUTFChars(env, dict, NULL);
    mrb_funcall(mrb, engine, "load_dictionary", 1, mrb_str_new_cstr(mrb, c));
    (*env)->ReleaseStringUTFChars(env, dict, c);
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_boshiamy_BoshiamyJNI_input(JNIEnv *env, jobject thiz, jchar key) {
    mrb_value arr = mrb_funcall(mrb, engine, "input", 1, mrb_fixnum_value((int)key));
    mrb_int len = mrb_ary_len(mrb, arr);
    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray out = (*env)->NewObjectArray(env, len, strCls, NULL);
    for (mrb_int i = 0; i < len; i++) {
        mrb_value s = mrb_ary_ref(mrb, arr, i);
        jstring js = (*env)->NewStringUTF(env, mrb_str_to_cstr(mrb, s));
        (*env)->SetObjectArrayElement(env, out, i, js);
        (*env)->DeleteLocalRef(env, js);
    }
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_example_boshiamy_BoshiamyJNI_select(JNIEnv *env, jobject thiz, jint index) {
    mrb_value r = mrb_funcall(mrb, engine, "select", 1, mrb_fixnum_value(index));
    if (mrb_nil_p(r)) return NULL;
    return (*env)->NewStringUTF(env, mrb_str_to_cstr(mrb, r));
}

JNIEXPORT jstring JNICALL
Java_com_example_boshiamy_BoshiamyJNI_selkeys(JNIEnv *env, jobject thiz) {
    mrb_value r = mrb_funcall(mrb, engine, "selkeys", 0);
    return (*env)->NewStringUTF(env, mrb_str_to_cstr(mrb, r));
}

JNIEXPORT jstring JNICALL
Java_com_example_boshiamy_BoshiamyJNI_buffer(JNIEnv *env, jobject thiz) {
    mrb_value r = mrb_funcall(mrb, engine, "buffer", 0);
    return (*env)->NewStringUTF(env, mrb_str_to_cstr(mrb, r));
}

JNIEXPORT void JNICALL
Java_com_example_boshiamy_BoshiamyJNI_reset(JNIEnv *env, jobject thiz) {
    mrb_funcall(mrb, engine, "reset", 0);
}
EOF

# ---------- mruby 引擎 ----------
cat > "$ROOT/app/src/main/assets/boshiamy_engine.rb" <<'EOF'
class BoshiamyEngine
  attr_reader :selkeys

  def initialize
    @table = {}
    @symbols = {}
    @buffer = ""
    @selkeys = "0123456789"
  end

  def load_dictionary(data)
    data.each_line do |line|
      line = line.chomp
      next if line.empty?
      if line.start_with?("%selkey")
        @selkeys = line.split(/\s+/)[1]
      elsif line.start_with?("%")
        next
      else
        code, char = line.split("\t")
        next unless code && char
        target = code.start_with?(",") ? @symbols : @table
        (target[code] ||= []) << char
      end
    end
  end

  def input(key)
    @buffer << key.chr
    @table[@buffer] || []
  end

  def select(index)
    chars = @table[@buffer] || []
    ch = chars[index]
    @buffer = "" if ch
    ch
  end

  def select_by_key(key)
    idx = @selkeys.index(key.chr)
    return nil if idx.nil?
    select(idx)
  end

  def buffer; @buffer; end
  def reset;  @buffer = ""; end
end
EOF

# ---------- 词典占位 ----------
cat > "$ROOT/app/src/main/assets/boshiamy.txt" <<'EOF'
%ename boshiamy
%cname 嘸蝦米
%selkey 0123456789
%chardef begin
a 對
aa 寸
aaa 鑫
%chardef end
EOF

# ---------- mruby 编译脚本 ----------
cat > "$ROOT/build_mruby.sh" <<'EOF'
#!/usr/bin/env bash
set -e
: "${ANDROID_NDK_HOME:?请先 export ANDROID_NDK_HOME}"
MRUBY_VERSION=3.3.0

if [ ! -d mruby ]; then
  curl -LO "https://github.com/mruby/mruby/archive/refs/tags/${MRUBY_VERSION}.tar.gz"
  tar xzf "${MRUBY_VERSION}.tar.gz"
  mv "mruby-${MRUBY_VERSION}" mruby
fi

# 1) host 编译 mrbc
cd mruby && rake MRUBY_CONFIG=default && cd ..
HOST_MRBC=mruby/build/host/bin/mrbc

# 2) 交叉编译 Android 版 libmruby.a
cat > mruby/build_config.rb <<EOC
MRuby::CrossBuild.new('android-arm64-v8a') do |conf|
  toolchain :clang
  bin = "#{ENV['ANDROID_NDK_HOME']}/toolchains/llvm/prebuilt/linux-x86_64/bin"
  conf.cc.command      = "#{bin}/aarch64-linux-android24-clang"
  conf.linker.command  = "#{bin}/aarch64-linux-android24-clang"
  conf.archiver.command= "#{bin}/llvm-ar"
  conf.gembox 'default'
end
EOC
(cd mruby && rake MRUBY_CONFIG=build_config.rb)

# 3) 引擎编译为字节码 C 数组
$HOST_MRBC -Bengine_irep \
  -o app/src/main/cpp/engine_irep.c \
  app/src/main/assets/boshiamy_engine.rb

echo "✅ mruby 完成。现在可以把你的完整 boshiamy.txt 覆盖到 app/src/main/assets/"
EOF
chmod +x "$ROOT/build_mruby.sh"

# ---------- README ----------
cat > "$ROOT/README.md" <<'EOF'
# Boshiamy mruby IME

## 构建步骤

1. `export ANDROID_NDK_HOME=/path/to/ndk`
2. `./build_mruby.sh`        # 编译 mruby 与 engine_irep.c
3. 用你的完整 boshiamy.txt 覆盖 `app/src/main/assets/boshiamy.txt`
4. 用 Android Studio 打开本目录，或 `./gradlew assembleDebug`
5. 安装 APK → 系统设置 → 语言与输入法 → 启用 "嘸蝦米 mruby"

## 关键点

- `%selkey` 由 mruby 运行时解析，`0123456789` / `1234567890` 均兼容
- 候选字标签从 `BoshiamyJNI.selkeys()` 动态取，UI 不硬编码
- 修改输入法逻辑只需改 `boshiamy_engine.rb` 后重跑 `build_mruby.sh`
EOF

# ---------- 打包 ----------
zip -r mruby-ime.zip "$ROOT" > /dev/null
echo "✅ 已生成 mruby-ime.zip"