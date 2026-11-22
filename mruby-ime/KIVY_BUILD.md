# Kivy frontend migration — build notes

Goal: replace the Kotlin view-hierarchy keyboard with a Python/Kivy input view,
keeping the Kotlin `InputMethodService` (the mruby engine + JNI stay untouched).

Branch: `feature/kivy-migration` (baseline before the migration: `c890474`).

## Current state

| Piece | Status |
| --- | --- |
| Kotlin IME + mruby engine + JNI | **working**, builds to a ~1.2 MB release APK |
| `KivyBridge` (TCP JSON on 127.0.0.1:19876) in `MrubyimeIME.kt` | written, unused by the shipped path |
| `app/src/main/python/kivy_ime/keyboard.py` (Kivy UI + socket client) | written |
| `app/src/main/python/main.py` | **temporary Hello World** — exists only to prove the Kivy APK toolchain |
| Kivy APK (buildozer) | **not built yet** — blocked on host-python compile issues (see below) |

`onCreateInputView()` still returns the Kotlin `KeyboardView`; the shipped APK is
the normal (non-Kivy) keyboard.

## Building the Kivy APK

Use the wrapper — it sets the toolchain env and re-applies the p4a patches:

```bash
cd mruby-ime
./buildozer_apk.sh debug      # -> bin/*.apk
```

First run downloads ~1 GB of recipes and compiles CPython/SDL2/Kivy for
`arm64-v8a`; expect tens of minutes. Do not interrupt it.

### Why the wrapper + patches exist

buildozer clones python-for-android into
`.buildozer/android/platform/python-for-android/` (gitignored, so a fresh clone
loses local edits). `patch_p4a.sh` (idempotent) patches the `hostpython3` recipe:

1. **`_ssl` vs OpenSSL 4.0.** hostpython3 builds CPython 3.14 as the *host*
   interpreter. Its `_ssl` module references `SSLv3_method` / `TLSv1_method` /
   `TLSv1_1_method` / `TLSv1_2_method`. This machine's `/usr/lib/libssl.so` is
   **OpenSSL 4.0**, which removed all of them (only `TLS_method` remains), so
   `_ssl` fails to import (`undefined symbol: TLSv1_2_method`) and
   `make sharedinstall` aborts. The patch adds
   `-DOPENSSL_NO_SSL3 -DOPENSSL_NO_TLS1_METHOD -DOPENSSL_NO_TLS1_1_METHOD
   -DOPENSSL_NO_TLS1_2_METHOD` to `CFLAGS`, so `_ssl.c` skips those accessors and
   falls back to `TLS_method`. The host python only compiles target modules and
   never negotiates TLS.

2. **`make -j 24` ETXTBSY.** The parallel host-python build runs the freshly
   linked `./Programs/_freeze_module` before the kernel flushed it →
   `ETXTBSY` / 「文字檔忙碌中」. The patch caps `-j` at 4.

Other environment quirks (not repo files):

- buildozer only runs from the venv:
  `/home/cc/Desktop/mrubyime_mini/kivy-venv/bin/python -m buildozer …`
- buildozer downloads its **own** SDK/NDK into `~/.buildozer/android/platform/`
  and ignores `android.sdk_path`/`android.ndk_path`; that is expected and works.
- `sdkmanager --licenses` hangs on this SDK CLI; the licenses are already
  accepted under `/home/cc/Android/Sdk/licenses/`, so leave it alone.

## Next steps once the APK builds

1. Confirm the Hello World APK installs and launches (toolchain proof).
2. Switch `main.py` to the real Kivy keyboard UI and verify it runs standalone.
3. Embed Kivy in the IME: `onCreateInputView()` returns a `View` hosting the
   Kivy surface; connect `KivyBridge` ↔ `keyboard.py`; route key events through
   `KivyBridge` → `onKeyPressedPublic` etc.
4. Only then remove the Kotlin `KeyboardView` renderer path.

Rollback: `git checkout master`, or `git reset --hard c890474` for the pre-migration
tree.
