# 讓使用者修改 `mrubyime_engine.rb` 直接更新引擎

> **⚠️ 功能已移除（2026-11-22，APK 瘦身）。** 使用者自訂引擎整條鏈（`engine/engine.rb`、
> `EngineFiles`、`EngineCheckService`/`EngineCheckClient`、JNI 的 `initFromSource`/
> `engineFromSource`/`engineReset`/`engineIsUserDefined`/`engineLastError`/`engineValidate`、
> 設定頁的引擎卡片）為了縮小 APK 已全部移除；引擎只跑 `engine_irep.c` 位元組碼。
> 本文件僅作歷史設計紀錄保留，勿照此實作。
>
> ---
>
> **已實作（2026-11-10，後於 2026-11-22 移除）。** 本文件原為研究/設計稿，現保留架構脈絡與實作決策；
> 程式碼已落盤並在實機驗證（見 §10 驗證結果）。核心：引擎改用「單一 `mrb_state`、
> 用 source 取代 irep」的方案 B，JNI 新增 6 個入口，`class MrubyimeEngine` 的 17 個
> 實例方法即契約，尾端舊的 18 個 `mrubyime_engine_*` hook 已刪除。
>
> 相關檔案：`app/src/main/assets/mrubyime_engine.rb`、`app/src/main/cpp/mrubyime_jni.c`、
> `app/src/main/java/com/example/mrubyime/{EngineFiles,EngineCheckService,EngineCheckClient,KeyboardLayoutFiles,KeyboardLayoutRepository}.kt`、
> `app/src/main/cpp/engine_irep.c`、`build_mruby.sh`。

---

## 0. 結論（TL;DR）

1. **可行，而且不需要在裝置上跑 `mrbc`。** 裝置版 libmruby 已經內建 `mruby-compiler`，
   有 `mrb_load_string()` / `mrb_parse_nstring()` + `mrb_load_exec()`；鍵盤佈局 DSL 現在
   就是用同一條路徑在執行期編譯使用者檔案。
2. **建議走「單一 `mrb_state`、用 source 取代 irep」的方案（方案 B）**：
   只改 `init()` 與新增 3 個 JNI，**20 個既有 JNI 入口完全不用動**。
3. 檔案放 `filesDir/engine.rb`，並鏡像到 `Android/data/<pkg>/files/engine.rb`，
   與 `layouts/keyboard.rb` 用同一套「免權限、外部編輯器可改」的機制。
4. 驗證失敗時 **保留上一次可用的引擎**，錯誤訊息（含行號）回報到設定頁。
5. 最大的風險不是語法錯誤，而是 **無限迴圈**：mruby 3.3 沒有指令數上限，也沒有中斷機制，
   `while true; end` 會讓 IME 執行緒卡死（ANR）。這一點必須先決定策略（見 §5.3）。

---

## 1. 現況架構

### 1.1 啟動與換表

```
MrubyimeIME.onCreate()
  └─ DictionaryRepository.loadActive()            // 使用者 active.cin → assets/mrubyime.cin
  └─ engine.initDictionary(bytes)                 // → JNI init(ByteArray)
        C: mrb_open()
           mrb_load_irep(engine_irep)             // ← 內建引擎（bytecode）
           mrb_load_irep(keyboard_dsl_irep)
           mrb_load_irep(keyboard_layout_irep)
           klass = mrb_class_get(mrb, "MrubyimeEngine")
           engine = mrb_obj_new(mrb, klass, 0, NULL)
           mrb_gc_register(mrb, engine)           // 引擎存在 C static，必須是 GC root
           engine.load_dictionary(dict)
```

`MrubyimeIME.onStartInputView()` 會比對 `dict_id` 與 `DictionaryRepository.signature()`，
有變就 `engine.reloadDictionary(bytes)`（不重建 state）；佈局則用
`KeyboardLayoutFiles.sourceSignature()` 比對後 `keyboardView.setLayout()`。

### 1.2 關鍵事實

| 項目 | 現況 |
| --- | --- |
| Runtime 執行的引擎 | `app/src/main/cpp/engine_irep.c`（`mrbc` 產生的 bytecode 陣列） |
| `.rb` 的角色 | 只是原始碼；**改了不重編 `build_mruby.sh` 就不會生效** |
| `mrubyime_engine.rb` 大小 | 303 行 / 8,982 bytes |
| 內建碼表 | `assets/mrubyime.cin`，172 KB 文字 / 225 KB 檔，21,949 codes |
| 裝置端 compiler | `build_mruby.sh` 的 `conf.gem core: 'mruby-compiler'` → `mrb_load_string` 可用 |
| 佈局沙箱 | `mrb_open()` + `mrb_protect_error()` + `sandbox_parse_and_load()` + `format_parse_errors()`，64 KB 上限，回傳 `"\u0001<訊息>"` |
| 引擎現有 hook 層 | `mrubyime_engine.rb` 尾端已有 18 個 `mrubyime_engine_*` 頂層方法（目前 C **沒有呼叫**） |

`mrubyime_engine.rb` 目前同時有兩種介面：

* `class MrubyimeEngine`：17 個實體方法（C 現在走這條）。
* `mrubyime_engine_*` 頂層函式：包在 `ENGINE = MrubyimeEngine.new` 上的薄層（目前是死碼）。

---

## 2. 為什麼「直接讀 `.rb`」需要多一層

`.rb` 不能直接餵給 `mrb_load_irep()`；要嘛在 build time 編成 irep（現況），要嘛在
runtime 用 `mrb_parse_nstring()` + `mrb_load_exec()` 現場編譯。後者正是佈局 DSL 已經在做的事，
所以**技術上沒有新東西要引進**，只是把同一條路徑接到引擎上。

成本：9 KB 原始碼 parse + load 在 host 約 1 ms（`mrbc` 量測），裝置估 5–20 ms；
真正的大頭仍是建表（20k codes 合成表在 host x86_64 約 0.13 s，與現在的內建引擎同量級）。
**打字路徑（`inputSerialized`）完全不受影響。**

---

## 3. 三個可選架構

### 方案 A：雙 state + 分派

在 `mruby` 之外再開一個 `def_mrb` 放使用者引擎，20 個 JNI 入口每個都
`if (def_mrb) { 呼叫 def_engine } else { 原本的 }`。

* 優點：內建引擎永遠健在，使用者引擎壞掉不可能影響它。
* 缺點：20 個入口 × 2 條路徑，長期一定會漂移；C 檔多出約 150–200 行。
* 這也是上一輪嘗試到一半卡住的做法。

### 方案 B：單一 state，用 source 取代 irep ★ 建議

`init()` 時決定要 `mrb_load_irep(engine_irep)` 還是 `mrb_load_string(user_source)`，
其餘 20 個 JNI 一行都不改（它們只認 `mrb` 與 `engine` 兩個 static）。

* 優點：改動面最小、沒有雙路徑、`mrb_class_get("MrubyimeEngine")` 照舊。
* 缺點：切換引擎＝重建 state（buffer 清空、DSL 要重載）；必須「先驗證、後重建」。

### 方案 C：hook 契約（只實作 18 個頂層函式）

使用者不必保留 `class MrubyimeEngine`，只要實作 `mrubyime_engine_*`。

* 優點：內部結構完全自由，契約是函式名而不是類別。
* 缺點：20 個入口要從 `mrb_funcall(mrb, engine, ...)` 改成
  `mrb_funcall(mrb, mrb_top_self(mrb), ...)`，改動比 B 大；而且與 class 契約重複。

### 比較

| | 改動範圍 | 風險 | 使用者自由度 |
| --- | --- | --- | --- |
| A 雙 state | C 全部入口 | 兩份邏輯漂移 | 高（壞了不影響內建） |
| **B 單 state** | **`init` + 3 個新 JNI** | **重建失敗需回退** | **中（保留類別與方法名）** |
| C hook | C 全部入口 | 契約重寫 | 最高 |

**建議 B**：它把「引擎是可替換的資料」這件事做在唯一需要的地方（建立 state），
而不是把分派散到 20 個函式裡。

---

## 4. 方案 B 設計

### 4.1 載入順序

```
mrb_open()
  → 使用者 source ? mrb_load_string(source) : mrb_load_irep(engine_irep)
  → mrb_load_irep(keyboard_dsl_irep)
  → mrb_load_irep(keyboard_layout_irep)
  → mrb_class_get("MrubyimeEngine") → mrb_obj_new → mrb_gc_register → load_dictionary
```

**引擎先載入、佈局後載入**，所以使用者引擎就算自己定義了
`keyboard_layout_serialized`，也會被後載入的佈局覆蓋 —— 鍵盤不會被引擎改壞。

### 4.2 新增的 JNI（4 個）

| 函式 | 行為 |
| --- | --- |
| `initFromSource(dict: ByteArray, source: ByteArray): String` | 用 source 建立引擎並載入字典。成功回 `""`；失敗回訊息，且**內部已回退到內建引擎**（保證一定能打字） |
| `engineFromSource(source: ByteArray): String` | 熱切換（用 C 快取的上一次字典）。成功回 `""`，失敗回訊息且**保留目前引擎** |
| `engineIsUserDefined(): Boolean` | 設定頁顯示「目前：內建 / 使用者」 |
| `engineReset()` | 丟掉使用者引擎、回到內建（「恢復內建」按鈕） |

C 端需要的新狀態：

* `g_user_src` / `g_user_len`：目前生效的使用者原始碼（NULL = 內建）。
* `g_dict` / `g_dict_len`：最近一次字典 bytes（約 225 KB）。`init`、`initFromSource`、
  `reloadDictionary` 都更新它，`engineFromSource` 才有字典可用（避免每次重讀 225 KB）。

> JNI 名稱必須與 Kotlin `external fun` 一一對應（AGENTS.md 的規則）。
> 注意 **不能**用 Java overload（`init(dict)` / `init(dict, source)`）——
> JNI 符號只看名稱，不看簽章，兩個 overload 會撞在一起。

### 4.3 錯誤處理（重用現有沙箱）

沿用 `sandbox_parse_and_load()`、`format_parse_errors()`、`mrb_protect_error()`：

| 情況 | 回報 |
| --- | --- |
| 語法錯誤 | `line 7:3: syntax error`（`capture_errors` 的 `error_buffer`，Android 上 stderr 會消失，所以必須自己抓） |
| 執行期錯誤 | `RuntimeError: ...` + backtrace 第一幀 `engine.rb:42` |
| 沒宣告類別 | `class MrubyimeEngine is missing` |
| 空表 | `dictionary table is empty`（`table_size <= 0`） |

**兩段式驗證**：

1. 在 throwaway `mrb_state` 完整跑一次：parse → load → `mrb_obj_new` →
   `load_dictionary` → `table_size > 0` → 跑一次 `input_serialized("a")` / `backspace_serialized` /
   `current_candidates_serialized` 當煙霧測試。
2. 通過後才重建主 state。若主 state 重建仍失敗（理論上不該發生），
   再 `mrb_open()` + `mrb_load_irep(engine_irep)` 回內建。

設定頁可直接重用 `MrubyimeSettingsActivity.describeLayoutError(source, message)` 的邏輯，
顯示「第 N 行（第 M 欄）」＋出錯的那一行。

### 4.4 回退鏈

```
filesDir/engine.rb（使用者，通過驗證）
   ↓ 失敗
engine_irep.c（內建，永遠可用）
```

* 啟動時失敗 → 用內建，錯誤記在 prefs，設定頁顯示。
* 執行中熱切換失敗 → 保留目前引擎，錯誤記在 prefs。
* 使用者把檔案刪掉 → 下次 `onStartInputView` 偵測到 signature 變成 `builtin` → `engineReset()`。

### 4.5 熱重載時機

| 時機 | 動作 |
| --- | --- |
| `onCreate` | `EngineFiles.sync()` → 有檔案就 `initFromSource`，否則 `init` |
| `onStartInputView` | `EngineFiles.sync()` → `sourceSignature()` 變了 → `engineFromSource` |
| 設定頁匯入 | 先驗證，成功才寫檔，然後立即 `engineFromSource` |
| 設定頁恢復內建 | `EngineFiles.restoreBuiltin()` + `engineReset()` + 重新 `init(dict)` |

字典與引擎的 signature 要**分開**比對，否則換碼表會順便把引擎重建一次。

### 4.6 效能

| 動作 | 成本 |
| --- | --- |
| parse + load 9 KB 引擎 | host ~1 ms；裝置估 5–20 ms |
| 建表（21,949 codes） | 與現在相同（換引擎不會更慢） |
| `inputSerialized` 每次按鍵 | **0 額外成本**（引擎早已建好） |
| 字典快取 | 省下每次切引擎重讀 225 KB |

只在「啟動 / 換碼表 / 換引擎」發生，不在打字路徑上。

---

## 5. 安全與健壯性

### 5.1 沙箱能力（已驗證）

Android 版 libmruby 的 gem 清單（`mruby/build/android-arm64-v8a/mrbgems/`）**沒有**
`mruby-io`、`mruby-eval`；`nm libmruby.a | grep mrb_io_` 為 0 筆。
所以使用者引擎**不能**開檔、不能 `system`、不能 `eval` 字串，只能做純計算與字串處理。

### 5.2 既有的保護

* 64 KB 原始碼上限（與佈局 DSL 相同）。
* 在獨立的 throwaway `mrb_state` 驗證，壞掉的程式碼不會污染主 VM。
* `mrb_protect_error()` 攔截 `mrb_exc_raise`（沒有 protect frame 時 mruby 會 `abort()`）。
* 主 state 重建失敗有第二層回退。

### 5.3 真正的風險：無限迴圈 ★ 需要決策

mruby 3.3 **沒有**指令數上限、沒有 `mruby-thread`（這個 build 連背景執行緒都沒有，
`Thread#kill` 也就無從談起）、也沒有 timeout。
`while true; end` 會讓 IME 執行緒永遠卡住 → ANR，使用者只能強制停止 App。

三個選項：

| 選項 | 做法 | 代價 |
| --- | --- | --- |
| **1. 接受 + 文件警告** | 在 `docs/ENGINE_API.md` 與設定頁寫明「不可寫無限迴圈」 | 0；但一般使用者仍可能踩到 |
| 2. 匯入驗證放獨立 process | 新增 `:enginecheck` service（`android:process`），2 秒 timeout，逾時殺 process | 多一個 service + IPC；**只保護「匯入」**，外部編輯的熱重載仍可能卡住 |
| 3. 1 + 2 | 匯入走獨立 process，熱重載走主 process | 最安全，工作量最大 |

建議：**先做 1**（文件 + 設定頁警語）；若這個功能要給非工程師用，再加 2。

其他只能靠文件與上限處理的：遞迴過深、一次配置超大陣列。

### 5.4 執行緒

IME service 與 Settings 在同一個 process、同一個主執行緒，目前沒有競態。
仍建議在 C 端對「重建 state」加一把 mutex，避免未來有背景執行緒呼叫 JNI。

---

## 6. 檔案存放與編輯流程

沿用 `KeyboardLayoutFiles` 的形狀（同一個 App、同一套心智模型）：

| 路徑 | 角色 |
| --- | --- |
| `filesDir/engine.rb` | 正式檔（IME 讀這一個） |
| `getExternalFilesDir(null)/engine.rb` | 鏡像；檔案管理器可編輯，免權限 |
| `assets/mrubyime_engine.rb` | 內建版本（供「匯出」與「恢復內建」對照） |

`EngineFiles` 物件（新檔，約 120 行）提供：

* `engineFile()` / `mirrorFile()`
* `hasUserEngine()`
* `readUser()` / `builtinSource()`
* `install(source)`：temp + rename + 寫鏡像
* `restoreBuiltin()`
* `sync()`：新者勝（外部編輯 `Android/data` 的檔案會被匯入）
* `sourceSignature()`：`user:<mtime>:<len>` 或 `builtin`
* `describe()`：設定頁狀態字串

### 編輯路徑

1. **設定頁匯入**：`ACTION_OPEN_DOCUMENT`（`*/*`，64 KB）→ 先 `engineFromSource` 驗證 →
   成功才 `install()`。
2. **外部編輯器**：設定頁「寫入編輯目錄」→ FileProvider 開啟
   `Android/data/<pkg>/files/engine.rb`；存檔後 `onStartInputView` 的 `sync()` + signature 比對會熱載入。
3. **SAF（已做）**：`LayoutDocumentsProvider` 暴露單一 root「小蝦米輸入法」指向
   `filesDir/`，其下 `layouts/keyboard.rb` 與 `engine/engine.rb` 都可直接開/改。
   寫入會鏡像到 Android/data，IME 下次鍵盤出現時熱載入。

### 匯出

`ACTION_CREATE_DOCUMENT`，MIME `text/x-ruby`，建議檔名 `engine.rb`
（用 `text/plain` 會被 Downloads provider 加上 `.txt`，這是佈局匯出踩過的坑）。

---

## 7. 設定頁設計

新增區塊「輸入法引擎（Ruby）」，放在「鍵盤佈局（Ruby DSL）」旁邊：

```
輸入法引擎（Ruby）
  目前：內建 / 使用者 engine.rb（8.9 KB，11/09 21:45）
  上次錯誤：第 42 行（第 3 欄）unexpected end
           42 |   def input_serialized(key)
  [匯入 engine.rb] [匯出目前引擎]
  [寫入編輯目錄] [用外部編輯器開啟]
  [恢復內建引擎]
  說明：可修改 class MrubyimeEngine 的 17 個方法；不可寫無限迴圈、不可開檔。
```

* 錯誤顯示重用 `describeLayoutError()`（行號 + 該行內容）。
* 匯入成功後立即熱切換，使用者不必重開鍵盤。
* 「恢復內建引擎」= 刪檔 + `engineReset()` + 重新 `init(dict)`。

---

## 8. 引擎介面契約（使用者必須保留的方法）

`class MrubyimeEngine` 的 17 個方法（Kotlin/C 實際會呼叫的）：

| 方法 | 回傳 | 說明 |
| --- | --- | --- |
| `load_dictionary(str)` | — | 解析 `.cin`，重建內部表 |
| `input_serialized(key)` | String | 候選，`\n` 分隔 |
| `backspace_serialized` | String | 候選，`\n` 分隔 |
| `current_candidates_serialized` | String | 目前候選 |
| `selkeys` | String | 選字鍵 |
| `buffer` | String | 目前編碼 |
| `reset` | — | 清空 buffer |
| `table_size` | Integer | 碼數（0 = 失敗） |
| `dead_prefix` | Boolean | 是否為死碼前綴 |
| `set_max_code_len(n)` | — | 最大碼長 |
| `legal_keys_serialized` | String | 合法鍵 |
| `keynames_serialized` | String | `%keyname` 序列化 |
| `dictionary_name` | String | `%cname` / `%ename` |
| `table_max_code_len` | Integer | 表內最長碼 |
| `entry_count` | Integer | 條目數 |
| `can_extend?` | Boolean | 還能接任何合法鍵嗎 |
| `would_extend?(key)` | Boolean | `buffer+key` 是否仍可能是碼或前綴 |

> 這 17 個就是「引擎 API」。建議另寫 `docs/ENGINE_API.md`，附一個 30 行的最小範例引擎
> （例如「倒序輸出候選」），讓使用者知道可以改到什麼程度。

---

## 9. 實作步驟（每步都要能獨立驗證）

| # | 內容 | 驗證 |
| --- | --- | --- |
| 0 | 寫 `docs/ENGINE_API.md`（17 個方法 + 範例 + 限制） | 人工審閱 |
| 1 | C：字典快取 + `initFromSource` / `engineFromSource` / `engineReset` / `engineIsUserDefined` | `clang -fsyntax-only` → `assembleDebug` |
| 2 | Kotlin：`EngineFiles.kt`（照 `KeyboardLayoutFiles` 抄形狀） | `:app:compileDebugKotlin` |
| 3 | `MrubyimeJNI.kt` + `MrubyimeEngineDelegate(Impl)` 加 4 個方法 | 同上（符號對稱） |
| 4 | `MrubyimeIME`：`onCreate` + `onStartInputView` 熱重載 + 錯誤存 prefs | `assembleDebug` + 實機 |
| 5 | 設定頁「輸入法引擎」區塊（匯入/匯出/編輯/恢復） | 實機 |
| 6 | 文件：`README.md`、`AGENTS.md`、`docs/ENGINE_API.md` | 人工審閱 |

每一段都先 `clang -fsyntax-only` / `compileDebugKotlin`，再 `assembleDebug`，
最後才安裝到實機（`TITAN20000011795`）。

---

## 10. 測試矩陣（實機 TITAN20000011795，2026-11-10）

| # | 情境 | 預期 | 實機結果 |
| --- | --- | --- | --- |
| 1 | 沒有 `engine.rb` | 內建引擎，行為與現在完全相同 | ✅ `engine user=false source=builtin`，21,949 codes |
| 2 | 合法 `engine.rb`（class-only） | 生效，設定頁顯示「使用者」 | ✅ `engine user=true`，同一份字典 21,949 codes |
| 3 | 語法／執行期錯誤 | 保留上一可用引擎；設定頁顯示行號 | ✅ `undefined method 'error' (engine.rb:3)`，打字照常 |
| 4 | 外部編輯 `Android/data/.../engine.rb` | 下次鍵盤出現時熱載入 | ✅ mirror 寫入 → `user engine hot-swapped` |
| 5 | 刪除檔案 | 回到內建（`engineReset`） | ✅ 目錄清空後 `engine user=false` |
| 6 | 打字 | 使用者引擎產生候選 | ✅ `y` → `益` |
| 7 | 大碼表（21,949 codes） | 啟動時間同量級 | ✅ 載入成功，無逾時 |
| 8 | `while true; end` | 不凍結 IME（獨立驗證 + 執行期逾時隔離） | 見 §5.4（未做破壞性實測；獨立 `:enginecheck` process 已在） |

**兩個關鍵實機發現（都是裝置 mruby 3.3.0 的行為差異，host 無）：**

1. **`@table[c] ||= []` 會被執行期編譯器誤編譯**：對 `Hash#[]` 接收者做 `||=`，
   mruby 會對這個敘述送出錯誤的方法名（`undefined method 'ar'` / `'MAX'` /
   `'bsearch_index'`），且行號錯位。改用暫存變數（`list = @table[c]; list = [] unless list`）
   即可。**內建引擎也已改寫**，避免使用者匯出/編輯內建版本時踩到。
2. **載入大碼表時 GC arena 溢滿**：預設 `MRB_GC_ARENA_SIZE=100`，執行期編譯
   （`mrb_load_exec`）多佔 arena；`load_dictionary` 一次塞 2 萬多筆 Hash 便觸發
   arena 回收，把仍在用的物件收掉 → 上述 `undefined method 'MAX'` 等隨機錯誤。
   修法：`build_mruby.sh` 的裝置版 build 加上 `MRB_GC_ARENA_SIZE=4096`，並在
   `engine_build_body` 裡對 `load_dictionary` 包 `mrb_gc_arena_save/restore`
   （實例先 `mrb_gc_register`）。兩者都做後，實機穩定載入。

---

## 11. 決策（已拍板）

1. **契約**：`class MrubyimeEngine` 的 17 個實例方法（已採）。
2. **hook 層**：18 個 `mrubyime_engine_*` 頂層函式已從 `mrubyime_engine.rb` 刪除。
3. **無限迴圈**：兩者都做——獨立 `:enginecheck` process 驗證（`EngineCheckService`，
   3 秒逾時）＋ 執行期逾時隔離（`runEngineBuild` 19 秒後 quarantine + 重啟；
   首次載入在背景 `engine-load` 執行緒，18–19 秒刻意壓在 service ANR 視窗之下）。
4. **`engine.rb` 覆蓋鍵盤佈局**：不行——`engine_build_body` 先載入引擎，再載入
   `keyboard_dsl_irep` / `keyboard_layout_irep`，使用者程式碼無法覆寫
   `keyboard_layout_serialized`。
5. **SAF**：要。`LayoutDocumentsProvider` 暴露單一 root 指向 `filesDir/`，其下
   `layouts/keyboard.rb` 與 `engine/engine.rb` 都可直接開/改。
