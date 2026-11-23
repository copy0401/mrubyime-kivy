# KIVY_BUILD.md — Kivy frontend migration build notes

**Goal:** replace the Kotlin view-hierarchy keyboard (`KeyboardView` +
`KeyboardRenderer`) with a Python/Kivy input view, keeping the Kotlin
`InputMethodService` (mruby engine + JNI) unchanged.

**Branch:** `feature/kivy-migration` (baseline before migration: `c890474`).

---

## Current state (2026-11-23)

| Piece | Status |
|-------|--------|
| Kotlin IME + mruby engine + JNI | **working**, builds to a ~1.2 MB release APK |
| `KivyBridge` (TCP JSON on 127.0.0.1:19876) in `MrubyimeIME.kt` | implemented, used by Kivy UI |
| `app/src/main/python/kivy_ime/keyboard.py` (Kivy UI + socket client) | **complete & verified** |
| `app/src/main/python/kivy_ime/bridge.py` | **complete** |
| `app/src/main/python/main.py` | Kivy app entry point |
| `KivyImeHost.kt` | Android host: unpacks private.tar, boots SDL + Python, hosts SDL surface |
| `PythonActivity.java` | Java shim for p4a `android` python module (`mActivity` + Context delegation) |
| `org/renpy/android/Hardware.java` | JNI shim for `android` module's DPI query |
| `sync_python.sh` | **fast Python-only rebuild** (2 s incremental) |
| Kivy APK (buildozer) | **built & working** — 21 MB release APK |

The shipped APK **is the Kivy keyboard** (`onCreateInputView()` returns the
SDL surface hosted by `KivyImeHost`).  The old Kotlin `KeyboardView` path is
kept only as fallback.

---

## Fast iteration: Python-only changes (15–30 s)

```bash
./sync_python.sh          # 2 s incremental + install
```

What it does:

1. Compiles 5 Python files with `-OO`
2. Replaces 5 `.pyc` entries **in-place** inside `assets/private.tar`
   (keeps p4a's exact member set, DIR types, USTAR format, gzip wrapper)
3. Updates `private_version` in `res/values/strings.xml`
4. `gradlew assembleRelease --offline` (≈ 1 s incremental)
6. `adb install -r` (21 MB APK, 1.3 s)

---

## Full build (when Kotlin / mruby / buildozer changes)

```bash
cd mruby-ime && ./build_all.sh          # mruby ireps + JSON + APK + install
# or
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleRelease
```

---

## Build environment (fixed)

```
JAVA_HOME=/opt/android-studio/jbr
ANDROID_SDK_ROOT=/home/cc/Android/Sdk
ANDROID_NDK_HOME=/home/cc/Android/Sdk/ndk/30.0.16248370
PATH=/home/cc/Desktop/mrubyime_mini/kivy-venv/bin:$PATH
```

- `buildozer` **must** be invoked from the venv (`/home/cc/Desktop/mrubyime_mini/kivy-venv/bin/python -m buildozer …`)
- Buildozer downloads its own SDK/NDK to `~/.buildozer/android/platform/` and ignores `android.sdk_path`/`android.ndk_path`
- `sdkmanager --licenses` hangs on this SDK CLI; licenses already accepted in `/home/cc/Android/Sdk/licenses/` (`android-sdk-license` = `24333f8a63b6825ea9c5514f83c2829b004d1fee`)
- Buildozer re-clones p4a when `.buildozer/state.db` is missing — always build via `./sync_python.sh` or `./buildozer_apk.sh` which apply the p4a patches first.

---

## p4a patches (`patch_p4a.sh`)

| # | Fix | Why |
|---|-----|-----|
| 1 | `hostpython3` `_ssl` CFLAGS: `-DOPENSSL_NO_SSL3 -DOPENSSL_NO_TLS1_METHOD -DOPENSSL_NO_TLS1_1_METHOD -DOPENSSL_NO_TLS1_2_METHOD` | System OpenSSL 4.0 removed `TLSv1_method` etc.; `_ssl` would fail with `undefined symbol: TLSv1_2_method` |
| 2 | `hostpython3` `make -j$(min(cpu_count, 4))` | `ETXTBSY` ("Text file busy") on `./Programs/_freeze_module` with `-j 24` |
| 3 | Skip `pip install -U pip` in p4a's build.py | pip 26.3 from venv breaks on Python 3.14 (`ImportError: cannot import name 'capture_logging'`) |
| 4 | Gradle wrapper → `gradle-8.13-all.zip` | 8.14.3 needs Java < 25; Android Studio JBR is 25.0.4 |
| 5 | AGP `8.8.0` in `build.tmpl.gradle` | 8.11.0 incompatible with Gradle 9.0 |
| 5 | Pass `dist_name` to build.py + `settings.gradle` template | Fixes "project name must not be empty" on Gradle 9 |

Run `./patch_p4a.sh` after any fresh p4a clone (buildozer re-clones when `.buildozer/state.db` is missing).

---

## Build verification

```bash
# 1. Python-only change
./sync_python.sh                    # 2 s incremental, installs APK

# 2. Desktop smoke test (8 s, no phone)
./kivy-venv/bin/python /tmp/opencode/verify_fixes.py
# 4 UI fixes verified: backspace no arrow, chip bg/bold, placeholder flat, bold selection

# 3. Visual check
./kivy-venv/bin/python /tmp/opencode/kivy_smoke.py
# writes /tmp/opencode/kivy_zh.png / kivy_en.png (10 s)

# 4. Full APK build + install
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleRelease --offline
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## APK size breakdown (release)

| Component | Size |
|-----------|------|
| `libmrubyime.so` (mruby + engine) | ~600 KB |
| `libpybundle.so` (python bundle tar) | ~13 MB |
| `libpython3.14.so` | ~5.7 MB |
| `libSDL2.so` + mixers/ttf/image | ~3 MB |
| `libcrypto.so` / `libssl.so` / `libffi.so` / `libsqlite3.so` | ~1.2 MB |
| Java/Kotlin code (R8 + shrinkResources) | 77 KB dex |
| **Total APK** | **≈ 21 MB** (was ~4.7 MB before Kivy + python) |

Size pass: R8 minify + resource shrinking, `useLegacyPackaging=true` (compresses .so),
WebP launcher icons, minimal gembox (no `Set`/`Fiber`/`Enumerator`/`Time`/`Struct`/`Data`/`Pack`/`Random`/`ObjectSpace`).

---

## Known issues / watch list

1. **`default_input_method` reset** — Android sometimes reverts to Google LatinIME.
   Workaround: `adb shell settings put secure default_input_method com.example.mrubyime/.MrubyimeIME`
2. **StrictMode network on main thread** — `KivyBridge` writes from main thread;
   `MrubyimeIME.onCreate` sets `ThreadPolicy.permitNetwork()`.
3. **StrictMode GZIPInputStream** — `AssetExtract` uses `GZIPInputStream` on the
   main thread; same `ThreadPolicy.permitNetwork()` covers it.
4. **`libpybundle.so` strip** — it's a tar, not ELF; `packagingOptions.jniLibs`
   `keepDebugSymbols += "**/libpybundle.so"` in `build.gradle.kts`.
5. **R8 keeps `org.libsdl.app.**` / `org.kivy.android.**` / `org.renpy.android.**`
   (proguard-rules.pro) — JNI resolves these by class/method name.

---

## Rollback

```bash
git checkout master                    # back to pure Kotlin keyboard
# or
git reset --hard c890474              # pre-migration tree
```