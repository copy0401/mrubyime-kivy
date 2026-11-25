package com.example.mrubyime

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil

class MrubyimeSettingsActivity : Activity() {
    private companion object {
        const val REQ_DICT_IMPORT = 2001
        const val REQ_DICT_EXPORT = 2002
    }

    private lateinit var prefs: SharedPreferences

    private lateinit var cbHideKeyboard: CheckBox
    private lateinit var cbAutoCommit: CheckBox
    private lateinit var cbClearDeadCode: CheckBox
    private lateinit var cbMaxCodeClear: CheckBox

    private lateinit var rgInlineMode: RadioGroup

    private lateinit var rgDictList: RadioGroup
    private lateinit var tvDictInfo: TextView
    private lateinit var tvDictStatus: TextView

    private var isSyncingUi = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!::prefs.isInitialized || isSyncingUi) return@OnSharedPreferenceChangeListener
        when (key) {
            MrubyimeIME.KEY_HIDE_KEY_ROWS -> {
                isSyncingUi = true
                cbHideKeyboard.isChecked = prefs.getBoolean(key, false)
                isSyncingUi = false
            }
            MrubyimeIME.KEY_AUTO_COMMIT -> {
                isSyncingUi = true
                cbAutoCommit.isChecked = prefs.getBoolean(key, MrubyimeIME.DEFAULT_AUTO_COMMIT)
                isSyncingUi = false
            }
            MrubyimeIME.KEY_CLEAR_DEAD_CODE -> {
                isSyncingUi = true
                cbClearDeadCode.isChecked =
                    prefs.getBoolean(key, MrubyimeIME.DEFAULT_CLEAR_DEAD_CODE)
                isSyncingUi = false
            }
            MrubyimeIME.KEY_MAX_CODE_CLEAR -> {
                isSyncingUi = true
                cbMaxCodeClear.isChecked =
                    prefs.getBoolean(key, MrubyimeIME.DEFAULT_MAX_CODE_CLEAR)
                isSyncingUi = false
            }
            DictionaryRepository.KEY_DICT_ID -> {
                isSyncingUi = true
                refreshDictList()
                isSyncingUi = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(MrubyimeIME.PREFS_NAME, MODE_PRIVATE)
        bindViews()
        initListeners()
        syncValuesFromPrefs()
        refreshDictList()
        applyTheme()
        normalizeButtonWidths()

        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    private fun bindViews() {
        cbHideKeyboard = findViewById(R.id.cb_hide_keyboard)
        cbAutoCommit = findViewById(R.id.cb_auto_commit)
        cbClearDeadCode = findViewById(R.id.cb_clear_dead_code)
        cbMaxCodeClear = findViewById(R.id.cb_max_code_clear)
        rgInlineMode = findViewById(R.id.rg_inline_mode)

        rgDictList = findViewById(R.id.rg_dict_list)
        tvDictInfo = findViewById(R.id.tv_dict_info)
        tvDictStatus = findViewById(R.id.tv_dict_status)
    }

    private fun initListeners() {
        findViewById<Button>(R.id.btn_enable_ime).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btn_select_ime).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        cbHideKeyboard.setOnCheckedChangeListener { _, checked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(MrubyimeIME.KEY_HIDE_KEY_ROWS, checked).apply()
        }

        cbAutoCommit.setOnCheckedChangeListener { _, checked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(MrubyimeIME.KEY_AUTO_COMMIT, checked).apply()
        }

        cbClearDeadCode.setOnCheckedChangeListener { _, checked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(MrubyimeIME.KEY_CLEAR_DEAD_CODE, checked).apply()
        }

        cbMaxCodeClear.setOnCheckedChangeListener { _, checked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(MrubyimeIME.KEY_MAX_CODE_CLEAR, checked).apply()
        }

        rgInlineMode.setOnCheckedChangeListener { _, checkedId ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.rb_inline_code -> MrubyimeIME.INLINE_MODE_CODE
                R.id.rb_inline_candidate -> MrubyimeIME.INLINE_MODE_CANDIDATE
                R.id.rb_inline_candidate_code -> MrubyimeIME.INLINE_MODE_CANDIDATE_WITH_CODE
                R.id.rb_inline_code_upper -> MrubyimeIME.INLINE_MODE_CODE_UPPER
                R.id.rb_inline_candidate_code_upper -> MrubyimeIME.INLINE_MODE_CANDIDATE_WITH_CODE_UPPER
                R.id.rb_inline_code_fullwidth -> MrubyimeIME.INLINE_MODE_CODE_FULLWIDTH
                R.id.rb_inline_candidate_code_fullwidth -> MrubyimeIME.INLINE_MODE_CANDIDATE_WITH_CODE_FULLWIDTH
                else -> MrubyimeIME.INLINE_MODE_OFF
            }
            prefs.edit().putInt(MrubyimeIME.KEY_INLINE_COMPOSING_MODE, mode).apply()
        }

        // Dictionary (.cin)
        findViewById<Button>(R.id.btn_dict_import).setOnClickListener { launchDictImport() }
        findViewById<Button>(R.id.btn_dict_export).setOnClickListener { launchDictExport() }
        findViewById<Button>(R.id.btn_dict_reset).setOnClickListener { resetDictionary() }

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_DICT_IMPORT -> importDictFromUri(uri)
            REQ_DICT_EXPORT -> exportDictToUri(uri)
        }
    }

    // ------------------------------------------------------------ dictionary

    private fun launchDictImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_DICT_IMPORT)
    }

    private fun launchDictExport() {
        val id = DictionaryRepository.activeId(this)
        val base = when {
            id == DictionaryRepository.DEFAULT_ID -> "mrubyime"
            id.startsWith("builtin:") -> id.removePrefix("builtin:")
            else -> "active"
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // text/plain makes Downloads append its own ".txt" (mrubyime.cin.txt);
            // this MIME is unknown to MimeTypeMap, so the .cin name is kept as typed.
            type = "text/x-cin"
            putExtra(Intent.EXTRA_TITLE, "$base.cin")
        }
        startActivityForResult(intent, REQ_DICT_EXPORT)
    }

    private fun readBytesFromUri(uri: Uri, max: Int): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > max) null else bytes
        }
    } catch (t: Throwable) {
        null
    }

    private fun importDictFromUri(uri: Uri) {
        val bytes = readBytesFromUri(uri, CinTable.MAX_BYTES)
        if (bytes == null) {
            setDictStatus("✗ 匯入失敗：讀不到檔案，或檔案超過 4 MB")
            return
        }
        val info = CinTable.parse(bytes)
        if (info == null) {
            setDictStatus("✗ 匯入失敗：不是可用的 .cin（找不到 %chardef 或沒有條目）")
            return
        }
        if (!DictionaryRepository.saveUser(this, bytes)) {
            setDictStatus("✗ 匯入失敗：無法寫入碼表目錄")
            return
        }
        refreshDictList()
        setDictStatus(
            "✓ 已匯入 ${info.name}：${info.codes} 碼 / ${info.entries} 條 / " +
                "最長 ${info.maxCode} 碼 / 選字鍵 ${info.selkeys}"
        )
    }

    private fun exportDictToUri(uri: Uri) {
        val id = DictionaryRepository.activeId(this)
        val bytes = DictionaryRepository.read(this, id)
        if (bytes == null) {
            setDictStatus("✗ 匯出失敗：找不到目前的碼表")
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            setDictStatus("✓ 已匯出 ${bytes.size} bytes")
        } catch (t: Throwable) {
            setDictStatus("✗ 匯出失敗：${t.message}")
        }
    }

    private fun resetDictionary() {
        DictionaryRepository.restoreBuiltin(this)
        refreshDictList()
        setDictStatus("✓ 已恢復內建形碼碼表")
    }

    /** Rebuilds the radio list from the repository and checks the active entry. */
    private fun refreshDictList() {
        rgDictList.removeAllViews()
        val active = DictionaryRepository.activeId(this)
        for (entry in DictionaryRepository.list(this)) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = entry.name
                textSize = 15f
                tag = entry.id
                isChecked = entry.id == active
            }
            rb.setOnCheckedChangeListener { _, checked ->
                if (!checked || isSyncingUi) return@setOnCheckedChangeListener
                isSyncingUi = true
                DictionaryRepository.setActive(this, entry.id)
                isSyncingUi = false
                updateDictInfo()
                setDictStatus("✓ 已切換；回到鍵盤即生效")
            }
            rgDictList.addView(rb)
        }
        updateDictInfo()
        applyTheme()
    }

    private fun updateDictInfo() {
        val id = DictionaryRepository.activeId(this)
        val info = DictionaryRepository.read(this, id)?.let { CinTable.parse(it) }
        tvDictInfo.text = if (info == null) {
            "目前：$id（無法讀取）"
        } else {
            buildString {
                append("目前：").append(info.name).append('\n')
                append("編碼 ").append(info.codes).append(" 碼 · 候選 ").append(info.entries)
                append(" 條 · 最長 ").append(info.maxCode).append(" 碼 · 選字鍵 ")
                append(info.selkeys)
                append('\n').append("字根鍵：").append(info.keys)
            }
        }
    }

    private fun setDictStatus(text: String) {
        tvDictStatus.text = text
    }

    private fun readTextFromUri(uri: Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > KeyboardLayoutFiles.MAX_BYTES) null
            else bytes.toString(Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        null
    }

    private fun syncValuesFromPrefs() {
        isSyncingUi = true

        cbHideKeyboard.isChecked = prefs.getBoolean(MrubyimeIME.KEY_HIDE_KEY_ROWS, false)
        cbAutoCommit.isChecked = prefs.getBoolean(MrubyimeIME.KEY_AUTO_COMMIT, MrubyimeIME.DEFAULT_AUTO_COMMIT)
        cbClearDeadCode.isChecked =
            prefs.getBoolean(MrubyimeIME.KEY_CLEAR_DEAD_CODE, MrubyimeIME.DEFAULT_CLEAR_DEAD_CODE)
        cbMaxCodeClear.isChecked =
            prefs.getBoolean(MrubyimeIME.KEY_MAX_CODE_CLEAR, MrubyimeIME.DEFAULT_MAX_CODE_CLEAR)

        val inlineMode = prefs.getInt(MrubyimeIME.KEY_INLINE_COMPOSING_MODE, MrubyimeIME.DEFAULT_INLINE_MODE)
        val targetRbId = when (inlineMode) {
            MrubyimeIME.INLINE_MODE_CODE -> R.id.rb_inline_code
            MrubyimeIME.INLINE_MODE_CANDIDATE -> R.id.rb_inline_candidate
            MrubyimeIME.INLINE_MODE_CANDIDATE_WITH_CODE -> R.id.rb_inline_candidate_code
            MrubyimeIME.INLINE_MODE_CODE_UPPER -> R.id.rb_inline_code_upper
            MrubyimeIME.INLINE_MODE_CANDIDATE_WITH_CODE_UPPER -> R.id.rb_inline_candidate_code_upper
            MrubyimeIME.INLINE_MODE_CODE_FULLWIDTH -> R.id.rb_inline_code_fullwidth
            MrubyimeIME.INLINE_MODE_CANDIDATE_WITH_CODE_FULLWIDTH -> R.id.rb_inline_candidate_code_fullwidth
            else -> R.id.rb_inline_off
        }
        rgInlineMode.check(targetRbId)

        isSyncingUi = false
    }

    /**
     * Every button on the page shares one width (the widest label + its padding),
     * so stacked rows line up; the XML rows centre them. Measuring instead of
     * hard-coding dp keeps it correct under a non-default font scale.
     */
    private fun normalizeButtonWidths() {
        val ids = listOf(
            R.id.btn_enable_ime, R.id.btn_select_ime,
            R.id.btn_dict_import, R.id.btn_dict_export, R.id.btn_dict_reset
        )
        val density = resources.displayMetrics.density
        val buttons = ids.map { findViewById<Button>(it) }
        // Never wider than one column pair would allow on a narrow screen.
        val ceiling = (resources.displayMetrics.widthPixels - 96 * density).toInt()
        var width = (112 * density).toInt()
        buttons.forEach { button ->
            val need = ceil(button.paint.measureText(button.text.toString())).toInt() +
                button.paddingLeft + button.paddingRight
            if (need > width) width = need
        }
        width = width.coerceAtMost(ceiling)
        buttons.forEach { button ->
            button.layoutParams = button.layoutParams.apply { this.width = width }
            button.minWidth = width
        }
    }

    /**
     * Flat plate used for buttons and the test field. [radiusDp] rounds the
     * corners — buttons pass one, the test input field stays square.
     */
    private fun box(fill: Int, stroke: Int? = null, radiusDp: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            if (stroke != null) setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), stroke)
            if (radiusDp > 0f) cornerRadius = radiusDp * resources.displayMetrics.density
        }

    /**
     * Light Mode only (Dark mode removed). White canvas, black accent, light-grey plates.
     */
    private fun applyTheme() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val greySub = 0xFF666666.toInt()
        val barFill = 0xFFDDDDDD.toInt()

        findViewById<ScrollView>(R.id.settings_scroll)
            .setBackgroundColor(if (true) white else black)
        findViewById<LinearLayout>(R.id.settings_root)
            .setBackgroundColor(if (true) white else black)

        findViewById<TextView>(R.id.settings_title)
            .setTextColor(if (true) black else white)

        findViewById<TextView>(R.id.tv_system_hint)
            .setTextColor(greySub)

        // Sections are flat: no card plate, no frame, no accent bar — only spacing
        // and the section titles group them (by request).

        listOf(
            R.id.sec_system_title,
            R.id.sec_display_title,
            R.id.sec_inline_title,
            R.id.sec_dict_title
        ).forEach { id ->
            findViewById<TextView>(id).setTextColor(if (true) black else white)
        }

        listOf(
            R.id.rb_inline_off,
            R.id.rb_inline_code,
            R.id.rb_inline_candidate,
            R.id.rb_inline_candidate_code,
            R.id.rb_inline_code_upper,
            R.id.rb_inline_candidate_code_upper,
            R.id.rb_inline_code_fullwidth,
            R.id.rb_inline_candidate_code_fullwidth
        ).forEach { id ->
            findViewById<RadioButton>(id).apply {
                setTextColor(if (true) black else white)
                buttonTintList = ColorStateList.valueOf(if (true) black else white)
            }
        }

        listOf(tvDictInfo, tvDictStatus).forEach {
            it.setTextColor(greySub)
        }

        // Dynamically created .cin radio buttons: same ink as the inline radios.
        for (i in 0 until rgDictList.childCount) {
            val rb = rgDictList.getChildAt(i) as? RadioButton ?: continue
            rb.setTextColor(if (true) black else white)
            rb.buttonTintList = ColorStateList.valueOf(if (true) black else white)
        }

        // Every button shares one look (by request): outline plate only — white
        // plate + 1 dp black frame in Light Mode, clear plate + 1 dp white frame in
        // Dark Mode, with the theme ink. No filled/inverted CTAs, no per-key colour,
        // and the label sits on the left edge of every button.
        val btnRadius = 8f // dp — rounded plates for buttons only, not the text field
        val btnPadH = (16 * resources.displayMetrics.density).toInt()
        listOf(
            R.id.btn_enable_ime,
            R.id.btn_select_ime,
            R.id.btn_dict_import,
            R.id.btn_dict_export,
            R.id.btn_dict_reset
        ).forEach { id ->
            findViewById<Button>(id).apply {
                backgroundTintList = null
                background = box(white, black, btnRadius)
                setTextColor(black)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPaddingRelative(btnPadH, paddingTop, btnPadH, paddingBottom)
            }
        }

        listOf(cbHideKeyboard, cbAutoCommit, cbClearDeadCode, cbMaxCodeClear).forEach {
            it.setTextColor(black)
            it.buttonTintList = ColorStateList.valueOf(black)
        }

        findViewById<EditText>(R.id.et_test_input).apply {
            // The card plate is #1C1C1E, so the field needs its own lighter plate
            background = box(white, black)
            setTextColor(black)
            setHintTextColor(greySub)
            // Cursor colour follows the field's background so it stays visible.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val d = resources.displayMetrics.density
                val ink = black
                textCursorDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(ink)
                    setSize((2 * d).toInt(), (22 * d).toInt())
                }
            }
        }

        window.statusBarColor = white
        window.navigationBarColor = white
        var flags = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }
}
