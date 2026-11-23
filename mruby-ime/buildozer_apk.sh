#!/usr/bin/env bash
# Build the Kivy APK with buildozer on this machine.
#
# Why a wrapper: the toolchain (JDK, SDK, NDK) and the Python venv that has
# buildozer are not on PATH by default, and python-for-android needs the two
# hostpython3 patches applied (see patch_p4a.sh). Invoke buildozer through
# this script rather than directly.
#
#   ./buildozer_apk.sh            # debug APK  -> bin/*.apk
#   ./buildozer_apk.sh release    # release APK (needs [app] release config)
#
# First run downloads ~1 GB of p4a recipes and compiles CPython/SDL2/Kivy for
# arm64-v8a; it takes tens of minutes and must not be interrupted.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here"

export JAVA_HOME=/opt/android-studio/jbr
export ANDROID_SDK_ROOT=/home/cc/Android/Sdk
export ANDROID_NDK_HOME=/home/cc/Android/Sdk/ndk/30.0.16248370
# pyjnius' build backend shells out to `javac`; JAVA_HOME alone is not
# enough because it looks it up on PATH (fails with "Unable to find javac").
export PATH="$JAVA_HOME/bin:/home/cc/Desktop/mrubyime_mini/kivy-venv/bin:$PATH"
hash -r

command="${1:-debug}"

# Apply the p4a hostpython3 patches (idempotent; buildozer re-clones p4a
# when .buildozer/state.db is missing, which drops the patches).
./patch_p4a.sh

python -m buildozer android "$command"
