# KIVY_KEYBOARD.md — How to adjust the Kivy keyboard layout / theme / gestures

**Target audience:** anyone who wants to change the on-screen keyboard (layout,
colours, sizes, gestures, sounds) without touching the Kotlin mruby engine.

The Kivy frontend is in `app/src/main/python/kivy_ime/`.  It replaces the old
Kotlin `KeyboardView`/`KeyboardRenderer` entirely; the Kotlin `InputMethodService`
still runs the mruby engine and JNI bridge.

---

## Fast iteration cycle (no APK rebuild)

1. Edit the Python files in `app/src/main/python/kivy_ime/`
2. Run on the desktop (8 s) to see the look:
   ```bash
   ./kivy-venv/bin/python /tmp/opencode/verify_fixes.py
   # or
   ./kivy-venv/bin/python /tmp/opencode/kivy_smoke.py
   ```
3. When it looks right, push to the phone (15 – 30 s):
   ```bash
   ./sync_python.sh          # rebuilds assets/private.tar, bumps sha1, gradle, install
   ```

---

## Files you will edit

| File | What it controls |
|------|------------------|
| `app/src/main/python/kivy_ime/keyboard.py` | **All visual/layout logic** — rows, keys, chips, colours, gestures, sizes |
| `app/src/main/python/kivy_ime/bridge.py` | Socket protocol (rarely) |
| `app/src/main/python/main.py` | Entry point (once) |

---

## Layout & keys (`keyboard.py`)

### 12-column grid

```python
COLUMNS = 12
gap_h = dp(2)
gap_v = dp(2)
outer = dp(3)
pad = dp(3)
bar_h = dp(48)
body_h = dp(240)
```

**Chinese and English have separate row sets.** `ROWS` is the English set
(lowercase letters); `CHINESE_ROWS` is derived from it by `_uppercase_row()`,
which uppercases ASCII letters in `label` **only** — `value` stays lowercase so
the mruby engine keeps receiving the same code characters, and `sub`/`up` are
symbols that `.upper()` leaves alone. `_rebuild_rows()` picks
`ROWS if self.english else CHINESE_ROWS`, so `set_english()` swaps the whole
keyboard. To change one mode only, edit `ROWS` and the derivation (or give
`CHINESE_ROWS` its own literal rows).

Each row in `ROWS` is a list of key specs.  A spec is a dict with:

| Key | Type | Meaning |
|-----|------|---------|
| `label` | str | visible text on the key |
| `value` | str/None | what is sent to the engine (None = use label) |
| `action` | str | `"code"`, `"backspace"`, `"space"`, `"enter"`, `"toggle_mode"`, `"hide"`, `"key_event"`, `"clipboard"`, `"combo"`, `"datetime"`, `"weekday"`, `"noop"` |
| `sub` | str | top-right corner label (swipe-up symbol) |
| `gestures` | dict | `"up"`, `"down"`, `"left"`, `"right"` → action spec |
| `repeat` | bool | long-press repeat (backspace) |
| `long_press` | str/action | long-press action |
| `span` | int | 1-12 columns |
| `font_size` | sp | overrides default (sp) |

**Example:** the backspace key
```python
{"label": "⬅", "value": None, "action": "backspace", "repeat": True}
```

**Swipe-up on the `1` key:**
```python
{"label": "1", "value": "1", "sub": "!", "up": "!"}
```

### Five rows (built-in)

```python
ROW_1 = [{"label": d, "value": d, "sub": s, "up": s}
         for d, s in zip("1234567890-=", "!@#$%^&*()_+")]
ROW_2 = [{"label": k.upper(), "value": k, "sub": s, "up": s}
         for k, s in zip("qwertyuiop[]", "          {}")]
ROW_3 = [{"label": k.upper(), "value": k, "sub": s, "up": s}
         for k, s in zip("asdfghjkl;'\\", '         :\\"|')]
ROW_4 = [{"label": k.upper(), "value": k, "sub": s, "up": s}
         for k, s in zip("zxcvbnm,./`", "       <>?~")] + \
        [{"label": "⬅", "value": None, "action": "backspace", "repeat": True}]
ROW_5 = [
    {"label": "中", "action": "toggle_mode", "span": 2},
    {"label": "", "action": "space", "span": 8,
     "gestures": {"down": "toggle_mode", "left": "cursor_left",
                  "right": "cursor_right"}},
    {"label": "⏎", "action": "enter", "span": 2,
     "gestures": {"up": {"action": "combo", "value": "shift+enter"}}}
]
```

The English page uses `ENGLISH_BAR` (12 single-unit action keys) and the same
letter rows without `transform: upper`.

---

## Colours (HHKB light theme)

```python
INK = (0.0, 0.0, 0.0, 1.0)          # text
MUTED = (0.4, 0.4, 0.4, 1.0)        # sub-label
BAR_BG = (245/255,)*3 + (1,)        # candidate bar
KEY_BG = (224/255,)*3 + (1,)        # key plate
KEY_PRESSED = (204/255,)*3 + (1,)   # pressed plate
SEAM = (153/255,)*3 + (1,)          # 1 dp seam lines
CHIP_SELECTED = (204/255,)*3 + (1,) # selected chip
CLEAR = (1, 1, 1, 0)                # transparent
```

Changing these constants recolours the whole keyboard instantly.

---

## Candidate chips

```python
class Chip(Widget):
    selected = BooleanProperty(False)
```

* `selected=True`  → plate = `CHIP_SELECTED` (#CCCCCC), text **bold**
* `selected=False` → plate = `CLEAR` (transparent), text normal

Only the active chip is `selected=True`.  The placeholder (empty candidate row)
is `Chip("", selected=False)`.

---

## Bar keys have no borders

Every key in the Chinese and English bar is created with `seams=False`
(`_rebuild_bar` → `_make_key(..., seams=False)` / `Key(..., seams=False)`), so
`Key._sync()` leaves all four seam `Line` point lists empty. The bar therefore
reads as **one continuous plate** — no 1 dp outline around the buffer box, the
game button, `▼`, or any of the English `TAB / ^A / ^C / ^V / ⇧ ⇩ ⇦ ⇨ / DT / W`
keys.

Only the bar is affected: the letter/number rows keep their per-side seams.
The game-mode bar (`_enter_game`) builds its own keys and is unchanged.

To put the borders back, drop `seams=False` from `_rebuild_bar` (or pass
`seams=True` for individual keys).

---

## Gestures

```python
key = Key(..., gestures={"up": "!", "down": "toggle_mode", ...})
```

| Direction | Example | What it does |
|-----------|---------|--------------|
| `"up"` | `"!"` | commits `!` |
| `"down"` | `"toggle_mode"` | switches Chinese/English |
| `"left"` | `"cursor_left"` | moves text cursor |
| `"right"` | `"cursor_right"` | moves text cursor |

A gesture fires **instead of** the normal tap action when the finger moves
> 16 dp in that direction.

---

## Key sounds

```python
class SoundPlayer:
    PATHS = ("/system/media/audio/ui/KeypressStandard.ogg", ...)
```

`SoundPlayer.click()` is called on every `on_touch_down`.  Volume = 0.6.

---

## Changing sizes / paddings

Edit the constants at the top of `ImeKeyboard.__init__`:

```python
gap_h = dp(2)      # horizontal gap between keys
gap_v = dp(2)      # vertical gap between rows
outer = dp(3)      # outer margin
pad = dp(3)        # window padding
bar_h = dp(48)     # candidate bar height
body_h = dp(240)   # key rows total height
```

Row height is computed automatically:
```python
row_h = (body_h - 2*outer - gap_v*(len(ROWS)-1)) / len(ROWS)
```

---

## Fonts

**The bundled font is Unifont-T**, shipped inside the APK as
`app/src/main/python/assets/unifont_t.otf` (5.0 MB). It is a real file at
runtime because p4a unpacks the private bundle to
`filesDir/app/`, so `LabelBase.register()` can take the path directly:

```
filesDir/app/assets/unifont_t.otf     # device (bundled)
/home/cc/Desktop/mrubyime_mini/unifont_t.otf   # desktop fallback
```

It was chosen because it covers **every** label the keyboard uses, including
the glyphs Noto Sans CJK lacks — `⌫ U+232B`, `⏎ U+23CE`, `⇧ ⇩ ⇦ ⇨` and the
box-drawing set. With Noto those keys rendered as tofu. The catch: Unifont is a
pixel-style face, so the whole keyboard looks blocky/retro.

`_bundled_font_paths()` probes `cwd/assets`, `dirname(cwd)/assets`, the module
dir and its parent, so both the device layout and a host run find it. After
that, `_CJK_CANDIDATES` still lists the system faces as a fallback:

```
/system/fonts/NotoSansCJK-Regular.ttc
/system/fonts/NotoSansCJKsc-Regular.otf
/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc  # desktop
```

If nothing is found it falls back to Roboto (no CJK → tofu).  Change
`FONT_NAME` or add a path to `_CJK_CANDIDATES`.

`sync_python.sh` ships **non-Python files too**: everything under
`app/src/main/python/` that is not `.py`/`.pyc` (the font, future images) is
added to `private.tar` as a new entry, with its parent directory entries, and
still with mtime/uid/gid zeroed so the bundle sha1 stays deterministic.

---

## Bridge: never send `value: null`

`bridge.send()` drops any payload key whose value is `None`. This is not
cosmetic — Kotlin's `JSONObject.optString(name, null)` returns the **string**
`"null"` for a JSON null (`JSONObject.NULL` is a non-null object, so
`JSON.toString()` falls back to `String.valueOf()`). That string reached
`SimpleDateFormat` and threw `Illegal pattern character 'n'`, which is exactly
why the English bar's `DT` and `W` keys silently did nothing.
`MrubyimeIME.onKivyAction()` also normalises `"null"`/blank to absent, and logs
`unhandled kivy action: <type>` for anything it does not recognise.


---

## Actions sent to the Kotlin engine

| `action` | Sent JSON | Kotlin handler |
|----------|-----------|----------------|
| `"code"` | `{"type":"key","char":"x"}` | `onKeyPressedPublic` |
| `"backspace"` | `{"type":"backspace"}` | `onBackspacePressedPublic` |
| `"space"` | `{"type":"space"}` | `onSpacePressedPublic` |
| `"enter"` | `{"type":"enter"}` | `onEnterPressedPublic` |
| `"toggle_mode"` | `{"type":"toggle_mode"}` | `toggleModePublic` |
| `"hide"` | `{"type":"hide"}` | `clearCompositionAndHide` |
| `"key_event"` | `{"type":"key_event","value":"up"}` | `onKivyAction` |
| `"clipboard"` | `{"type":"clipboard","value":"copy"}` | `onKivyAction` |
| `"combo"` | `{"type":"combo","value":"ctrl+c"}` | `onKivyAction` |
| `"datetime"` | `{"type":"datetime","value":"yyyy-MM-dd"}` | `onKivyAction` |
| `"weekday"` | `{"type":"weekday"}` | `onKivyAction` |

---

## Fast preview on desktop (no phone)

```bash
./kivy-venv/bin/python /tmp/opencode/verify_fixes.py
# 8 s, runs the widget tree headless, prints all 4 UI fixes

./kivy-venv/bin/python /tmp/opencode/kivy_smoke.py
# 10 s, writes kivy_zh.png / kivy_en.png
```

Screenshots land in `/tmp/opencode/`.

---

## Android build & install (only when you need the real IME)

```bash
./sync_python.sh          # 15–30 s: private.tar → sha1 → gradle → install
```

What it does:
1. Compiles the 5 Python files with `-OO`
2. Replaces the 5 `.pyc` entries **in-place** inside `assets/private.tar`
   (keeps p4a's exact member set, DIR types, USTAR format, gzip wrapper)
3. Updates `private_version` in `res/values/strings.xml`
4. `gradlew assembleRelease --offline` (≈ 1 s incremental)
5. `adb install -r` (21 MB APK, 1.3 s)

---

## The code box (`編碼區`) never wraps

The buffer lives in a 2-of-12-column cell, so a long code used to wrap onto a
second line. It now always stays on **one line and shows only the tail**:

- `_refresh_bar()` trims the text to the last N characters, where
  `N = floor((box_width − 8 dp) / char_width)`.
- `_buffer_char_width()` measures the widest glyph once with a `CoreLabel` at
  the box's own font size (`sp(24)`). Unifont is monospace, so the count is
  exact on device (~6–7 codes); a proportional font errs on the safe side.
- The label also keeps `shorten=True` / `shorten_from="left"` as a guard: if
  the estimate is ever wrong the label clips with `...` instead of wrapping.

There is deliberately **no ellipsis** in the normal case — `...` costs 3
character widths out of ~7, so `...L;'` showed only 4 codes where `GHKL;'`
shows 6. Tapping the box still commits the **whole** buffer (`_buffer_key.value`
is set to the full text, not the visible tail).

Alternatives considered, if the tail-only look ever stops being enough:

| Approach | Effect | Cost |
|---|---|---|
| Fixed last-N (e.g. 4) | guaranteed fit, no measurement | wastes room on a wide box |
| Shrink the font to fit N | all N codes visible, no clipping | text gets small |
| Horizontal scroll to the end | full text readable | no indicator, more code |
| Widen the box (`buffer weight`) | more codes visible | steals candidate width |

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Keyboard area all black | `private.tar` not gzipped / DIR entries wrong | `./sync_python.sh` rebuilds it correctly |
| `Entrypoint not found (.py)` | `private.tar` not extracted | Check logcat: `I python: Unpacking private assets` |
| `ClassNotFound: org.renpy.android.Hardware` | missing Java shim | `org/renpy/android/Hardware.java` must exist |
| Colours wrong / black keys | theme constants | check `BAR_BG`, `KEY_BG`, `SEAM` |
| Chips all grey / no selection | `selected` binding | `Chip.selected` BooleanProperty + `bold=selected` |
| Backspace sends arrow glyph | repeat path used label | `default_action()` returns `(action, value)` |

---

## Quick reference: key files

```
app/src/main/python/kivy_ime/keyboard.py   # 1000+ lines, all layout logic
app/src/main/python/kivy_ime/bridge.py     # socket client (JSON lines)
app/src/main/python/main.py                # entry point
app/src/main/java/.../KivyImeHost.kt       # Android host (unpack, SDL bootstrap)
app/src/main/java/.../PythonActivity.java  # Java shim for p4a android module
sync_python.sh                              # fast Python-only rebuild script
```

---

## Summary

| Change type | File | Rebuild |
|-------------|------|---------|
| Key label / position / swipe | `keyboard.py` `ROWS` / `ENGLISH_BAR` | `./sync_python.sh` |
| Colour / size / padding | constants at top of `ImeKeyboard` | `./sync_python.sh` |
| Gesture mapping | `gestures={...}` in key spec | `./sync_python.sh` |
| Font / sound | `_CJK_CANDIDATES` / `SoundPlayer.PATHS` | `./sync_python.sh` |
| Socket protocol | `bridge.py` | `./sync_python.sh` |
| Kotlin-side logic (bridge commands) | `KivyImeHost.kt` / `MrubyimeIME.kt` | `gradlew assembleRelease` |

**Always test on desktop first (`verify_fixes.py` / `kivy_smoke.py`),
then push to device with `./sync_python.sh`.**