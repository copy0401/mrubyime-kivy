#!/usr/bin/env bash
# Rebuild the python bundle asset (assets/private.tar) from
# app/src/main/python/**, refresh its sha1 in res/values/strings.xml, rebuild
# the APK and install it -- without a python-for-android (buildozer) run.
#
# Use this for ANY Python-only change (kivy_ime/keyboard.py layout, theme
# colours, gestures, chips...). It is the fast path: ~10 s instead of the
# ~2 min full buildozer rebuild.
#
#   ./sync_python.sh            # build + install, keep app data
#
# IMPORTANT: p4a's private.tar is a **gzip-compressed** USTAR tar (its name
# just lacks the .gz). The device unpacks it with org.kamranzafar.jtar wrapped
# in a GZIPInputStream (AssetExtract.extractTar). So the replacement must keep
# p4a's exact entry set, member types (DIR vs REG) and the gzip wrapper:
#   - pax headers      -> jtar mis-parses the archive
#   - a directory written as a zero-length REG file -> the next entry under it
#     fails to extract ("could not open <path>")
#   - no gzip          -> GZIPInputStream throws, nothing is extracted
# In all three cases the app cannot import kivy_ime and the IME surface stays
# black (the SDL surface exists, Kivy never ran).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here"

APP="$here/app/src/main/python"
TAR="$here/app/src/main/assets/private.tar"
RES="$here/app/src/main/res/values/strings.xml"

sha1="$(mktemp)"; trap 'rm -f "$sha1"' EXIT

python3 - "$APP" "$TAR" "$sha1" <<'PY'
import hashlib, os, py_compile, shutil, sys, tarfile, tempfile, io

app, out_tar, sha1_file = sys.argv[1:4]

# The tar we ship IS the reference: p4a built it once (sitecustomize.py
# included), so repacking it keeps the exact entry set, member types (DIR vs
# REG) and gzip wrapper -- which is what org.kamranzafar.jtar on device needs.
base = tarfile.open(out_tar)
members = base.getmembers()
data = {m.name: (base.extractfile(m).read() if m.isfile() else b"")
        for m in members}
base.close()

# Compile the tree the way p4a does (PYTHONOPTIMIZE=2 -> -OO).
# NOTE: a FIXED build dir, not mkdtemp(): a .pyc records the module's
# __file__, so a changing path changes the bytes and the sha1 with them.
build = "/tmp/kivy_ime_pyc_build"
if os.path.isdir(build):
    shutil.rmtree(build)
os.makedirs(build)
for rel in ["main.py", "sitecustomize.py",
            "kivy_ime/__init__.py", "kivy_ime/bridge.py", "kivy_ime/keyboard.py"]:
    source = app
    dst = os.path.join(build, rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copy(os.path.join(source, rel), dst)
    py_compile.compile(dst, cfile=dst + "c", doraise=True, optimize=2)

    # A .pyc header stores the source mtime (bytes 8-12), so the same sources
    # compile to different bytes on every run. Zero it for a stable sha1.
    pyc = dst + "c"
    with open(pyc, "r+b") as fh:
        head = fh.read(16)
        fh.seek(8)
        fh.write(b"\0" * 4)
        fh.close()

compiled = {rel: os.path.join(build, rel + "c") for rel in
            ["main.py", "sitecustomize.py",
             "kivy_ime/__init__.py", "kivy_ime/bridge.py", "kivy_ime/keyboard.py"]}

replacements = {
    "main.pyc": "main.py",
    "sitecustomize.pyc": "sitecustomize.py",
    "kivy_ime/__init__.pyc": "kivy_ime/__init__.py",
    "kivy_ime/bridge.pyc": "kivy_ime/bridge.py",
    "kivy_ime/keyboard.pyc": "kivy_ime/keyboard.py",
    "__pycache__/main.cpython-314.pyc": "main.py",
    "kivy_ime/__pycache__/__init__.cpython-314.pyc": "kivy_ime/__init__.py",
    "kivy_ime/__pycache__/bridge.cpython-314.pyc": "kivy_ime/bridge.py",
    "kivy_ime/__pycache__/keyboard.cpython-314.pyc": "kivy_ime/keyboard.py",
}

changed = []
for member in members:
    rel = replacements.get(member.name)
    if rel is None or rel not in compiled:
        continue                      # keep p4a's entry untouched
    with open(compiled[rel], "rb") as fh:
        payload = fh.read()
    data[member.name] = payload
    member.size = len(payload)        # type/mode/owner stay as p4a wrote them
    changed.append(member.name)

# Non-Python assets (fonts, images, samples...) ship too: anything under
# source.dir that is not .py / .pyc is added to the bundle so it lands in
# files/app/ next to the code -- Kivy's LabelBase needs a *real* path, which is
# exactly what the unpacked bundle gives us.
#
# IMPORTANT: this tar is also the *base* we repack from, so a file that is
# already a member must only have its bytes refreshed -- never be appended
# again. Adding it unconditionally duplicated the 5 MB font on every run
# (8 copies after 8 syncs, ~1.3 MB of extra APK each time).
base_names = {m.name for m in members}
extra_members = []
added_dirs = set()
for root, _dirs, files in os.walk(app):
    rel_dir = os.path.relpath(root, app)
    if rel_dir != "." and "__pycache__" in rel_dir:
        continue
    for name in sorted(files):
        if name.endswith((".py", ".pyc")):
            continue
        rel = name if rel_dir == "." else os.path.join(rel_dir, name)
        with open(os.path.join(root, name), "rb") as fh:
            data[rel] = fh.read()          # last write wins = newest bytes
        if rel in base_names:
            continue                       # already shipped, bytes refreshed
        # Parent directory entries first (jtar needs them, once each).
        parts = rel.split(os.sep)
        for depth in range(1, len(parts)):
            d = os.sep.join(parts[:depth])
            if d and d not in base_names and d not in added_dirs:
                m = tarfile.TarInfo(d)
                m.type = tarfile.DIRTYPE
                extra_members.append(m)
                added_dirs.add(d)
        m = tarfile.TarInfo(rel)
        m.type = tarfile.REGTYPE
        m.size = len(data[rel])
        extra_members.append(m)
        base_names.add(rel)

# Deterministic output: identical sources -> identical sha1 -> the app skips
# re-unpacking the 13 MB python bundle (PythonUtil.unpackAsset deletes the
# whole app dir first, so a spurious sha1 change costs a full re-extract).
# Dedupe by name as well: an older bundle may already carry repeats.
ordered = []
seen = set()
for member in members + extra_members:
    if member.name in seen:
        continue
    seen.add(member.name)
    ordered.append(member)

for member in ordered:
    member.mtime = 0
    member.uid = member.gid = 0
    member.uname = member.gname = ""

import gzip
raw = tempfile.NamedTemporaryFile(delete=False, suffix=".tar")
raw.close()
out = tarfile.open(raw.name, "w", format=tarfile.USTAR_FORMAT)
for member in ordered:
    if member.isdir():
        out.addfile(member)
    else:
        out.addfile(member, io.BytesIO(data[member.name]))
out.close()

with open(raw.name, "rb") as src, gzip.GzipFile(
        out_tar, "wb", compresslevel=9, mtime=0) as dst:
    dst.write(src.read())
os.unlink(raw.name)

with open(sha1_file, "w") as fh:
    fh.write(hashlib.sha1(open(out_tar, "rb").read()).hexdigest())
print("replaced: %s" % ", ".join(sorted(set(changed))))
if extra_members:
    print("added   : %s" % ", ".join(m.name for m in extra_members))
PY

SHA="$(cat "$sha1")"
sed -i "s|<string name=\"private_version\">[0-9a-f]*</string>|<string name=\"private_version\">${SHA}</string>|" "$RES"
# Gradle reruns the asset-merge task only if the input is newer than the output.
touch "$TAR"
echo "private_version=${SHA}"

JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleRelease --offline
adb install -r app/build/outputs/apk/release/app-release.apk
echo "installed"