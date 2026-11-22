# 小蝦米輸入法 (XiaoXiaMi IME)

「小蝦米輸入法」是一款高效能、極致輕量且具備工業級架構的 Android **`.cin` 形碼輸入法**。內建「萬國蝦米」（uniliu）碼表，但引擎本身與嘸蝦米無關：只要換上 `.cin`，同一套鍵盤就能打注音、倉頡、大易、行列、五筆、速成、輕鬆、小鶴音形、虎碼……等碼表。底層核心採用 **mruby** 嵌入式虛擬機來執行 Ruby 碼表解析與輸入邏輯，並透過 C-JNI 與 Kotlin 進行無縫橋接，結合現代化的黑白極簡美學與電子紙最佳化設計。

---

## 🌟 核心功能與特色

### 1. mruby 通用 `.cin` 輸入引擎
- **純 Ruby 邏輯**：碼表解析、字根匹配與選單邏輯完全由嵌入式 Ruby 引擎 (`mrubyime_engine.rb`) 驅動。引擎讀 `%cname` / `%ename`、`%selkey`、`%keyname`、`%chardef`，**不假設** 31 鍵或嘸蝦米的任何規則。
- **可替換碼表**：設定頁「輸入法碼表（.cin）」可切換內建碼表、匯入任意 `.cin`、匯出目前碼表、恢復內建碼表。使用者碼表存於 `filesDir/dicts/active.cin`，並鏡像到 `Android/data/…/files/dicts/active.cin` 供外部編輯；回到鍵盤即熱重載（不必重開 App）。
- **內建碼表也有一份可編輯的複本**：`Android/data/<套件>/files/dicts/mrubyime.cin`（2.0 MB，與 App 內附的完全相同）。用檔案管理器改它就會生效（下次鍵盤出現時熱重載）；刪掉它就會自動從 App 重新複製一份內建的，改壞了也不會壞掉輸入（會自動退回 App 內附的版本）。
- **字根鍵由碼表決定**：合法鍵＝`%keyname` 的鍵 ∪ 所有編碼出現的字元。因此注音可以用數字、行列可以用 `;` `/`、大易可以用標點；嘸蝦米仍是 `a-z` ＋ `, . ' [ ]`。
- **鍵面標籤跟著碼表換**：`%keyname` 的標籤會套到鍵盤上（例：倉頡 `a → 日`、注音 `1 → ㄅ`）；若標籤只是按鍵本身的全形版本（`a → Ａ`、`,` → `，`），則保留原本的 A-Z 外觀，所以內建嘸蝦米鍵盤不變。
- **一個鍵對應多個字根時會換行**：`%keyname` 的值超過 2 個字元（例：`g → ㄍㄐㄜ`、`a → ㄘㄟ`）會每 2 個字元換一行，`ㄍㄐㄜ` 顯示成「ㄍㄐ／ㄜ」；超過 2 行時字級自動縮小，維持原本的 18sp 字型大小給 1–2 字元的標籤。
- **選字鍵由 `%selkey` 決定**：候選 chip 的標籤與軟體選字都用同一組 `%selkey`（嘸蝦米是 `0123456789`，第一個候選是 `0`；若碼表寫 `1234567890`，第一個候選就是 `1`）。
- **最長碼由碼表計算**：「最大碼清空組字」開啟時以該碼表的最長碼為上限（嘸蝦米 5、行列 4…），關閉時為 140。
- **滿 4 碼且唯一候選自動上屏（可開關，預設關）**：開啟後，當編碼達到 **4 碼（或碼表最長碼，取較小者）**、候選字唯一、且沒有任何合法鍵能再延長時，自動輸出該字元。`can_extend?` 會避免把 4 碼前綴誤判成完整碼。
- **死碼清空編碼區（可開關，預設關）**：開啟後，按鍵若確定無候選（1–3 碼且**該碼表的任何合法鍵**都無法接續成碼），自動清空編碼區；關閉（預設）時死碼會保留在編碼區不自動清除。

### 2. 5 行自定義虛擬鍵盤（Ruby DSL → IR → View 渲染與手勢手感）
- **鍵盤由 Ruby DSL 執行期產生**：內建版面寫在 `keyboard_layout.rb`（`Keyboard.layout`），啟動時由 mruby 產生 IR JSON，再由 Kotlin `KeyboardRenderer` 建立一般的 `TextView` / `LinearLayout` 檢視樹；不再 inflate `view_keyboard.xml`，也沒有 Canvas 自繪。使用者匯入的版面走同一套 DSL。
- **Space 鍵不顯示候選字**：Space 鍵面保持空白（不再把目前選取的候選字顯示在空白鍵上），候選字只出現在候選列；點擊 Space 仍會上屏目前選取的候選字，下滑仍可切換中英打。
- **Space 按鍵下滑切換中英打**：在 Space 空格鍵上向下滑動即可快速無縫切換中打/英打模式（硬體 **Alt+Space／Shift+Space** 同樣可切換；單純按 Shift 不再切換）。候選列已移除 `中/英` 狀態鈕。
- **Space 左右滑動移動游標**：在 Space 上左右滑動可將文字游標前後移動，每滑行約 24 dp 移動一個字元（持續拖曳會連續移動），不必伸手去點輸入框即可修錯字。
- **數字鍵：沒有編碼時直接打數字**：中文模式下若編碼區是空的，按 `1`～`0` 會直接輸出該數字；若有編碼，數字鍵仍是「選字鍵」（依候選字標籤選取），不會破壞組字。**若目前碼表把數字列為字根鍵**（例如注音的聲調鍵），數字在沒有候選時會進入編碼，不會被當成純文字。
- **英打字母上滑輸入大寫**：英文模式下在任一字母鍵上向上滑動，會輸出該字母的**大寫**（不必按 Shift）；一般點擊仍輸出小寫。
- **切換成英打時清空編碼區**：切到英打模式時，**編碼區與引擎緩衝會一併清空**（英打直接輸出字母、不使用嘸蝦米組字，因此不留舊編碼）。切回中打時會自動再查一次表（若緩衝仍有殘留編碼才會顯示候選）。
- **專屬按鍵佈局（12 欄格線、5 列）**：第 1 列 `1`～`0` 再加 `-` `=`（12 鍵）；第 2 列 `Q`～`P` 加 `[` `]`；第 3 列 `A`～`L` 加 `;` `'` `\`；第 4 列 `Z`～`M` 加 `,` `.` `/` `` ` `` 與等寬的長按連續刪除 `⌫`；第 5 列中打為 `中`（切換中英，2 單位）＋整列空白鍵（8 單位）＋`⏎`（2 單位），英打為 `英`＋空白鍵＋`⏎`。
- **上滑輸入符號**：第 1 列上滑輸出 `! @ # $ % ^ & * ( ) _ +`；`[` `]` `;` `'` `\` `,` `.` `/` `` ` `` 上滑輸出 `{ } : " | < > ? ~`。上滑會輸出什麼就印在該鍵**右上角**（`sub_label`），中打／英打都適用；英文模式下字母鍵上滑則輸出大寫。
- **候選列寬度分配（合計 12 單位，與鍵盤等寬）**：中打＝編碼區 **2**、候選區 **9**、`▼` **1**；英打＝12 個動作鍵各 1（`TAB`、空白、`^A ^C ^V`、`⇧ ⇩ ⇦ ⇨`、`DT`、`W`、`▼`）。完全由版面 DSL 決定（`buffer weight: 2` / `candidates weight: 9` / `hide` 1），**設定頁沒有覆蓋項**，`▼` 永遠保持 1 單位。
- **鍵盤高度與邊距可調**：候選列高度 + 按鍵區高度 + 下方預留高度（隱藏按鍵列時只剩候選列 + 下方預留），左右預留寬度，全部寫在版面 DSL（`height:`／`padding:`），熱重載即時生效。
- **大小寫動態連動**：中文打字模式下，鍵盤字母按鍵顯示為**大寫**（`Q`, `W`, `E`...）；英文模式下顯示為**小寫**（`q`, `w`, `e`...）。
- **零動畫/無水波紋/直角**：全面移除所有按鍵與候選字的點擊動畫、ripple 與圓角；鍵盤間距完全由 DSL 的 `grid gap_h` / `gap_v` / `outer`（水平間距、垂直間距、外緣留白）與 `KeyRowLayout` 決定。
- **按鍵間距只是視覺、觸控相連**：`grid gap_h` / `grid gap_v` 不再造成觸控死區。每顆按鍵的**觸控範圍仍是完整的格線格位**，只是鍵面與文字往內縮 `gap/2`（`KeyFrameLayout`），列與列之間也由 `RowsLayout` 讓相鄰列各分到一半間距（鍵的背景與文字同樣內縮）。所以手指按在兩鍵之間的縫上時，會由最近的按鍵接手，不會「按了沒反應」。`outer`（左右外緣）與 `bar gap:`（候選列槽位間距）維持原行為，仍是版面留白。
- **按鍵間距 0（內建預設）**：內建版面設為 `grid columns: 12, gap_h: 0, gap_v: 0, outer: 0`，因此相鄰按鍵**完全貼齊**、列與列之間也沒有間距（水平、垂直皆為 0），鍵盤**左右也無外緣留白、直接貼齊視窗緣**（深色模式才不會在按鍵區兩側露出一道 `#1C1C1E` 深色粗邊）；畫面上仍看得到的細線是**每顆按鍵自身的 1dp `keyBorder` 外框**（淺色模式黑框、深色模式 `#1C1C1E` 深框），不是間距。使用者可自行在 DSL 調大 `gap_h` / `gap_v` / `outer` 來恢復間隙。

### 3. 極簡候選列排版
- **編碼顯示區**：位於候選列最左側，**字體 24sp 粗體（與候選字完全相同大小）**，寬度 2 單位（12 欄格線中的 2 欄），可容納約 **6 碼**；當「最大碼清空組字」關閉、編碼超過 **6 碼**時，編碼框只顯示**最後 6 碼**（引擎仍保留完整緩衝）。超長編碼不換行，自動以 `TruncateAt.START` 收尾；點擊可直接將目前編碼上屏；在編碼框上**上滑**則直接輸出「輸入框內聯顯示」目前顯示的內容（依 `inline_composing_mode`，例如 `會 ＡＤ`；若內聯顯示關閉則退回輸出原始編碼）。**編碼框不畫邊框**（深色 `#3A3A3C`、淺色純白底），寬度由 DSL 的 `buffer weight:` 決定（設定頁已無此項設定），所以空框也不會讓其他按鈕忽寬忽窄。
- **死碼 / 最大碼自動清空（兩個可開關）**：
  - **「死碼清空編碼區」**（預設**關**）：當只有 **1～3 碼、候選為 0** 時，系統**試算「再加上任何 1 個字根（31 個合法鍵）」的組合**：若**所有組合都仍然沒有候選**（此前綴已證明不可能成立），才清除；若**有任何一個組合有候選**（例如 `aad` 可再接成 `aadb`/`aadk`/`aado`、`,,` 可接成 `,,f`），就**保留**讓使用者繼續打。試算只往前看**一個字根**。（實務上 31 個單鍵本身都是有效碼、都有候選，所以 1 碼的分支不會實際觸發。）關閉後死碼保留在編碼區。
  - **「最大碼清空組字」**（預設**關**）：當編碼達**最大碼**且**完全沒有候選**時，編碼區與引擎緩衝**自動清空**。開關**開啟**時最大碼 = **5**（達 5 碼無候選即清，經典行為）；**關閉**（預設）時最大碼 = **140**（幾乎不限制），可持續打長碼、超過 6 碼只顯示最後 6 碼。
- **候選列的空白格**：沒有候選字時，候選列保留**一個空的、與候選 chip 等寬的格子**（不可點擊）。**中打時**該格用與選中候選**同一組 `border_selected`**（內建＝**2 dp 底部橫線**）；**英打時不帶橫線**（英打沒有選取），因此**切換中／英打會即時重繪**（`placeholderFramed`／`styledTheme` 判斷）。一旦有候選字就換成真正的候選 chip。
- **候選字與絕對索引（不分頁）**：**取消分頁**，所有候選字放在同一條可橫向捲動的列上（候選區 9 單位 ≈ 1065px，約同時看到 8 個；最多 12 個時以**整頁捲動**左右瀏覽），字體 **24sp 粗體**；每個 chip 的格式一律是**編號緊接字元、中間不空格、也不加括號**（如 `0微`、`1徵`），每個 chip（含空白佔位框）都有相同的最小寬度（以 `0微` 的 24sp 粗體實測值 + 左右 padding），所以候選框**寬度完全相同**、選取提示在「未輸入」與「輸入後」也**一模一樣寬**；**選中者以 `2dp` 底部橫線標示**（深色模式：白色橫線＋白色文字；淺色模式：黑色橫線＋黑色文字），未選中者在深色模式帶一圈**點線外框**、淺色模式無框（深色模式白字、`#3A3A3C` 底；淺色模式黑字、白底）；候選字編號沿整條列**絕對排序**（`0` 起算），變更選取時會自動捲動到該候選；候選 chip **重複使用、不重建**（只更新有變動的文字，選取橫線只在選取位置或主題改變時重畫），因此輸入過程中選中候選不會閃爍。
- **編碼區與候選字同一基線**：兩者皆設 `includeFontPadding=false`，候選字額外呼叫 `setFallbackLineSpacing(false)`（API 28+）。若不關閉 fallback line spacing，候選字因含 CJK 會採用 CJK 字型的行高，基線會比純拉丁字母的編碼區低約 6px，看起來「一高一低」。
- **數字鍵 0–9 選字**：中打且有候選字時，實體／虛擬數字鍵直接以候選字上的編號選字（編號沿整條列絕對，按 `9` 可直接選到已捲出畫面外的候選並自動捲回）；若該數字沒有對應候選則忽略，不會破壞目前組字。
- **無頁碼、無翻頁鈕，改為整頁捲動（可循環）**：原本的頁數指示器（`1/3`）與 `◀` / `▶` 翻頁鈕都已移除；候選字全部在同一列，滑動時**內容跟著手指即時移動**，放開後再**平滑吸附**到最近的一頁（拖動距離不到一頁的 8% 會退回原來那一頁），不會停在半個字中間。翻頁**循環**：在最後一頁繼續往前滑會回到第一頁，在第一頁往回滑會跳到最後一頁。單指輕點候選字仍可直接選字（滑動超過觸控門檻才會被判定為捲動）。
- **標點字根與英打動作列**：
  - **中打模式**：`, . ' [ ]` 這五個嘸蝦米字根鍵已直接放進字母列（第 2 列的 `[` `]`、第 3 列的 `;` `'` `\`、第 4 列的 `,` `.` `/` 與倒引號鍵），點擊即以該字根進入組字；候選列只剩「編碼區 2 單位 ＋ 候選區 9 單位 ＋ `▼` 1 單位」。
  - **英打模式**：候選列自動切換為 12 鍵動作列（每鍵 1 單位）：`TAB`、空白鍵、`^A`（全選）、`^C`（複製）、`^V`（貼上）、`⇧ ⇩ ⇦ ⇨`（方向鍵）、`DT`（日期時間）、`W`（星期）、`▼`；原編碼區與候選字列隱藏。
- **候選列底色與按鍵一致（Dark Mode）**：整個候選列（**背景及所有子元件**：編碼框、候選字、標點鍵、符號鍵、`▼`）底色皆為 `#3A3A3C`，與字母鍵完全相同（程式以 `DARK_BAR_FILL` 統一指定；按鍵另有 `#505054` 的按下態）；候選列不再有深色底 `#2C2C2E`，只有文字本身在區分內容。
- **候選列寬度分配（合計 12 單位，與鍵盤等寬）**：
  - 中打模式：編碼區 2、候選區 9、`▼` 1（來自 DSL `buffer weight` / `candidates weight`，設定頁無覆蓋；`▼` 固定 1）。
  - 英打模式：12 鍵動作列各 1 單位（`TAB`、空白、`^A ^C ^V`、`⇧ ⇩ ⇦ ⇨`、`DT`、`W`、`▼`）。
- **點擊編碼直接輸出**：點擊候選列中的輸入編碼（如 `AA`）可直接將編碼字串上屏輸出；在編碼上**上滑**則改為輸出「輸入框內聯顯示」目前顯示的內容（依 `inline_composing_mode`，例如 `會 ＡＤ`）。

### 4. 硬體方向鍵（D-pad）導航
- **左 / 右鍵**：在候選字之間移動選取游標（切換被選中的候選字）。
- **上 / 下鍵**：候選列翻頁（上＝前一頁、下＝下一頁），並把選取框移到該頁第一個可見候選；**翻頁會循環**，最後一頁再往下會回到第一頁，第一頁往上會跳到最後一頁。

### 5. 黑白極簡設定頁面與自訂尺寸控制
- **黑白專屬主題**：設定頁面採用純黑背景（`#000000`）、**白色**標題/強調色（`#FFFFFF`）與暗灰控制項（`#2C2C2E`），無任何藍色、無金色、無左上角多餘標題。鍵盤按鍵文字一律為白色 `#FFFFFF`。
- **可滾動控制面板（ScrollView）**：設定頁全面支援垂直平滑滾動，在任意解析度或橫向螢幕皆能舒適調整，並配合 `adjustResize` 使測試輸入框在鍵盤彈出時自動避讓。
- **尺寸與字體只看版面 DSL（設定頁已移除這些控制項）**：候選列高度、按鍵區高度、左／右／下方預留邊距、編碼區寬度、候選字字體、按鍵字體，**全部由版面檔決定**，設定頁不再提供覆蓋它們的選項（設定頁只留「外觀與模式」的行為開關）：
  - `height bar: 48, body: 240` → 候選列 48 dp、按鍵區 240 dp。
  - `padding left: 0, right: 0, bottom: 0` → 左右／下方預留（dp）。
  - `buffer weight: 2` + `candidates weight: 9` → 編碼區與候選區的寬度比（`hide` 固定 1）。
  - `buffer font: { size: 24 }` / `candidates font: { size: 24 }` → 編碼框與候選字大小（sp，預設 24、粗體）；`candidates` 沒寫 `font:` 時沿用 24sp 粗體。
  - `font:` 也吃 **`family:`**（字型檔名，例如 `family: "NotoSansCJK"`），編碼區、候選字與按鍵都支援；字型檔放 `assets/fonts/`（內建）、`filesDir/fonts/` 或 `Android/data/com.example.mrubyime/files/fonts/`（使用者可見）。詳見 `assets/fonts/README.txt`。
  - 鍵盤總高度 = `bar + body + bottom`（「隱藏虛擬鍵盤」時 = `bar + bottom`，並夾在螢幕高度內）；改 `keyboard.rb` 後回到鍵盤立即熱重載。
- **要還原預設尺寸／字體**：在「鍵盤佈局（Ruby DSL）」區按「恢復內建佈局」（原本的「恢復預設高度與字體設定」按鈕已隨控制項一起移除）。
- **候選字字體**：只從 DSL 來，候選 chip 的最小寬度（`minimumWidth`）依此字級重算，選中外框緊湊且等寬、基準線完全對齊。
- **內建即時打字測試**：測試輸入框 (`EditText`) 放在「外觀與模式」區塊**上方**，而且**沒有自己的標題**（只有一個輸入框，靠熱重載即時反應）；改完 `keyboard.rb` 回到此欄即即時熱重載變更，無需重開輸入法。
- **行為開關預設值**：淺色模式（`light_theme`）**預設開**；滿4碼且唯一候選自動上屏（`auto_commit`）**預設關**；死碼清空編碼區（`clear_dead_code`）**預設關**；最大碼清空組字（`max_code_clear`）**預設關**（→ 最大碼 140）；輸入框內聯顯示（`inline_composing_mode`）**預設 `1` = 輸入碼 `aad`**，其餘選項：`0` 不顯示、`2` 選中候選字 `微`、`3` 選中候選字＋編碼 `微 aad`、`4` 大寫輸入碼 `AAD`、`5` 選中候選字＋大寫編碼 `微 AAD`、`6` 全形大寫輸入碼 `ＡＡＤ`、`7` 選中候選字＋全形大寫輸入碼 `微 ＡＡＤ`（全形＝ASCII 可列印字元加 `0xFEE0`）。
- **主題：淺色模式（Light Mode，預設）＋ Dark Mode**：預設為淺色模式（`light_theme=true`，純白底、黑色標題與黑框控制項）。關閉「**淺色模式**」勾選項（`cb_light_theme`，偏好鍵 `light_theme`）即切到深色模式——**設定頁與鍵盤皆為黑白單色**（`#3A3A3C` 底、白色 `#FFFFFF` 編碼框與選中候選字強調）。**深色模式的鍵盤也畫 1 dp `keyBorder` 格線**（`#1C1C1E`，比鍵面 `#3A3A3C` 深一階），讓每顆按鍵的邊界清楚可見（代價是深色按鍵不再有按下變色，與淺色一致）。設定頁與虛擬鍵盤立即同步變色、不需重開鍵盤。
- **淺色模式（Light Mode）設計原則**：以純黑與純白為主，不用金色，**鍵盤**也不加圓角，也不加動畫／漣漪。設定頁為純白背景、黑色標題與黑色文字，按鈕與輸入框為白底加 1dp 黑色外框；**設定頁按鈕一律同一種外觀、8dp 圓角、全頁同寬、置中、一顆一列（上下堆疊），文字全部靠左**：不再有「黑底白字」與「白底黑字」兩種樣式——淺色模式全部是白底 + 1dp 黑色外框 + 黑字，深色模式全部是透明底 + 1dp 白色外框 + 白字（`applyTheme()` 用同一個迴圈套 15 顆按鈕，`gravity = START or CENTER_VERTICAL`、左右內距 16dp）；寬度由 `normalizeButtonWidths()` 量測「最寬的按鈕文字 + 左右 padding」（至少 112dp，上限為頁寬減 96dp）寫回每顆按鈕；按鈕不再兩顆並排（成對的橫向 `LinearLayout` 已移除），每顆都是卡片直向 `LinearLayout` 的直接子項，靠 `layout_gravity="center_horizontal"` 置中、`marginBottom=8dp` 分隔。**頁面沒有任何裝飾直條**（標題列與各區標題前的 4dp／5dp 色條已刪除，只剩文字與間距）（僅設定頁，鍵盤與輸入框仍是直角）；鍵盤背景為純白，**候選列（`key_bar`）及其所有子元件（編碼框、候選字、標點鍵、符號鍵、`▼`）都是純白 `#FFFFFF`**（文字／圖示為黑色），鍵盤本體仍為純白，每顆字母／數字鍵為**白底 + 1dp 黑色外框**（淺色模式不設按下變色，維持電子紙穩定）。候選字未選中為**白底黑字、無框**（`border` 顏色是 `seam`＝白色，在白底上自然隱形；深色模式才會看到點線框）；**選中者為白底黑字 + `2dp` 黑色底部橫線**（取代先前的 `[0 微]` 括號，格式為 `0微`）。**候選列最上方另有一條 `1dp` 黑色橫線**（`bar border: { …, side: :top }`，同樣內縮）。**編碼區不畫邊框**（白底黑字，與候選字同大小、同一基線），`▼` 一律黑色；只有字母／數字鍵網格保留 1dp 黑框；鍵盤本身延伸到系統導覽列底下，導覽列（含鍵盤收起的 `⌄`、地球鍵）也一併改成白底黑圖示。
- **長按 `▼` 快速切換**：在候選列最右的 `▼` 上**長按**即可切換「是否隱藏虛擬鍵盤」（全鍵盤 ↔ 只留候選列），不必進設定頁；**短按**仍是隱藏整個鍵盤。設定頁的勾選項會同步更新；此鍵關閉觸覺回饋（`isHapticFeedbackEnabled = false`），長按不會震動。
- **收起鍵盤時自動清掉未完成的組字**：打到一半（例：`aad`、內聯顯示的 `你好`）按 `▼`、系統返回鍵、切到別的 App 或別的輸入法，編碼區與輸入框裡的內聯組字都會被**直接丟棄（不會送出）**，不會留在輸入框裡，下一個欄位也不會繼承上一段組字。
- **切換輸入法不再等將近 1.3 秒**：解析好的碼表存在 JNI 的靜態 mruby 狀態裡，`onDestroy` 不會拆掉它，所以切到別的輸入法再切回來（同一個行程）會直接沿用，logcat 顯示 `engine reused (same process) … load=0ms`；冷啟動（行程被系統回收後）在舊的「一行 100 詞」碼表約 1.0 秒，換成現在的上游格式（一行一筆 `詞 權重`，可讀可改）約 **2.6 秒**，成本主要落在 14 萬行 `%phrase` 的解析。讀取 2.0 MB 碼表仍在背景執行緒，服務建立時不會卡在主執行緒。
- **隱藏虛擬鍵盤（僅保留候選列）**：設定頁的勾選項，開啟後鍵盤只留最上方動態高度的候選列（編碼、候選字、標點/符號、`▼`），五列字母／數字鍵／符號與工具列全部隱藏，適合外接實體鍵盤時使用；鍵盤視窗高度同步縮到候選列高度。設定存於 `mrubyime_settings`（鍵 `hide_virtual_keyboard`），切換時立即生效、不需重開鍵盤。
- **鍵盤佈局（ruby dsl）**：設定頁新增「鍵盤佈局（ruby dsl）」區塊，可**匯入 (.rb)**（`ACTION_OPEN_DOCUMENT`，任何檔案管理器皆可）、**匯出**（`ACTION_CREATE_DOCUMENT`，建議檔名 `keyboard.rb`）、**寫入編輯目錄**、**恢復內建佈局**、**用外部編輯器開啟**（FileProvider `content://com.example.mrubyime.layouts/keyboard.rb`）。使用者版面存為 `layouts/keyboard.rb`：對外顯示於 `Android/data/com.example.mrubyime/files/layouts/`（Android/data 檔案管理器可直接編輯），並同步到 `filesDir/layouts/keyboard.rb`（root/Shizuku）；兩份以**較新者為準**，且匯入前一定先通過 DSL 沙箱驗證，壞檔不會讓鍵盤失效。回到鍵盤時自動熱重載，不需重啟輸入法。**不需任何儲存權限**。
- **DSL 寫錯會直接告訴你第幾行**：匯入失敗時狀態列會顯示沙箱回報的**行號與欄號**，並附上那一行的原始內容，例如
  ```
  ✗ 匯入失敗：第 6 行（第 20 欄）：key "q" span: 1 action: :code
  　　syntax error, unexpected local variable or method, expecting "'end'"
  ```
  少打 `end` 這種「檔尾才發現」的錯誤也會指出位置（`第 10 行 … unexpected end of file, expecting "end"`）。語法檢查之外的**執行期錯誤**（例如打錯方法名）則顯示 `undefined method 'wrong_method_name' (keyboard.rb:8:in instance_exec)` 並標出第 8 行；同一份訊息也寫進 logcat（`adb logcat -s MrubyimeJNI MrubyimeLayout MrubyimeIME`）。最多同時列出 3 個錯誤。
- **SAF／檔案管理器（DocumentsUI、MT 管理器）入口**：內建 `DocumentsProvider`（`content://com.example.mrubyime.documents`），**只有一個儲存位置，直接開在 `Android/data/com.example.mrubyime/files`** —— 也就是檔案管理器在磁碟上看到的那個資料夾：

  ```
  Android/data/com.example.mrubyime/files/
    layouts/keyboard.rb   ← 鍵盤佈局（Ruby DSL）
    fonts/<名稱>.ttf       ← 自訂字型
    dicts/active.cin      ← 匯入的使用者碼表
    dicts/mrubyime.cin    ← 內建碼表的可編輯複本（改它就會生效）
    sounds/<名稱>.ogg      ← 按鍵音
  ```

  在系統「檔案」App、MT 管理器或任何 SAF 選擇器裡會出現「**小蝦米輸入法 → Android/data/com.example.mrubyime/files**」（檔案選擇器抽屜的「瀏覽其他應用程式中的檔案」區塊），可直接**新增／編輯／改名／刪除**上面的檔案。**資料夾選擇器（`ACTION_OPEN_DOCUMENT_TREE`，MT 管理器的「授權資料夾」）也看得到它**，選「使用這個資料夾」就能拿到長期授權。第一次開啟時若還沒有 `keyboard.rb`，會自動從內建版本複製一份，方便直接編輯；`fonts/` 等資料夾也已建立好。
  在那裡存檔後回到鍵盤即生效（外部寫入會同步進 App 讀取的內部副本）。此 provider 以 `MANAGE_DOCUMENTS`（系統專用）保護，不需任何儲存權限。
- **Low-Poly 蝦米 App Logo**：採用低多邊形（Low-Poly）幾何風格蝦米圖案與 "nil" 標誌。

---

## ⚙️ 系統架構與技術亮點

1. **Ruby Keyboard DSL → IR JSON → 標準 Android View 階層**：
   - 鍵盤與候選列的版面寫在 `assets/keyboard_layout.rb`（使用 `assets/keyboard_dsl.rb` 的 `Keyboard.layout` DSL），啟動時由 mruby（`keyboard_layout_serialized`）產生 IR JSON，再由 Kotlin `KeyboardLayoutParser` 解析成 `KeyboardLayout`，`KeyboardRenderer` 建立一般的 `TextView` / `LinearLayout` 檢視樹。`view_keyboard.xml` 已不再 inflate，只保留 `R.id.*` 常數。
   - `KeyRowLayout` 以 `columns` 欄網格（內建 12 欄）、`span`（固定比例）、`weight`（彈性）、`align`（start／center／end／space_between）與 `gap`（dp）排版；按鍵外觀由 IR 的 theme token 與 `KeyboardView.solid()` 於程式內繪製（無 ripple、無圓角），版面調整直接改 Ruby DSL，不需要重寫 View 程式。
   - 內建版面的 IR 同時在建置時輸出 `assets/keyboard_layout.json` 作為備援（執行期 DSL 失敗時使用），並支援在獨立 `mrb_state` 沙箱中載入使用者匯入的 DSL（64 KB 上限）。
2. **UTF-8 字符導向編譯（`MRB_UTF8_STRING`）**：
   - 在 mruby 交叉編譯配置中明確啟用 `MRB_UTF8_STRING` 巨集，確保 mruby 字串方法（`length`、`index`、`[]`）正確以**字符**而非位元組運算。
3. **雙向 UTF-8 位元組傳遞（`utf8_to_jstring` / `mrb_str_from_jbytes`）**：
   - 輸出：捨棄 Android JNI 嚴格的 `NewStringUTF`（Modified UTF-8 陷阱），改用 `NewByteArray` 與反射 `new String(bytes, "UTF-8")`，實現 100% 無損的中文字串轉譯。
   - 輸入：字典與按鍵一律以 `ByteArray`（原始 UTF-8）跨 JNI，C 端以 `GetByteArrayElements` + `mrb_str_new` 建立字串，完全避開 Modified UTF-8，含 BMP 以外字元也不失真。
   - 碼表熱替換：`reloadDictionary(ByteArray)` 直接呼叫引擎的 `load_dictionary`，不重建 `mrb_state`；`legalKeys()` / `keynamesSerialized()` / `dictionaryName()` / `tableMaxCodeLen()` / `entryCount()` / `canExtend()` 提供 Kotlin 政策層所需的碼表資訊。
4. **序列化傳遞（Serialization Passing）**：
   - 候選字陣列在 Ruby 端透過 `\n` 序列化結合成單一字串跨越 JNI 邊界，在 Kotlin 端透過 `.split("\n")` 還原，極大降低 JNI 呼叫與記憶體開銷。
5. **委派模式（Delegation Pattern）與手勢相容性**：
   - 透過 `MrubyimeEngineDelegate` 與 `KeyboardInteractionListener` 介面，支援觸控點擊與 Space 鍵下滑手勢相容。
6. **mruby 物件生命週期管理**：
   - 引擎實例保存在 C 靜態變數中，初始化後以 `mrb_gc_register` 註冊為 GC root，避免在兩次 JNI 呼叫之間被回收。
7. **電子紙最佳化渲染與候選列捲動**：
   - 候選 chip **池化重用**（`createChip()` / `paintChip()`）：只更新有變動的文字，選取橫線只在選取位置或主題改變時重畫；`text` 只在真的不同時才設定（`updateBuffer()` 亦然），避免每次按鍵重繪整片鍵盤造成電子紙閃爍。
   - 候選列由 `StepScrollView`（`HorizontalScrollView` 子類）承載：拖曳時**內容跟著手指**移動，放開後**平滑吸附**到整頁邊界（未達一頁 8% 會退回原頁），頁面**可循環**；硬體方向鍵 ↑/↓ 以 `stepPage()` 翻頁並把選取框移到該頁第一個可見候選。
   - **死碼清除**由 Ruby 引擎的唯讀 `dead_prefix` 判定（1–3 碼前綴：加任何 1 個合法鍵都無候選即視為死碼），由 `MrubyimeIME.onKeyPressed` 執行 `engine.reset()`；受設定頁「死碼清空編碼區」（`clear_dead_code`）開關控制。「最大碼清空組字」（`max_code_clear`）另控制引擎最大碼（`setMaxCodeLen`）：開 = 目前碼表的最長碼、關 = 140，達上限且無候選時清空。
8. **可替換 `.cin` 與鍵面標籤**：
   - `DictionaryRepository` 依 `dict_id` 決定來源：`builtin:mrubyime`（有 `dicts/mrubyime.cin` 就用它，否則用 `assets/mrubyime.cin`）、`builtin:<name>`（`assets/dicts/<name>.cin`）、`user:active`（`filesDir/dicts/active.cin`，鏡像到 `Android/data/…/files/dicts/active.cin`）。
   - 匯入時以 `CinTable.parse` 驗證（UTF-8、`%chardef`、4 MB 上限），成功後原子寫入並立即設為使用中；`MrubyimeIME.onStartInputView` 以 `dict_id` 與檔案 size/mtime 偵測變更並熱重載。
   - 鍵面標籤由 `%keyname` 決定：`KeyboardView.setDictLabels()` 只覆蓋「非按鍵本身」的標籤（`a → 日` 會換，`a → Ａ` 不會），中英模式切換時自動還原／重套。

---

## 🎛️ 鍵盤 DSL 按鍵動作速查

所有按鍵動作都由同一個 dispatcher 處理，**點擊、`long_press`、`gestures:` 用同一組動作名稱**：

| `action:` | 用途 | 參數 |
|---|---|---|
| `:code` | 送出字元 | `value:`（省略時取標籤第一個字元） |
| `:space` / `:enter` / `:backspace` / `:ime_hide` / `:noop` | 既有按鍵 | — |
| `:toggle_mode` | **切換中／英打**（與 Space 下滑相同） | — |
| `:combo` | **送出組合鍵** | `value: "ctrl+shift+1"`（也支援 `alt+tab`、`ctrl+a`、`shift+space`…） |
| `:key_event` | **送單一特殊鍵** | `value: "ins"` / `"del"` / `"home"` / `"end"` / `"pgup"` / `"pgdn"` / `"tab"` / `"capslock"` / `"esc"` / `"menu"` / `"f1"`…`"f12"` / `"up"`… / `"numlock"` / `"scrolllock"` / `"printscreen"` / `"pause"` / `"volup"` / `"voldown"` / `"mute"` / `"back"` / `"search"` |
| `:modifier` | **黏著修飾鍵**（按一下亮起，下一次按鍵帶上它） | `value: :ctrl/:shift/:alt/:meta` |
| `:date` / `:time` / `:datetime` / `:weekday` | **送出日期／時間／星期** | `value:` 可換格式，如 `"yyyy/MM/dd"`、`"HH:mm:ss"`、`"EEEE"` |
| `:dpad` | 方向鍵 | `value: :up/:down/:left/:right` |
| `:clipboard` | 全選／複製／貼上 | `value: :select_all/:copy/:paste` |

`gestures:` 可掛在任一按鍵上（`swipe_up` / `swipe_down` / `swipe_left` / `swipe_right`）；值是**字串**代表「送出這個字」，是符號代表動作，Hash 則可用 `{ action: :combo, value: "shift+enter" }`（也接受短寫 `{ a: :combo, v: "shift+enter" }`）。`sub_label:` 會以較小字級顯示在按鍵**右上角**。

### 1. 切換中／英打的按鈕
```ruby
key :toggle, span: 1, label: "中/英", action: :toggle_mode, flat: true
```

### 2. 送出組合鍵（例如 Ctrl+Shift+1）的按鈕
```ruby
key :c1, span: 1, label: "C+S+1", action: :combo, value: "ctrl+shift+1", flat: true
```

### 3. 點擊送 `w`、上滑送 `W`，中間顯示 `w`、右上顯示 `W`
```ruby
key "w", span: 1, action: :code, value: "w",
    sub_label: "W", gestures: { swipe_up: "W" }
```

### 4. 特殊鍵（INS / DEL / HOME / END / PageUp / PageDown / Tab / CapsLock / F1–F12）
```ruby
key :ins,  span: 1, label: "INS",  action: :key_event, value: "ins"
key :del,  span: 1, label: "DEL",  action: :key_event, value: "del"
key :home, span: 1, label: "HOME", action: :key_event, value: "home"
key :end,  span: 1, label: "END",  action: :key_event, value: "end"
key :pgup, span: 1, label: "PGUP", action: :key_event, value: "pgup"
key :pgdn, span: 1, label: "PGDN", action: :key_event, value: "pgdn"
key :tab,  span: 1, label: "TAB",  action: :key_event, value: "tab"
key :caps, span: 1, label: "CAPS", action: :key_event, value: "capslock"
key :f5,   span: 1, label: "F5",   action: :key_event, value: "f5"
```
`key_event` 也可以接修飾鍵寫成組合（與 `combo` 完全等價）：`value: "shift+tab"`、`value: "ctrl+home"`。

### 5. Alt / Ctrl / Shift / Super 修飾鍵（黏著）
`modifier` 是**一次性黏著**：按一下 → 標籤加底線（表示已鎖定）→ 下一個 `code` / `combo` / `key_event` 會帶上它，之後自動解除。
```ruby
key :ctrl,  span: 1, label: "CTRL",  action: :modifier, value: :ctrl
key :shift, span: 1, label: "SHIFT", action: :modifier, value: :shift
key :alt,   span: 1, label: "ALT",   action: :modifier, value: :alt
key :meta,  span: 1, label: "SUPER", action: :modifier, value: :meta
```
例：按 `CTRL` 再按 `c` → 送出 `Ctrl+C`；按 `SHIFT` 再按 `w` → 送出 `Shift+W`（大寫 `W`）。

### 6. 日期 / 時間 / 星期
```ruby
key :date, span: 1, label: "DATE", action: :date        # 2026-11-04
key :time, span: 1, label: "TIME", action: :time        # 14:35
key :day,  span: 1, label: "DAY",  action: :weekday     # 星期三（跟隨系統語系）
key :dt,   span: 1, label: "DT",   action: :datetime    # 2026-11-04 14:35
key :us,   span: 1, label: "US",   action: :date, value: "MM/dd/yyyy"
```
`value:` 是 `java.text.SimpleDateFormat` 樣式，按鍵按下的**當下**才取值。

### 7. 按鍵文字靠左／置中／靠右
```ruby
key "L", span: 1, action: :code, value: "a", align: :left
key "C", span: 1, action: :code, value: "b"                # 預設置中
key "R", span: 1, action: :code, value: "c", align: :right
```
（`align:` 只影響**鍵內文字**；左右各留 8 dp 讓字不貼邊框。）

### 8. 整列靠左／置中／靠右
`row align:` 只在該列 **span 總和 < `columns`** 時有作用：剩下的空格按對齊方式分配。
```ruby
row align: :left do    # = :start（預設），剩餘留右邊
  key "A", span: 1, action: :code, value: "x"
  key "B", span: 1, action: :code, value: "y"
end
row align: :center do  # 左右平均留白
  key "A", span: 1, action: :code, value: "x"
end
row align: :right do   # = :end，剩餘留左邊
  key "A", span: 1, action: :code, value: "x"
end
row align: :space_between do  # 剩餘平均塞進按鍵之間
  key "A", span: 1, action: :code, value: "x"
  key "B", span: 1, action: :code, value: "y"
end
```

### 9. 邊框不要變粗（相鄰按鍵共用一條線）
淺色主題的 `keyBorder` 現在**只畫右邊與下邊**（第一欄加左邊、第一列加上邊），所以兩顆相鄰按鍵的縫是**一條 1 dp 線**，不會兩條疊成 2 dp。這是 `KeyView.borderLeft/borderTop` + `KeyboardView.gridKeyDrawable()` 做的；**內建深色主題也定義 `keyBorder`（`#1C1C1E`），同一條分邊繪製路徑在深色也畫出格線**——所以深色按鍵（非 flat）與淺色一樣是純色鍵面、無按下變色，`keyPressed` 目前只在真正的 `flat` 候選列符號鍵上用到。`flat: true` 的按鍵（第 5 列、候選列符號）不畫框，而且底色改用候選列的 `bar` token；候選列上的 flat 鍵在深色主題仍有按下變色，鍵盤列上的 flat 鍵則完全沒有按下態。

### 10. 顏色：候選欄 / 候選字 / 按鍵 / 按鍵字
顏色全部寫在 `theme` 裡，**沒定義的會自動往後退**（`keyInk` → `ink`，`chip` → `bar`），所以只改想改的就好：

| token | 控制 |
|---|---|
| `bar` | 候選列（含 buffer、符號鍵）底色 |
| `barInk` | 候選列上**符號鍵**的字色 |
| `buffer` | 編碼區底色（預設同 `bar`） |
| `bufferInk` | 編碼區字色 |
| `chip` / `chipInk` | 候選字**未選中**的底色 / 字色 |
| `chipSelected` / `chipSelectedInk` | 候選字**已選中**的底色 / 字色 |
| `chipMark` | 選中候選的底部那條線的顏色 |
| `key` / `keyInk` | 一般按鍵底色 / 字色 |
| `keyPressed` | 按鍵按下的底色（深色主題用） |
| `keyBorder` | 按鍵框線顏色（淺色黑框、深色 `#1C1C1E`；分邊只畫右+下，見第 9 點） |
| `seam` | 整個鍵盤的最底層底色（鍵盤縫的顏色） |
| `muted` | 右上角小標籤（`sub_label`）的字色 |
| `lampOn` / `lampOff` | 狀態燈**亮／暗**的墨色（見第 15 節） |

> 解析順序是「單鍵 `style:` → 專用 token → 通用 `ink`／`bar`」，例如 `keyInk` → `ink`、`chip` → `bar`、`chipMark` → `ink`、`lampOff` → `keyBorder` → `ink`。所以只寫想改的 token 就好。

```ruby
theme :light do
  color :bar, "#FFFFFF"
  color :barInk, "#CC0000"          # 候選列符號鍵 → 紅字
  color :bufferInk, "#0000CC"       # 編碼區 → 藍字
  color :chip, "#E8E8E8"            # 候選字底
  color :chipInk, "#007700"         # 候選字 → 綠字
  color :chipSelected, "#FFF3A0"    # 選中候選底
  color :chipSelectedInk, "#AA00AA" # 選中候選 → 紫字
  color :chipMark, "#FF0000"        # 選中候選底部紅線
  color :key, "#FFFFFF"
  color :keyInk, "#000000"
  color :keyBorder, "#000000"
  color :seam, "#FFFFFF"
  color :muted, "#888888"
end
```

**單顆按鍵覆寫**（優先於 theme token）：
```ruby
key "B", span: 1, action: :code, value: "b", ink: :accent, bg: :keyAlt
key "C", span: 1, action: :code, value: "c",
    style: { border: { color: "#FF0000", width: 2 }, radius: 8 }
```
`color :accent, "#FF8000"`、`color :keyAlt, "#EEEEEE"` 這種自訂 token 可以放在同一個 theme 裡，`ink: :accent`／`bg: :keyAlt` 會自動解析（也可以直接寫 `ink: "#FF8000"`）。

### 11. 組合鍵：任意修飾鍵 × 任意鍵，還能串成序列
```ruby
key :a, action: :combo, value: "ctrl+alt+shift+super+1"   # 四個修飾鍵一起
key :b, action: :combo, value: "alt+f7"
key :c, action: :combo, value: "shift+period"             # 也可寫 "shift+."
key :d, action: :combo, value: "super+tab"
key :e, action: :combo, value: "ctrl+a|text:hello|ctrl+v" # 序列：先 Ctrl+A、打 hello、再 Ctrl+V
```
- 修飾鍵：`ctrl`（`control`）、`shift`、`alt`、`meta`（`win`/`super`/`cmd`）
- 按鍵：`a`–`z`、`0`–`9`、`f1`–`f12`、`enter`/`tab`/`esc`/`space`/`del`/`ins`/`home`/`end`/`pgup`/`pgdn`/`capslock`/`menu`、方向鍵、`comma`/`period`/`slash`/`semicolon`/`apostrophe`/`minus`/`equal`/`backslash`/`plus`/`star`…
- 單字元符號也可以直接寫：`"ctrl+,"`、`"shift+/"`、`"ctrl+["`
- `|` 分隔多段（依序送出），`text:...` 是直接插入文字
- 手勢也吃同一組動作：`gestures: { swipe_up: { action: :combo, value: "shift+enter" } }`（⏎ 上滑＝換行）

### 12. 這個 DSL 還能定義什麼
| 分類 | DSL | 說明 |
|---|---|---|
| 版本 | `Keyboard.layout version: 1 do … end` | 目前只有 `1`；block 可接收情境參數 `ctx`（見下） |
| 版面格線 | `grid columns: 10, gap_h: 0, gap_v: 0, outer: 0` | 欄數、水平／垂直間距、左右外距（**dp**，不是格數） |
| 高度 | `height bar: 48, body: 240` | 候選列／鍵盤本體高度（dp）；**鍵盤總高 = bar + body + bottom padding**（設定的「隱藏虛擬鍵盤」開啟時 = bar + bottom），設定頁不再覆蓋 |
| 列高 | `row height: 1` | 同一頁各列的**比例**（LinearLayout weight），不是 dp |
| 邊距 | `padding left: 0, right: 0, bottom: 0` | 鍵盤外距（dp）；`left`/`right` 是 `KeyboardView` 內距，`bottom` 一併計入總高 |
| 音效 | `sound :click, volume: 0.6` | 版面預設音效，見第 14 節 |
| 主題 | `theme :light do color :token, "#RRGGBB" end` | 兩套 token；`state` 目前保留未使用 |
| 分頁 | `page :chinese do … end`、`page :english do … end` | 中文＝**第一頁**（`defaultPage`），英文＝頁名 `english`（或 `en`）；**只有這兩頁會被顯示** |
| 條件頁 | `page :chinese, when: ->(ctx) { ctx[:input_type] == "number" } do … end` | 條件成立才建立該頁（顯示規則仍同上） |
| 候選列 | `bar height: 48, bg: :bar, gap: 0` | 高度 dp、底色 token、slot 間距（**dp**） |
| 候選列外框 | `bar … border: { width: 2, color: :seam, style: :dashed } do … end` | **內縮**繪製：框線吃掉 bar 自己的內容寬度，不佔版面、不會被螢幕裁掉；`style:` = `:solid`／`:dashed`／`:dotted`；`width: 0`（預設）＝不畫 |
| 候選列內容 | `buffer` / `candidates` / `status` / `slot` / `key` | 每個都吃 `span:`（格數）與 `weight:`（剩餘寬度比例） |
| 編碼區 | `buffer span: 1.3, role: :buffer, format: :uppercase, max_chars: 6` | 只有 `span`／`format`／`max_chars`／`role`／`id` 有作用 |
| 候選字 | `candidates span: 5.7, weight: 9, role: :candidate_row, font: { size: 24, weight: :bold, family: "NotoSansCJK" }, chip_style: { bg: :chip, ink: :chipInk }` | `font:` 是 chip 字級（sp，預設 24 粗體）與字型檔名；`chip_style` 可改底色／字色／選取線 |
| 群組 | `slot :punct, span: 2.5, gap: 0 do key ",", span: 0.5 … end` | 群組內按鍵再分 `span` |
| 狀態燈 | `status span:, gap: do lamp :shift, label: "S" end` | 見第 15 節；`gap` 單位是 **dp** |
| 按鍵 | `key 標籤, span:, weight:, role:, label:, label_zh:, label_en:, sub_label:, sub_label_zh:, sub_label_en:, action:, value:, align:, transform:, label_fit:, label_wrap:, flat:, ink:, bg:, style:, desc:, sound:, sound_volume:, haptic:, gestures:, long_press:, repeat:` | 字串標籤預設 `action: :code`；符號標籤預設 `noop`，且要自己給 `label:`（或 `label_zh`/`label_en`）與 `action:`，否則鍵面空白／沒有作用；`desc:` 是無障礙描述 |
| 標籤塞不下 | `label_fit: :wrap / :shrink / :none`、`label_wrap: 2` | 多字元標籤（例：`key "ㄍㄐㄜ"` 或碼表 `%keyname g→ㄍㄐㄜ`）塞不進格子時：`:wrap` 每 `label_wrap`（預設 2）字換行；`:shrink` 縮小字級維持一行；`:none` 直接裁掉。碼表 `%keyname` 標籤預設 `:wrap`，DSL 標籤預設 `:none`（所以 `TAB`/`DT` 不會斷行） |
| 空白 | `spacer span: 1` | **只在 `row` 內有效**（隱形、不可點） |
| 動作 | `action:` 16 種（見上表）＋ `value:` | 送字元、組合鍵、特殊鍵、修飾鍵、日期時間 |
| 手勢 | `gestures: { swipe_up:/down:/left:/right: }` | 每鍵可自訂四個方向（字串＝送出文字；Hash＝動作，如 `{ action: :combo, value: "shift+enter" }`） |
| 長按 | `long_press: :toggle_hide_rows` | **目前只支援這一個**（其他值會被忽略） |
| 連發 | `repeat: { delay: 400, interval: 80 }` | 只對 `action: :backspace` 生效 |
| 觸感 | `haptic: :light/:medium/:heavy` | 單鍵震動回饋（預設無） |
| 音效覆寫 | 每鍵 `sound:` / `sound_volume:` | 優先於版面層 |
| 列對齊 | `row align: :left/:center/:right/:space_between` | 只在該列 span 總和 < `columns` 時有作用 |
| 範圍 | `page … do … end`、`rows do … end`、`bar do … end`、`slot … do … end` | 純 Ruby，可 `each`、`to_s` 組鍵 |

**`role:` 可用的值**（決定 Android view id 與內建手勢，不影響外觀）：
`buffer`、`candidate_row`、`hide`、`space`、`space_alt`、`backspace`、`enter`、`dpad_up`、`dpad_down`、`dpad_left`、`dpad_right`、`select_all`、`copy`、`paste`。

**`ctx` 可用的鍵**（全部是字串，由 `MrubyimeIME.layoutContext()` 產生）：
`mode`（`chinese`/`english`）、`theme`（`light`/`dark`）、`locale`、`input_type`（`text`/`number`/`phone`/`datetime`/`email`/`url`/`password`）、`ime_action`、`screen_w`、`screen_h`、`density`、`safe_bottom`、`bar_height`、`body_height`、`left_pad`、`right_pad`。

**候選 chip 的外觀**（`chip_style` 會直接併進 chip 的 `style`）：
```ruby
candidates span: 5.7, role: :candidate_row,
           font: { size: 24, weight: :bold },
           chip_style: {
             bg: :chip, ink: :chipInk,
             selected: { border: { bottom: 3, color: :chipMark } }
           }
```
chip 的顯示格式固定是「選字鍵＋字」（`0微`），字級由 `candidates font:` 控制（例如 `font: { size: 28, weight: :normal }`；省略＝24sp 粗體）；`chip_style` 只改顏色與選取線。

**候選字的邊框**（粗細／顏色／樣式一律只用這兩個參數，不用寫進 `chip_style`）：
```ruby
candidates weight: 9,
           border: { width: 1, color: :seam, style: :dotted },              # 沒選中的每一顆
           border_selected: { width: 3, color: :ink, style: :solid, side: :bottom }  # 選中那一顆
```
| 參數 | 說明 |
|---|---|
| `width:` | 線寬（**dp**）；`0` 或省略＝不畫（預設就是這個） |
| `color:` | 顏色 token 或 `"#RRGGBB"`，解析順序同單鍵 `style: { border: … }`（`chipMark` → `ink`） |
| `style:` | `:solid` 實線、`:dashed` 虛線、`:dotted` 點線 |
| `side:` | `:all` 四邊（預設）或 `:top`／`:bottom`／`:left`／`:right` 單邊——選中的底線就是 `border_selected: { …, side: :bottom }` |

`border:` 只作用在**未選中**的候選，`border_selected:` 只作用在**選中**那顆，兩者互不影響（舊版面只寫 `chip_style: { selected: { border: { bottom: 3 } } }` 也照舊）。

**整個候選列（bar）的邊框**（內縮繪製，不佔版面寬度）：
```ruby
page :chinese do
  bar height: 48, bg: :bar, gap: 0,
      border: { width: 1, color: :ink, style: :solid, side: :top } do   # 內建：只畫上緣 1dp
    buffer weight: 2
    candidates weight: 9,
               border: { width: 1, color: :seam, style: :dotted },              # 未選中：點線一圈
               border_selected: { width: 2, color: :ink, style: :solid, side: :bottom }  # 選中：2dp 底線
    key :hide, weight: 1, label: "▼", action: :ime_hide, flat: true
  end
  …
end
```
- 邊框畫在 bar 內緣：bar 的內容（buffer／candidates／`▼`）會整體內縮一個線寬，所以**版面寬度不變**、也不會被 IME 視窗邊緣裁掉。
- `width:` 單位 dp、`style:` 只收 `:solid`／`:dashed`／`:dotted`、`side:` 預設 `:all`（可 `:top`／`:bottom`／`:left`／`:right`）；`color:` 走一般 token 解析。
- 中、英兩頁可以各寫各的 `border:`（**內建中／英頁都只畫上緣 1 dp**；若某頁不寫 `border:`，該頁就不會被內縮、保持完整寬度）。
- 以上就是**內建實際使用的寫法**：中／英兩頁的 bar 都只畫上緣 1 dp 黑線；候選未選中為白色點線框（淺色下隱形、深色下可見）、選中為 2 dp 黑底線。

**字級與字型**：單鍵 `font: { size: 20 }`、`buffer font: { size: 24, weight: :bold }`、`candidates font: { size: 24 }` 都已生效（設定頁已無字體滑桿）；`font:` 也吃 `weight: :normal/:bold` 與 `family: "檔名"`（編碼區／候選字／按鍵都支援；字型檔放 `assets/fonts/`、`filesDir/fonts/` 或 Android/data 的 `files/fonts/`）。

**目前有欄位但還沒接上 UI 的**：`icon:`（不會畫圖示）、`min_touch`、`theme … state`、`candidates autoscroll:`、額外分頁、候選字分頁按鈕、長按巨集。

### 13. 標籤隨中英模式自動換字
```ruby
key :tog, span: 2, action: :toggle_mode,
    label_zh: "→ 英文", label_en: "→ 中文",
    sub_label_zh: "模式", sub_label_en: "MODE"
```
- `label_zh`：中文模式顯示；`label_en`：英文模式顯示；沒給就退回 `label`
- `sub_label_zh` / `sub_label_en`：右上角小標籤也跟著換
- 動作（`action:`/`value:`）**不會**跟著換，`code` 鍵請明確寫 `value:`
- 觸發：`toggle_mode`、Space 下滑、硬體 Alt+Space／Shift+Space、`setEnglishMode()` → 都走 `KeyboardView.applyModeLabels()`

實測：同一顆鍵在中文模式是「→ 英文／模式」，切到英文模式變「→ 中文／MODE」。

### 14. 每個鍵自訂音效
版面層預設（所有鍵共用）：
```ruby
sound :click              # 系統點擊音
sound :click, volume: 0.6 # 音量 0~1
sound :none               # 版面預設靜音（單鍵仍可用 sound: 覆寫；不寫 = 無聲，與舊版相同）
```
單鍵覆寫（優先於版面層）：
```ruby
key "A", span: 1, action: :code, value: "a", sound: :none
key "B", span: 1, action: :code, value: "b", sound: :click
key "C", span: 1, action: :code, value: "c", sound: :click, sound_volume: 0.3
key :d,  span: 1, label: "D", action: :code, value: "d", sound: "ding.ogg"
```
自訂音檔尋找順序（`SoundPool`，`setLayout()` 時預先載入）：
1. `assets/sounds/<name>`
2. `assets/<name>`
3. 絕對路徑，例如 `sound: "/sdcard/Download/ding.ogg"`
4. `filesDir/sounds/<name>`（= `/data/data/com.example.mrubyime/files/sounds/ding.ogg`）

放入音檔（不需儲存權限）：
```bash
adb push ding.ogg /sdcard/Android/data/com.example.mrubyime/files/sounds/ding.ogg
```
`ogg/wav/mp3/m4a` 已在 `build.gradle.kts` 設 `noCompress`，assets 內的音檔可直接 `openFd`。

單鍵震動（可與音效並用）：
```ruby
key "E", span: 1, action: :code, value: "e", haptic: :light   # light / medium / heavy
```

### 15. 候選列的狀態燈（CapsLock / Shift / Ctrl / Alt / Super / 中英…）

在 `bar` 裡放一條 `status`，裡面每個 `lamp` 就是一顆小燈：

```ruby
bar height: 48, bg: :bar, gap: 0 do
  buffer     span: 1.3, role: :buffer, format: :uppercase, max_chars: 6
  candidates span: 4.2, role: :candidate_row

  status span: 2.0, gap: 2 do            # gap: 燈與燈之間的間距（dp）
    lamp :caps,    label: "A"            # CapsLock
    lamp :shift,   label: "S"            # Shift
    lamp :ctrl,    label: "C"            # Ctrl
    lamp :alt,     label: "M"            # Alt
    lamp :meta,    label: "◆"            # Super / Win / Command
    lamp :english, label: "EN"           # 英文模式時亮
  end

  slot :punct, span: 2.0 do
    [",", ".", "'", "[", "]"].each { |c| key c, span: 0.4, action: :code, flat: true }
  end
  key :hide, span: 0.5, role: :hide, label: "▼",
      action: :ime_hide, flat: true, ink: :muted
end
```

`lamp 狀態, label:, span:, on_ink:, off_ink:`（`indicator` 是同義字）

| `狀態` | 亮起時機 |
|---|---|
| `:caps` / `:capslock` | 實體 CapsLock 開啟（讀 `KeyEvent.isCapsLockOn`） |
| `:shift` / `:ctrl` / `:alt` / `:meta`（＝`:super` `:win` `:cmd`） | 實體鍵按住（`event.metaState`）**或** 軟體鍵盤的黏著修飾鍵已鎖定 |
| `:latch` / `:modifier` | 任一個軟體 `modifier:` 鍵已鎖定（不限哪個） |
| `:english` / `:en` | 目前是英打模式 |
| `:chinese` / `:zh` | 目前是中文模式 |
| `:buffer` | 編碼區有字 |
| `:candidates` | 有候選字 |

- 亮 = 用 `lampOn` 顏色 token；暗 = 用 `lampOff` token（未定義就取 `keyBorder`／`ink`），再混入 **35%** 候選列底色，**兩個主題都自動可讀**，不必各寫一色
- `on_ink:` / `off_ink:` 可單顆覆寫顏色（填 `#RRGGBB`）；暗燈一律以 35% 混入底色，**透明目前不支援**（`#00000000` 也會被當成不透明）
- 狀態燈 **不能點**（純顯示），間距、字級固定 11sp 粗體，不佔 `keyViews`、不影響按鍵事件
- 範例主題（淺色）：
  ```ruby
  theme :light do
    color :lampOn,  "#000000"
    color :lampOff, "#AAAAAA"   # 這是「暗燈的墨色」，不是底色
  end
  theme :dark do
    color :lampOn,  "#FFFFFF"
    color :lampOff, "#8E8E93"
  end
  ```

⚠️ 注意：`status span:` 會跟 `buffer`／`candidates`／`punct`／`hide` 一起分掉候選列的寬度（總和 10）。把 `candidates` 縮小太多，候選字會看不到幾個——建議 `status` ≤ 2、候選至少留 4 格，或用 `lamp ..., span:` 讓重要燈寬一點（上面的範例是 1.3 + 4.2 + 2.0 + 2.0 + 0.5 = 10）。

實測：實體 `KEYCODE_SHIFT_LEFT` 切換到英打 → `EN` 燈點亮；軟體 `modifier:` shift 鍵一按 → `S` 燈與 `:shift`／`:latch` 同時亮（底線鎖定的鍵與燈號一致）；再按一次 → 熄。


### 16. 完整可匯入範例（已用 DSL 驗證）

下面是一份**最小但可用**的兩頁版面：中文頁＝編碼區／候選／狀態燈／`▼`＋三列字母；英文頁＝19 符號列＋三列小寫字母。可直接存成 `keyboard.rb` 匯入（或貼進 `keyboard_layout.rb` 重新建置）。

```ruby
Keyboard.layout version: 1 do |ctx|
  grid columns: 10, gap_h: 0, gap_v: 0, outer: 0
  height bar: 48, body: 240
  padding left: 0, right: 0, bottom: 0
  sound :click, volume: 0.5

  theme :light do
    color :bar, "#FFFFFF"
    color :key, "#FFFFFF"
    color :keyBorder, "#000000"
    color :ink, "#000000"
    color :lampOn, "#000000"
    color :lampOff, "#BBBBBB"
  end
  theme :dark do
    color :bar, "#3A3A3C"
    color :key, "#3A3A3C"
    color :keyPressed, "#505054"
    color :seam, "#1C1C1E"
    color :ink, "#FFFFFF"
    color :lampOn, "#FFFFFF"
    color :lampOff, "#6E6E73"
  end

  page :chinese do
    bar height: 48, bg: :bar, gap: 0 do
      buffer span: 1.3, role: :buffer, format: :uppercase, max_chars: 6
      candidates span: 6.2, role: :candidate_row
      status span: 2.0, gap: 2 do
        lamp :shift, label: "S"
        lamp :english, label: "EN", span: 1.5
      end
      key :hide, span: 0.5, role: :hide, label: "▼",
          action: :ime_hide, flat: true, ink: :muted
    end

    rows do
      row do
        "qwertyuiop".each_char do |c|
          key c, span: 1, action: :code, transform: :upper
        end
      end
      row do
        "asdfghjkl".each_char do |c|
          key c, span: 1, action: :code, transform: :upper
        end
        key :backspace, span: 1, role: :backspace, label: "⌫",
            action: :backspace, repeat: { delay: 400, interval: 80 }
      end
      row do
        "zxcv".each_char { |c| key c, span: 1, action: :code, transform: :upper }
        key :space, span: 2, role: :space, label: "", action: :space
        "bnm".each_char { |c| key c, span: 1, action: :code, transform: :upper }
        key :enter, span: 1, role: :enter, label: "⏎", action: :enter,
            gestures: { swipe_up: { action: :combo, value: "shift+enter" } }
      end
    end
  end

  page :english do
    bar height: 48, bg: :bar, gap: 0 do
      slot :symbols, span: 9.5 do
        ["`", "~", ";", "=", "|", "\"", "$", "%", "^", "&", "<", ">",
         "{", "}", ",", ".", "'", "[", "]"].each do |c|
          key c, span: 0.5, action: :code, flat: true
        end
      end
      key :hide, span: 0.5, role: :hide, label: "▼",
          action: :ime_hide, flat: true, ink: :muted
    end

    rows do
      row do
        "qwertyuiop".each_char { |c| key c, span: 1, action: :code }
      end
      row do
        "asdfghjkl".each_char { |c| key c, span: 1, action: :code }
        key :backspace, span: 1, role: :backspace, label: "⌫",
            action: :backspace, repeat: { delay: 400, interval: 80 }
      end
      row do
        "zxcv".each_char { |c| key c, span: 1, action: :code }
        key :space, span: 2, role: :space, label: "", action: :space
        "bnm".each_char { |c| key c, span: 1, action: :code }
        key :enter, span: 1, role: :enter, label: "⏎", action: :enter,
            gestures: { swipe_up: { action: :combo, value: "shift+enter" } }
      end
    end
  end
end
```

要「什麼都有」的完整版就看 `keyboard_layout.rb`（內建版面本體，含第 5 列符號／方向鍵／剪貼簿鍵）。

> 要交給外部設計者或 AI 的完整設計包（檔案清單、DSL 速查、配色規則、驗證指令、提示詞範本），請看 `docs/LAYOUT_DSL.md` 與可直接匯入的 `docs/layout_template.rb`。

#### 十鍵功能示範列（可直接貼進 `rows do … end`）

```ruby
row do
  key :ctrl,  span: 1, label: "CTRL",  action: :modifier, value: :ctrl
  key :combo, span: 1, label: "C+S+1", action: :combo, value: "ctrl+shift+1"
  key :ins,   span: 1, label: "INS",   action: :key_event, value: "ins"
  key :date,  span: 1, label: "DATE",  action: :date
  key :time,  span: 1, label: "TIME",  action: :time
  key :day,   span: 1, label: "DAY",   action: :weekday
  key :all,   span: 1, label: "^A",    action: :clipboard, value: :select_all
  key :left,  span: 1, label: "⬅",    action: :dpad, value: :left
  key :tog,   span: 1, label_zh: "→EN", label_en: "→中", action: :toggle_mode
  key :hide,  span: 1, label: "▼",     action: :ime_hide
end
```

（每個鍵都是 `span: 1`，10 鍵剛好佔滿 10 欄；`toggle_mode` 鍵的顯示文字由 `label_zh`／`label_en` 決定，`label` 可以省略。）


### 對齊：不同列、不同按鍵數也能對齊
位置只由**格線**決定：`unit = (寬度 − 2×outer) / columns`，每個邊界都是 `round(outer + 累計span × unit)`，所以「10 顆 span 1」的一列與「AA(span 2)、BB(span 2)、X0…X5(span 1)」的一列會在**同一條垂直縫**上（不再因為逐鍵 `toInt()` 截斷而每鍵漂移 1–2px）。因此：
- 想讓某列與其他列對齊 → 讓該列 **span 總和 = `columns`**（內建是 12）。
- 不需要去清 `TextView` 的隱性 padding（`KeyRowLayout` 以 `EXACTLY` 量測，padding 不會影響格線）。
- 間距一律用 DSL（單位都是 **dp**）：`grid gap_h` / `grid gap_v` / `grid outer`，以及 `bar gap:`、群組 `gap:`、`status gap:`。

---

## 🛠️ 建構與安裝步驟

### 1. 環境準備
- 確保已安裝 **Android SDK** 與 **Android NDK**（建議版本 NDK 30+）。
- 設定環境變數 `ANDROID_NDK_HOME`：
  ```bash
  export ANDROID_NDK_HOME=/path/to/Your/Android/Sdk/ndk/30.0.16248370
  ```

### 2. 準備碼表（`.cin`）
- 內建預設碼表：`app/src/main/assets/mrubyime.cin`（uniliu 萬國蝦米，30,478 碼 / 187,422 筆＝47,788 個字 + 139,637 個詞）。這是目前唯一隨 App 附的碼表。`%phrase` 每行一筆、格式為 `詞 權重`（例如 `你好 121`、`一 213167`），權重就是候選排序的依據；但**一字的詞（連同權重）會被忽略**，單字順序一律照 `%chardef`，詞頻不會打亂它。
- 其他**內建**碼表：把 `<name>.cin` 放進 `app/src/main/assets/dicts/`，重新編譯後會出現在設定頁清單（id 為 `builtin:<name>`）。
- **不必重新編譯**：直接在 App 設定頁「匯入 (.cin)」即可；檔案會存成 `filesDir/dicts/active.cin` 並立即生效。匯入前會檢查 `%chardef` 與檔案大小（上限 4 MB）。
- 支援的指令：`%ename` / `%cname` / `%selkey` / `%keyname begin…end` / `%chardef begin…end` / `%phrase begin…end`。其他指令忽略；以 `#` 開頭的行視為註解；檔案需為 UTF-8（可含 BOM）。
- **一個字的任一組碼都能組詞**：詞組的查表鍵是「每個字最短碼接起來」（三峽區＝`s`＋`egn`＋`fv`＝`segnfv`），但**不必照最短碼打**——引擎會把整串 buffer 切成表裡任何合法的碼，再檢查接出來的詞是不是詞集裡的詞。所以 `segnfooo`（區＝`fooo`）、`segnfxx`（`fxx`）也一樣出現「三峽區」，而且因為它在 `%phrase` 裡（有權重）排第一；`segnf`、`segnfo` 這種打到一半的尾碼也會先給答案。
- **`%phrase` 詞組**：`<碼> <詞…>` 或**只寫詞**（引擎自動推碼），一行可放多個詞；行尾若是最後一個純數字欄位，會被當成該列的**權重**（`你好 121`，數字一定要放在最後），候選就依權重由大到小排；但有幾項**一律忽略**：一字的詞（連權重，單字順序只照 `%chardef`）、長度 1 的字沒有推碼價值；詳見 `docs/PHRASE_TEACHING.md`。
- **`%selkey` 可以同時是字根**：按鍵會先看「加了這個鍵還能不能接成下一個編碼」；能接就繼續組字，不能接才選字。以注音 `%selkey 1234567890`、`%keyname 1 ㄅ` 為例：`2`（ㄉ）之後按 `8`（ㄚ）會組成 `28`、再按 `4` 成為 `284`（大），而 `284` 有候選後按 `1` 才是選第 1 個候選。嘸蝦米的數字不是字根，所以仍是一律選字。

### 3. 一鍵編譯與安裝
專案根目錄提供了一鍵自動化編譯指令稿 `build_all.sh`：
```bash
./build_all.sh
```
該指令稿會自動依序執行：
1. 下載並編譯 host 版 mrbc。
2. 交叉編譯 Android (arm64-v8a) 版 libmruby.a（帶有 `MRB_UTF8_STRING`、`MRB_ARY_LENGTH_MAX=0`、`MRB_GC_ARENA_SIZE=4096` 巨集；**最小 gem 集 + `-Os` + thin LTO**，體積最佳化；`mruby-compiler` 保留供使用者匯入的 DSL 使用）。
3. 將 `app/src/main/mruby/mrubyime_engine.rb`、`keyboard_dsl.rb`、`keyboard_layout.rb` 編譯為位元組碼 C 陣列（`engine_irep.c`、`keyboard_dsl_irep.c`、`keyboard_layout_irep.c`）。
4. 以系統 Ruby 執行同一份 DSL，產生 `assets/keyboard_layout.json` 備援 IR。
5. 透過 Gradle 編譯 Android Debug APK。
6. 自動透過 ADB 將 APK 安裝至已連接的手機。

---

## 📱 手機啟用與測試步驟
1. 打開手機設定或點擊 App 內的 **「1. 啟用輸入法設定」**。
2. 啟用 **「小蝦米輸入法」**。
3. 點擊 **「2. 切換/選擇輸入法」** 將當前輸入法切換為小蝦米。
4. 您可以直接在 App 內的測試輸入框中打字，開始感受極速流暢的形碼打字體驗！
