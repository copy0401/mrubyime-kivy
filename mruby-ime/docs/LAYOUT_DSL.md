# 鍵盤佈局／配色設計包（Keyboard DSL v1）

這份文件是給「要設計鍵盤版面或配色、但不改 Kotlin」的人（或 AI）看的。
對方只需要交回**一個 `.rb` 檔**，就能在 App 裡匯入、熱載入，不需要重新編譯 APK。

---

## 1. 要給對方的檔案

| 檔案 | 內容 | 必要性 |
|---|---|---|
| `app/src/main/assets/keyboard_layout.rb` | 內建版面本體；檔頭有完整 DSL 註解與實際範例 | ★★★ 必給 |
| `docs/layout_template.rb` | 最小可匯入範本（12 欄、中／英兩頁、黑白） | ★★★ 建議 |
| `app/src/main/assets/keyboard_dsl.rb` | DSL 實作（精確的選項名稱、預設值、驗證規則） | ★★ 給 AI 很有用 |
| `README.md` 第 10–16 節 | 配色、完整範例、對齊、狀態燈 | ★★ |
| `app/src/main/assets/fonts/README.txt` | 自訂字型的放置位置與寫法 | ★ 只有要換字型才給 |
| `app/src/main/assets/sounds/README.txt` | 自訂音效的放置位置與寫法 | ★ 只有要換音效才給 |

**不用給**：`*.kt`、`*.c`、`boshiamy_engine.rb`、`.cin` 碼表。
（除非同時要設計 `%keyname` 鍵面，那才需要該張碼表的 `%keyname` 段落。）

> 最精簡的一包：`keyboard_layout.rb` + `layout_template.rb` + 這份文件。

---

## 2. 檔案格式與硬限制

- 一個純 Ruby 檔，最外層固定是：

  ```ruby
  Keyboard.layout version: 1 do |ctx|
    # grid / height / padding / theme / page …
  end
  ```

- 裝置端是 **mruby 3.3**：不能使用 Regexp、`eval`、`require`、外部 gem。
  純字串、陣列、Hash、`each`、`map`、字串插值都可以。
- 匯入上限 **64 KB**。
- **只有第一頁**（第一個被建立的 `page`，也就是 `defaultPage`）與頁名為
  `english` 或 `en` 的頁面會被顯示。其他分頁即使寫了也不會出現。
- 匯入後會存成 `layouts/keyboard.rb`，下次鍵盤出現時熱載入。
  格式錯誤會在設定頁顯示「第 N 行（第 M 欄）」與出錯的那一行。
- 預設外觀：**直角、無動畫、無 ripple**；淺色模式（預設）是純黑白。
- 尺寸、字級、字型**只由 DSL 決定**，設定頁不會覆蓋。

---

## 3. 最小可匯入範本

直接看 `docs/layout_template.rb`。它是 12 欄、5 列、中英各一頁的可編譯起點：

```ruby
Keyboard.layout version: 1 do |ctx|
  grid columns: 12, gap_h: 0, gap_v: 0, outer: 0
  height bar: 48, body: 240
  padding left: 0, right: 0, bottom: 0
  # theme :light / theme :dark
  # page :chinese / page :english
end
```

---

## 4. DSL 速查

### 4.1 全域設定

| DSL | 說明 | 預設 |
|---|---|---|
| `grid columns: 12, gap_h: 0, gap_v: 0, outer: 0` | 欄數、水平／垂直間距、左右外距（`gap_*`／`outer` 單位 **dp**）。`gap_h`/`gap_v` 是**視覺**間距，觸控範圍仍相連（按到縫上會由最近的鍵接手）；`outer` 是外緣留白，內建設 **0**（鍵盤貼齊視窗緣，深色模式才不會在兩側露出深色粗邊） | `10 / 1 / 1 / 0.5` |
| `height bar: 48, body: 240` | 候選列高度／按鍵區高度（dp）。總高 = `bar + body + padding.bottom`；隱藏按鍵列時 = `bar + padding.bottom` | `48 / 240` |
| `padding left: 0, right: 0, bottom: 0` | 鍵盤左右內距與底部預留（dp） | `0 / 0 / 0` |
| `sound :click, volume: 0.6` | 版面預設音效；`:none` 靜音；也可給檔名 | 無聲 |
| `min_touch 48` | 解析但**目前未使用** | `48` |

**格線是對齊的關鍵**：每個邊界都由 `outer + 累計 span × unit` 算出。
一列的 `span` 總和建議等於 `columns`；不足的部分才會交給 `row align:`。

### 4.2 主題與顏色 token

```ruby
theme :light do
  color :bar, "#FFFFFF"
  color :key, "#FFFFFF"
  color :keyBorder, "#000000"
  color :ink, "#000000"
end

theme :dark do
  color :bar, "#3A3A3C"
  color :key, "#3A3A3C"
  color :keyPressed, "#505054"
  color :seam, "#1C1C1E"
  color :ink, "#FFFFFF"
end
```

顏色值支援 `#RGB`、`#RRGGBB`、`#AARRGGBB`。**全部 token 都可省略**，沒給就沿著下表往後退：

| token | 控制 | 後退順序 |
|---|---|---|
| `bar` | 候選列底色 | 淺色白／深色 `#3A3A3C` |
| `barInk` | 候選列上的符號鍵字色 | → `ink` |
| `buffer` / `bufferInk` | 編碼區底色／字色 | `buffer` → `bar`；`bufferInk` → `ink` |
| `chip` / `chipInk` | 未選中候選的底色／字色 | `chip` → `bar`；`chipInk` → `ink` |
| `chipSelected` / `chipSelectedInk` | 選中候選的底色／字色 | → `chip` / `chipInk` → `bar` / `ink` |
| `chipMark` | 候選框線／選取線的預設顏色 | → `ink` |
| `key` / `keyInk` | 一般按鍵底色／字色 | `key` → 淺色白／深色灰；`keyInk` → `ink` |
| `keyPressed` | 按鍵按下的底色（深色主題用） | → 按鍵底色 |
| `keyBorder` | 按鍵框線顏色（淺色主題用） | 無（不給就不畫框） |
| `seam` | 鍵盤最底層／鍵縫顏色 | 淺色白／深色 `#1C1C1E` |
| `muted` | 右上角小字 `sub_label` 的字色 | → `ink` |
| `lampOn` / `lampOff` | 狀態燈亮／暗的**墨色** | `lampOn` → `ink`；`lampOff` → `keyBorder` → `ink` |

> `lampOn` / `lampOff` 是「字色」，不是底色。暗燈會自動往 `bar` 底色混 35%。
>
> 只定義 `theme :dark` 而沒有 `theme :light` 時，淺色模式會退回第一個主題。
> **兩套都寫**最安全。

單鍵可以再覆寫：`ink:`、`bg:`、`style: { border: { color:, width: }, radius: }`。

### 4.3 分頁與候選列

```ruby
page :chinese do
  bar height: 48, bg: :bar, gap: 0,
      border: { width: 1, color: :ink, style: :solid, side: :top } do
    buffer weight: 2, role: :buffer, format: :uppercase, max_chars: 6
    candidates weight: 9, role: :candidate_row,
               border: { width: 1, color: :seam, style: :dotted },
               border_selected: { width: 2, color: :ink, style: :solid, side: :bottom }
    key :hide, weight: 1, role: :hide, label: "▼", action: :ime_hide, flat: true
  end
  rows do
    # row do … end
  end
end

page :english do
  # 英文頁；頁名必須是 english 或 en 才會顯示
end
```

- `page :name, when: ->(c) { c[:input_type] == "number" } do … end`
  條件成立才建立該頁；但顯示規則仍然只認第一頁與 `english` / `en`。
- `bar` 的 slot 有三種：`buffer`、`candidates`、`key`，另外可用
  `slot :名字 do … end` 放一整組按鍵、`status do lamp … end` 放狀態燈。
- `span:` 是固定格數；`weight:` 是剩餘寬度的比例。**全部用 `weight`** 最直覺：
  內建中文列是 `buffer 2 : candidates 9 : hide 1 = 12`。
- `bar border:` 是**內縮**繪製：框線吃掉 bar 自己的內容寬度，不佔版面、不會被裁掉。
  `style:` 只收 `:solid` / `:dashed` / `:dotted`；`side:` 預設 `:all`，
  可只給 `:top` / `:bottom` / `:left` / `:right`；`width: 0`（預設）＝不畫。
- `bar height:` 省略時用全域 `height bar:`；`bar gap:` 預設 1 dp（單位 dp）。
- `bar bg:` 目前**不影響外觀**（候選列固定用 `bar` token）。

### 4.4 編碼區與候選字

```ruby
buffer weight: 2, role: :buffer, format: :uppercase, max_chars: 6,
       font: { size: 24, weight: :bold, family: "NotoSansCJK" }

candidates weight: 9, role: :candidate_row,
           font: { size: 24, weight: :bold, family: "NotoSansCJK" },
           border: { width: 1, color: :seam, style: :dotted },
           border_selected: { width: 2, color: :ink, style: :solid, side: :bottom }
```

| 參數 | 說明 |
|---|---|
| `format: :uppercase` | 編碼顯示大寫（預設）；其他值＝原樣 |
| `max_chars: 6` | 編碼過長時只顯示後 6 碼（預設 6） |
| `font: { size:, weight:, family: }` | 字級 sp、`:normal` / `:bold`、字型檔名 |
| `border:` | **未選中**每一顆候選的外框 |
| `border_selected:` | **選中**那一顆的外框（可只畫 `side: :bottom`） |
| `chip_style:` | 進階：直接併進 chip 的 style（`bg:` / `ink:` / `selected:`） |
| `autoscroll:` | 解析但**目前未使用** |

候選 chip 的文字固定是「選字鍵＋字」（例如 `0微`），字級由 `candidates font:` 控制；
所有 chip 等寬，寬度以 `0微` 為基準。`border:` 與 `border_selected:` 互不影響。

### 4.5 列與按鍵

```ruby
rows do
  row height: 1, align: :start, gap_h: 0 do
    key "a", span: 1, action: :code, value: "a", transform: :upper
    key :space, span: 10, role: :space, label: "", action: :space
    spacer span: 1
  end
end
```

| 參數 | 說明 |
|---|---|
| `row height:` | 同一頁各列的**比例**（`RowsLayout` weight），不是 dp；預設 1 |
| `row align:` | `:left` / `:center` / `:right` / `:space_between`；只在 span 總和 < `columns` 時有作用 |
| `row gap_h:` | 該列的水平間距（dp），覆寫 `grid gap_h` |
| `spacer span:` | 隱形、不可點的空白（只在 `row` 內有效） |

**按鍵選項**

| 選項 | 說明 |
|---|---|
| `span:` / `weight:` | 固定格數／剩餘寬度比例（預設 `span: 1`） |
| `role:` | 決定 Android view id 與內建手勢（見 4.9），不影響外觀 |
| `label:` | 鍵面文字 |
| `label_zh:` / `label_en:` | 依中／英模式換字；沒給就退回 `label` |
| `sub_label:` / `sub_label_zh:` / `sub_label_en:` | 右上角小字，同樣可依模式換 |
| `action:` / `value:` | 動作與參數（見 4.6） |
| `align:` | 單鍵文字 `:left` / `:center` / `:right` |
| `transform: :upper` | 鍵面轉大寫；**送出的值仍由 `value:` 決定** |
| `flat: true` | 不畫框、底色改用候選列的 `bar` 色 |
| `ink:` / `bg:` | 覆寫這個鍵的顏色 token |
| `style: { border: { color:, width: }, radius: }` | 單鍵外框與圓角（dp） |
| `font: { size:, weight:, family: }` | 字級（sp）、粗細、字型檔名 |
| `label_fit:` | `:wrap` / `:shrink` / `:none`（見下） |
| `label_wrap: 2` | `:wrap` 時每行幾個字（預設 2） |
| `sound:` / `sound_volume:` | 單鍵音效（優先於版面層） |
| `haptic: :light/:medium/:heavy` | 單鍵震動 |
| `gestures:` | 四方向手勢（見 4.7） |
| `long_press:` | 目前只支援 `:toggle_hide_rows` |
| `repeat: { delay: 400, interval: 80 }` | 只對 `action: :backspace` 生效 |
| `desc:` | 無障礙描述 |
| `icon:` | 解析但**目前不會畫圖示** |

**標籤塞不下時**

| `label_fit:` | 行為 |
|---|---|
| `:wrap` | 每 `label_wrap` 個字換行（預設 2 字一行） |
| `:shrink` | 維持一行，自動縮字級，**最小縮到 50%** |
| `:none` | 照原樣，超出格子會被裁掉 |

- DSL 自己寫的標籤預設 `:none`（所以 `TAB` / `DT` 不會斷行）。
- 碼表 `%keyname` 的標籤預設 `:wrap`（例如 `g → ㄍㄐㄜ`）。
- 每個鍵的標籤都放在格子大小的裁切容器裡，**不會溢出到隔壁鍵**。

**間距如何繪製（`gap_h` / `gap_v` / `outer` / `bar gap:`）**

- `grid gap_h` 與 `grid gap_v` 只影響**外觀**：鍵面與文字往內縮 `gap/2`，鍵的觸控範圍仍是完整格位。
- 手指按在兩鍵之間的縫上時，會由最近的鍵接手（縫的中線是分界），**不會變成點不到的死區**。
- `outer` 是鍵盤左右外緣留白、`bar gap:` 是候選列槽位間距，這兩者仍是留白，不屬於「按鍵間距」。

**按鍵預設動作**

- 字串標籤（`key "a"`）→ 預設 `action: :code`，`value` 省略時送標籤第一個字元。
- 符號標籤（`key :enter`）→ 預設 `action: :noop`。
  所以符號標籤**一定要自己寫 `label:` 與 `action:`**，否則鍵面空白、點了沒反應。

### 4.6 動作（`action:`）

| 動作 | `value:` | 說明 |
|---|---|---|
| `:code` | 任意字串 | 送出該字元（省略時用標籤第一個字元） |
| `:space` / `:enter` / `:backspace` / `:ime_hide` / `:noop` | — | 空白／換行／退格／收鍵盤／無作用 |
| `:toggle_mode` | — | 切換中／英（同第 5 列的中/英鍵、Space 下滑、Alt+Space／Shift+Space） |
| `:combo` | `"ctrl+shift+1"` | 送組合鍵；可用 `\|` 串接：`"ctrl+a\|text:hi\|ctrl+v"` |
| `:key_event` | `"ins"` / `"del"` / `"home"` / `"end"` / `"pgup"` / `"pgdn"` / `"tab"` / `"capslock"` / `"esc"` / `"menu"` / `"f1"`…`"f12"` / `"up"`… | 送特殊鍵 |
| `:modifier` | `ctrl` / `shift` / `alt` / `meta` | 一次性黏著修飾鍵，套用到下一個 `code` / `combo` / `key_event` |
| `:date` / `:time` / `:weekday` / `:datetime` | `SimpleDateFormat` 樣式 | 點擊當下取時間 |
| `:dpad` | `up` / `down` / `left` / `right` | 方向鍵 |
| `:clipboard` | `select_all` / `copy` / `paste` | 全選／複製／貼上 |

`:date` / `:time` / `:weekday` / `:datetime` 的預設樣式分別是
`yyyy-MM-dd` / `HH:mm` / `EEEE` / `yyyy-MM-dd HH:mm`。

### 4.7 手勢、長按、連發

```ruby
key "S", action: :code, value: "s",
    gestures: {
      swipe_up: "S",                                  # 字串＝送出這個字
      swipe_down: :toggle_mode,                       # 符號＝動作
      swipe_left: { action: :combo, value: "ctrl+z" } # Hash＝動作＋參數
    }

key :backspace, action: :backspace, repeat: { delay: 400, interval: 80 }
key :hide, action: :ime_hide, long_press: :toggle_hide_rows
```

- `gestures:` 可用 `swipe_up` / `swipe_down` / `swipe_left` / `swipe_right`。
- 字串＝送出文字；符號＝動作；Hash 可寫 `{ action:, value: }` 或短版 `{ a:, v: }`。
- 一旦某個鍵寫了 `gestures:`，英文模式的自動「上滑送大寫」就不會附加。
- `long_press:` 目前只支援 `:toggle_hide_rows`（其他值會被忽略）。
- `repeat:` 只對 `:backspace` 生效。

### 4.8 狀態燈

```ruby
status span: 2, gap: 2 do
  lamp :shift, label: "S"
  lamp :english, label: "EN", span: 1.5
end
```

- 狀態：`:caps` / `:shift` / `:ctrl` / `:alt` / `:meta` / `:latch` /
  `:english` / `:chinese` / `:buffer` / `:candidates`。
- `label:` 是燈上的字；`span:` 是燈的格數；`gap:` 單位 dp。
- `on_ink:` / `off_ink:` 可個別覆寫亮／暗墨色。
- 狀態燈**不可點擊**，也不會觸發任何動作。
- 亮＝對應狀態成立（實體修飾鍵、黏著修飾鍵、CapsLock、中英、buffer 非空…）；
  暗＝自動往 bar 底色混 35%。

### 4.9 `role:`（不影響外觀）

`buffer`、`candidate_row`、`hide`、`space`、`space_alt`、`backspace`、`enter`、
`dpad_up`、`dpad_down`、`dpad_left`、`dpad_right`、`select_all`、`copy`、`paste`。

### 4.10 `ctx`（`Keyboard.layout` 的 block 參數）

全部是字串：

`mode`（`chinese` / `english`）、`theme`（`light` / `dark`）、`locale`、
`input_type`（`text` / `number` / `phone` / `datetime` / `email` / `url` / `password`）、
`ime_action`、`screen_w`、`screen_h`、`density`、`safe_bottom`。

```ruby
page :num, when: ->(c) { c[:input_type] == "number" } do
  # …
end
```

---

## 5. 配色規則（設計時請先看）

1. **淺色模式是預設**，也是電子紙模式：純白底、黑字、1 dp 黑色按鍵框。
2. 淺色模式的按鍵框是**分邊繪製**（右＋下固定，第一欄加左、第一列加頂），
   相鄰鍵共用一條 1 dp 線。**不要**改成整圈 `setStroke()`，內部線會變 2 dp。
3. 深色模式通常不給 `keyBorder`，改用 `key` / `keyPressed` 的按下變色。
4. `bar border:` 是內縮的；不要用外距去模擬框線，會被 IME 視窗裁掉。
5. 候選未選中與選中要分開寫：`border:`（全部）與 `border_selected:`（選中）。
6. `flat: true` 的鍵底色走 `bar`，適合候選列上的符號鍵與第 5 列功能鍵。
7. 兩個主題都寫；只寫一個會讓另一個模式退回第一個主題。
8. 顏色 token 可以互相指向（`color :keyInk, :ink`），解析最多 4 層。

---

## 6. 常見錯誤

| 症狀 | 原因／修法 |
|---|---|
| 某列右邊空一格、跟別列對不齊 | 該列 `span` 總和 ≠ `columns`；補 `spacer` 或調整 span |
| 中文模式字母鍵變成 `ㄅ` / `日` | 目前碼表有 `%keyname`，會覆蓋中文鍵面；這是預期行為 |
| `key "A"` 送出大寫 A | `action: :code` 的預設值是標籤本身；要送小寫請寫 `value: "a"` |
| 符號鍵空白、點了沒反應 | 符號標籤預設 `noop`；要補 `label:` 與 `action:` |
| `TAB` / `DT` 被斷成兩行 | 它們是 DSL 標籤，預設 `:none`；若加了 `label_fit: :wrap` 才會斷 |
| 多字元鍵面被切掉 | 加 `label_fit: :wrap`（每 2 字換行）或 `:shrink`（縮字級） |
| 長按沒反應 | 只支援 `long_press: :toggle_hide_rows` |
| 第二、三頁不出現 | 只顯示第一頁與 `english` / `en` |
| 改了 `.rb` 但 App 沒變 | 使用者版面要重新匯入或等下次鍵盤出現；內建版面要重跑 `build_mruby.sh` |
| 匯入說 `no layout has been defined` | 最外層沒有 `Keyboard.layout version: 1 do … end` |

---

## 7. 驗證（不用手機）

### 7.1 主機端語法／IR 檢查

在 `mruby-ime/` 下：

```bash
ruby -e '
  load "app/src/main/assets/keyboard_dsl.rb"
  load "docs/layout_template.rb"
  s = keyboard_layout_serialized("mode=chinese\ntheme=light\n")
  puts s
' | python3 -m json.tool > /tmp/layout.json
```

- 有輸出 JSON 且 `python3 -m json.tool` 不報錯＝語法與結構都成立。
- 想看英文頁的 IR，把 context 改成 `mode=english\ntheme=dark\n`。
- 這只驗證語法與 IR 結構；實際字寬量測（`:shrink`、換行）仍以裝置為準。

### 7.2 App 內驗證

1. 設定 → 「鍵盤佈局（Ruby DSL）」→「匯入 DSL…」。
2. 選 `.rb` 檔（任何副檔名都可以，會存成 `layouts/keyboard.rb`）。
3. 失敗會顯示 `第 N 行（第 M 欄）` 與出錯的那一行；
   執行期錯誤會顯示 `undefined method 'foo' (keyboard.rb:8:in …)`。
4. 成功後切到輸入框，鍵盤會用新版面。
5. 要回預設：同一區塊的「恢復內建佈局」。

---

## 8. 給 AI 的提示詞（可直接複製）

```text
你是一位 Android 輸入法鍵盤的版面設計師。請用「小蝦米輸入法」的
Keyboard DSL v1 產生一份可直接匯入的 keyboard_layout.rb。

【必讀附件】
- keyboard_layout.rb：內建版面本體與完整 DSL 註解（照它的寫法）
- keyboard_dsl.rb：DSL 實作（選項名稱與預設值以此為準）
- layout_template.rb：最小可編譯範本

【硬性限制】
- 最外層必須是 `Keyboard.layout version: 1 do |ctx| … end`
- 只用 mruby 3.3 支援的語法：不能有 Regexp、eval、require、外部 gem
- 檔案 ≤ 64 KB
- 只會顯示第一個被建立的 page 與 page :english / :en
- 每個 row 的 span 總和要等於 grid columns
- 符號標籤一定要寫 label: 與 action:
- 顏色優先寫在 theme :light / theme :dark 的 color :token, "#RRGGBB"；
  單鍵需要時才用 ink: / bg: 覆寫
- 不要使用動畫、ripple、圓角以外的裝飾；預設直角
- 不要修改任何 Kotlin / C / 引擎程式

【設計目標】
（在這裡寫：例如「12 欄、5 列、黑白電子紙、候選列 buffer 2 : candidates 9 : ▼ 1、
中文鍵面大寫、英文小寫、第 5 列 中/英 + 空白 + ⏎」）

【輸出】
- 只輸出一個完整的 Ruby 檔，不要 Markdown 解說
- 檔頭用註解寫版面總覽
- 最後自我檢查：span 總和、page 名稱、theme 兩套、符號鍵的 label/action

【驗證】
輸出後請用以下指令確認（在 mruby-ime/ 下）：
ruby -e 'load "app/src/main/assets/keyboard_dsl.rb"; load "你的檔.rb"; puts keyboard_layout_serialized("mode=chinese\ntheme=light\n")'
若報錯，請修正後再輸出。
```

> 如果對方是用 ChatGPT／Claude 之類的聊天介面，把
> `keyboard_layout.rb`、`keyboard_dsl.rb`、`layout_template.rb` 三個檔案內容貼進對話，
> 再貼上上面的提示詞即可。三個檔案加起來約 45 KB，多數模型的上下文都放得下。

---

## 9. 套用與回退

- 匯入後存成 `filesDir/layouts/keyboard.rb`，並鏡射到
  `Android/data/com.example.mrubyime/files/layouts/keyboard.rb`。
- 下次鍵盤出現時會比對 mtime + size，有變更就熱載入（不用重開 App）。
- 外部編輯器可直接改 `keyboard.rb`；存檔後回到輸入框就會重載。
- 要回到內建版面：設定 →「恢復內建佈局」。
- 內建版面（`keyboard_layout.rb`）是編譯進 APK 的 bytecode，
  改它必須重跑 `./build_mruby.sh`；使用者版面則不用重新編譯。
