# 小蝦米輸入法 — 鍵盤前端（Python / Kivy）

這份文件說明**現行**的鍵盤畫面。輸入引擎（mruby、`.cin` 解析、候選字產生）仍在 Kotlin 端，
本文件只涵蓋「引擎給出候選字之後，如何畫出來、如何互動」。

> **歷史文件：** `README.md` 第 2 節之後描述的是舊的 **Ruby DSL → IR JSON → Kotlin
> `KeyboardRenderer`** 版面系統。那套系統的程式碼仍在，但**不再驅動畫面**，請勿據此
> 修改鍵盤。其對照與失效範圍見 `README.md` 頂端的狀態說明。

---

## 它在哪裡

```
mruby-ime/app/src/main/python/
├── main.py                  # 進入點：python-for-android source.dir
└── kivy_ime/
    ├── __init__.py
    ├── keyboard.py          # ★ 整個鍵盤 UI 都在這（約 1555 行）
    └── bridge.py            # 本機 TCP JSON 通訊（port 19876）
```

`keyboard.py` 是唯一需要改的地方。改它不需要碰 Ruby、不需要跑 `build_mruby.sh`、
不需要重編 mruby。

---

## 架構：兩端、一條 TCP

```
┌──────────────────────────── Android IME window ────────────────────────────┐
│                                                                              │
│  Kotlin  MrubyimeIME : InputMethodService          Python / Kivy             │
│  ┌──────────────────────────────┐                ┌────────────────────────┐ │
│  │ .cin 碼表解析（mruby, JNI）    │                │ keyboard.py（UI）       │ │
│  │ 候選字排序、組字 buffer        │                │ 12 欄 × 5 列、手勢、音效 │ │
│  │ 內聯組字 / commit 到輸入框    │                │ 候選 chip、小恐龍遊戲    │ │
│  └──────────────┬───────────────┘                └───────────┬────────────┘ │
│                 │      KivyImeHost.kt（宿主、視窗尺寸、生命週期）│              │
│                 │   ┌──────────────────────────────────────┐  │              │
│                 └──►│  localhost TCP  ·  JSON 每行一筆      │◄─┘              │
│                     │  Kotlin ↔ Python（KivyBridge，19876）  │                 │
│                     └──────────────────────────────────────┘                 │
└──────────────────────────────────────────────────────────────────────────────┘
```

- **Kotlin 端**負責輸入法該做的事：讀碼表、算候選字、commit 到 `InputConnection`、
  處理實體鍵盤事件（`onKeyDown`）、顯示/隱藏（`requestHideSelf`）。
- **Python 端**只負責畫面：按鍵幾何、顏色、按下回饋、滑動手勢、候選 chip 顯示、音效、
  小恐龍遊戲。它不解析碼表、不決定候選順序——它把使用者按了什麼送過去，把 Kotlin
  回傳的 buffer / 候選字畫出來。
- Kivy 在 IME 視窗內以 **SDL surface 全螢幕**繪製，再由 `KivyImeHost` 指定鍵盤高度。

因為分割線在「候選字產生 / 候選字呈現」，所以**換碼表、改引擎不需要動鍵盤程式；
改鍵盤外觀不需要重編 Kotlin 或 mruby**。

---

## 237 ms 迭代，不是 3 分鐘

一般 Android 專案改一個按鍵顏色要重編 Kotlin、重打 APK、重裝、重開輸入法。
這裡的 Python 被打包成 `assets/private.tar`（p4a 的 Python bundle），所以改 UI 只要
**重打包那個 tar、重簽 APK、`adb install -r`**：

```bash
cd mruby-ime && ./sync_python.sh
```

| 動作 | 大約時間 |
|---|---|
| `./sync_python.sh`（改 Python 後） | **2 秒**（增量）／約 10 秒（從乾淨 `app/build` 全編） |
| `./build_all.sh`（連 mruby 一起） | 約 60–80 秒 |
| `./buildozer_apk.sh`（全量 p4a 重編） | 數十分鐘 |

`sync_python.sh` 只做三件事：把 `app/src/main/python/**` 編成 `.pyc`、重打包
`private.tar`、更新 `private_version` 的 sha1，然後跑一次 Gradle。它**刻意不呼叫
buildozer**，所以不需要下載 p4a/CPython/SDL2。打包是**決定性的**（固定路徑、mtime/uid/gid
歸零），原始碼沒改就得到同一個 sha1，App 不會白白重新解壓 13 MB 的 bundle。

---

## 版面

12 欄格線、5 列，中英各一套（同一份 `ROWS` 衍生）。

```
中國模式                                   English
┌────────────────────────────────────────┐ ┌────────────────────────────────────────┐
│ 編碼區(2)     候選字 chip × n (9)   ▼(1) │ │ TAB  ·  ^A  ^C  ^V  ⇧ ⇩ ⇦ ⇨  DT  W  ▼ │
├────┬────┬────┬────┬────┬────┬────┬────┬─┤ ├────────────────────────────────────────┤
│ 1  │ 2  │ 3  │ 4  │ 5  │ 6  │ 7  │ 8  │…│ │ 1  2  3  4  5  6  7  8  9  0  -  =        │
│ Q  │ W  │ E  │ R  │ T  │ Y  │ U  │ I  │…│ │ q  w  e  r  t  y  u  i  o  p  [  ]        │
│ A  │ S  │ D  │ F  │ G  │ H  │ J  │ K  │…│ │ a  s  d  f  g  h  j  k  l  ;  '  \        │
│ Z  │ X  │ C  │ V  │ B  │ N  │ M  │ ,  │…│ │ z  x  c  v  b  n  m  ,  .  /  `  ⌫        │
│  中/英  │      空白（8 單位）      │  ⏎  │ │  中/英  │      空白（8 單位）      │  ⏎  │
└────┴────┴────┴────┴────┴────┴────┴────┴─┘ └────────────────────────────────────────┘
```

- 中英模式差在**鍵帽字**（大寫／小寫）與**上方那一列**：中文是編碼區＋候選 chip，
  英文是 12 鍵功能列（`TAB`、剪貼簿 `^A ^C ^Vce`、方向鍵、`DT` 日期時間、`W` 星期、`▼`）。
- 每列固定 12 欄；第 5 列是 `中/英`(2) + `空白`(8) + `⏎`(2)。
- 倒引號鍵在第 4 列 `/` 的位置，它的上滑字元是 `~`（畫在鍵右上角）。

### 上滑＝另一個字元

字母與符號鍵往上滑會送出另一個字元，該字元以小字印在鍵的右上角（`sub_label`）。
實作上是在鍵上偵測「按下後向上拖超過 16 dp」。

| 下排 | `[` | `]` | `;` | `'` | `\` | `,` | `.` | `/` | 倒引號 | `⌫` |
|---|---|---|---|---|---|---|---|---|---|---|
| 上滑 | `{` | `}` | `:` | `"` | `|` | `<` | `>` | `?` | `~` | — |

數字列 1–0 `-` `=` 上滑為 `! @ # $ % ^ & * ( ) _ +`。
英文字母鍵上滑==送出該字母的大寫。

### 編碼區（左側長條）

- **24sp 粗體、全大寫、無邊框**，寬度固定 2 單位（候選區 9 單位、`▼` 1 單位，共 12）。
- **不換行**。碼太長時只顯示**最後 6 碼**（前綴往左裁掉），因為正在打的永遠是尾端。
  引擎內部仍保存完整 buffer，候選字不受影響。
- **點一下**：送出目前這串碼（純文字 commit）。
- **往上滑**：送出畫面上內聯組合文字所顯示的內容（`inline_composing_mode`）。

### 候選字 chip

- **24sp 粗體**，格式 `0你好` `1您好`（`%selkey` 的選字鍵緊接字面，中間無空格）。
- 每一個 chip 都**等寬**（以 `0微` 為最小寬度量測），所以選字提示不會讓版面跳動。
  超過 `%selkey` 提供的第 10 個之後，chip 只顯示字、不顯示鍵號。
- 整個候選列**不換頁、不顯示頁碼箭頭**，改成單一可橫向拖動的列：手指拖動時跟隨，
  放手後吸附到「整頁」邊界並**循環**。
- 被選中的 chip 有**選取標記**（DSL 的 `border_selected`；內建版面是底部一條 2 dp 實線）。
  沒有候選字時顯示一個**佔位 chip**，讓編碼區的高度不會塌陷。
- chip 是**池化重用**的：只新增/移除差額、文字有變才換字、選取或主題有變才重畫。
  打字時不會重建整排。

### 顏色

淺色為主（電子紙取用場景）：白底、黑字、1 dp 黑色分隔線、選取那一條 2 dp 黑線。
深色主題另有一組 token，但所有顏色都**從版面 DSL／`ThemeResolver` 的 token 解析**
（`ink`、`seam`、`keyBorder`、`chipSelected`、`lampOn`、`lampOff`…），不在 Python 裡寫死色碼。

---

## 手勢與動作一覽

所有動作都由同一個分派器處理（點擊、長按、滑動都走它），所以行為一致：

| 動作 | 說明 |
|---|---|
| `code` | 送出一個字元（預設是鍵面第一字） |
| `space` | 空白；**上滑**＝切中英；**左右滑**＝移動游標（每約 24 dp 一格） |
| `backspace` | 退格，按住 400 ms 後每 80 ms 連發 |
| `enter` | 換行 |
| `ime_hide` | 收鍵盤 |
| `toggle_mode` | 明確切中／英 |
| `combo` | 送組合鍵（例 `ctrl+shift+1`、`shift+enter`、`alt+tab`） |
| `key_event` | 送單一特殊鍵（`ins` `del` `home` `end` `pgup` `pgdn` `tab` `capslock` `menu` `f1`–`f12`、方向鍵） |
| `modifier` | 黏著式 Ctrl／Shift／Alt／Meta：按一下亮起（底線），套用到下一個鍵，然後自動放開 |
| `date` `time` `weekday` `datetime` | 送出當下時間，格式由 `value:` 決定（`SimpleDateFormat`），**點擊當下**才取值 |
| `dpad` | 單方向游標移動（`up` `down` `left` `right`） |
| `clipboard` | `select_all` `copy` `paste` |
| `noop` | 佔位，不做任何事 |

- **長按 `▼`**：切換「完整鍵盤 ⇄ 只留 48 dp 候選列」。長按震動已關閉。
- **隱藏鍵盤（`▼` 點一下、Back、切到別的 App）** 一律**放棄目前組字**：清空 buffer、
  清空候選、不把任何東西送進輸入框。所以半打的碼不會留在輸入框裡變成內聯文字。
- 實體鍵盤：`Alt+Space` / `Shift+Space` 切中英（單按 Shift 不再切換）；
  `←` `→` 移動候選選取；`↑` `↓` 整頁翻候選；`DEL` `SPACE` `ENTER` 在組字中交給組字邏輯；
  `ESC` 清空組字。按住不放會**重複執行同一段邏輯**（不會漏到 App 那邊變成一串 `aaaa`）。

---

## 小遊戲

候選列右側有一個小恐龍鍵（`🦖` 以向量繪製，因為 NotoColorEmoji 在 SDL_ttf 下無法上色）。
點它會把整個鍵盤換成遊戲畫面，`▼` 旁邊多一個 `KB` 鍵可以回到打字。

- 玩法：**點一下／往上滑＝跳**，**往下滑＝蹲**（蹲下時恐龍較矮，可閃過較高的仙人掌）。
- 障礙物有兩種：地面仙人掌、空中飛鳥。速度與生成頻率隨分數上升。
- 分數顯示在右上 `HI 00000   00000`（最高分、本局），超過舊紀錄時更新。
- 撞到即 `GAME OVER`，**點畫面任何位置**重來。
- 遊戲文字全是英文、按鍵有音效，與輸入法語系無關；離開遊戲一定會回到中文打字。

`dino_game.py` 是同一支遊戲的桌面獨立版，不需要手機也能跑：

```bash
./kivy-venv/bin/python mruby-ime/app/src/main/python/dino_game.py
```

---

## 改鍵盤（Python）

編輯 `mruby-ime/app/src/main/python/kivy_ime/keyboard.py`：

```bash
cd mruby-ime
./sync_python.sh          # 編譯 + 打包 + 簽 APK + adb install -r
```

不需要 `build_all.sh`、不需要 `buildozer`、不需要改任何 `.rb`。
`sync_python.sh` 也會把 `app/src/main/python/assets/` 底下**非 `.py`** 的檔案
（字型等）一併打包進 `private.tar`。

### 字型

鍵盤用 **Unifont-T**（`app/src/main/python/assets/unifont_t.otf`，5.0 MB）繪製。
它涵蓋 Noto Sans CJK 缺的符號：`⌫ U+232B`、`⏎ U+23CE`、`▼`、`⇧ ⇩ ⇦ ⇨`、
框線字元、以及大量 CJK（繁簡）。選擇性 fallback 依序為：

1. `filesDir/app/assets/unifont_t.otf`（打包在 bundle 裡，裝置上的實際路徑）
2. 專案裡追蹤的 `app/src/main/python/assets/unifont_t.otf`（桌面預覽用）
3. `NotoSansCJK-Regular.ttc`
4. 系統字型（`/system/fonts/…`）

要換字型：把檔案放進 `app/src/main/python/assets/`，改 `_bundled_font_paths()`
或 `_desktop_font_paths()`。字型的檔名不需要是 `.ttf`，`.otf` 也可以。

---

## 除錯

```bash
# 看 Python 端輸出（Kivy 的 print 會進 logcat）
adb logcat -s python | grep kivy-ime

# 字型是否載入成功
adb logcat -s python | grep "CJK font"

# Kotlin 端（引擎、bridge、JNI 錯誤）
adb logcat -s MrubyimeIME MrubyimeJNI
```

Python 端常見失敗原因是 `private.tar` 沒有打進去或打的內容不完整——
看 `asset unpack` 相關的 log，或直接確認 APK：

```bash
unzip -l app/build/outputs/apk/release/app-release.apk | grep private.tar
```

---

## 為什麼是這個架構

- **Kotlin 當 InputMethodService**：Android 的輸入法生命週期、`InputConnection`
  commit、實體鍵盤事件都在 Kotlin 這邊，這些沒法搬去 Python。
- **Python/Kivy 當畫面**：版面要常常迭代。一次 2 秒和一次 3 分鐘的差別，決定了
  你願不願意把一個按鍵的間距改到滿意。
- **TCP JSON 而不是直接 JNI 互叫**：兩邊各自活在一個 thread／runtime，用
  localhost 的 socket 溝通讓 Python 崩了也不會帶走輸入法服務；訊息是純文字，logcat
  兩邊都能直接看。
- **`private.tar` 進版控**：這是 p4a 產出的 Python bundle，內容是 **CPython 3.14**
  的 `.pyc`。CI 沒有 3.14 也沒有 p4a，所以建置時直接沿用已提交的那一份；只改 Python
  時，在本機跑 `sync_python.sh` 重打包再提交即可。
- **只編 `arm64-v8a`**：JNI 的 libmruby 只交叉編譯這個 ABI，x86_64 模擬器載不起來。

---

## 與 Ruby DSL 的關係（為什麼還留著那些檔案）

`keyboard_layout.rb`、`keyboard_dsl.rb`、`keyboard_layout.json`、三個 `*_irep.c`
仍會在建置流程中被 `build_mruby.sh` 產生，但**沒有任何地方把它們渲染到畫面上**：

- `MrubyimeIME.useKivyFrontend()` 讀 `kivy_frontend`，預設 `true`，而且**設定頁
  沒有對應開關**，所以沒有正常途徑能切掉。
- 就算真的切掉，`onCreateInputView()` 回傳的是一個**空的 `FrameLayout`**，
  不是 DSL 鍵盤。舊的 `KeyboardView` / `KeyboardRenderer` 路徑是不可達的死碼。
- `keyboard_dsl.rb` 的 Builder 與 `layoutFromSourceSerialized` JNI **仍在用**，
  但用途只剩「驗證匯入的 DSL」，驗證結果不會顯示在螢幕上。
- `LayoutDocumentsProvider`（Android/data SAF 根目錄）**仍在用**——它同時
  服務 `dicts/`、`fonts/`、`sounds/` 與 `layouts/`，不是專屬 DSL。

保留它們的理由：`keyboard_layout.rb` 是現行版面的來源（12 欄、5 列、扁平黑白、
編碼區 2 : 候選 9 : `▼` 1 都是從它移植過來的），`keyboard_dsl.rb` 是 `.cin` 鍵面的
驗證器，`LayoutDocumentsProvider` 是檔案存取入口。要恢復「Ruby DSL 可自訂鍵盤畫面」
請另開分支，並先決定 Kivy 與 DSL 兩條渲染路徑的優先順序。
