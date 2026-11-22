# 引擎能力藍圖（Engine Capabilities）

> 說明：`files/engine/engine.rb`（或內建 `mrubyime_engine.rb`）能幫 IME 做到什麼、
> 引擎契約長什麼樣、以及哪些功能需要動到 IME 層（Kotlin）才能做。
> 這份文件是「給想改寫引擎的人／給想評估能不能做某個功能的人」的索引。
>
> 相關：`docs/ENGINE_HOT_RELOAD.md`（載入機制與安全性）、`docs/LAYOUT_DSL.md`（鍵盤佈局）。

---

## 0. 一句話總結

引擎是**完全自由的 Ruby 物件**，契約只有 17 個方法名；凡是不需要動到 IME 畫面的功能
（字、詞、補全、整句當一個候選、動態候選）都**純 Ruby 就能做**。需要動畫面的
（整句+游標逐字挑選、候選列分組、上屏後再選字）才需要改 Kotlin。

---

## 1. 方法契約（全部）

C bridge（`mrubyime_jni.c`）只用這 17 個方法。改寫引擎時**名稱與回傳型別不可變**，
內部實作完全自由。

| 方法 | 回傳 | 用途 |
| --- | --- | --- |
| `load_dictionary(data)` | — | 載入 `.cin` 全文（字串）。重新載入會重設 buffer 與所有表。 |
| `input(key)` | Array<String> | 輸入一個鍵（字元），回傳候選；**副作用是累加 `@buffer`**。 |
| `input_serialized(key)` | String | 同上，候選以 `"\n"` 串接。IME 只認這個。 |
| `current_candidates_serialized` | String | 讀取目前 `@buffer` 的候選，**不修改 buffer**（模式切回時用）。 |
| `backspace` | Array<String> | 退一格；回傳退格後 buffer 的候選。 |
| `backspace_serialized` | String | 同上，`"\n"` 串接。 |
| `reset` | — | 清空 `@buffer`。 |
| `table_size` | Integer | 表內「碼」的個數（IME 用於回退判斷）。 |
| `entry_count` | Integer | 候選總數（IME 設定頁顯示）。 |
| `selkeys` | String | 選取鍵字串，例如 `"0123456789"`。 |
| `buffer` | String | 目前組字緩衝。 |
| `legal_keys_serialized` | String | 全部合法鍵（每個字元一個）。IME 用它判斷某鍵是否進組字。 |
| `keynames_serialized` | String | `key<TAB>label` 每行一個；IME 用它覆蓋中文鍵面。 |
| `dictionary_name` | String | 碼表顯示名。 |
| `table_max_code_len` | Integer | 最長碼長（單字）。IME 用於自動送出門檻。 |
| `can_extend?` | Boolean | 是否還有合法鍵能往下延伸（單候選自動送出時避免誤送前綴）。 |
| `would_extend?(key)` | Boolean | 某鍵加進 buffer 後是否仍是「碼或碼的前綴」（`%selkey` 同時是字根時用）。 |
| `dead_prefix` | Boolean | 目前 buffer 是否「怎麼加都死」（死碼清空用）。唯讀。 |
| `set_max_code_len(len)` | — | 設定目前最長查詢碼長（IME 由「最大碼清空組字」開關決定）。 |

內建引擎另外提供兩個**唯讀診斷**方法（不在 JNI 契約內，給測試/除錯用）：
`weight_for(詞)`（該候選的權重，0＝沒有權重）、`phrase_weight_count`（載入後有權重的
候選數，目前的內建碼表是 100,149 個）。

> **只要守住名字與回傳型別，引擎內部可以完全重寫**：換語言模型、加詞庫、改候選排序、
> 甚至把 buffer 當句子緩衝都行。

---

## 2. 內建引擎目前已支援的

- **泛用 `.cin` 解析**：`%ename/%cname/%selkey/%keyname/%chardef`。
- **詞組（`%phrase begin/end`）**：把數個字元碼連起來對應到一個詞，兩種寫法：
  - **有碼**：`ayyedn 今天 今日`（一個碼可對應多個詞）
  - **只有詞、沒有碼**：`你好 您好`（**每個詞各自**用該詞每個字的碼接起來推碼）
  ```
  %phrase begin
  ayyedn 今天 今日
  你好 您好          ← 只有詞：你好 → pns+gz、您好 → 各自推自己的碼
  %phrase end
  ```
  一行可以放**任意多個**只有詞的項目。有碼的詞組會建立前綴索引（打前綴就補全）；
  只有詞的自動推碼詞組**不建前綴索引**——13 萬個詞的前綴會讓每次按鍵都要掃全表，
  改由「自動組詞」負責前綴。
  - **權重（選填）**：一行**最後**若是一個純 ASCII 數字欄位，它就是這一列的**詞頻權重**：

    ```
    %phrase begin
    你好 121            ← 詞＝你好，權重 121
    一一九 21           ← 詞＝一一九，權重 21
    今天 今日 500        ← 兩詞共用權重 500
    ayyedn 今天 今日 900 ← 有碼寫法也可以帶權重
    %phrase end
    ```

    規則：只有「該行有 2 欄以上、且最後一欄全是 0–9」才會被剝掉當權重，所以**單獨一欄的
    數字詞**（`2024`）仍然是一個詞，不會被吃掉。**一字的詞一律忽略（連權重一起丟掉）**，
    所以單字的順序永遠只由 `%chardef` 決定，`%phrase` 不會重排或重複單字。權重進
    `@phrase_freq`（只放多字詞；內建表約 100,149 筆），候選排序就變成
    **① 完整碼／詞集 ② 權重大→小 ③ 用碼數少**，且同一候選只留第一次出現
    （`%chardef` 同一個字可能有兩個碼）。沒有任何權重行時這個表是空的，排序與舊版完全相同。
- **自動組詞（不用 `%phrase`）**：buffer 若能切成一段段單字碼，就接成詞當候選。會列出**所有
  切法**，排序是 **① 詞集裡有的詞優先 → ② 權重大的優先 → ③ 用碼數少的優先**，最多
  `MAX_COMPLETIONS`（現在 16）個。**每個碼會依序試最多 3 個字**（`COMPOSE_CHARS_MAX`）：內建表有
  10,688 個碼帶替代字（占全部 `%chardef` 的 36 %），只取第一個字會讓 17 % 的詞永遠組不出來，
  打到替換碼時也會回錯字（`wwnjn` → 違是；改成 3 個字後 → 這是，因為「這是」在詞集裡、
  權重 3779）。可組詞率 83 % → 98.6 %（2 個字 95.7 %、4 個字 99.5 %），分支約 ×1.5，
  由 40,000 步的 `@seg_budget` 吸收；主機實測每鍵 0.4 ms（5 碼）～2.3 ms（140 碼，含 P1/P2）。
  **組詞分兩趟走**：先走「每個碼只取第一個字」的標準趟（`SEG_BUDGET_CANON`＝3,000 步），再走
  3 個字的替代趟，兩趟各自有候選清單與尾碼前綴。這是必要的：`segnfooo` 是 `s|egn|fooo`（三峽區），
  但 `seg` 本身也是一個碼（綏），替代趟會先把它整棵子樹走完、吃光 120 組讀法的上限，`s` 那條分支
  永遠輪不到，詞就不會出現。分兩趟之後**一個字的任一組碼都能用**：`segnfooo`、`segnfxx`
  （`s|egn|fxx`）與 `segnfv`（推導出的詞組碼）都會把「三峽區」排在第一；隨機抽 240 個 `%phrase` 詞、
  用**非最短碼**拼出來，進前 16 名的次數從 154 次提升到 193 次（第一名 115 → 154 次）。
  **尾碼只打一半也會先給答案**：`compose_candidates` 在走訪時只記錄每個切點最多 4 組已完成的
  前綴（`TAIL_HEADS`），再由 `tail_readings` 用 `@tail_index`（前綴 → 再 1～2 鍵就能完成的碼，
  `TAIL_LOOKAHEAD`）補完，且只保留能變成**詞集裡已知的詞**的組合（每個前綴先試「就是該字
  標準碼」的候選）。所以 `pnsg` 會先出「你好」（不是 `g`＝機 的「你機」）、`wwnj` 出「這是」、
  `gnsq` 出「你們」。猜測是在真實讀法**之後**才合併，不會擠掉它們。
  詞集就是所有 `%phrase` 的詞（含只有詞的寫法）。例表裡有 你(pns)、
  好(gz) → 打 `p n s g z` 出「你好」；`miepsesnm` 的貪婪切法是「黴崇勿」，但「微生物」在詞集裡，
  所以排第一。這就是「形碼連續輸入」的底層機制。因為可能有歧義（見下例），所以是多候選讓你選。
  可組到**多長的整串**取決於 buffer 上限：**「最大碼清空組字」關（預設）= 140 碼**，
  可以一句接一句地組；**開 = 表最長單字碼**（嘸蝦米 5）就只組到 5 碼。
  「死碼清空編碼區」「滿4碼唯一候選自動上屏」**不影響**組詞（它們只在有候選時動作）。
- **自動補全**：buffer 是某碼的嚴格前綴（且無完全命中）時，回傳「可能完成的候選」，
  最多 `MAX_COMPLETIONS`（現在 16；前 10 個有選取鍵，其餘顯示純詞、可點可滑）個。邊打邊補。
- **`BUILTIN_PHRASES`**：附 20 組常用詞範例，**預設註解掉**（不啟用）；要哪個把該行
  `# ` 去掉即可。
- **前綴/組合感知**：`can_extend?` / `would_extend?` / `dead_prefix` 都考慮詞組與自動組詞，
  所以詞組前綴/組詞中的 buffer 不會被死碼清空或自動送出誤殺。
- **載入是非阻塞的**：整張 `.cin` 在背景執行緒（`engine-load`）解析，鍵盤立刻就能出現；
  解析完成後 `onEngineReady` 才開放打字並重建鍵盤。內建 uniliu（2.0 MB、187,422 筆，
  `%phrase` 為一行一筆 `詞 權重`，一字詞已忽略）在手機上約 **2.8 秒**（主機測試版約 1.09 秒，P1 的尾碼索引再加約 0.05 秒；
  同一份詞表改成一行 100 詞可降到約 0.5 秒，權重則讓它再慢約 0.15 秒）。載入期間編碼區顯示 `…`。
  「最大碼清空組字」的逾時保護是 19 秒（背景執行緒，不會 ANR）。目前沒有「解析結果快取」，
  重新開啟行程會再付一次；同一行程內切換輸入法再回來是 `load=0ms`。

---

## 3. 怎麼用（使用者角度）

1. **自動組詞**：不用設定，打單字碼連起來（`pnsgz`）就出「你好」。
2. **自訂詞組**：在 `.cin` 的 `%chardef end` 後加 `%phrase`——可寫 `<碼> <詞>` 或**只寫詞**。
3. **改引擎**：Settings → 輸入法引擎（Ruby）→ 匯入或寫入編輯目錄，改 `engine.rb`
   （要啟用 `BUILTIN_PHRASES` 就取消註解）。

---

## 4. 改寫範例（一頁看完）

```ruby
class MrubyimeEngine
  # ...（第 1 節的 17 個方法，至少 `input_serialized` 一定要有）

  # 詞組優先：命中詞組回傳詞，否則原字根，否則補全
  def input_serialized(key)
    k = key.downcase
    @buffer << k
    return [] if @buffer.length > @max_code_len
    @phrases[@buffer] || @table[@buffer] || completions(@buffer)
  end

  def completions(buf)
    out = []
    @table.each do |code, list|          # 找以 buf 開頭的碼
      next unless code.start_with?(buf)
      list.each { |c| out << c unless out.include?(c) }
      break if out.length >= 9
    end
    out
  end
end
```

---

## 5. 哪些功能「引擎做不到、要動 IME 層」

| 功能 | 引擎能做嗎 | 為什麼 | 需要的 IME 層改動 |
| --- | --- | --- | --- |
| **詞組輸入** | ✅ 可 | 回傳詞候選即可 | 無 |
| **自動補全** | ✅ 可 | 前綴→候選，單候選自動送出 | 無 |
| **形碼整句（整句當一候選送出）** | ✅ 可 | 引擎把多字串成一句回傳，IME 一次送出 | 無（UX 是「整句選取」） |
| **整句 + 游標逐字挑選、上屏後再選** | ⚠️ 不行 | 引擎沒有游標控制權 | `onKeyPressed` 左右移游標；`InputConnection.setComposingRegion` 標記未定詞；候選列變成「多詞選擇條」 |
| **候選分組／多列候選** | ❌ 不行 | 候選列是單列 chips | 渲染層（`StepScrollView`/chips）改成多列或分組 |
| **跨應用字典同步** | ⚠️ 受限 | 引擎無 File/網路（mruby 沙箱） | 需 Kotlin 讀資料後傳進 `load_dictionary` |
| **學習用戶常用詞** | ⚠️ 受限 | 無法寫檔 | 需 Kotlin 提供持久化通道給引擎 |

### 我對「真整句」的建議（要做的話）
1. **先不改**：整句當一候選送出已可用，先用著。
2. 若真要「微軟/搜狗式整句」：改 `MrubyimeIME.onKeyPressed` 加「左右移游標選字」、
   用 `InputConnection.setComposingRegion` 標未定詞、候選列改成「多詞 + 目前選中的詞再展開字選項」。
   這是**一次中等改動**，建議獨立成一個功能再做。

---

## 6. 沙箱限制（改寫前必看）

- **不能寫檔案、開 IO、做 Thread、`system`、`eval`**（mruby-io / mruby-eval 沒編進去）。
- **不能無限迴圈**：mruby 無指令上限，`while true; end` 會凍結 IME；被逾時 quarantine。
- **別用 `@table[c] ||= []`**：裝置執行期編譯器對 `Hash#[]` 的 `||=` 會誤編譯方法名
  （`undefined method 'ar'`）。用暫存變數。
- **64 KB 上限**：`engine.rb` 最多 64 KB（與佈局同）。
- **UTF-8 字串**：裝置有 `MRB_UTF8_STRING`，`String#length`/`[]` 是字元索引，不是位元組。

---

## 7. 詞組碼怎麼算（給想自己加詞的人）

形碼（嘸蝦米/倉頡/大易…）的詞 = 把每個字的碼**依序相連**：
```
今天 = 今(ayy) + 天(edn) = "ayyedn"
你好 = 你(pns) + 好(gz)  = "pnsgz"
中文 = 中(ci) + 文(wx)   = "ciwx"
打字 = 打(jti) + 字(nzj) = "jtinzj"
```
所以在 `.cin` 寫 `ayyedn 今天` 即可；打 `ayyedn` 就出「今天」。詞碼通常比單字碼長，
因此**「最大碼清空組字」要關掉**（預設就是關，max 140），詞組碼才打得完。

---

## 8. 驗證（怎麼確認你的引擎有詞組/補全）

最直接：設定頁的「即時打字測試」欄位，打詞組碼，看候選列是否出現詞。
Host 端快速驗證（不用裝機）：
```bash
cd mruby-ime
cat app/src/main/assets/mrubyime_engine.rb /tmp/your_test.rb > /tmp/run.rb
mruby/build/host-test/bin/mruby /tmp/run.rb
```