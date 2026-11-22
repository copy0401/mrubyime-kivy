# ============================================================================
#  小蝦米輸入法 ─ 鍵盤佈局範本（Keyboard DSL v1）
# ============================================================================
#
#  這是「可直接匯入」的最小起點：12 欄、5 列、中／英各一頁、淺色＋深色。
#  改完存成 .rb，在 App 裡：
#      設定 → 鍵盤佈局（Ruby DSL）→ 匯入 DSL…
#  就會存成 layouts/keyboard.rb 並熱載入，不需要重新編譯 APK。
#
#  完整選項、顏色 token、動作、常見錯誤請看 docs/LAYOUT_DSL.md。
#  裝置端是 mruby 3.3：不要用 Regexp、eval、require 或外部 gem。
#
#  版面總覽（12 欄；鍵與鍵、列與列之間 gap 全 0，只留外緣 3 dp）
#    第 1 列   1 2 3 4 5 6 7 8 9 0 - =      上滑 → ! @ # $ % ^ & * ( ) _ +
#    第 2 列   q w e r t y u i o p [ ]      上滑 → Q…P ／ { }
#    第 3 列   a s d f g h j k l ; ' \      上滑 → A…L ／ : " |
#    第 4 列   z x c v b n m , . / ` ⌫      上滑 → Z…M ／ < > ? ~
#    第 5 列   中/英(2) + 空白(8) + ⏎(2)
#
#    中文頁：字母鍵顯示大寫（transform: :upper）；候選列 = 編碼 2 : 候選 9 : ▼ 1
#    英文頁：字母鍵顯示小寫；候選列換成 12 顆等寬動作鍵
# ============================================================================

Keyboard.layout version: 1 do |ctx|
  # ---- 格線、高度、外距 -----------------------------------------------------
  grid columns: 12, gap_h: 0, gap_v: 0, outer: 0
  height bar: 48, body: 240
  padding left: 0, right: 0, bottom: 0

  # ---- 淺色（預設，電子紙）：純白底黑字，1 dp 黑色按鍵框 -------------------
  # keyBorder 非空 → 淺色模式畫分邊框線；相鄰鍵共用一條線，不會疊成 2 dp。
  theme :light do
    color :bar, "#FFFFFF"
    color :key, "#FFFFFF"
    color :keyPressed, "#FFFFFF"
    color :keyBorder, "#000000"
    color :ink, "#000000"
    color :seam, "#FFFFFF"
    color :muted, "#000000"
  end

  # ---- 深色：深灰底白字，按鍵用按下變色（不給 keyBorder） -------------------
  theme :dark do
    color :bar, "#3A3A3C"
    color :key, "#3A3A3C"
    color :keyPressed, "#505054"
    color :ink, "#FFFFFF"
    color :muted, "#8E8E93"
    color :seam, "#1C1C1E"
  end

  # ============================================================ 中文頁（第一頁）
  page :chinese do
    # 候選列：上緣 1 dp 墨線；buffer 2 : candidates 9 : ▼ 1 = 12 單位。
    # bar border: 是內縮繪製，不佔版面、不會被 IME 視窗裁掉。
    bar height: 48, bg: :bar, gap: 0,
        border: { width: 1, color: :ink, style: :solid, side: :top } do

      # 編碼區：format: :uppercase 讓 aad 顯示成 AAD；max_chars: 6 只顯示後 6 碼。
      buffer weight: 2, role: :buffer, format: :uppercase, max_chars: 6,
             font: { size: 24, weight: :bold }

      # 候選字：未選中一圈點線框；選中 2 dp 墨色底線。
      candidates weight: 9, role: :candidate_row,
                 border: { width: 1, color: :seam, style: :dotted },
                 border_selected: { width: 2, color: :ink, style: :solid, side: :bottom }

      # 隱藏鍵：點＝收鍵盤；長按＝切換「只留候選列」。
      key :hide, weight: 1, role: :hide, label: "▼", desc: "隱藏鍵盤",
          action: :ime_hide, long_press: :toggle_hide_rows, ink: :muted, flat: true
    end

    rows do
      # 第 1 列：數字與符號；sub_label 與上滑送出的字元保持一致。
      row do
        %w[1 2 3 4 5 6 7 8 9 0 - =].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c,
              sub_label: "!@#$%^&*()_+"[i],
              gestures: { swipe_up: "!@#$%^&*()_+"[i] }
        end
      end

      # 第 2 列：q…p 與 [ ]。中文頁 transform 讓鍵面顯示大寫，值仍送小寫。
      # label_fit: :wrap 讓碼表 %keyname 的多字元標籤（例：g→ㄍㄐㄜ）每 2 字換行。
      row do
        %w[ q w e r t y u i o p [ ] ].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c, transform: :upper,
              label_fit: :wrap,
              sub_label: "          {}"[i],
              gestures: { swipe_up: "QWERTYUIOP{}"[i] }
        end
      end

      # 第 3 列：a…l 與 ; ' \（; ' \ 是純文字，不進組字）。
      row do
        %w[ a s d f g h j k l ; ' \\ ].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c, transform: :upper,
              label_fit: :wrap,
              sub_label: "         :\"|"[i],
              gestures: { swipe_up: "ASDFGHJKL:\"|"[i] }
        end
      end

      # 第 4 列：z…m、, . / 與倒引號 `，最後一顆是刪除鍵（長按連發）。
      row do
        %w[ z x c v b n m , . / `].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c, transform: :upper,
              label_fit: :wrap,
              sub_label: "       <>?~"[i],
              gestures: { swipe_up: "ZXCVBNM<>?~"[i] }
        end
        key :backspace, weight: 1, role: :backspace, label: "⌫", desc: "刪除",
            action: :backspace, repeat: { delay: 400, interval: 80 }
      end

      # 第 5 列：中/英(2) + 空白(8) + ⏎(2)。
      # 空白鍵下滑切換中英；硬體 Alt+Space／Shift+Space 也會切換。
      row do
        key :toggle, span: 2, label: "中", desc: "切換中英（Alt+Space／Shift+Space）",
            action: :toggle_mode

        key :space_alt, span: 8, role: :space_alt, label: "", action: :space,
            desc: "空白（下滑切換中英）",
            gestures: { swipe_down: :toggle_mode }

        key :enter, span: 2, role: :enter, label: "⏎", desc: "輸入", action: :enter,
            gestures: { swipe_up: { action: :combo, value: "shift+enter" } }
      end
    end
  end

  # ============================================================ 英文頁
  page :english do
    # 候選列換成 12 顆等寬動作鍵；align 讓 12 顆平均撐滿。
    bar height: 48, bg: :bar, gap: 0, align: :space_between,
        border: { width: 1, color: :ink, style: :solid, side: :top } do

      # 窄格子裡的多字元動作鍵用 label_fit: :shrink 縮字級，保持一行。
      key :tab,   label: "TAB", weight: 1, action: :key_event, value: "tab",
          flat: true, label_fit: :shrink
      key :blank, label: "",    weight: 1, action: :noop, flat: true
      key :c1,    label: "^A",  weight: 1, action: :clipboard, value: "select_all", flat: true
      key :c2,    label: "^C",  weight: 1, action: :clipboard, value: "copy", flat: true
      key :c3,    label: "^V",  weight: 1, action: :clipboard, value: "paste", flat: true
      key :up,    label: "⇧",   weight: 1, action: :key_event, value: "up", flat: true
      key :down,  label: "⇩",   weight: 1, action: :key_event, value: "down", flat: true
      key :left,  label: "⇦",   weight: 1, action: :key_event, value: "left", flat: true
      key :right, label: "⇨",   weight: 1, action: :key_event, value: "right", flat: true
      key :dt,    label: "DT",  weight: 1, action: :datetime,
          flat: true, label_fit: :shrink
      key :day,   label: "W",   weight: 1, action: :weekday, flat: true

      key :hide, weight: 1, role: :hide, label: "▼", desc: "隱藏鍵盤",
          action: :ime_hide, long_press: :toggle_hide_rows, flat: true, ink: :muted
    end

    rows do
      row do
        %w[1 2 3 4 5 6 7 8 9 0 - =].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c,
              sub_label: "!@#$%^&*()_+"[i],
              gestures: { swipe_up: "!@#$%^&*()_+"[i] }
        end
      end

      # 英文頁不加 transform → 鍵面顯示小寫。
      row do
        %w[ q w e r t y u i o p [ ] ].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c,
              sub_label: "          {}"[i],
              gestures: { swipe_up: "QWERTYUIOP{}"[i] }
        end
      end

      row do
        %w[ a s d f g h j k l ; ' \\ ].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c,
              sub_label: "         :\"|"[i],
              gestures: { swipe_up: "ASDFGHJKL:\"|"[i] }
        end
      end

      row do
        %w[ z x c v b n m , . / `].each_with_index do |c, i|
          key c, weight: 1, action: :code, value: c,
              sub_label: "       <>?~"[i],
              gestures: { swipe_up: "ZXCVBNM<>?~"[i] }
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
            gestures: { swipe_down: :toggle_mode }

        key :enter, span: 2, role: :enter, label: "⏎", desc: "輸入", action: :enter,
            gestures: { swipe_up: { action: :combo, value: "shift+enter" } }
      end
    end
  end
end
