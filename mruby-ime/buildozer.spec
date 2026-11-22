[app]

# (str) Title of your application
title = 小蝦米輸入法

# (str) Package name
package.name = mrubyime

# (str) Package domain (needed for android/ios packaging)
package.domain = com.example

# (str) Source code where the main application lives
source.dir = app/src/main/python

# (list) Application requirements
requirements = python3,kivy,pyjnius

# (str) Supported orientation (one of landscape, sensorLandscape, sensorPortrait, portrait, all)
orientation = portrait

# (list) Permissions
android.permissions = INTERNET

# (str) Android API version to build with
android.api = 24

# (str) Minimum API version to support
android.minapi = 24

# (str) Android NDK version to use
android.ndk = 30.0.16248370

# (str) Android SDK version to build with
android.sdk = 34

# (str) Android NDK path
android.ndk_path = /home/cc/Android/Sdk/ndk/30.0.16248370

# (str) Android SDK path
android.sdk_path = /home/cc/Android/Sdk

# (list) Additional source files to include
source.include_exts = py

# (list) Application source files to exclude
source.exclude_exts = spec

# (str) Application version
version = 1.0

# (int) Application version code
version.code = 1

# (str) Bootstrap to use
android.bootstrap = sdl2

# (int) Port to expose for debug
android.debug_port = 5000

# (str) Launch script
android.entrypoint = kivy_ime/keyboard.py

# (list) Source files to exclude from the build
source.exclude_dirs = tests, tests_data

# (bool) Copy libs instead of symlinking
android.copy_libs = 1

# (str) Android launch mode
android.launch_mode = singleTask

# (bool) Copy assets instead of copying files
android.copy_assets = 1

# (bool) Enable multi-threading for build
android.build_threads = 4

# (str) Android gradle plugin version
android.gradle_plugin_version = 7.4.2

# (list) Additional gradle arguments
android.gradle_args = -Pandroid.injected.build.mode=assembly

[buildozer]

# (int) Log level (0 = error only, 1 = info, 2 = debug)
log_level = 2

# (int) Display warning if buildozer is run as root (0 = false, 1 = true)
warn_on_root = 1

# (str) Path to buildozer working directory
build_dir = .buildozer

# (str) Path to build output
output_dir = ./bin

# (list) Source patterns to exclude from build
source.exclude_patterns = __pycache__,*.pyc

[app:source.include_patterns]
*.py
