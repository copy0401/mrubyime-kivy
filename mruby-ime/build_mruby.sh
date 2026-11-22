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
#
# Size-optimized for the APK: only the gems the engine/DSL actually use, -Os,
# thin LTO, per-function sections and hidden visibility. The JNI symbols stay
# exported (JNIEXPORT carries default visibility); mruby-compiler is kept so
# user-imported keyboard layouts still compile on device (mrb_load_string).
# NOTE: user Ruby engines were removed — the engine only runs as bytecode.
cat > mruby/build_config.rb <<EOC
MRuby::CrossBuild.new('android-arm64-v8a') do |conf|
  toolchain :clang
  bin = "#{ENV['ANDROID_NDK_HOME']}/toolchains/llvm/prebuilt/linux-x86_64/bin"
  conf.cc.command      = "#{bin}/aarch64-linux-android24-clang"
  conf.linker.command  = "#{bin}/aarch64-linux-android24-clang"
  conf.archiver.command= "#{bin}/llvm-ar"
  conf.cc.flags       << "-fPIC"
  # size build
  conf.cc.flags       << "-Os" << "-DNDEBUG" << "-g0"
  conf.cc.flags       << "-ffunction-sections" << "-fdata-sections"
  conf.cc.flags       << "-fvisibility=hidden"
  conf.cc.flags       << "-fno-unwind-tables" << "-fno-asynchronous-unwind-tables"
  conf.cc.flags       << "-flto=thin"
  conf.linker.flags   << "-flto=thin"
  conf.cc.defines     << "MRB_UTF8_STRING"
  # Uniliu's word list makes Hashes with >131072 entries; Hash#values/#keys
  # build an RArray of every entry, so lift mruby's default array cap.
  conf.cc.defines     << "MRB_ARY_LENGTH_MAX=0"
  # Larger GC arena: loading a large .cin table in one call then overflows the
  # default 100-slot arena, and the forced recycle can collect an object still
  # in use (surfacing as `undefined method 'ar'` / `'MAX'` / `'bsearch_index'`).
  conf.cc.defines     << "MRB_GC_ARENA_SIZE=4096"
  # Minimal gem set — everything mrubyime_engine.rb / keyboard_dsl.rb touch.
  # (mruby-object-ext provides instance_exec for the DSL; mruby-sprintf the
  # \\uXXXX quoting. Set/Fiber/Enumerator/Lazy/Time/Struct/Pack are NOT built.)
  conf.gem core: 'mruby-enum-ext'      # sort_by, each_with_index
  conf.gem core: 'mruby-array-ext'
  conf.gem core: 'mruby-hash-ext'
  conf.gem core: 'mruby-string-ext'
  conf.gem core: 'mruby-symbol-ext'
  conf.gem core: 'mruby-range-ext'
  conf.gem core: 'mruby-numeric-ext'
  conf.gem core: 'mruby-kernel-ext'
  conf.gem core: 'mruby-proc-ext'
  conf.gem core: 'mruby-object-ext'    # instance_exec (DSL builders)
  conf.gem core: 'mruby-sprintf'
  # Needed on-device to compile user-imported keyboard layouts (Ruby DSL).
  # The built-in layout is shipped as bytecode and needs no compiler at runtime.
  conf.gem core: 'mruby-compiler'
end
EOC
(cd mruby && rake MRUBY_CONFIG=build_config.rb)

# 3) 引擎编译为字节码 C 数组（源码在 app/src/main/mruby/，不进 assets）
$HOST_MRBC -Bengine_irep \
  -o app/src/main/cpp/engine_irep.c \
  app/src/main/mruby/mrubyime_engine.rb

# 4) 鍵盤 DSL 與內建佈局編譯為位元組碼 C 陣列
#    keyboard_layout.rb 仍留在 assets：它是使用者自訂佈局的種子檔。
$HOST_MRBC -Bkeyboard_dsl_irep \
  -o app/src/main/cpp/keyboard_dsl_irep.c \
  app/src/main/assets/keyboard_dsl.rb
$HOST_MRBC -Bkeyboard_layout_irep \
  -o app/src/main/cpp/keyboard_layout_irep.c \
  app/src/main/assets/keyboard_layout.rb

# 5) 產生內建佈局的 IR JSON（assets 備援 + golden snapshot）
#    用系統 ruby 執行同一個 DSL；裝置上仍以執行期 JNI 為主。
ruby - <<'RUBY'
load "app/src/main/assets/keyboard_dsl.rb"
load "app/src/main/assets/keyboard_layout.rb"
File.write("app/src/main/assets/keyboard_layout.json",
           Keyboard.layout_serialized("mode=chinese\ntheme=light\n"))
RUBY

echo "✅ mruby 完成。"
