# Skill Guide: Building Embedded mruby Input Methods on Android

This skill guide outlines the architectural patterns, C-JNI bridging techniques, and performance optimizations for building Android Input Method Services (IME) powered by an embedded **mruby** virtual machine.

---

## 1. Core Architecture Diagram

```
┌───────────────────────────────────────────────────────────┐
│               Android Presentation Layer (UI)             │
│   • BoshiamyIME (InputMethodService)                       │
│   • KeyboardView + KeyboardRenderer (DSL IR → View tree)  │
│   • StepScrollView (Full-page looping horizontal scroll)   │
│   • BoshiamySettingsActivity (Settings & live theme sync)  │
└─────────────────────────────┬─────────────────────────────┘
                              │
                              ▼ (Delegation Pattern)
┌───────────────────────────────────────────────────────────┐
│     BoshiamyEngineDelegate / MrbuBoshiamyEngineDelegate   │
└─────────────────────────────┬─────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────┐
│               Native C-JNI Bridge (boshiamy_jni.c)        │
│   • Memory Arena Protection (mrb_gc_arena_save/restore)   │
│   • Safe UTF-8 String Conversion (utf8_to_jstring)         │
│   • Raw UTF-8 Inbound Bytes (mrb_str_from_jbytes)         │
│   • GC Root Registration (mrb_gc_register)                │
│   • Layout DSL sandbox (mrb_open + mrb_load_string)        │
└─────────────────────────────┬─────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────┐
│                 Embedded mruby VM (libmruby.a)            │
│   • MRB_UTF8_STRING Compile Macro                         │
│   • mruby-compiler (user-imported layout DSL)             │
│   • Bytecode: engine / keyboard_dsl / keyboard_layout      │
└───────────────────────────────────────────────────────────┘
```

---

## 2. Critical C-JNI & mruby Interoperability Rules

### Rule A: UTF-8 Encoding in mruby
Standard C strings in mruby process raw bytes. For CJK (Chinese, Japanese, Korean) input methods, string slicing and character counting must operate on UTF-8 characters.
* **Requirement**: Add `-DMRB_UTF8_STRING` to CFLAGS in `build_mruby.sh`.
* **Effect**: Methods like `String#length`, `String#[]`, and `String#index` in Ruby process multi-byte CJK characters natively.

### Rule B: Safe UTF-8 JNI String Conversion
Android JNI's `NewStringUTF` crashes or corrupts text when encountering non-Modified-UTF-8 bytes.
* **Requirement**: Convert C strings into Java `String` using byte arrays and explicit UTF-8 encoding:
  ```c
  jstring utf8_to_jstring(JNIEnv *env, const char *ptr, mrb_int len) {
      if (!ptr || len <= 0) return (*env)->NewStringUTF(env, "");
      jbyteArray bytes = (*env)->NewByteArray(env, len);
      if (!bytes) return (*env)->NewStringUTF(env, "");
      (*env)->SetByteArrayRegion(env, bytes, 0, len, (const jbyte *)ptr);
      jclass strCls = (*env)->FindClass(env, "java/lang/String");
      jmethodID ctor = strCls ? (*env)->GetMethodID(env, strCls, "<init>", "([BLjava/lang/String;)V") : NULL;
      jstring charset = (*env)->NewStringUTF(env, "UTF-8");
      jstring str = (strCls && ctor)
          ? (jstring)(*env)->NewObject(env, strCls, ctor, bytes, charset)
          : NULL;
      if (charset) (*env)->DeleteLocalRef(env, charset);
      if (strCls) (*env)->DeleteLocalRef(env, strCls);
      (*env)->DeleteLocalRef(env, bytes);
      return str ? str : (*env)->NewStringUTF(env, "");
  }
  ```

* **Inbound direction has the same trap**: never read dictionary or keystroke text with `GetStringUTFChars` (Modified UTF-8). Pass a `ByteArray` from Kotlin, then build the mruby string with `GetByteArrayElements` + `mrb_str_new` (see `mrb_str_from_jbytes` in `boshiamy_jni.c`).

### Rule C: Serialized String Passing over JNI
Crossing the JNI boundary repeatedly for array items causes performance degradation.
* **Requirement**: Serialize array data into a single string joined by `\n` in Ruby (`candidates.join("\n")`).
* **Handling**: Pass the single string across JNI and deserialize in Kotlin (`serialized.split("\n")`).

### Rule D: GC Roots for Long-Lived Objects
An engine object stored in a C static variable is invisible to mruby's garbage collector.
* **Requirement**: Call `mrb_gc_register(mrb, engine)` right after `mrb_obj_new`; otherwise the VM may collect it between JNI calls.

### Rule E: Runtime Layout DSL (Bytecode + Sandbox)
The keyboard layout is data, not Kotlin: `assets/keyboard_layout.rb` is evaluated at runtime and returns IR JSON.
* **Requirement**: Build with `mruby-compiler` in the Android gembox (`mrb_load_string` must exist on device). The built-in DSL/layout are shipped as bytecode (`keyboard_dsl_irep.c`, `keyboard_layout_irep.c`) and loaded by `init()`.
* **Sandbox**: user-imported layouts run in a **throwaway `mrb_state`** (`mrb_open`, load `keyboard_dsl_irep`, then `mrb_load_string(source)`), capped at **64 KB**, never touching the engine state. Always `mrb_close` the sandbox and return `""` on failure.
* **No Regexp/eval in the DSL**: the device mruby build has no `regexp` gem, so the DSL and layouts use only plain string operations (`each_char`, `index`, `[]`).

---

## 3. Generic `.cin` Engine Logic

1. **Table-Driven, Not 形碼-Specific**: `boshiamy_engine.rb` parses `%ename`/`%cname`, `%selkey`, `%keyname` and `%chardef`. `@legal_keys` = `%keyname` keys ∪ every character in a code; `@table_max_code_len` = the longest code. The class/file name is kept only for JNI/prefs compatibility. A line starting with `#` is a comment.
2. **Case Normalization**: Normalize input keycodes to lowercase before matching against the dictionary Hash table.
3. **Max Code Length Enforcement**:
   - The engine's max code length is configurable over JNI (`setMaxCodeLen`): **the active table's longest code** when 「最大碼清空組字」 is on, **140** when off (default).
   - `input` **appends** to the buffer (never truncates); once the buffer is past `@max_code_len` it stops resolving candidates (returns `[]`) but keeps accumulating, so the code box can show the last 6 codes.
4. **Auto-Commit Rule**:
   - When the buffer reaches **≥ min(4, table max)** AND the lookup returns exactly 1 candidate AND `engine.canExtend()` is false (no legal key can extend it), automatically commit the candidate and reset the engine state ("滿4碼且唯一候選自動上屏"; **default off**, gated by `auto_commit`).
5. **Dead-Code Auto-Clear** (two independent gates in `BoshiamyIME.onKeyPressed`):
   - **「死碼清空編碼區」** (`clear_dead_code`, default off): a **1–3 char** buffer with zero candidates AND `engine.deadPrefix()` true → clear. `dead_prefix` (engine, read-only) returns true only if **every one of the active table's legal keys appended to the buffer yields zero candidates**. A prefix that can still grow is preserved (e.g. `aad` → `aadb`/`aadk`/`aado`, `,,` → `,,f`); a provably dead one (`a'`, `[a`, `,aa`) is cleared. The trial looks only **one key ahead**.
   - **「最大碼清空組字」** (`max_code_clear`, default off → max 140): when the buffer reaches the current max code length with zero candidates → clear. With the switch on (max 5, as the built-in table) a 5-code dead code wipes the box; with it off the buffer keeps growing and the code box shows only the last 6.
6. **Replaceable Tables**: `DictionaryRepository` resolves `builtin:boshiamy` (`assets/boshiamy.cin`), `builtin:<name>` (`assets/dicts/<name>.cin`) and `user:active` (`filesDir/dicts/active.cin`, mirrored to Android/data). `dict_id` in `boshiamy_settings` selects it; `onStartInputView` compares the id + file signature and hot-reloads. `%keyname` labels are applied to the key caps in Chinese mode (`KeyboardView.setDictLabels`) unless they are trivial variants of the key itself.

---

## 4. IME UI Latency & Standard View Rendering

1. **Generate Once, Update in Place**:
   - `BoshiamyIME.onCreateInputView` loads the layout (`KeyboardLayoutRepository`: runtime DSL JSON → `assets/keyboard_layout.json` → emergency layout), and `KeyboardRenderer` builds the bar + key rows for each page **once**. Afterwards only `text`, `visibility`, and `background` change. Never rebuild the key tree per keystroke — the ≤12 candidate chips are **pooled and updated in place** (`createChip()` + `paintChip()`): only the chips whose text, selection or theme actually changed are repainted, so a keystroke never blanks the row (avoiding e-ink flashing). The same "assign `text` only when it differs" guard covers `updateBuffer()`.
   - Chips sit in a `StepScrollView` (a `HorizontalScrollView` subclass); the selected chip is kept in view via `scrollTo(target)`.
2. **Declarative DSL Keys and IR Themes**:
   - Keys are regular `TextView`s created from `KeySpec` (label, span/weight, action, gestures, repeat, font, style). Appearance comes from the IR theme tokens (`bar`, `barInk`, `buffer`, `bufferInk`, `chip`, `chipInk`, `chipSelected`, `chipSelectedInk`, `chipMark`, `key`, `keyInk`, `keyPressed`, `keyBorder`, `ink`, `muted`, `seam`, `lampOn`, `lampOff`) resolved by `ThemeResolver`; `KeyboardView.solid()` builds the drawables (no XML shape selectors for the keyboard).
   - **Candidate bar layout (DSL, 12-unit grid)**:
     - **Chinese mode**: `buffer` weight **2** (`font: { size: 24 }`, fits ~6 codes, long codes show the last 6; **no frame**) + `candidates` weight **9** (`StepScrollView`, `font: { size: 24, weight: :bold }`) + hide `▼` weight **1** = 12. **The DSL is the only source for the bar split** — the 編碼區寬度 slider is gone.
     - **English mode**: 12 action keys of weight 1 — `TAB`, blank (`noop`), `^A` `^C` `^V` (`clipboard`), `⇧ ⇩ ⇦ ⇨` (`key_event` up/down/left/right), `DT` (`datetime`), `W` (`weekday`), `▼`; there is no buffer/candidate row on the English page.
   - **Gaps come from `KeyRowLayout`** (`span`/`weight`/`align`, `gapBetweenPx`/`gapOuterPx`) and are **dp values from the DSL** (`grid gap_h/gap_v/outer`, `bar gap:`, group/status `gap:`), not `layout_margin`s. Parser defaults when omitted are 1 dp / 1 dp / 0.5 dp; the **built-in layout sets `gap_h`/`gap_v` and `outer` all to 0** (flush keys, keyboard flush against the window edges so Dark Mode shows no `#1C1C1E` band down the sides). **No rounded corners**: drawables have `cornerRadius = 0` unless the DSL sets one.
3. **Touch Event Dispatch & Gesture Handling**:
   - `btn_hide_keyboard` (`▼`): **tap** hides the IME (`requestHideSelf(0)`), **long-press** toggles `hide_virtual_keyboard` (full keyboard ↔ 48 dp candidate bar only). `isHapticFeedbackEnabled = false` suppresses the long-press vibration.
   - Settings toggle `隱藏虛擬鍵盤` (`cb_hide_keyboard`, pref `hide_virtual_keyboard`): `BoshiamyIME` listens via `SharedPreferences.OnSharedPreferenceChangeListener` and calls `applyKeyboardSize()`, setting `hideKeyRows` (`key_rows` → `GONE`) and resizing the window. Always mutate the existing `layoutParams` instance to avoid `ClassCastException`.
   - Space key: label is always blank (never mirrored). **Tap** commits the selected candidate or buffer or space character. **Swipe-down (>30 dp)** toggles Chinese/English mode; **swipe left/right** moves the text cursor (~24 dp per character).
   - Backspace key (`key_backspace`): press-and-hold deletes once immediately, then repeats after 400 ms every 80 ms via `Handler`.
   - Mode switch:
     - `onToggleMode()` (Space swipe) and the hardware hotkey Alt+Space / Shift+Space flip `isEnglishMode` and call `applyModeSwitch()`.
     - Entering **English clears the composition area** (`engine.reset()` + empty candidates) and displays the English page's 12-key action bar.
     - Returning to **Chinese** calls `requeryCandidatesIfChinese()` (peeks via `engine.currentCandidatesSerialized()`) and restores the candidate bar.
   - In English mode: tapping a letter/digit key commits that character via `currentInputConnection.commitText(ch, 1)`; the bar keys dispatch their own actions (`key_event`, `clipboard`, `datetime`, `weekday`).
4. **Strict Height Constraint & Dynamic Sizing**:
   - Keyboard height comes from the layout IR, never from preferences:
     - `height bar: 48` → candidate bar height; `height body: 240` → key area height.
     - `padding left:/right:/bottom:` → side insets and the bottom reserve (applied via `KeyboardView.setPadding`).
   - Total height = `bar + body + bottom` (or `bar + bottom` when `hide_virtual_keyboard` is enabled), clamped to `screenHeightPx()` by `KeyboardView.layoutHeightPx()`; `BoshiamyIME.applyKeyboardSize()` sets `keyboardHeightPx` and `KeyboardView.onMeasure` rewrites the spec to `EXACTLY`. The IME window is `wrap_content`, so `onMeasure` enforcement is mandatory.
   - The old `candidate_bar_height` / `keyboard_body_height` / `keyboard_*_padding` / `candidate_font_size` / `key_font_size` / `buffer_width_weight` prefs and their sliders were **removed**: **the layout DSL is the single source of truth for size and type**. Stale values left in `boshiamy_settings` are ignored; to restore defaults use 「恢復內建佈局」.
5. **Zero Touch Animation / Ripple Suppression**:
   - Plain `<shape>` / `<selector>` backgrounds; no ripple, no `selectableItemBackground`.
6. **Absolute Candidate Indexing, Font Sizing & Clean Highlighting**:
   - **Dynamic Font Sizes**:
     - `buffer font: { size: 24 }` → buffer code (default 24 sp); `candidates font: { size: 24, weight: :bold }` → candidate chips (default 24 sp bold). `font weight:` also accepts `:normal`.
     - per-key `font: { size: 18 }` → letter/number keys (default 18 sp); the `⏎` enter key scales with the same size.
   - Candidate chips: text is **bold, formatted `n字`** (absolute index `0`–`9` followed immediately by glyph, no spaces, no brackets).
   - Padding: 3 dp horizontal (`PadH`). Every chip (including the empty placeholder) recalculates `minimumWidth = ceil(paint.measureText("0微")) + 2 * PadH` on font size change, keeping chip widths completely uniform.
   - **Baseline alignment**: both buffer and chips set `includeFontPadding=false`; chips also call `setFallbackLineSpacing(false)` (API 28+) so CJK fallback fonts do not push the chip baseline ~6 px lower than the buffer ("一高一低").
   - **StepScrollView (no pagination)**: candidate chips are placed in a single horizontally scrollable row. The view tracks the finger during dragging (`scrollBy`), settles onto full-page boundaries on release (`smoothScrollTo`), and **loops across page boundaries**. Single taps pass through to select candidates.
   - **Placeholder chip**: shown when there are no candidates. Carries the 3 dp selection bottom border in Chinese mode; plain in English mode.
   - Selected candidate: marked with a **3 dp bottom border** (white in Dark Mode, black in Light Mode) plus the theme ink; no full frame anywhere.
   - Selection keys come from the active table's `%selkey`. While candidates are visible, a key whose absolute label matches (`selkeys[i]`) commits that candidate; an unmatched selection key is swallowed so the composition survives. **A `%selkey` value may also be a radical** (注音 `%selkey 1234567890` + `1 ㄅ`): such a key only selects when it can no longer extend the code (`engine.wouldExtend(key)` = `buffer+key` is a code or a prefix of one), so `2 8 4` builds ㄉㄚˋ and `284` then `1` selects. Non-code `%selkey` digits (形碼) always select. With no candidates, a digit that is not a legal key is committed as plain text.
7. **Listener Wiring / Property Shadowing**:
   - Avoid property shadowing when setting listeners on `KeyboardView`: assign outside `apply` or qualify explicitly with `this@BoshiamyIME`.

---

## 8. Theme & Settings: e-ink Light Mode (default) & Dark Mode

> Default is **Light Mode** (`light_theme` default `true`). Both **settings page and keyboard are monochrome** in Dark Mode (white accents on `#3A3A3C`/`#000000`).

1. **Settings Activity (`BoshiamySettingsActivity`)**:
   - Rebuilt as a comprehensive, scrollable control panel (`ScrollView`).
   - Sections: 系統設定 → **即時打字測試（只有一個輸入框，無標題）** → 外觀與模式 → 輸入框內聯顯示 → 鍵盤佈局（ruby dsl）. **Keyboard size / padding / font controls no longer exist here** — those are layout-DSL attributes (see section 4), edited in `layouts/keyboard.rb`; this page only holds behaviour switches. (The user-engine card was removed with user engines.)
   - Includes an inline `EditText` test input with `adjustResize`.
   - Behaviour preference changes (`hide_virtual_keyboard`, `light_theme`, …) propagate immediately via `OnSharedPreferenceChangeListener` to `BoshiamyIME` without requiring an IME restart; a layout edit hot-reloads on `onStartInputView`.
2. **One preference, two surfaces**:
   - `light_theme` (boolean, `boshiamy_settings`) is toggled by `cb_light_theme` (`淺色模式`). Both `BoshiamyIME` and `BoshiamySettingsActivity` register a preference change listener to re-skin live.
   - `KeyboardView.applyTheme()` applies the **resolved IR theme tokens** (`ThemeResolver`) to the generated keys/chips; there is no hard-coded colour table for the keyboard surfaces. It repaints the bar, buffer, chips, punctuation/symbol keys, hide `▼` and the key grid.
3. **Dark Mode**:
   - Candidate bar / key grid: `#3A3A3C` (`bar`/`key`), pressed `#505054`, seams `#1C1C1E`, ink `#FFFFFF`.
   - Selected chip (and Chinese placeholder): **3 dp white bottom border**.
4. **Light Mode (e-ink)**:
   - Pure black `#000000`, pure white `#FFFFFF`; the bar (`key_bar`), buffer, chips, punctuation/symbol keys and `▼` are **pure white**, ink black.
   - Only the **selected chip / Chinese placeholder** gets a **3 dp black bottom border**; the buffer box and all other bar items are frameless.
   - Key grid: white `GradientDrawable` with a 1 dp black stroke; **no press effect** (the background never changes when pressed).
   - Navigation bar: `BoshiamyIME.applyNavigationBarTheme()` sets `navigationBarColor` to `#000000` (Dark) or `#FFFFFF` (Light) and toggles `SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR`. Re-applied in `onStartInputView()`.
