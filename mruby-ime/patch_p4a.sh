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

# --- fix 3: skip pip upgrade in build.py (pip 26.3 breaks on Py 3.14) ----
build_py = path.replace("recipes/hostpython3/__init__.py", "build.py")
build_src = open(build_py, encoding="utf-8").read()
orig_build = build_src

old_pip_upgrade = '''        # Prepare base environment and upgrade pip:
        base_env = dict(copy.copy(os.environ))
        base_env["PYTHONPATH"] = ctx.get_site_packages_dir(arch)
        info('Upgrade pip to latest version')
        shprint(sh.bash, '-c', (
            "source venv/bin/activate && pip install -U pip"
        ), _env=copy.copy(base_env))'''

new_pip_upgrade = '''        # Prepare base environment:
        base_env = dict(copy.copy(os.environ))
        base_env["PYTHONPATH"] = ctx.get_site_packages_dir(arch)
        # SKIP pip upgrade — pip 26.3 from venv breaks on Python 3.14
        # info('Upgrade pip to latest version')
        # shprint(sh.bash, '-c', (
        #     "source venv/bin/activate && pip install -U pip"
        # ), _env=copy.copy(base_env))'''

if old_pip_upgrade in build_src:
    build_src = build_src.replace(old_pip_upgrade, new_pip_upgrade, 1)
    open(build_py, "w", encoding="utf-8").write(build_src)
    print("patched", build_py)
elif "SKIP pip upgrade" not in build_src:
    sys.exit("fix 3: could not find pip upgrade block to patch in build.py")

# --- fix 4: gradle wrapper version for JDK 25 compatibility ---
wrapper_props = path.replace("recipes/hostpython3/__init__.py",
                            "bootstraps/common/build/gradle/wrapper/gradle-wrapper.properties")
wrapper_src = open(wrapper_props, encoding="utf-8").read()
orig_wrapper = wrapper_src

old_gradle = "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-all.zip"
new_gradle = "# Use 9.0 with AGP 8.8 to support JDK 25 (major version 69)\ndistributionUrl=https\\://services.gradle.org/distributions/gradle-9.0-bin.zip"
if old_gradle in wrapper_src:
    wrapper_src = wrapper_src.replace(old_gradle, new_gradle, 1)
    open(wrapper_props, "w", encoding="utf-8").write(wrapper_src)
    print("patched", wrapper_props)
elif "gradle-9.0-bin.zip" not in wrapper_src:
    sys.exit("fix 4: could not find gradle wrapper URL to patch")

# --- fix 5: Android Gradle Plugin 8.8 for Gradle 9.0 + JDK 25 ---
build_tmpl = path.replace("recipes/hostpython3/__init__.py",
                         "bootstraps/common/build/templates/build.tmpl.gradle")
build_tmpl_src = open(build_tmpl, encoding="utf-8").read()
orig_build_tmpl = build_tmpl_src

old_agp = "classpath 'com.android.tools.build:gradle:8.7.0'"
new_agp = "classpath 'com.android.tools.build:gradle:8.8.0'"
if old_agp in build_tmpl_src:
    build_tmpl_src = build_tmpl_src.replace(old_agp, new_agp, 1)
    open(build_tmpl, "w", encoding="utf-8").write(build_tmpl_src)
    print("patched", build_tmpl)
elif "8.8.0" not in build_tmpl_src:
    sys.exit("fix 5: could not find AGP version to patch in build.tmpl.gradle")

# --- fix 6: pass dist_name to build.py and add it to argument parser ---
toolchain_py = path.replace("recipes/hostpython3/__init__.py", "toolchain.py")
tc_src = open(toolchain_py, encoding="utf-8").read()
if 'args.unknown_args += ["--dist-name", args.dist_name]' not in tc_src:
    old_dist = '''        if getattr(args, "private", None) is not None:
            # Pass this value on to the internal bootstrap build.py:
            args.unknown_args += ["--private", args.private]'''
    new_dist = '''        if getattr(args, "private", None) is not None:
            # Pass this value on to the internal bootstrap build.py:
            args.unknown_args += ["--private", args.private]
        if getattr(args, "dist_name", None):
            args.unknown_args += ["--dist-name", args.dist_name]'''
    if old_dist in tc_src:
        tc_src = tc_src.replace(old_dist, new_dist, 1)
        open(toolchain_py, "w", encoding="utf-8").write(tc_src)
        print("patched", toolchain_py)
    else:
        sys.exit("fix 6: could not find private arg location in toolchain.py")

# Add --dist-name to build.py argument parser
build_py = path.replace("recipes/hostpython3/__init__.py",
                       "bootstraps/common/build/build.py")
bp_src = open(build_py, encoding="utf-8").read()
if "--dist-name" not in bp_src:
    old_name = '''    ap.add_argument('--name', dest='name',
                    help=('The human-readable name of the project.'),
                    required=True)'''
    new_name = '''    ap.add_argument('--name', dest='name',
                    help=('The human-readable name of the project.'),
                    required=True)
    ap.add_argument('--dist-name', dest='dist_name',
                    help=('The name of the distribution (used for rootProject.name).'),
                    default='')'''
    if old_name in bp_src:
        bp_src = bp_src.replace(old_name, new_name, 1)
        open(build_py, "w", encoding="utf-8").write(bp_src)
        print("patched", build_py)
    else:
        sys.exit("fix 6: could not find name arg in build.py")

if src != orig:
    open(path, "w", encoding="utf-8").write(src)
    print("patched", path)
else:
    print("already patched", path)
PY
