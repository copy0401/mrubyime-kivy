#!/usr/bin/env bash
# Patch the python-for-android (p4a) checkout that buildozer clones into
# `.buildozer/android/platform/python-for-android/` so the Kivy APK can be
# built on this machine (Arch/CachyOS, Python 3.14 host, OpenSSL 3.5.x).
#
# buildozer re-clones p4a whenever `.buildozer/state.db` is missing, so this
# script is idempotent and must be re-run after a fresh clone. It is called
# from `buildozer_apk.sh`.
#
# Two fixes, both in the `hostpython3` recipe:
#
#   1. hostpython3 builds CPython 3.14 as the *host* interpreter (used only to
#      compile the target Android modules). Its `_ssl` module references the
#      deprecated protocol accessors SSLv3_method / TLSv1_method /
#      TLSv1_1_method / TLSv1_2_method. This system's /usr/lib/libssl.so is
#      OpenSSL 4.0, which removed them all (only TLS_method survives) ->
#      "_ssl failed to import: undefined symbol: TLSv1_2_method" makes
#      `make sharedinstall` fail. Define OPENSSL_NO_SSL3 / OPENSSL_NO_TLS1_METHOD
#      / OPENSSL_NO_TLS1_1_METHOD / OPENSSL_NO_TLS1_2_METHOD so `_ssl.c` skips
#      them and falls back to TLS_method.
#
#   2. `make -j $(cpu_count)` races on `./Programs/_freeze_module`: a parallel
#      job executes it before the kernel flushed the freshly linked binary ->
#      ETXTBSY ("Text file busy" / 文字檔忙碌中). Cap the job count at 4.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
recipe="$here/.buildozer/android/platform/python-for-android/pythonforandroid/recipes/hostpython3/__init__.py"

if [ ! -f "$recipe" ]; then
  echo "p4a hostpython3 recipe not found at $recipe" >&2
  echo "Run 'python -m buildozer android debug' once so buildozer clones p4a." >&2
  exit 1
fi

python3 - "$recipe" <<'PY'
import sys

path = sys.argv[1]
src = open(path, encoding="utf-8").read()
orig = src

# --- fix 1: disable TLSv1 / TLSv1.1 in the host _ssl build -----------------
old_env = '''    def get_recipe_env(self, arch=None):
        env = os.environ.copy()
        openssl_prereq = OpenSSLPrerequisite()
        if env.get("PKG_CONFIG_PATH", ""):
            env["PKG_CONFIG_PATH"] = os.pathsep.join(
                [openssl_prereq.pkg_config_location, env["PKG_CONFIG_PATH"]]
            )
        else:
            env["PKG_CONFIG_PATH"] = openssl_prereq.pkg_config_location
        return env
'''
new_env = '''    def get_recipe_env(self, arch=None):
        env = os.environ.copy()
        # Host python 3.14's `_ssl` references the deprecated protocol
        # accessors SSLv3_method / TLSv1_method / TLSv1_1_method /
        # TLSv1_2_method. This system's /usr/lib/libssl.so is OpenSSL 4.0,
        # which removed them all (only TLS_method survives), so `_ssl`
        # fails to import ("undefined symbol: TLSv1_2_method") and breaks
        # `make sharedinstall`. The host python is only used to compile
        # target modules and never negotiates TLS, so tell `_ssl.c` those
        # methods are unavailable; it then falls back to TLS_method.
        cflags = env.get("CFLAGS", "")
        cflags += (
            " -DOPENSSL_NO_SSL3"
            " -DOPENSSL_NO_TLS1_METHOD"
            " -DOPENSSL_NO_TLS1_1_METHOD"
            " -DOPENSSL_NO_TLS1_2_METHOD"
        )
        env["CFLAGS"] = cflags
        return env
'''
if old_env in src:
    src = src.replace(old_env, new_env, 1)
elif "OPENSSL_NO_TLS1_2_METHOD" not in src:
    sys.exit("fix 1: could not find get_recipe_env() to patch")

# --- fix 2: cap host python make parallelism ------------------------------
old_make = '            shprint(sh.make, "-j", str(cpu_count()), "-C", build_dir, _env=env)'
new_make = '''            # Use a small -j for the host python build. With -j 24 the
            # freshly linked ./Programs/_freeze_module is executed by a
            # parallel job before the kernel has flushed it, which fails
            # with ETXTBSY ("Text file busy" / 文字檔忙碌中) on this
            # filesystem. Serialising (or low parallelism) avoids the race.
            jobs = min(cpu_count(), 4)
            shprint(sh.make, "-j", str(jobs), "-C", build_dir, _env=env)'''
if old_make in src:
    src = src.replace(old_make, new_make, 1)
elif "jobs = min(cpu_count(), 4)" not in src:
    sys.exit("fix 2: could not find the make -j line to patch")

if src != orig:
    open(path, "w", encoding="utf-8").write(src)
    print("patched", path)
else:
    print("already patched", path)
PY
