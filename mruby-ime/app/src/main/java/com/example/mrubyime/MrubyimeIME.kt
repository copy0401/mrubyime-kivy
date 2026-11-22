package com.example.mrubyime

import android.content.SharedPreferences
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.hardware.display.DisplayManager
import android.os.Build
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import kotlin.math.roundToInt

class MrubyimeIME : InputMethodService() {
    companion object {
        const val PREFS_NAME = "mrubyime_settings"

        const val KEY_HIDE_KEY_ROWS = "hide_virtual_keyboard"

        const val KEY_CANDIDATE_BAR_HEIGHT = "candidate_bar_height"
        const val KEY_KEYBOARD_BODY_HEIGHT = "keyboard_body_height"
        const val KEY_KEYBOARD_BOTTOM_PADDING = "keyboard_bottom_padding"
        const val KEY_KEYBOARD_LEFT_PADDING = "keyboard_left_padding"
        const val KEY_KEYBOARD_RIGHT_PADDING = "keyboard_right_padding"

        const val KEY_CANDIDATE_FONT_SIZE = "candidate_font_size"
        const val KEY_KEY_FONT_SIZE = "key_font_size"
        const val KEY_BUFFER_WIDTH_WEIGHT = "buffer_width_weight"
        const val KEY_INLINE_COMPOSING_MODE = "inline_composing_mode"
        const val KEY_AUTO_COMMIT = "auto_commit"
        const val KEY_CLEAR_DEAD_CODE = "clear_dead_code"
        const val KEY_MAX_CODE_CLEAR = "max_code_clear"

        const val INLINE_MODE_OFF = 0
        const val INLINE_MODE_CODE = 1
        const val INLINE_MODE_CANDIDATE = 2
        const val INLINE_MODE_CANDIDATE_WITH_CODE = 3
        /** Uppercase input code, e.g. `AAD`. */
        const val INLINE_MODE_CODE_UPPER = 4
        /** Selected candidate + uppercase code, e.g. `微 AAD`. */
        const val INLINE_MODE_CANDIDATE_WITH_CODE_UPPER = 5
        /** Fullwidth uppercase code, e.g. `ＡＡＤ`. */
        const val INLINE_MODE_CODE_FULLWIDTH = 6
        /** Selected candidate + fullwidth uppercase code, e.g. `微 ＡＡＤ`. */
        const val INLINE_MODE_CANDIDATE_WITH_CODE_FULLWIDTH = 7

        const val DEFAULT_AUTO_COMMIT = false
        const val DEFAULT_CLEAR_DEAD_CODE = false
        const val DEFAULT_MAX_CODE_CLEAR = false

        /** Default inline composing display: input code (1). */
        const val DEFAULT_INLINE_MODE = INLINE_MODE_CODE

        /**
         * Max code length when 「最大碼清空組字」 is off: effectively unlimited.
         * When it is on, the active table's own longest code is used instead.
         */
        const val MAX_CODE_LONG = 140

        /**
         * Composition gets slower with every code in the buffer. Anything under
         * ~16 ms is hidden by the key-press animation, so only a slow resolve is
         * worth a log line (it is the signal for adding a parse cache / beam).
         */
        private const val SLOW_INPUT_MS = 30L

        const val DEFAULT_CANDIDATE_BAR_HEIGHT = 48
        const val DEFAULT_KEYBOARD_BODY_HEIGHT = 240
        const val DEFAULT_KEYBOARD_BOTTOM_PADDING = 0
        const val DEFAULT_KEYBOARD_LEFT_PADDING = 0
        const val DEFAULT_KEYBOARD_RIGHT_PADDING = 0

        const val DEFAULT_CANDIDATE_FONT_SIZE = 24
        const val DEFAULT_KEY_FONT_SIZE = 18
        const val DEFAULT_BUFFER_WIDTH_WEIGHT = 20 // 2.0

        /**
         * While true the code box / candidate split comes from the layout DSL
         * (`buffer weight:` / `candidates weight:`); the 編碼區寬度 slider only
         * takes over once the user actually moves it.
         */
        const val KEY_BUFFER_WIDTH_FOLLOW = "buffer_width_follow_layout"
        const val DEFAULT_BUFFER_WIDTH_FOLLOW = true

        const val MIN_CANDIDATE_BAR_HEIGHT = 28
        const val MAX_CANDIDATE_BAR_HEIGHT = 120

        const val MIN_KEYBOARD_BODY_HEIGHT = 100
        const val MAX_KEYBOARD_BODY_HEIGHT = 800

        const val MIN_KEYBOARD_PADDING = 0
        const val MAX_KEYBOARD_PADDING = 300
        const val MAX_KEYBOARD_BOTTOM_PADDING = 250

        const val MIN_CANDIDATE_FONT_SIZE = 10
        const val MAX_CANDIDATE_FONT_SIZE = 48

        const val MIN_KEY_FONT_SIZE = 10
        const val MAX_KEY_FONT_SIZE = 36

        const val MIN_BUFFER_WIDTH_WEIGHT = 5  // 0.5
        const val MAX_BUFFER_WIDTH_WEIGHT = 30 // 3.0
    }

    private var candidates: Array<String> = emptyArray()
    private var selkeys: String = "0123456789"
    private var selectedCandidateIndex: Int = 0
    private var isEnglishMode: Boolean = false

    // Active .cin table and the metadata derived from it. Everything below is
    // table-driven so 注音/倉頡/行列/大易/五筆… do not need Kotlin changes.
    private var dictId: String = DictionaryRepository.DEFAULT_ID
    private var dictName: String = "嘸蝦米"
    private var dictKeynames: String = ""
    private var dictLegalKeys: String = "abcdefghijklmnopqrstuvwxyz,.'[]"
    private var dictMaxCode: Int = 5
    private var dictSignature: String = ""

    private val engine: MrubyimeEngineDelegate = MrubyimeEngineDelegateImpl()
    private lateinit var keyboardView: KeyboardView

    /** Signature of the layout source the current view was built from (see [reloadLayoutIfChanged]). */
    private var layoutSignature: String? = null

    /**
     * True once the background engine build finished. Every path that touches
     * the mruby state (typing, metadata, layout DSL) checks this first, so the
     * main thread never races the state swap inside the JNI bridge.
     */
    @Volatile private var engineReady: Boolean = false
    @Volatile private var serviceDestroyed: Boolean = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Survives `onCreate`/`onDestroy`: the parsed table lives in the JNI bridge
     * (a C static `mrb_state`), and `onDestroy` never tears it down. When the
     * service is re-created in the same process — switching to another IME and
     * back, which is exactly when the user notices the wait — the already
     * parsed table is reused instead of paying the ~1.2 s mruby parse again.
     * Only an exact `dict_id` + signature match is trusted, and a new process
     * starts with an empty cache.
     */
    private object EngineCache {
        @Volatile private var cachedId: String? = null
        @Volatile private var cachedSignature: String? = null

        fun matches(id: String, signature: String): Boolean =
            cachedId != null && cachedId == id && cachedSignature == signature

        fun record(id: String, signature: String) {
            cachedId = id
            cachedSignature = signature
        }

        fun clear() {
            cachedId = null
            cachedSignature = null
        }
    }

    /** Live reaction to the settings toggle, even while the keyboard is open. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!::keyboardView.isInitialized) return@OnSharedPreferenceChangeListener
        when (key) {
            KEY_HIDE_KEY_ROWS -> applyKeyboardSize()
            KEY_BUFFER_WIDTH_WEIGHT,
            KEY_BUFFER_WIDTH_FOLLOW -> applyBufferWidthWeight()
            KEY_INLINE_COMPOSING_MODE -> updateBufferAndCandidatesView()
            KEY_MAX_CODE_CLEAR -> applyMaxCodeLength()
            DictionaryRepository.KEY_DICT_ID -> reloadDictionary()
            // Size / bar height / font prefs are ignored: the layout DSL owns them.
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("MrubyimeIME", "onCreate")
        FontStore.ensureDirs(this)
        try {
            // Cheap id + signature only: the 1.7 MB table itself is read on the
            // engine-load thread (see startEngineLoad), so service creation does
            // not block on storage.
            dictId = DictionaryRepository.activeId(this)
            dictSignature = DictionaryRepository.signature(this)
            if (!tryReuseEngine()) startEngineLoad()
        } catch (e: Throwable) {
            Log.e("MrubyimeIME", "Error initializing IME", e)
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
    }

    /**
     * Reuses a table that a previous instance of this service already parsed in
     * the same process (the JNI engine is a C static that `onDestroy` leaves
     * alone). Switching to another input method and back would otherwise rebuild
     * the whole 1.7 MB table in mruby (~1.0 s). Returns false when there is
     * nothing trustworthy to reuse, in which case [startEngineLoad] runs.
     */
    private fun tryReuseEngine(): Boolean {
        if (!EngineCache.matches(dictId, dictSignature)) return false
        return try {
            engineReady = true
            // Never inherit the previous field's half-typed code.
            engine.reset()
            candidates = emptyArray()
            selectedCandidateIndex = 0
            applyDictionaryMeta()
            applyMaxCodeLength()
            Log.i(
                "MrubyimeIME",
                "engine reused (same process) id=$dictId name=$dictName " +
                    "codes=${engine.getTableSize()} load=0ms"
            )
            true
        } catch (t: Throwable) {
            Log.e("MrubyimeIME", "engine reuse failed, rebuilding", t)
            EngineCache.clear()
            engineReady = false
            false
        }
    }

    /**
     * Loads the table and builds the engine on a background thread. Nothing else
     * touches the mruby state while [engineReady] is false, so the JNI state
     * swap is never racing a read from the main thread.
     */
    private fun startEngineLoad() {
        engineReady = false
        val initialId = dictId
        Thread({
            val t0 = android.os.SystemClock.elapsedRealtime()
            var id = initialId
            try {
                val loaded = DictionaryRepository.loadActive(this)
                if (loaded == null) {
                    Log.e("MrubyimeIME", "No .cin dictionary available")
                } else {
                    id = loaded.first
                    buildEngine(loaded.second)
                }
            } catch (t: Throwable) {
                Log.e("MrubyimeIME", "engine load failed", t)
            }
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            mainHandler.post { onEngineReady(ms, id) }
        }, "engine-load").start()
    }

    /** Main-thread continuation of [startEngineLoad] once the build finished. */
    private fun onEngineReady(loadMs: Long, id: String) {
        if (serviceDestroyed) return
        engineReady = true
        dictId = id
        // The background build may have just seeded `dicts/mrubyime.cin`, which
        // changes the table's signature — re-read it so the next keyboard show
        // does not mistake that for an external edit and reload the table.
        dictSignature = DictionaryRepository.signature(this)
        EngineCache.record(dictId, dictSignature)
        applyDictionaryMeta()
        applyMaxCodeLength()
        Log.i(
            "MrubyimeIME",
            "dictionary id=$dictId name=$dictName codes=${engine.getTableSize()} " +
                "entries=${engine.entryCount()} max=$dictMaxCode selkeys=$selkeys"
        )
        logLayoutDiagnostics()
        if (::keyboardView.isInitialized) {
            // The initial view used the build-time JSON snapshot because the
            // engine was still loading; rebuild it from the runtime DSL (and
            // apply a user layout that was skipped) now that it is ready.
            keyboardView.setDictLabels(dictKeyLabels())
            keyboardView.setLayout(loadKeyboardLayout())
            applyThemePref()
            applyKeyboardSize()
            applyBufferWidthWeight()
            updateBufferAndCandidatesView()
        }
    }

    /**
     * Pulls table metadata (name, legal keys, key caps, max code) from mruby.
     * Everything is table-driven, so 注音/倉頡/行列/大易/五筆… need no Kotlin
     * change. The `%selkey`-vs-radical conflict is resolved at press time in
     * [onKeyPressed] using the engine's prefix check, not by remapping keys.
     */
    private fun applyDictionaryMeta() {
        if (!engineReady) return
        val sk = engine.getSelkeys()
        if (sk.isNotEmpty()) selkeys = sk.lowercase()
        dictKeynames = engine.keynamesSerialized()
        dictLegalKeys = engine.legalKeys().ifEmpty { "abcdefghijklmnopqrstuvwxyz,.'[]" }
        dictMaxCode = engine.tableMaxCodeLen().coerceAtLeast(1)
        dictName = engine.dictionaryName().ifEmpty { "自訂碼表" }
    }

    /** Hot-swaps the table when the user picks another one in Settings. */
    private fun reloadDictionary() {
        if (!engineReady) return
        val loaded = DictionaryRepository.loadActive(this) ?: return
        dictId = loaded.first
        engine.reloadDictionary(loaded.second)
        if (engine.getTableSize() <= 0) {
            // A broken table (usually a truncated `dicts/mrubyime.cin` the user
            // edited) must not brick typing: fall back to the shipped asset
            // explicitly — re-reading the same id would just return it again.
            Log.e("MrubyimeIME", "reload failed for $dictId, reverting to the shipped built-in")
            dictId = DictionaryRepository.DEFAULT_ID
            DictionaryRepository.setActive(this, DictionaryRepository.DEFAULT_ID)
            DictionaryRepository.assetBytes(this)?.let { engine.reloadDictionary(it) }
        }
        applyDictionaryMeta()
        applyMaxCodeLength()
        dictSignature = DictionaryRepository.signature(this)
        EngineCache.record(dictId, dictSignature)
        candidates = emptyArray()
        selectedCandidateIndex = 0
        if (::keyboardView.isInitialized) {
            keyboardView.setDictLabels(dictKeyLabels())
            updateBufferAndCandidatesView()
        }
        Log.i("MrubyimeIME", "dictionary switched to id=$dictId name=$dictName codes=${engine.getTableSize()}")
    }

    /**
     * Builds the engine from the compiled bytecode (`engine_irep.c`) with the
     * given table. Runs on the `engine-load` background thread (see
     * [startEngineLoad]).
     */
    private fun buildEngine(dict: ByteArray) {
        engine.initDictionary(dict)
        // Seeding/hashing the editable table copy comes AFTER the table is
        // live: it is pure convenience IO and used to delay every cold start.
        DictionaryRepository.seedBuiltinCopy(this)
    }

    /** `%keyname` labels for the keyboard; the built-in 嘸蝦米 table keeps A-Z. */
    private fun dictKeyLabels(): Map<String, String> {
        if (dictId == DictionaryRepository.DEFAULT_ID) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in dictKeynames.lineSequence()) {
            val i = line.indexOf('\t')
            if (i in 1 until line.length - 1) out[line.substring(0, i)] = line.substring(i + 1)
        }
        return out
    }

    override fun onDestroy() {
        Log.i("MrubyimeIME", "onDestroy")
        serviceDestroyed = true
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private val interactionListener = object : KeyboardView.KeyboardInteractionListener {
        override fun onKey(ch: Char) = onKeyPressed(ch)
        override fun onBackspace() = onBackspacePressed()
        override fun onSpace() = onSpacePressed()
        override fun onEnter() = onEnterPressed()
        override fun onToggleMode() {
            // Qualify explicitly: KeyboardView has same-named properties, and an
            // unqualified assignment would silently target the view instead.
            this@MrubyimeIME.isEnglishMode = !this@MrubyimeIME.isEnglishMode
            Log.d("MrubyimeIME", "onToggleMode executed! isEnglishMode=$isEnglishMode")
            applyModeSwitch()
        }
        override fun onHideKeyboard() {
            // A half-finished code (`aad` with candidates, `pns` of 你好) used to
            // stay behind as an inline composing region after the keyboard went
            // away, and the next field inherited that stale composition. Drop it
            // first (it is NOT committed), then hide.
            clearCompositionBeforeHide()
            requestHideSelf(0)
        }
        override fun onToggleHideKeyRows() {
            // Long-press on ▼ flips the "隱藏虛擬鍵盤" preference; the
            // SharedPreferences listener below resizes the keyboard live.
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val next = !prefs.getBoolean(KEY_HIDE_KEY_ROWS, false)
            Log.d("MrubyimeIME", "onToggleHideKeyRows -> $next")
            prefs.edit().putBoolean(KEY_HIDE_KEY_ROWS, next).apply()
        }
        override fun onCommitBuffer() {
            if (!engineReady) return
            val buf = engine.getBuffer()
            if (buf.isNotEmpty()) commit(buf)
        }
        /** Swipe up on the code box: commit exactly what the inline display shows. */
        override fun onCommitInlineComposing() {
            if (!engineReady) return
            val buf = engine.getBuffer()
            if (buf.isEmpty()) return
            val inline = inlineComposingText(buf)
            commit(if (inline.isNotEmpty()) inline else buf)
        }
        override fun onCandidateSelected(index: Int, candidate: String) {
            this@MrubyimeIME.selectedCandidateIndex = index
            commit(candidate)
        }
        override fun onMoveCursor(forward: Boolean) {
            sendDownUpKeyEvents(
                if (forward) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            )
        }
        override fun onDpad(keyCode: Int) {
            sendDownUpKeyEvents(keyCode)
        }
        override fun onSelectAll() {
            currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
        }
        override fun onCopy() {
            currentInputConnection?.performContextMenuAction(android.R.id.copy)
        }
        override fun onPaste() {
            currentInputConnection?.performContextMenuAction(android.R.id.paste)
        }
        override fun onKeyCombo(spec: String) = sendKeyCombo(spec)
        override fun onCommitText(text: String) = commit(text)
    }

    /**
     * `combo` DSL action: "ctrl+shift+1", "alt+tab", "ctrl+a" … Sends a DOWN/UP
     * key event with the requested meta state to the editor.
     */
    /**
     * `combo` DSL action: one or more segments separated by `|`, sent in order.
     * A segment is either a key combo ("ctrl+shift+1", "alt+tab", "f5") or a
     * literal insertion ("text:hello").
     */
    private fun sendKeyCombo(spec: String) {
        for (segment in spec.split("|")) {
            val part = segment.trim()
            if (part.isEmpty()) continue
            if (part.startsWith("text:", ignoreCase = true)) {
                val text = part.substring(5)
                if (text.isNotEmpty()) commit(text)
            } else {
                sendSingleCombo(part)
            }
        }
    }

    private fun sendSingleCombo(spec: String) {
        var meta = 0
        var keyCode = 0
        var modifierKey = 0
        for (raw in spec.split("+")) {
            when (val part = raw.trim().lowercase()) {
                "" -> Unit
                "ctrl", "control" -> {
                    meta = meta or KeyEvent.META_CTRL_ON
                    modifierKey = KeyEvent.KEYCODE_CTRL_LEFT
                }
                "shift" -> {
                    meta = meta or KeyEvent.META_SHIFT_ON
                    modifierKey = KeyEvent.KEYCODE_SHIFT_LEFT
                }
                "alt" -> {
                    meta = meta or KeyEvent.META_ALT_ON
                    modifierKey = KeyEvent.KEYCODE_ALT_LEFT
                }
                "meta", "win", "super", "cmd" -> {
                    meta = meta or KeyEvent.META_META_ON
                    modifierKey = KeyEvent.KEYCODE_META_LEFT
                }
                else -> keyCodeFor(part)?.let { keyCode = it }
            }
        }
        // A lone modifier ("ctrl") is sent as a physical modifier key tap.
        if (keyCode == 0) keyCode = modifierKey
        val ic = currentInputConnection ?: return
        if (keyCode == 0) {
            Log.w("MrubyimeIME", "unknown combo '$spec'")
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    /** Maps a DSL combo token to an Android key code. */
    private fun keyCodeFor(token: String): Int? {
        if (token.length == 1) {
            val c = token[0]
            return when {
                c in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
                c in '0'..'9' -> if (c == '0') KeyEvent.KEYCODE_0 else KeyEvent.KEYCODE_1 + (c - '1')
                c == ',' -> KeyEvent.KEYCODE_COMMA
                c == '.' -> KeyEvent.KEYCODE_PERIOD
                c == '/' -> KeyEvent.KEYCODE_SLASH
                c == ';' -> KeyEvent.KEYCODE_SEMICOLON
                c == '\'' -> KeyEvent.KEYCODE_APOSTROPHE
                c == '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
                c == ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
                c == '-' -> KeyEvent.KEYCODE_MINUS
                c == '=' -> KeyEvent.KEYCODE_EQUALS
                c == '\\' -> KeyEvent.KEYCODE_BACKSLASH
                c == '`' -> KeyEvent.KEYCODE_GRAVE
                c == ' ' -> KeyEvent.KEYCODE_SPACE
                c == '*' -> KeyEvent.KEYCODE_STAR
                c == '#' -> KeyEvent.KEYCODE_POUND
                c == '@' -> KeyEvent.KEYCODE_AT
                c == '+' -> KeyEvent.KEYCODE_PLUS
                else -> null
            }
        }
        return when (token) {
            "space" -> KeyEvent.KEYCODE_SPACE
            "enter", "return" -> KeyEvent.KEYCODE_ENTER
            "tab" -> KeyEvent.KEYCODE_TAB
            "esc", "escape" -> KeyEvent.KEYCODE_ESCAPE
            "backspace" -> KeyEvent.KEYCODE_DEL
            "comma" -> KeyEvent.KEYCODE_COMMA
            "period", "dot" -> KeyEvent.KEYCODE_PERIOD
            "slash" -> KeyEvent.KEYCODE_SLASH
            "semicolon" -> KeyEvent.KEYCODE_SEMICOLON
            "apostrophe", "quote" -> KeyEvent.KEYCODE_APOSTROPHE
            "leftbracket", "lbracket" -> KeyEvent.KEYCODE_LEFT_BRACKET
            "rightbracket", "rbracket" -> KeyEvent.KEYCODE_RIGHT_BRACKET
            "minus", "dash", "hyphen" -> KeyEvent.KEYCODE_MINUS
            "equal", "equals" -> KeyEvent.KEYCODE_EQUALS
            "backslash" -> KeyEvent.KEYCODE_BACKSLASH
            "grave", "backtick" -> KeyEvent.KEYCODE_GRAVE
            "plus" -> KeyEvent.KEYCODE_PLUS
            "star" -> KeyEvent.KEYCODE_STAR
            "at" -> KeyEvent.KEYCODE_AT
            "hash", "pound" -> KeyEvent.KEYCODE_POUND
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "home" -> KeyEvent.KEYCODE_MOVE_HOME
            "end" -> KeyEvent.KEYCODE_MOVE_END
            "ins", "insert" -> KeyEvent.KEYCODE_INSERT
            "del", "delete", "forward_del" -> KeyEvent.KEYCODE_FORWARD_DEL
            "pageup", "pgup", "page_up" -> KeyEvent.KEYCODE_PAGE_UP
            "pagedown", "pgdn", "page_down" -> KeyEvent.KEYCODE_PAGE_DOWN
            "caps", "capslock", "caps_lock" -> KeyEvent.KEYCODE_CAPS_LOCK
            "menu" -> KeyEvent.KEYCODE_MENU
            "numlock", "num_lock" -> KeyEvent.KEYCODE_NUM_LOCK
            "scrolllock", "scroll_lock" -> KeyEvent.KEYCODE_SCROLL_LOCK
            "printscreen", "prtsc" -> KeyEvent.KEYCODE_SYSRQ
            "pause", "break" -> KeyEvent.KEYCODE_BREAK
            "ctrl", "control" -> KeyEvent.KEYCODE_CTRL_LEFT
            "shift" -> KeyEvent.KEYCODE_SHIFT_LEFT
            "alt" -> KeyEvent.KEYCODE_ALT_LEFT
            "meta", "win", "super", "cmd" -> KeyEvent.KEYCODE_META_LEFT
            "volup", "volume_up" -> KeyEvent.KEYCODE_VOLUME_UP
            "voldown", "volume_down" -> KeyEvent.KEYCODE_VOLUME_DOWN
            "mute" -> KeyEvent.KEYCODE_VOLUME_MUTE
            "back" -> KeyEvent.KEYCODE_BACK
            "search" -> KeyEvent.KEYCODE_SEARCH
            "notification" -> KeyEvent.KEYCODE_NOTIFICATION
            else -> {
                val f = token.removePrefix("f").toIntOrNull()
                if (token.length in 2..3 && token[0] == 'f' && f != null && f in 1..12) {
                    KeyEvent.KEYCODE_F1 + (f - 1)
                } else {
                    null
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        keyboardView = KeyboardView(this, layout = loadKeyboardLayout())
        keyboardView.listener = interactionListener
        keyboardView.setDictLabels(dictKeyLabels())
        applyThemePref()
        applyKeyboardSize()
        applyBufferWidthWeight()
        if (engineReady) updateBufferAndCandidatesView() else showEngineLoadingPlaceholder()
        layoutSignature = KeyboardLayoutFiles.sourceSignature(this)
        return keyboardView
    }

    /**
     * The engine is still parsing the `.cin` on its background thread, so the
     * keyboard is already usable for layout but typing is not. Show a hint in
     * the code box instead of leaving it blank.
     */
    private fun showEngineLoadingPlaceholder() {
        if (!::keyboardView.isInitialized) return
        keyboardView.isEnglishMode = isEnglishMode
        keyboardView.bufferText = "…"
        keyboardView.candidates = emptyArray()
        keyboardView.selectedCandidateIndex = 0
        keyboardView.selkeys = selkeys
        keyboardView.render()
    }

    /**
     * `layouts/keyboard.rb` may be edited by an outside file manager while the IME
     * service stays alive, so re-check it every time the keyboard is shown and
     * rebuild the view tree only when the file actually changed.
     */
    private fun reloadLayoutIfChanged() {
        // Import an edit made to the Android/data mirror before comparing signatures.
        KeyboardLayoutFiles.sync(this)
        val signature = KeyboardLayoutFiles.sourceSignature(this)
        if (signature == layoutSignature) return
        layoutSignature = signature
        Log.i(
            "MrubyimeIME",
            "keyboard layout changed -> ${KeyboardLayoutFiles.describe(this)}"
        )
        keyboardView.setLayout(loadKeyboardLayout())
        keyboardView.setDictLabels(dictKeyLabels())
        applyThemePref()
        applyKeyboardSize()
        applyBufferWidthWeight()
        updateBufferAndCandidatesView()
    }

    /**
     * Context handed to the DSL. The built-in layout ignores most of it today,
     * but user layouts can branch on input type / theme / screen size.
     */
    private fun layoutContext(): String {
        val metrics = resources.displayMetrics
        val info = currentInputEditorInfo
        val inputType = info?.inputType ?: 0
        val typeName = when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> "number"
            InputType.TYPE_CLASS_PHONE -> "phone"
            InputType.TYPE_CLASS_DATETIME -> "datetime"
            else -> when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> "email"
                InputType.TYPE_TEXT_VARIATION_URI -> "url"
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_NUMBER_VARIATION_PASSWORD -> "password"
                else -> "text"
            }
        }
        val imeAction = if (info != null) info.imeOptions and EditorInfo.IME_MASK_ACTION else 0
        return buildString {
            append("mode=").append(if (isEnglishMode) "english" else "chinese").append('\n')
            append("theme=light").append('\n')
            append("locale=").append(java.util.Locale.getDefault().toString()).append('\n')
            append("input_type=").append(typeName).append('\n')
            append("ime_action=").append(imeAction).append('\n')
            append("screen_w=").append(metrics.widthPixels).append('\n')
            append("screen_h=").append(metrics.heightPixels).append('\n')
            append("density=").append(metrics.density).append('\n')
            append("safe_bottom=0").append('\n')
        }
    }

    /** Runtime DSL first (mruby), then the build-time JSON asset, then emergency. */
    private fun loadKeyboardLayout(): KeyboardLayout {
        // Same rule as engine.rb: refresh an untouched seed, so an APK update
        // actually ships the new layout instead of last release's seeded copy.
        KeyboardLayoutFiles.exportBuiltin(this)
        return KeyboardLayoutRepository.load(this, if (engineReady) engine else null, layoutContext())
    }

    /** Keeps a broken DSL visible in logcat without needing a text field. */
    private fun logLayoutDiagnostics() {
        if (!engineReady) return
        try {
            val json = engine.layoutSerialized(layoutContext().toByteArray(Charsets.UTF_8))
            val parsed = KeyboardLayoutParser.parseOrNull(json)
            Log.i(
                "MrubyimeIME",
                "layout JSON bytes=${json.length} parsed=${parsed != null} pages=${parsed?.pages?.map { it.name }}"
            )
            if (parsed == null && json.isNotEmpty()) {
                Log.w("MrubyimeIME", "layout JSON head=${json.take(240)}")
            }
        } catch (t: Throwable) {
            Log.e("MrubyimeIME", "layout diagnostics failed", t)
        }
        try {
            val source = KeyboardLayoutFiles.keyboardSource(this)
            if (source == null) {
                Log.i("MrubyimeIME", "layout source=builtin")
            } else {
                val result = KeyboardLayoutRepository.validateSourceDirect(source, layoutContext())
                Log.i(
                    "MrubyimeIME",
                    "layout source=${KeyboardLayoutFiles.keyboardFile(this).absolutePath} " +
                        "parsed=${result.ok} pages=${result.layout?.pages?.map { it.name }}"
                )
                if (!result.ok) {
                    Log.e("MrubyimeIME", "user layout error: ${result.error}")
                }
            }
        } catch (t: Throwable) {
            Log.e("MrubyimeIME", "user layout diagnostics failed", t)
        }
    }

    /**
     * The e-ink friendly Light Mode. Dark mode removed.
     */
    private fun applyThemePref() {
        if (!::keyboardView.isInitialized) return
        applyNavigationBarTheme()
    }

    /**
     * 「最大碼清空組字」 controls the engine's query limit. With the switch on it
     * is the active table's longest code (5 for 嘸蝦米, 4 for 行列, …); off it
     * grows to 140 and the code box shows only the last 6 codes. The engine
     * stops resolving candidates once the buffer is past the limit.
     */
    private fun applyMaxCodeLength() {
        if (!engineReady) return
        val maxCodeClear = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_MAX_CODE_CLEAR, DEFAULT_MAX_CODE_CLEAR)
        engine.setMaxCodeLen(if (maxCodeClear) dictMaxCode else MAX_CODE_LONG)
    }

    /**
     * The IME window extends behind the navigation bar, so its scrim + the
     * system IME buttons (keyboard-down chevron, globe) are ours to colour:
     * white with dark icons in the e-ink Light Mode.
     */
    private fun applyNavigationBarTheme() {
        val win = window?.window ?: return
        win.attributes?.windowAnimations = R.style.NoIMEAnimation
        // Match keyboard key background color: #E0E0E0
        win.navigationBarColor = 0xFFE0E0E0.toInt()
        val decor = win.decorView
        var flags = decor.systemUiVisibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            if (flags != decor.systemUiVisibility) decor.systemUiVisibility = flags
        }
    }

    /**
     * Drops a half-finished code when the keyboard goes away.
     *
     * The buffer is deliberately **not** committed: leaving `aad` (or `pns` →
     * 你好) behind as an inline composing region meant the raw code stayed
     * visible in the field after the IME was hidden, and the next field
     * inherited that stale composition. Both the InputConnection composing
     * region and the engine buffer are cleared.
     */
    private fun clearCompositionBeforeHide() {
        try {
            currentInputConnection?.let { ic ->
                ic.setComposingText("", 0)
                ic.finishComposingText()
            }
        } catch (t: Throwable) {
            Log.e("MrubyimeIME", "clearCompositionBeforeHide failed", t)
        }
        if (!engineReady) return
        val hadBuffer = engine.getBuffer().isNotEmpty()
        engine.reset()
        candidates = emptyArray()
        selectedCandidateIndex = 0
        if (::keyboardView.isInitialized) updateBufferAndCandidatesView()
        if (hadBuffer) Log.i("MrubyimeIME", "composition cleared on hide")
    }

    /**
     * Catch-all for a disappearing keyboard: the ▼ key, Back and switching to
     * another app/IME all end here. `onFinishInputView` covers a field that
     * loses the input view without the window being hidden.
     */
    override fun onWindowHidden() {
        clearCompositionBeforeHide()
        super.onWindowHidden()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        clearCompositionBeforeHide()
        super.onFinishInputView(finishingInput)
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyNavigationBarTheme()
        // The table may have been changed while the input view did not exist yet
        // (Settings is a separate activity and the prefs listener bails out until
        // keyboardView is built), or by an external editor writing active.cin.
        if (DictionaryRepository.activeId(this) != dictId ||
            DictionaryRepository.signature(this) != dictSignature
        ) {
            reloadDictionary()
        }
        if (::keyboardView.isInitialized) reloadLayoutIfChanged()
    }

    private fun keyRowsHidden(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_HIDE_KEY_ROWS, false)

    /** Buffer width weight for the candidate bar: 0 = use the layout DSL as written. */
    private fun bufferWidthWeightRatio(): Float {
        val p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (p.getBoolean(KEY_BUFFER_WIDTH_FOLLOW, DEFAULT_BUFFER_WIDTH_FOLLOW)) return 0f
        return p.getInt(KEY_BUFFER_WIDTH_WEIGHT, DEFAULT_BUFFER_WIDTH_WEIGHT) / 10f
    }

    private fun applyBufferWidthWeight() {
        if (!::keyboardView.isInitialized) return
        keyboardView.bufferWidthWeight = bufferWidthWeightRatio()
    }

    /**
     * Sizes the keyboard from the layout IR (`height bar:` + `height body:` +
     * `padding bottom:`, or just bar + bottom padding when 「隱藏虛擬鍵盤」 hides
     * the key rows) and applies `padding left/right/bottom:`.
     */
    private fun applyKeyboardSize() {
        if (!::keyboardView.isInitialized) return
        val hidden = keyRowsHidden()
        keyboardView.hideKeyRows = hidden
        val totalHeightPx = keyboardView.layoutHeightPx(screenHeightPx())
        keyboardView.applyLayoutMetrics(screenHeightPx())
        Log.d("MrubyimeIME", "hideKeyRows=$hidden layoutHeightPx=$totalHeightPx")
        val lp = keyboardView.layoutParams
        if (lp != null && lp.height != totalHeightPx) {
            lp.height = totalHeightPx
            keyboardView.layoutParams = lp
        }
        keyboardView.requestLayout()
    }

    /** Full panel height (system bars included), so 40% means 40% of the screen. */
    private fun screenHeightPx(): Int {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val mode = display.mode
            if (mode != null && mode.physicalHeight > 0 && mode.physicalWidth > 0) {
                return if (display.rotation == Surface.ROTATION_0 || display.rotation == Surface.ROTATION_180) {
                    mode.physicalHeight
                } else {
                    mode.physicalWidth
                }
            }
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            if (metrics.heightPixels > 0) return metrics.heightPixels
        }
        return resources.displayMetrics.heightPixels
    }

    private fun onKeyPressed(ch: Char) {
        if (!engineReady) return
        Log.d("MrubyimeIME", "Key pressed: $ch, isEnglishMode=$isEnglishMode")
        val lowercaseCh = ch.lowercaseChar()

        if (isEnglishMode) {
            val finalChar = if (ch.isUpperCase()) ch.toString() else lowercaseCh.toString()
            currentInputConnection?.commitText(finalChar, 1)
            return
        }

        // A key is part of the code only if the active table says so (its
        // %keyname set plus every character that appears in a code). This is
        // what lets 注音 use digits, 行列 use ';' / '/', 大易 use punctuation…
        val isLegalKey = dictLegalKeys.indexOf(lowercaseCh) >= 0

        // Somehow a `%selkey` may also be a radical (注音 `%selkey 1234567890` +
        // `1 ㄅ`). While such a key can still extend the code (`2` → `28` → `284`)
        // it must extend, not select; it only picks a candidate when it can no
        // longer lead to a code. `%selkey` values that are not legal keys
        // (嘸蝦米 `0123456789`) always select as before.
        if (candidates.isNotEmpty()) {
            val idx = selkeys.indexOf(lowercaseCh)
            if (idx >= 0) {
                val extends = isLegalKey &&
                    engine.wouldExtend(lowercaseCh.toString().toByteArray(Charsets.UTF_8))
                if (!extends) {
                    if (idx < candidates.size) {
                        Log.d("MrubyimeIME", "Selecting candidate at index $idx: ${candidates[idx]}")
                        commit(candidates[idx])
                    }
                    return
                }
            }
        }

        // A digit that is NOT a table key is plain text when there is no
        // composition (and is swallowed while composing so the code survives).
        // A digit that IS a table key falls through and extends the code.
        if (lowercaseCh in '0'..'9' && !isLegalKey) {
            if (engine.getBuffer().isEmpty()) {
                currentInputConnection?.commitText(lowercaseCh.toString(), 1)
            }
            return
        }

        if (!isLegalKey) {
            val currentBuf = engine.getBuffer()
            if (currentBuf.isNotEmpty()) {
                if (candidates.isNotEmpty() && selectedCandidateIndex in candidates.indices) {
                    commit(candidates[selectedCandidateIndex])
                } else {
                    commit(currentBuf)
                }
            }
            currentInputConnection?.commitText(ch.toString(), 1)
            return
        }

        // New input -> reset the candidate selection
        selectedCandidateIndex = 0
        // Send string to mruby JNI engine (serialized format)
        val inputStartedNs = System.nanoTime()
        val serialized = engine.inputSerialized(lowercaseCh.toString().toByteArray(Charsets.UTF_8))
        val inputMs = (System.nanoTime() - inputStartedNs) / 1_000_000
        if (inputMs >= SLOW_INPUT_MS) {
            Log.i("MrubyimeIME", "slow input: ${inputMs}ms buffer=${engine.getBuffer().length}")
        }
        candidates = if (serialized.isEmpty()) emptyArray() else serialized.split("\n").toTypedArray()

        // 「最大碼清空組字」 decides the engine's query limit: on = the active
        // table's longest code, off = 140 (effectively unlimited). Push it to
        // the engine so it stops querying candidates past that length.
        val maxCodeClearEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_MAX_CODE_CLEAR, DEFAULT_MAX_CODE_CLEAR)
        val maxCode = if (maxCodeClearEnabled) dictMaxCode else MAX_CODE_LONG
        engine.setMaxCodeLen(maxCode)
        val bufAfterInput = engine.getBuffer()

        // Dead-code auto-clear, two independent gates:
        //  1. 「死碼清空編碼區」: a 1–3 code prefix with no candidates is cleared
        //     only when it is provably dead (adding any one of the table's legal
        //     keys still yields nothing). This is the mid-typing case — a short
        //     prefix that can never resolve.
        //  2. 「最大碼清空組字」: once the buffer reaches the max code length with
        //     no candidates it is cleared. With the switch on (max: the table's
        //     longest code) a full-length dead code wipes the box; with it off
        //     (max 140) the buffer keeps growing and the leading codes are trimmed
        //     visually to the last 6.
        val clearDeadCodeEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_CLEAR_DEAD_CODE, DEFAULT_CLEAR_DEAD_CODE)
        val dead = (clearDeadCodeEnabled &&
            bufAfterInput.length in 1..3 && candidates.isEmpty() && engine.deadPrefix()) ||
            (maxCodeClearEnabled && bufAfterInput.length >= maxCode && candidates.isEmpty())
        if (dead) {
            Log.d("MrubyimeIME", "Dead/max code cleared: '$bufAfterInput'")
            engine.reset()
            candidates = emptyArray()
        }
        
        // Auto-commit rule: a code with exactly 1 candidate that no legal key can
        // extend is unambiguous, so commit it instead of waiting for another key.
        // The threshold is 4 codes, or the table's own max for shorter tables.
        val currentBuf = engine.getBuffer()
        val autoCommitEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_AUTO_COMMIT, DEFAULT_AUTO_COMMIT)
        val autoThreshold = minOf(4, dictMaxCode)
        if (autoCommitEnabled && !isEnglishMode &&
            currentBuf.length >= autoThreshold &&
            candidates.size == 1 && !engine.canExtend()) {
            commit(candidates[0])
            return
        }

        Log.d("MrubyimeIME", "Candidates returned (serialized): $serialized")
        updateBufferAndCandidatesView()
    }

    private fun onSpacePressed() {
        if (!engineReady) return
        if (!isEnglishMode && candidates.isNotEmpty()) {
            val absIdx = selectedCandidateIndex
            if (absIdx in candidates.indices) {
                commit(candidates[absIdx])
            }
        } else {
            val currentBuf = engine.getBuffer()
            if (!isEnglishMode && currentBuf.isNotEmpty()) {
                commit(currentBuf)
            } else {
                currentInputConnection?.commitText(" ", 1)
            }
        }
    }

    private fun onBackspacePressed() {
        if (!engineReady) return
        val currentBuf = engine.getBuffer()
        if (!isEnglishMode && currentBuf.isNotEmpty()) {
            selectedCandidateIndex = 0
            val serialized = engine.backspaceSerialized()
            candidates = if (serialized.isEmpty()) emptyArray() else serialized.split("\n").toTypedArray()
            updateBufferAndCandidatesView()
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    private fun onEnterPressed() {
        if (!engineReady) return
        val currentBuf = engine.getBuffer()
        if (!isEnglishMode && candidates.isNotEmpty()) {
            if (selectedCandidateIndex in candidates.indices) {
                commit(candidates[selectedCandidateIndex])
            } else {
                commit(candidates[0])
            }
        } else if (!isEnglishMode && currentBuf.isNotEmpty()) {
            commit(currentBuf)
        } else {
            sendKeyChar('\n')
        }
    }

    /**
     * Runs after [isEnglishMode] has been flipped (Space swipe or hardware Shift).
     * Entering English mode **clears the code area** (raw letters ignore the
     * Mrubyime composition); returning to Chinese re-queries the buffer in case a
     * composition survived.
     */
    private fun applyModeSwitch() {
        if (!engineReady) return
        if (isEnglishMode) {
            // Entering English mode clears the code area: raw letters ignore the
            // Mrubyime composition (by request).
            engine.reset()
            candidates = emptyArray()
            selectedCandidateIndex = 0
        } else {
            // Back to Chinese: re-query in case a composition survived.
            requeryCandidatesIfChinese()
        }
        updateBufferAndCandidatesView()
    }

    private fun requeryCandidatesIfChinese() {
        if (!engineReady) return
        if (isEnglishMode) return
        val buf = engine.getBuffer()
        if (buf.isEmpty()) {
            candidates = emptyArray()
            return
        }
        val serialized = engine.currentCandidatesSerialized()
        candidates = if (serialized.isEmpty()) emptyArray()
                     else serialized.split("\n").toTypedArray()
        Log.d("MrubyimeIME", "requeried candidates for '$buf' -> ${candidates.size}")
    }

    private fun inlineComposingMode(): Int =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_INLINE_COMPOSING_MODE, DEFAULT_INLINE_MODE)

    private fun updateBufferAndCandidatesView() {
        if (!::keyboardView.isInitialized) return
        if (!engineReady) return
        keyboardView.isEnglishMode = isEnglishMode
        val buf = engine.getBuffer()
        keyboardView.bufferText = buf
        keyboardView.candidates = candidates
        keyboardView.selectedCandidateIndex = selectedCandidateIndex
        keyboardView.selkeys = selkeys
        keyboardView.render()

        updateInlineComposingText(buf)
    }

    /**
     * The exact text the inline composing region shows for [buf], per
     * `inline_composing_mode`. Returns `""` when inline display is off, in
     * English mode, or the buffer is empty. Swipe-up on the code box commits
     * this string verbatim (see [onCommitInlineComposing]).
     */
    private fun inlineComposingText(buf: String): String {
        if (isEnglishMode || buf.isEmpty()) return ""
        val mode = inlineComposingMode()
        if (mode == INLINE_MODE_OFF) return ""

        val selectedCandidate = if (candidates.isNotEmpty() && selectedCandidateIndex in candidates.indices) {
            candidates[selectedCandidateIndex]
        } else ""

        return when (mode) {
            INLINE_MODE_CODE -> buf
            INLINE_MODE_CANDIDATE -> if (selectedCandidate.isNotEmpty()) selectedCandidate else buf
            INLINE_MODE_CANDIDATE_WITH_CODE -> if (selectedCandidate.isNotEmpty()) "$selectedCandidate $buf" else buf
            INLINE_MODE_CODE_UPPER -> buf.uppercase()
            INLINE_MODE_CANDIDATE_WITH_CODE_UPPER ->
                if (selectedCandidate.isNotEmpty()) "$selectedCandidate ${buf.uppercase()}" else buf.uppercase()
            INLINE_MODE_CODE_FULLWIDTH -> fullwidth(buf)
            INLINE_MODE_CANDIDATE_WITH_CODE_FULLWIDTH ->
                if (selectedCandidate.isNotEmpty()) "$selectedCandidate ${fullwidth(buf)}" else fullwidth(buf)
            else -> ""
        }
    }

    private fun updateInlineComposingText(buf: String) {
        val ic = currentInputConnection ?: return
        val rawText = inlineComposingText(buf)
        if (rawText.isNotEmpty()) {
            val spannable = SpannableString(rawText)
            spannable.setSpan(UnderlineSpan(), 0, rawText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ic.setComposingText(spannable, 1)
        } else {
            ic.setComposingText("", 0)
        }
    }

    /**
     * Converts an ASCII code to its fullwidth uppercase form, e.g. `aad` -> `ＡＡＤ`
     * and `,.` -> `，．`. Everything in the printable ASCII range maps by the
     * standard `+0xFEE0` offset; anything else (CJK, already fullwidth) is kept.
     */
    private fun fullwidth(code: String): String = buildString(code.length) {
        for (ch in code) {
            val upper = ch.uppercaseChar()
            append(if (upper in '!'..'~') (upper.code + 0xFEE0).toChar() else upper)
        }
    }

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
        engine.reset()
        candidates = emptyArray()
        selectedCandidateIndex = 0
        updateBufferAndCandidatesView()
    }

    // Hardware Keyboard Interception
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!engineReady) return super.onKeyDown(keyCode, event)
        updateHardwareModifiers(event)
        // `event.repeatCount > 0` means the key is held down. Repeats are handled
        // exactly like the first press: letting them fall through to `super` made
        // the app insert the raw character, so holding `a` typed real "aaaa" into
        // the text while the IME code buffer only ever received one `a` — clearing
        // the buffer then left the typed letters behind. Only the mode hotkey is
        // repeat-sensitive.
        val repeating = event.repeatCount > 0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (!isEnglishMode && candidates.isNotEmpty()) {
                    keyboardView.stepCandidatePage(forward = false)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!isEnglishMode && candidates.isNotEmpty()) {
                    keyboardView.stepCandidatePage(forward = true)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!isEnglishMode && candidates.isNotEmpty()) {
                    if (selectedCandidateIndex > 0) {
                        selectedCandidateIndex--
                        updateBufferAndCandidatesView()
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isEnglishMode && candidates.isNotEmpty()) {
                    if (selectedCandidateIndex < candidates.size - 1) {
                        selectedCandidateIndex++
                        updateBufferAndCandidatesView()
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_DEL -> {
                val currentBuf = engine.getBuffer()
                if (!isEnglishMode && currentBuf.isNotEmpty()) {
                    onBackspacePressed()
                    return true
                }
            }
            KeyEvent.KEYCODE_SPACE -> {
                // 中/英 toggle hotkey: Alt+Space or Shift+Space. A bare Shift
                // is now an ordinary modifier (it used to toggle on its own).
                if (!repeating && (event.isAltPressed || event.isShiftPressed)) {
                    isEnglishMode = !isEnglishMode
                    applyModeSwitch()
                    return true
                }
                val currentBuf = engine.getBuffer()
                if (!isEnglishMode && (candidates.isNotEmpty() || currentBuf.isNotEmpty())) {
                    onSpacePressed()
                    return true
                }
            }
            KeyEvent.KEYCODE_ENTER -> {
                val currentBuf = engine.getBuffer()
                if (!isEnglishMode && (candidates.isNotEmpty() || currentBuf.isNotEmpty())) {
                    onEnterPressed()
                    return true
                }
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                val currentBuf = engine.getBuffer()
                if (!isEnglishMode && currentBuf.isNotEmpty()) {
                    engine.reset()
                    candidates = emptyArray()
                    selectedCandidateIndex = 0
                    updateBufferAndCandidatesView()
                    return true
                }
            }
            else -> {
                val unicode = event.unicodeChar
                if (unicode > 0) {
                    val ch = unicode.toChar()
                    if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                        ch == ',' || ch == '.' || ch == '\'' || ch == '[' || ch == ']') {
                        onKeyPressed(ch)
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        updateHardwareModifiers(event)
        return super.onKeyUp(keyCode, event)
    }

    /** Mirrors the physical modifier / caps-lock state onto the status lamps. */
    private fun updateHardwareModifiers(event: KeyEvent) {
        if (!::keyboardView.isInitialized) return
        keyboardView.hardwareModifiers = event.metaState
        keyboardView.capsLockOn = event.isCapsLockOn
    }
}