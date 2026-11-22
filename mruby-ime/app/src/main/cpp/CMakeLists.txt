cmake_minimum_required(VERSION 3.22)
project(mrubyime LANGUAGES C)

# 指向你本机 mruby 源码目录（build_mruby.sh 会编译到这里）
set(MRUBY_ROOT ${CMAKE_SOURCE_DIR}/../../../../mruby)

add_library(mruby STATIC IMPORTED)
set_target_properties(mruby PROPERTIES
    IMPORTED_LOCATION ${MRUBY_ROOT}/build/android-arm64-v8a/lib/libmruby.a)

add_library(mrubyime SHARED mrubyime_jni.c engine_irep.c keyboard_dsl_irep.c keyboard_layout_irep.c)
target_include_directories(mrubyime PRIVATE ${MRUBY_ROOT}/include)
target_link_libraries(mrubyime mruby log)

# Size build: match the libmruby config in build_mruby.sh (-Os, thin LTO,
# function sections, hidden visibility) and let the linker drop dead code.
# JNIEXPORT symbols keep default visibility, so the JNI surface still exports.
target_compile_options(mrubyime PRIVATE
    -Os -g0 -DNDEBUG
    -ffunction-sections -fdata-sections
    -fvisibility=hidden
    -fno-unwind-tables -fno-asynchronous-unwind-tables
    -flto=thin)
target_link_options(mrubyime PRIVATE
    -flto=thin
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL)
