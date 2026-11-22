字型放這裡（assets/fonts/）
=========================

鍵盤 DSL 的 `font: { family: "檔名" }` 會依序找：

  1. assets/fonts/<檔名>            ← 這個資料夾（打包進 APK，內建字型）
  2. filesDir/fonts/<檔名>          ← App 私有；用 root / run-as 放入
  3. Android/data/com.example.mrubyime/files/fonts/<檔名>
                                    ← 使用者可見，直接用檔案管理器放進去
  4. 以 "/" 開頭的絕對路徑

`family:` 可以寫 `NotoSansCJK`、`NotoSansCJK.ttf` 或 `NotoSansCJK.otf`
（沒寫副檔名時會自動試 .ttf / .otf）。找不到就退回系統預設字型，
logcat 會出現 `MrubyimeFont: font '...' not found`。

範例（layout DSL）：

    page :chinese do
      bar height: 48 do
        buffer weight: 2, font: { size: 24, family: "NotoSansCJK" }
        candidates weight: 9, font: { size: 24, family: "NotoSansCJK" }
      end
      rows do
        row do
          key "日", font: { size: 18, family: "NotoSansCJK" }
        end
      end
    end

App 啟動時會自動建立：
  /data/user/0/com.example.mrubyime/files/fonts/
  /sdcard/Android/data/com.example.mrubyime/files/fonts/

（這個 assets/fonts/ 資料夾本身不會自動建立到裝置上；它是 APK 內的內建字型。）
