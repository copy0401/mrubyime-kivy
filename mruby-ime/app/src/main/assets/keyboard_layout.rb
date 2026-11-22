# ============================================================================
#  小蝦米輸入法  ─  內建虛擬鍵盤佈局（Keyboard DSL v1）
# ============================================================================
#
# 這份檔案是「預設鍵盤」的唯一真實來源。建置時會被 mrbc 編譯成 bytecode
# （app/src/main/cpp/keyboard_layout_irep.c），執行時跑的是 bytecode，
# 「不會」讀這個 .rb 檔，所以改完一定要重跑：
#
#       cd mruby-ime && ./build_mruby.sh
#
# 使用者也可以在 App 內匯入自己的 keyboard.rb 覆蓋它（同一套 DSL，可在
# 設定頁「鍵盤佈局（Ruby DSL）」匯入／匯出／用外部編輯器開啟）。只有第一頁
# 與頁名 english / en 的頁面會被顯示。
#
# 限制：裝置端 mruby 沒有 regexp gem，這份檔案請只用純字串運算，也不要 eval。
#
# ----------------------------------------------------------------------------
#  版面總覽（12 欄格線、5 列；鍵與鍵、列與列之間 gap 全為 0，只留外緣 3 dp）
# ----------------------------------------------------------------------------
#   第 1 列   1 2 3 4 5 6 7 8 9 0 - =      上滑 → ! @ # $ % ^ & * ( ) _ +
#   第 2 列   q w e r t y u i o p [ ]      上滑 → Q…P ／ { }
#   第 3 列   a s d f g h j k l ; ' \      上滑 → A…L ／ : " |
#   第 4 列   z x c v b n m , . / ` ⌫      上滑 → Z…M ／ < > ? ~
#   第 5 列   中/英(2) + 空白(8) + ⏎(2)
#
#   中文頁：字母鍵顯示大寫（transform: :upper）；候選列 = 編碼區 2 : 候選 9 : ▼ 1
#   英文頁：字母鍵顯示小寫；候選列換成 12 顆等寬動作鍵（沒有編碼區／候選字）
#
#   `sub_label:` 是鍵面右上角的小字，內容＝該鍵上滑會送出的字元，兩者永遠一致。
#
# ----------------------------------------------------------------------------
#  常用指令與「預設值」（省略時就用預設值）
# ----------------------------------------------------------------------------
#
#   Keyboard.layout version: 1 do |ctx| … end
#       version 目前只有 1；block 參數 ctx 是情境資訊（見檔尾「ctx」）。
#
#   grid columns: 10, gap_h: 1, gap_v: 1, outer: 0.5
#       欄數與間距（單位 dp；columns 例外，是格數）。預設 10/1/1/0.5。
#       內建用 columns: 12、gap 全 0、outer: 0 → 按鍵完全貼齊，左右無外緣留白
 #       （深色模式才不會在按鍵區兩側露出 #1C1C1E 粗邊）。
#
#   height bar: 48, body: 240
#       候選列高度／鍵盤本體高度（dp，預設 48 / 240）。總高 = bar + body +
#       padding.bottom；「隱藏虛擬鍵盤」開啟時 = bar + padding.bottom。
#
#   padding left: 0, right: 0, bottom: 0
#       鍵盤外距（dp，預設全 0）：left/right 是左右內距，bottom 一併計入總高。
#
#   theme :light do color :token, "#RRGGBB" end
#       每個主題一份；沒定義的 token 會自動往後退（見檔尾「顏色 token」）。
#
#   page :chinese do … end        # 第一頁 = 預設顯示頁
#   page :english do … end        # 頁名 english 或 en 才會被顯示
#   page :num, when: ->(c) { c[:input_type] == "number" } do … end   # 條件成立才建立
#
#   bar height: 48, bg: :bar, gap: 0, align: :start, border: { … } do … end
#       候選列容器（gap 單位 dp）。slot 有三種：
#
#     buffer weight: 2, role: :buffer, format: :uppercase, max_chars: 6,
#            font: { size: 24, weight: :bold, family: "NotoSansCJK" }
#         format 預設 "uppercase"（編碼顯示大寫）；max_chars 預設 6（超過只顯示後 6 碼）；
#         font 預設 24sp 粗體；family 是字型檔名（見 assets/fonts/README.txt）。
#
#     candidates weight: 9, role: :candidate_row,
#                font: { size: 24, weight: :bold, family: "NotoSansCJK" },
#                border: { width: 1, color: :seam, style: :dotted },
#                border_selected: { width: 2, color: :ink, style: :solid, side: :bottom }
#         font 預設 24sp 粗體；border ＝「未選中」每一顆候選的外框，
#         border_selected ＝「選中」那一顆；style 只收 :solid / :dashed / :dotted；
#         side 預設 :all，可只給 :top / :bottom / :left / :right；width 單位 dp。
#
#     key :hide, weight: 1, role: :hide, label: "▼", action: :ime_hide, flat: true
#         另外也可用 slot :名字 do … end 放一整組按鍵（群組）。
#
#   bar … border: { width:, color:, style:, side: } do … end
#       整個候選列的外框，內縮繪製（吃掉 bar 自己的寬度，不佔版面、不會被裁掉）。
#       內建中／英頁都只畫上緣 1 dp：
#           border: { width: 1, color: :ink, style: :solid, side: :top }
#
#   rows do row do … end … end
#       一列一個 row；列高用 row height:（比例，預設 1）。
#       每列按鍵的 span 總和建議等於 columns（不足的部分靠 row align: 擺放）。
#
#   key 標籤, …   （字串標籤預設 action: :code；符號標籤如 key :enter 預設 :noop，
#                   所以符號標籤一定要自己寫 action: 與 label:）
#       可用選項：
#         span:/weight:        寬度（span 佔「格線格數」，weight 分剩餘空間）
#         role:                決定 Android view id 與內建手勢（見檔尾）
#         label:/label_zh:/label_en:             鍵面文字（可依中／英切換）
#         sub_label:/sub_label_zh:/sub_label_en: 右上角小字（可依中／英切換）
#         action:/value:       動作與參數（見「動作」一節）
#         align:               :left / :center / :right（單鍵文字對齊）
#         transform: :upper    鍵面轉大寫（值仍由 value: 決定）
#         flat: true           不畫邊框、底色改用候選列的 bar 色
#         ink: / bg:           覆寫某個顏色 token
#         style: { border: { color:, width: }, radius: }   單鍵外框與圓角
#         font: { size:, weight:, family: }                字級（sp）與字型檔名
#         label_fit: :wrap / :shrink / :none               多字元標籤塞不進格子時怎麼辦
#                                                          （:wrap 每 label_wrap 字換行、預設 2；
#                                                           :shrink 縮字級維持一行；:none 直接裁掉。
#                                                           碼表 %keyname 的標籤預設 :wrap）
#         label_wrap: 2                                     :wrap 時每行幾個字（預設 2）
#         sound: / sound_volume: / haptic:                 音效與震動
#         gestures: { swipe_up:/down:/left:/right: }       四方向手勢（字串＝送字；
#                                                           { action: :combo, value: "shift+enter" } 可送組合鍵）
#         long_press: :toggle_hide_rows                    目前只支援這一個
#         repeat: { delay: 400, interval: 80 }             只對 action: :backspace 生效
#         desc:                無障礙描述
#
# ----------------------------------------------------------------------------
#  動作（action:）
# ----------------------------------------------------------------------------
#         :code       送出 value:（省略時送標籤的第一個字元）
#         :space  :enter  :backspace  :ime_hide  :noop
#         :toggle_mode          切換中／英（同第 5 列的中/英鍵）
#         :combo        value: "ctrl+shift+1"；也可串成 "ctrl+a|text:hi|ctrl+v"
#         :key_event    value: "ins/del/home/end/pgup/pgdn/tab/capslock/esc/menu/f1..f12/up.."
#         :modifier     value: ctrl|shift|alt|meta（一次性黏著，套用到下一個鍵）
#         :date :time :weekday :datetime   value: SimpleDateFormat 樣式（點擊當下取值）
#         :dpad         value: up|down|left|right
#         :clipboard    value: select_all|copy|paste
#
# ----------------------------------------------------------------------------
#  顏色 token（寫在 theme 內，全部可省略；沒給就沿著「→」往後退）
# ----------------------------------------------------------------------------
#         bar / barInk                    候選列底色／其上的符號鍵字色
#         buffer / bufferInk              編碼區底色／字色（buffer → bar）
#         chip / chipInk                  未選中候選底色／字色（chip → bar）
#         chipSelected / chipSelectedInk / chipMark      選中的候選
#         key / keyInk / keyPressed / keyBorder          一般按鍵
#         seam                            鍵盤最底層（鍵縫）的顏色
#         muted                           右上角小字（sub_label）的字色
#         lampOn / lampOff                狀態燈亮／暗的墨色
#
# ----------------------------------------------------------------------------
#  範例：想改外觀時可以這樣寫
# ----------------------------------------------------------------------------
#         # 候選字放大、選中改成紅色 3 dp 底線
#         candidates weight: 9, font: { size: 28, weight: :normal },
#                    border_selected: { width: 3, color: "#CC0000", style: :solid, side: :bottom }
#
#         # 按鍵留 2 dp 間隙、加圓角
#         grid columns: 12, gap_h: 2, gap_v: 2, outer: 4
#         key "A", span: 1, action: :code, value: "a", style: { radius: 6 }
#
#         # 一整顆送出組合鍵、上滑送符號、長按切換列
#         key :copy, label: "複製", action: :combo, value: "ctrl+c"
#         key "S", action: :code, value: "s", gestures: { swipe_up: "S" },
#             long_press: :toggle_hide_rows
#
#         # 在候選列加一段自訂符號群組（span 可與其他 slot 併用）
#         slot :punct, span: 2, gap: 0 do
#           [",", ".", "'", "["].each { |c| key c, weight: 1, action: :code, flat: true }
#         end
#
# ----------------------------------------------------------------------------
#  role:（決定 Android view id 與內建手勢，不影響外觀）
# ----------------------------------------------------------------------------
#         buffer  candidate_row  hide  space  space_alt  backspace  enter
#         dpad_up  dpad_down  dpad_left  dpad_right
#         select_all  copy  paste
#
# ----------------------------------------------------------------------------
#  ctx:（Keyboard.layout 的 block 參數，全部是字串）
# ----------------------------------------------------------------------------
#         mode(chinese/english)  theme(light/dark)  locale
#         input_type(text/number/phone/datetime/email/url/password)
#         ime_action  screen_w  screen_h  density  safe_bottom
# ============================================================================

Keyboard.layout version: 1 do |ctx|
  # ---- 版面格線與高度（HHKB 風格）------------------------------------------
  # 鍵與鍵留白 2dp，外緣留白 3dp，較寬鬆舒適
  grid columns: 12, gap_h: 2, gap_v: 2, outer: 3
  height bar: 48, body: 240
  padding left: 3, right: 3, bottom: 3
  min_touch 48

  # ---- 主題（HHKB 風格）---------------------------------------------------
  theme :light do
    color :bar, "#F5F5F5"
    color :key, "#E0E0E0"
    color :keyPressed, "#CCCCCC"
    color :ink, "#000000"
    color :muted, "#666666"
    color :seam, "#E0E0E0"
    color :keyBorder, "#999999"
    color :buffer, "#F5F5F5"
    color :bufferInk, "#000000"
    color :chip, "#E0E0E0"
    color :chipInk, "#000000"
    color :chipSelected, "#CCCCCC"
    color :chipSelectedInk, "#000000"
    color :chipMark, "#E0E0E0"
  end

  # ========================================================== 中文頁（預設頁）
  page :chinese do
    # 候選列：整體一體化設計
    # - 統一背景色，無外框
    # - 內部元素共用背景，無內部邊框
    # - 無分隔線，完全一體化
    bar height: 48, bg: :bar, gap: 0 do

      # 組字編碼框
      buffer weight: 2, role: :buffer, format: :uppercase, max_chars: 6

      # 候選字捲動列：無框線；選中者用粗體區分
      candidates weight: 9, role: :candidate_row

      # 隱藏鍵
      key :hide, weight: 1, role: :hide, label: "▼", desc: "隱藏鍵盤",
          action: :ime_hide, long_press: :toggle_hide_rows, ink: :muted, flat: true
    end

    rows do
      # 第 1 列：數字與符號，上滑送 shift 符號。
      row do
        %w[1 2 3 4 5 6 7 8 9 0 - =].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c,
              sub_label: "!@#$%^&*()_+"[i], gestures: { swipe_up: "!@#$%^&*()_+"[i] }
        end
      end

      # 第 2 列：q…p 與 [ ]；中文頁 transform 讓鍵面顯示大寫，值仍送小寫。
      # label_fit: :wrap 讓碼表 %keyname 的多字元標籤（例：a→ㄘㄟ、g→ㄍㄐㄜ）每 2 字換行。
      row do
        %w[ q w e r t y u i o p [ ] ].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c, transform: :upper, label_fit: :wrap,
              sub_label: "          {}"[i], gestures: { swipe_up: "QWERTYUIOP{}"[i] }
        end
      end

      # 第 3 列：a…l 與 ; ' \（; ' \ 是純文字，不進組字）。
      row do
        %w[ a s d f g h j k l ; ' \\ ].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c, transform: :upper, label_fit: :wrap,
              sub_label: "         :\"|"[i], gestures: { swipe_up: "ASDFGHJKL:\"|"[i] }
        end
      end

      # 第 4 列：z…m、, . / 與倒引號 `，最後一顆是刪除鍵（長按連發）。
      row do
        %w[ z x c v b n m , . / `].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c, transform: :upper, label_fit: :wrap,
              sub_label: "       <>?~"[i], gestures: { swipe_up: "ZXCVBNM<>?~"[i] }
        end
        key :backspace, weight: 1, role: :backspace, label: "⌫", desc: "刪除",
            action: :backspace, repeat: { delay: 400, interval: 80 }
      end

      # 第 5 列：中/英(2) + 空白(8) + ⏎(2)。空白鍵下滑切換中英、左右滑移游標。
      # 硬體鍵盤：Alt+Space 或 Shift+Space 切換中英（單純 Shift 不再切換）。
      row do
        key :toggle, span: 2, label: "中", desc: "切換中英（Alt+Space／Shift+Space）",
            action: :toggle_mode

        key :space_alt, span: 8, role: :space_alt, label: "", action: :space,
            desc: "空白（下滑切換中英）",
            gestures: { swipe_down: :toggle_mode,
                        swipe_left: :cursor_left,
                        swipe_right: :cursor_right }

        key :enter, span: 2, role: :enter, label: "⏎", desc: "輸入", action: :enter,
            gestures: { swipe_up: { action: :combo, value: "shift+enter" } }
      end
    end
  end

  # ========================================================== 英文頁
  page :english do
    # 候選列：整體一體化設計
    bar height: 48, bg: :bar, gap: 0, align: :space_between do
      # 窄格子裡的多字元動作鍵用 label_fit: :shrink 縮字級，保持一行不被裁切。
      key :tab,   label: "TAB", weight: 1, action: :key_event, value: "tab", flat: true,
          label_fit: :shrink
      key :blank, label: "",    weight: 1, action: :noop, flat: true
      key :c1,    label: "^A",  weight: 1, action: :clipboard, value: "select_all", flat: true
      key :c2,    label: "^C",  weight: 1, action: :clipboard, value: "copy", flat: true
      key :c3,    label: "^V",  weight: 1, action: :clipboard, value: "paste", flat: true
      key :up,    label: "⇧",   weight: 1, action: :key_event, value: "up", flat: true
      key :down,  label: "⇩",   weight: 1, action: :key_event, value: "down", flat: true
      key :left,  label: "⇦",   weight: 1, action: :key_event, value: "left", flat: true
      key :right, label: "⇨",   weight: 1, action: :key_event, value: "right", flat: true
      key :dt,    label: "DT",  weight: 1, action: :datetime, flat: true,
          label_fit: :shrink                                               # 例如 2026-11-04 14:35
      key :day,   label: "W",   weight: 1, action: :weekday, flat: true    # 例如 星期三（跟系統語系）

      key :hide, weight: 1, role: :hide, label: "▼", desc: "隱藏鍵盤",
          action: :ime_hide, long_press: :toggle_hide_rows, flat: true, ink: :muted
    end

    rows do
      row do
        %w[1 2 3 4 5 6 7 8 9 0 - =].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c,
              sub_label: "!@#$%^&*()_+"[i], gestures: { swipe_up: "!@#$%^&*()_+"[i] }
        end
      end

      # 英文頁不加 transform → 鍵面顯示小寫。
      row do
        %w[ q w e r t y u i o p [ ] ].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c,
              sub_label: "          {}"[i], gestures: { swipe_up: "QWERTYUIOP{}"[i] }
        end
      end

      row do
        %w[ a s d f g h j k l ; ' \\ ].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c,
              sub_label: "         :\"|"[i], gestures: { swipe_up: "ASDFGHJKL:\"|"[i] }
        end
      end

      row do
        %w[ z x c v b n m , . / `].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c,
              sub_label: "       <>?~"[i], gestures: { swipe_up: "ZXCVBNM<>?~"[i] }
        end
        key :backspace, weight: 1, role: :backspace, label: "⌫", desc: "刪除",
            action: :backspace, repeat: { delay: 400, interval: 80 }
      end

      # 第 5 列與中文頁相同，只是中/英鍵顯示「英」。
      row do
        key :toggle, span: 2, label: "英", desc: "切換中英（Alt+Space／Shift+Space）",
            action: :toggle_mode

        key :space_alt, span: 8, role: :space_alt, label: "", action: :space,
            desc: "空白（下滑切換中英）",
            gestures: { swipe_down: :toggle_mode,
                        swipe_left: :cursor_left,
                        swipe_right: :cursor_right }

        key :enter, span: 2, role: :enter, label: "⏎", desc: "輸入", action: :enter,
            gestures: { swipe_up: { action: :combo, value: "shift+enter" } }
      end
    end
  end
end
