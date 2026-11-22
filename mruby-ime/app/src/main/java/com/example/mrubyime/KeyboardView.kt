package com.example.mrubyime

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathEffect
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Keyboard surface generated from the DSL IR (schema v1).
 *
 * The view tree (keys, bar slots, pages) is built once by [KeyboardRenderer];
 * state changes only update text / visibility / background — never a rebuild.
 * Candidate chips stay pooled here because they change on every keystroke.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    layout: KeyboardLayout? = null
) : LinearLayout(context, attrs) {

    interface KeyboardInteractionListener {
        fun onKey(ch: Char)
        fun onBackspace()
        fun onSpace()
        fun onEnter()
        fun onToggleMode()
        fun onHideKeyboard()

        /** Long-press on the hide (▼) key toggles the "hide virtual keyboard" setting. */
        fun onToggleHideKeyRows()
        fun onCommitBuffer()

        /** Swipe up on the code box: commit what the inline composing display shows. */
        fun onCommitInlineComposing()
        fun onCandidateSelected(index: Int, candidate: String)

        /** Space swiped left/right: move the text cursor one character. */
        fun onMoveCursor(forward: Boolean)

        fun onDpad(keyCode: Int)
        fun onSelectAll()
        fun onCopy()
        fun onPaste()

        /** `combo` action: e.g. "ctrl+shift+1" — sends a key event with meta state. */
        fun onKeyCombo(spec: String)

        /** `date`/`time`/`weekday` actions: commit the formatted string verbatim. */
        fun onCommitText(text: String)
    }

    var listener: KeyboardInteractionListener? = null

    var isEnglishMode: Boolean = false
    var bufferText: String = ""
    var candidates: Array<String> = emptyArray()
    var selectedCandidateIndex: Int = 0
    var selkeys: String = "0123456789"

    /** `%keyname` labels from the active .cin table (empty for the built-in 嘸蝦米). */
    private var dictKeyLabels: Map<String, String> = emptyMap()

    /** Hardware-modifier state of the last physical key event (drives `status` lamps). */
    var hardwareModifiers: Int = 0
        set(value) {
            if (field == value) return
            field = value
            updateStatusLamps()
        }

    /** Hardware CapsLock state of the last physical key event (drives `status` lamps). */
    var capsLockOn: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateStatusLamps()
        }

    /** Exact keyboard height in pixels; the IME window is WRAP_CONTENT, so onMeasure must enforce it. */
    var keyboardHeightPx: Int = 0

    /**
     * IR-derived metrics. The layout DSL owns the keyboard size
     * (`height bar:` / `height body:` / `padding …`), the bar height and the
     * font sizes; the settings screen no longer overrides any of them.
     */
    private var layoutSpec: KeyboardLayout? = null
    private var bufferTextSizeSp = 24f
    private var chipTextSizeSp = 24f

    /** Px height the current layout asks for, clamped to [maxHeightPx] when > 0. */
    fun layoutHeightPx(maxHeightPx: Int): Int {
        val l = layoutSpec ?: return 0
        val d = resources.displayMetrics.density
        val bar = (l.height.bar * d).roundToInt()
        val body = (l.height.body * d).roundToInt()
        val bottom = (l.padding.bottom * d).roundToInt()
        val raw = if (hideKeyRows) bar + bottom else bar + body + bottom
        return if (maxHeightPx > 0) raw.coerceAtMost(maxHeightPx) else raw
    }

    /** Applies `padding left/right/bottom:` and the total height from the layout IR. */
    fun applyLayoutMetrics(maxHeightPx: Int) {
        val l = layoutSpec ?: return
        val d = resources.displayMetrics.density
        leftPaddingPx = (l.padding.left * d).roundToInt()
        rightPaddingPx = (l.padding.right * d).roundToInt()
        bottomPaddingPx = (l.padding.bottom * d).roundToInt()
        keyboardHeightPx = layoutHeightPx(maxHeightPx)
    }

    /** Dynamic padding in pixels for reserved spaces around the keyboard. */
    var bottomPaddingPx: Int = 0
        set(value) {
            field = value
            updatePaddings()
        }

    var leftPaddingPx: Int = 0
        set(value) {
            field = value
            updatePaddings()
        }

    var rightPaddingPx: Int = 0
        set(value) {
            field = value
            updatePaddings()
        }

    private fun updatePaddings() {
        setPadding(leftPaddingPx, 0, rightPaddingPx, bottomPaddingPx)
    }

    /**
     * User override for the buffer box width in grid units (`0` = follow the
     * layout DSL, i.e. `buffer weight:` / `candidates weight:` as written).
     */
    var bufferWidthWeight: Float = 0f
        set(value) {
            field = value
            updateBufferWidthWeight()
        }

    /**
     * Settings toggle "隱藏虛擬鍵盤": hides the key rows but keeps the
     * candidate bar (buffer, candidates, punctuation, hide) usable.
     */
    var hideKeyRows: Boolean = false
        set(value) {
            field = value
            if (::rowsHost.isInitialized) {
                rowsHost.visibility = if (value) View.GONE else View.VISIBLE
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightSpec = if (keyboardHeightPx > 0) {
            MeasureSpec.makeMeasureSpec(keyboardHeightPx, MeasureSpec.EXACTLY)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, heightSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // `label_fit: :shrink` needs the measured cell width, which only exists now.
        fitShrinkLabels()
    }

    private lateinit var rendered: RenderedKeyboard
    private lateinit var barHost: ViewGroup
    private lateinit var rowsHost: ViewGroup

    private var bufferView: TextView? = null
    private var candidateScroll: StepScrollView? = null
    private var candidateContainer: LinearLayout? = null
    private var bufferSlot: BufferSlot? = null
    private var chipSpec: ChipSpec? = null
    private var bufferPage: PageViews? = null

    /**
     * Bar split captured from the IR (`captureBufferWidthBase`): the 編碼區寬度
     * slider only redistributes the buffer/candidate budget, so `▼` keeps its
     * own share and the bar never outgrows `columns`.
     */
    private var barUsesWeight = false
    private var barBufferWeight = 0f
    private var barCandidatesWeight = 0f
    private var barBufferSpan = 0f
    private var barCandidatesSpan = 0f

    private var resolver = ThemeResolver(null)

    private val handler = Handler(Looper.getMainLooper())

    /** Last selection index / theme the chips were painted with (see updateCandidates). */
    private var styledSelection = -2
    private var styledTheme = false
    private var showingPlaceholder = false
    private var placeholderFramed = false

    private companion object {
        /** Reference text whose width defines the empty candidate cell (`0` + 1 em glyph). */
        const val PLACEHOLDER_REFERENCE = "0微"

        const val SEAM_COLOR = 0xFFFFFFFF.toInt()
        const val BAR_FILL = 0xFFFFFFFF.toInt()
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(SEAM_COLOR)
        build(layout ?: KeyboardLayoutRepository.emergencyLayout())
        preloadSounds()
    }

    /**
     * Rebuilds the whole view tree from a new IR. Used when the user's
     * `layouts/keyboard.rb` changed while the keyboard service stayed alive; the
     * tree is regenerated once (not per keystroke) and all dynamic settings are
     * re-applied to the fresh views.
     */
    fun setLayout(newLayout: KeyboardLayout) {
        // Re-scan the font folders: a file copied in after start-up should be found.
        FontStore.clear()
        build(newLayout)
        preloadSounds()
        render()
    }

    /**
     * Applies a .cin table's `%keyname` labels to the matching key caps. Only
     * labels that differ from the key itself are used, so a table that maps
     * `a → Ａ` keeps the existing A-Z look while 倉頡 (`a → 日`) or 注音
     * (`1 → ㄅ`) replaces the caps.
     */
    fun setDictLabels(labels: Map<String, String>) {
        dictKeyLabels = labels
        if (::rendered.isInitialized) applyModeLabels()
    }

    private fun build(newLayout: KeyboardLayout) {
        removeAllViews()
        rendered = KeyboardRenderer(context, newLayout).render(this)
        barHost = rendered.barHost
        rowsHost = rendered.rowsHost

        bufferPage = null
        bufferView = null
        candidateScroll = null
        candidateContainer = null
        bufferSlot = null
        chipSpec = null
        showingPlaceholder = false
        placeholderFramed = false
        styledSelection = -2
        styledTheme = true

        for (page in rendered.pages.values) {
            if (page.bufferView != null) {
                bufferPage = page
                bufferView = page.bufferView
                candidateScroll = page.candidateScroll
                candidateContainer = page.candidateContainer
                bufferSlot = page.bufferSlot
                chipSpec = page.chipSpec
                break
            }
        }
        captureBufferWidthBase()
        wireKeys()
        updatePaddings()
        layoutSpec = newLayout
        bufferTextSizeSp = bufferSlot?.font?.size ?: 24f
        chipTextSizeSp = chipSpec?.font?.size ?: 24f
        rowsHost.visibility = if (hideKeyRows) View.GONE else View.VISIBLE
        applyTheme()
        updateCandidateFontSizes()
        updateKeyFontSizes()
        updateBufferWidthWeight()
    }

    // ---------------------------------------------------------------- wiring

    private fun wireKeys() {
        for (page in rendered.pages.values) {
            for (kv in page.keyViews) wireKey(kv)
        }
        bufferView?.setOnClickListener { listener?.onCommitBuffer() }
        attachBufferSwipeUp(bufferView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Same line metrics as the candidate chips (shared baseline).
            bufferView?.setFallbackLineSpacing(false)
        }
    }

    private fun wireKey(kv: KeyView) {
        val v = kv.view
        val spec = kv.spec

        spec.longPress?.let { lp ->
            v.setOnLongClickListener {
                when (lp.name) {
                    "toggle_hide_rows" -> listener?.onToggleHideKeyRows()
                }
                true
            }
            if (spec.action.name == "ime_hide") {
                // View.performLongClick() would fire a LONG_PRESS vibration tick.
                v.isHapticFeedbackEnabled = false
            }
        }

        when (spec.action.name) {
            "backspace" -> attachRepeatDelete(v, spec.repeat)
            "space" -> attachSpaceGesture(v)
            "noop" -> Unit
            else -> {
                v.setOnClickListener { dispatchAction(spec.action, kv) }
                when {
                    spec.gestures.isNotEmpty() -> attachGestures(v, kv, spec.gestures)
                    spec.action.name == "code" -> {
                        val ch = (spec.action.value ?: spec.label).firstOrNull()
                        if (ch != null && ch in 'a'..'z') attachLetterSwipeUp(v, ch)
                    }
                }
            }
        }
    }

    /** Runs one IR action, shared by taps, gestures and long-presses. */
    /** Sticky modifier bits latched by `modifier` keys; applied to the next key. */
    private var modifierLatch = 0
    private val modifierViews = mutableMapOf<Int, KeyView>()

    // --- per-key sound -----------------------------------------------------
    private var audioManager: android.media.AudioManager? = null
    private var soundPool: android.media.SoundPool? = null
    private val soundIds = mutableMapOf<String, Int>()

    private fun keySoundName(spec: KeySpec): String? =
        spec.sound ?: rendered.layout.sound?.default

    private fun keySoundVolume(spec: KeySpec): Float =
        (spec.soundVolume ?: rendered.layout.sound?.volume ?: 1f).coerceIn(0f, 1f)

    private fun playKeySound(kv: KeyView) {
        val name = keySoundName(kv.spec)?.trim() ?: return
        if (name.isEmpty() || name == "none") return
        val volume = keySoundVolume(kv.spec)
        if (name == "click" || name == "system" || name == "keypress") {
            val am = audioManager ?: (context.getSystemService(Context.AUDIO_SERVICE)
                as? android.media.AudioManager)?.also { audioManager = it }
            am?.playSoundEffect(android.view.SoundEffectConstants.CLICK, volume)
            return
        }
        val id = soundIds[name]?.takeIf { it > 0 }
            ?: (loadSound(name)?.also { soundIds[name] = it } ?: return)
        soundPool?.play(id, volume, volume, 1, 0, 1f)
    }

    /**
     * Loads `assets/sounds/<name>`, `assets/<name>`, `filesDir/sounds/<name>`,
     * `Android/data/<pkg>/files/sounds/<name>` or an absolute path.
     */
    private fun loadSound(name: String): Int? {
        val pool = soundPool ?: android.media.SoundPool.Builder()
            .setMaxStreams(4)
            .build()
            .also { soundPool = it }
        for (asset in listOf("sounds/$name", name)) {
            try {
                context.assets.openFd(asset).use { afd -> return pool.load(afd, 1) }
            } catch (_: Throwable) {
                // try the next location
            }
        }
        val candidates = mutableListOf<java.io.File>()
        java.io.File(name).takeIf { it.isAbsolute }?.let { candidates.add(it) }
        candidates.add(java.io.File(context.filesDir, "sounds/$name"))
        // Drop-in folder exposed by the SAF root (Android/data/<pkg>/files/sounds).
        runCatching { context.getExternalFilesDir(null) }
            .getOrNull()?.let { candidates.add(java.io.File(it, "sounds/$name")) }
        for (file in candidates) {
            if (!file.isFile) continue
            try {
                return pool.load(file.absolutePath, 1)
            } catch (_: Throwable) {
                // keep looking
            }
        }
        android.util.Log.w("MrubyimeIME", "key sound not found: $name")
        return null
    }

    /** Loads every custom sound referenced by the layout up front (SoundPool is async). */
    private fun preloadSounds() {
        if (!::rendered.isInitialized) return
        val names = mutableSetOf<String>()
        rendered.layout.sound?.default?.let { names.add(it) }
        for (page in rendered.pages.values) {
            for (kv in page.keyViews) kv.spec.sound?.let { names.add(it) }
        }
        for (name in names) {
            val n = name.trim()
            if (n.isEmpty() || n == "none" || n == "click" || n == "system" || n == "keypress") continue
            soundIds.getOrPut(n) { loadSound(n) ?: 0 }
        }
    }

    private fun applyHaptic(kv: KeyView) {
        when (kv.spec.haptic) {
            "light" -> kv.view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            "medium" -> kv.view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            "heavy" -> kv.view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun dispatchAction(action: ActionSpec?, source: KeyView? = null) {
        if (source != null) {
            playKeySound(source)
            applyHaptic(source)
        }
        when (action?.name) {
            "code" -> {
                // Send the physical key the DSL declared (`action.value`), never the
                // `%keyname` display label: the engine keys on the physical char
                // (注音 `1 ㄅ` is entered by pressing `1`).
                val ch = action.value?.firstOrNull() ?: return
                val meta = takeModifiers()
                if (meta != 0) listener?.onKeyCombo(withModifiers(meta, ch.toString()))
                else listener?.onKey(ch)
            }
            "space" -> listener?.onSpace()
            "enter" -> listener?.onEnter()
            "backspace" -> listener?.onBackspace()
            "ime_hide" -> listener?.onHideKeyboard()
            "toggle_mode" -> listener?.onToggleMode()
            "combo", "key_event" -> action.value?.let {
                val meta = takeModifiers()
                listener?.onKeyCombo(if (meta != 0) withModifiers(meta, it) else it)
            }
            "modifier" -> action.value?.let { toggleModifier(it, source) }
            "dpad" -> {
                val code = dpadCode(action.value)
                if (code != 0) listener?.onDpad(code)
            }
            "clipboard" -> when (action.value) {
                "select_all" -> listener?.onSelectAll()
                "copy" -> listener?.onCopy()
                "paste" -> listener?.onPaste()
            }
            "date" -> listener?.onCommitText(formatNow(action.value ?: "yyyy-MM-dd"))
            "time" -> listener?.onCommitText(formatNow(action.value ?: "HH:mm"))
            "weekday" -> listener?.onCommitText(formatNow(action.value ?: "EEEE"))
            "datetime" -> listener?.onCommitText(formatNow(action.value ?: "yyyy-MM-dd HH:mm"))
            else -> Unit
        }
    }

    private fun formatNow(pattern: String): String =
        java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
            .format(java.util.Date())

    private fun modifierBit(name: String): Int? = when (name.lowercase()) {
        "ctrl", "control" -> android.view.KeyEvent.META_CTRL_ON
        "shift" -> android.view.KeyEvent.META_SHIFT_ON
        "alt" -> android.view.KeyEvent.META_ALT_ON
        "meta", "win", "super", "cmd" -> android.view.KeyEvent.META_META_ON
        else -> null
    }

    private fun toggleModifier(name: String, source: KeyView?) {
        val bit = modifierBit(name) ?: return
        modifierLatch = modifierLatch xor bit
        if (source != null) modifierViews[bit] = source
        refreshModifierMarks()
    }

    /** Consumes the latched modifiers (one-shot, like a laptop Fn/Ctrl tap). */
    private fun takeModifiers(): Int {
        val meta = modifierLatch
        if (meta != 0) {
            modifierLatch = 0
            refreshModifierMarks()
        }
        return meta
    }

    private fun refreshModifierMarks() {
        for ((bit, kv) in modifierViews) {
            val flags = kv.label.paintFlags
            kv.label.paintFlags = if (modifierLatch and bit != 0) {
                flags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            } else {
                flags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }
        updateStatusLamps()
    }

    private fun withModifiers(meta: Int, key: String): String {
        val parts = mutableListOf<String>()
        if (meta and android.view.KeyEvent.META_CTRL_ON != 0) parts.add("ctrl")
        if (meta and android.view.KeyEvent.META_SHIFT_ON != 0) parts.add("shift")
        if (meta and android.view.KeyEvent.META_ALT_ON != 0) parts.add("alt")
        if (meta and android.view.KeyEvent.META_META_ON != 0) parts.add("meta")
        parts.add(key)
        return parts.joinToString("+")
    }

    /**
     * Generic per-key gestures from `gestures:` in the DSL (swipe_up / swipe_down
     * / swipe_left / swipe_right). The tap still runs the key's own action.
     */
    private fun attachGestures(view: View, kv: KeyView, gestures: Map<String, ActionSpec>) {
        var startX = 0f
        var startY = 0f
        var fired = false
        val threshold = dp(16)
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    startX = event.x
                    startY = event.y
                    fired = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!fired) {
                        val dx = event.x - startX
                        val dy = event.y - startY
                        val action = when {
                            abs(dy) >= abs(dx) && dy <= -threshold -> gestures["swipe_up"]
                            abs(dy) >= abs(dx) && dy >= threshold -> gestures["swipe_down"]
                            abs(dx) > abs(dy) && dx >= threshold -> gestures["swipe_right"]
                            abs(dx) > abs(dy) && dx <= -threshold -> gestures["swipe_left"]
                            else -> null
                        }
                        if (action != null) {
                            fired = true
                            v.isPressed = false
                            dispatchAction(action, kv)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (!fired) v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                else -> true
            }
        }
    }

    private fun dpadCode(value: String?): Int = when (value) {
        "up" -> KeyEvent.KEYCODE_DPAD_UP
        "down" -> KeyEvent.KEYCODE_DPAD_DOWN
        "left" -> KeyEvent.KEYCODE_DPAD_LEFT
        "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
        else -> 0
    }

    // ---------------------------------------------------------------- theme

    private fun solid(fill: Int, stroke: Int? = null, strokeDp: Float = 1f, radiusPx: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            if (radiusPx > 0f) cornerRadius = radiusPx
            if (stroke != null) setStroke(dp(strokeDp).coerceAtLeast(1), stroke)
        }

    private fun selector(fill: Int, pressed: Int, radiusPx: Float): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), solid(pressed, radiusPx = radiusPx))
            addState(intArrayOf(), solid(fill, radiusPx = radiusPx))
        }

    private fun keyBackground(kv: KeyView): Drawable {
        val spec = kv.spec
        val radiusPx = spec.style.radius * resources.displayMetrics.density
        if (spec.style.flat) {
            val fill = themed(barFill(), spec.style.bg, "bar")
            return solid(fill, radiusPx = radiusPx)
        }
        val fill = themed(keyFill(), spec.style.bg, "key")
        val borderColor = spec.style.border?.color?.let { resolver.colorOrNull(it) }
            ?: resolver.colorOrNull("keyBorder")
        if (borderColor != null) {
            // Seam lines are painted per side (right/bottom always, left/top only on
            // the outer edge) so flush neighbours share ONE line instead of
            // stacking two borders into a ~2 dp band.
            return gridKeyDrawable(
                fill,
                borderColor,
                dp(spec.style.border?.width ?: 1f).coerceAtLeast(1),
                radiusPx,
                kv.borderLeft,
                kv.borderTop
            )
        }
        return selector(fill, themed(fill, "keyPressed"), radiusPx)
    }

    /**
     * Key plate with per-side seams: fill + (right, bottom, optional left/top)
     * 1 dp strips. Each strip is drawn inside the key, so a shared boundary shows
     * exactly one line no matter how many rows/columns meet there.
     */
    private fun gridKeyDrawable(
        fill: Int,
        stroke: Int,
        strokePx: Int,
        radiusPx: Float,
        left: Boolean,
        top: Boolean
    ): Drawable {
        val layers = mutableListOf<Drawable>()
        val specs = mutableListOf<Pair<Int, IntArray>>() // gravity, [w, h]
        layers.add(
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(fill)
                if (radiusPx > 0f) cornerRadius = radiusPx
            }
        )
        fun line(gravity: Int, w: Int, h: Int) {
            layers.add(
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(stroke)
                }
            )
            specs.add(gravity to intArrayOf(w, h))
        }
        line(Gravity.RIGHT or Gravity.FILL_VERTICAL, strokePx, -1)
        line(Gravity.BOTTOM or Gravity.FILL_HORIZONTAL, -1, strokePx)
        if (left) line(Gravity.LEFT or Gravity.FILL_VERTICAL, strokePx, -1)
        if (top) line(Gravity.TOP or Gravity.FILL_HORIZONTAL, -1, strokePx)
        return LayerDrawable(layers.toTypedArray()).apply {
            specs.forEachIndexed { i, (gravity, size) ->
                setLayerGravity(i + 1, gravity)
                setLayerSize(i + 1, size[0], size[1])
            }
        }
    }

    /**
     * Draws `bar border:` inset. The bar stretches to the screen edges siblings
     * leave, so a stroke on the bar's own View would lose its left/right/top
     * sides to the window clip. Instead the bar plate is painted on the bar
     * container itself (`applyTheme`) and each page's container is padded by
     * **its own** border width, so a page without `border:` keeps its full
     * width; `KeyRowLayout` already lays keys out inside the padding.
     */
    private fun applyBarBorder() {
        if (!::rendered.isInitialized) return
        for (page in rendered.pages.values) {
            val container = page.barContainer ?: continue
            val inset = page.spec.bar?.border?.let { dp(it.width).coerceAtLeast(1) } ?: 0
            if (container.paddingStart != inset) container.setPadding(inset, inset, inset, inset)
        }
    }

    private fun barFill(): Int = BAR_FILL
    private fun keyFill(): Int = BAR_FILL

    private fun applyTheme() {
        resolver = ThemeResolver(rendered.layout.theme(true))
        val seam = themed(SEAM_COLOR, "seam")
        setBackgroundColor(seam)
        // The bar plate is painted on each page's bar container, so `bar border:`
        // has to live on that same drawable; the host stays a plain plate.
        barHost.background = solid(themed(BAR_FILL, "bar"))

        for (page in rendered.pages.values) {
            // Each page carries its own `bar border:`; a page without one stays a
            // plain plate (and its container is not inset — see applyBarBorder).
            // Use borderedPlateBar to draw stroke exactly at bounds edge (no half-stroke inset),
            // so bar's left/right borders align with keyboard keys' edges.
            page.barContainer?.background = borderedPlateBar(
                themed(BAR_FILL, "bar"),
                page.spec.bar?.border,
                INK_FALLBACK,
                0f,
                "keyBorder"
            )
            val mutedInk = themed(INK_FALLBACK, "muted", "ink")
            for (kv in page.keyViews) {
                kv.view.background = keyBackground(kv)
                val ink = themed(
                    INK_FALLBACK,
                    kv.spec.style.ink,
                    if (kv.isBar) "barInk" else "keyInk",
                    "ink"
                )
                kv.label.setTextColor(ink)
                kv.subLabelView?.setTextColor(mutedInk)
            }
            page.bufferView?.apply {
                // Use buffer slot's style for background/border if available
                val bs = page.bufferSlot
                background = if (bs?.style?.border != null) {
                    borderedPlate(
                        themed(BAR_FILL, bs.style.bg, "buffer", "bar"),
                        bs.style.border,
                        themed(INK_FALLBACK, bs.style.ink, "bufferInk", "ink"),
                        bs.style.radius ?: 0f,
                        "buffer"
                    )
                } else {
                    solid(themed(BAR_FILL, "buffer", "bar"))
                }
                setTextColor(themed(INK_FALLBACK, "bufferInk", "ink"))
            }
        }
        applyBarBorder()
        // Force the reused chips to be repainted on the next render().
        styledSelection = -2
        updateStatusLamps()
    }

    private val INK_FALLBACK = 0xFF000000.toInt()

    /**
     * First token that resolves to a color wins (so `keyInk` can fall back to the
     * shared `ink`), then [fallback]. Lets a layout theme define only what it
     * cares about and inherit the rest.
     */
    private fun themed(fallback: Int, vararg tokens: String?): Int {
        for (token in tokens) {
            if (token.isNullOrEmpty()) continue
            val parsed = resolver.colorOrNull(token)
            if (parsed != null) return parsed
        }
        return fallback
    }

    private fun updateStatusLamps() {
        if (!::rendered.isInitialized) return
        val onInk = themed(INK_FALLBACK, "lampOn", "ink")
        val offInk = themed(0x00000000, "lampOff", "keyBorder", "ink")
        val plate = themed(barFill(), "bar")
        for (page in rendered.pages.values) {
            for (lamp in page.lamps) {
                val on = lampStateOn(lamp.spec.state)
                val ink = if (on) {
                    lamp.spec.onInk?.let { resolver.colorOrNull(it) } ?: onInk
                } else {
                    // Dim lamps are blended 35 % toward the bar plate. (Alpha is
                    // forced opaque, so a transparent off_ink is not supported.)
                    val raw = lamp.spec.offInk?.let { resolver.colorOrNull(it) } ?: offInk
                    blendArgb(raw, plate, 0.35f)
                }
                lamp.view.setTextColor(ink)
                lamp.view.background = null
            }
        }
    }

    /** Dash / dot pattern for a `style:`d border, `null` when solid. */
    private fun borderEffect(spec: BorderSpec): PathEffect? {
        val w = dp(spec.width).coerceAtLeast(1).toFloat()
        return when {
            spec.dotted -> DashPathEffect(floatArrayOf(w, w * 2f), 0f)
            spec.dashed -> DashPathEffect(floatArrayOf(w * 3f, w * 2f), 0f)
            else -> null
        }
    }

    /**
     * Plate with the border of [spec] drawn on top: fill + stroke, honouring
     * `style:` (solid / dashed / dotted), `side:` (all or one edge) and `radius:`.
     * GradientDrawable cannot dash a stroke, which is why this exists.
     */
    private fun borderedPlate(
        fill: Int,
        spec: BorderSpec?,
        ink: Int,
        radiusDp: Float = 0f,
        defaultColorToken: String = "ink"
    ): Drawable {
        if (spec == null) {
            return solid(fill, radiusPx = radiusDp * resources.displayMetrics.density)
        }
        val color = themed(ink, spec.color, defaultColorToken, "ink")
        return BorderedPlateDrawable(
            fillColor = fill,
            strokeColor = color,
            strokeWidthPx = dp(spec.width),
            effect = borderEffect(spec),
            side = spec.side.lowercase(),
            cornerRadiusPx = radiusDp * resources.displayMetrics.density
        )
    }

    /**
     * Bar 專用：四周描邊畫在邊界上（不置中），與鍵盤鍵的左右邊緣對齊。
     * 使用 BorderedPlateDrawable(centerStroke = false) 避免 0.5dp 半描邊內縮導致的錯位。
     */
    private fun borderedPlateBar(
        fill: Int,
        spec: BorderSpec?,
        ink: Int,
        radiusDp: Float = 0f,
        defaultColorToken: String = "ink"
    ): Drawable {
        if (spec == null) {
            return solid(fill, radiusPx = radiusDp * resources.displayMetrics.density)
        }
        val color = themed(ink, spec.color, defaultColorToken, "ink")
        return BorderedPlateDrawable(
            fillColor = fill,
            strokeColor = color,
            strokeWidthPx = dp(spec.width),
            effect = borderEffect(spec),
            side = spec.side.lowercase(),
            cornerRadiusPx = 0f,
            centerStroke = false  // 關鍵：描邊畫在邊界上，不置中，與鍵盤鍵邊緣對齊
        )
    }

    /** Straight RGBA blend, `alpha` = weight of [fg] (0 -> bg, 1 -> fg). */
    private fun blendArgb(fg: Int, bg: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        val r = (fg shr 16 and 0xFF) * a + (bg shr 16 and 0xFF) * (1f - a)
        val g = (fg shr 8 and 0xFF) * a + (bg shr 8 and 0xFF) * (1f - a)
        val b = (fg and 0xFF) * a + (bg and 0xFF) * (1f - a)
        return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    private fun lampStateOn(state: String): Boolean = when (state.lowercase()) {
        "caps", "capslock" -> capsLockOn
        "shift" -> (modifierLatch or hardwareModifiers) and KeyEvent.META_SHIFT_ON != 0
        "ctrl", "control" -> (modifierLatch or hardwareModifiers) and KeyEvent.META_CTRL_ON != 0
        "alt" -> (modifierLatch or hardwareModifiers) and KeyEvent.META_ALT_ON != 0
        "meta", "super", "win", "command", "cmd" ->
            (modifierLatch or hardwareModifiers) and KeyEvent.META_META_ON != 0
        "latch", "modifier" -> modifierLatch != 0
        "english", "en" -> isEnglishMode
        "chinese", "zh" -> !isEnglishMode
        "buffer" -> bufferText.isNotEmpty()
        "candidates" -> candidates.isNotEmpty()
        else -> false
    }

    /** Refreshes every visible element from the current state properties. */
    fun render(alignToSelection: Boolean = true) {
        if (!::rendered.isInitialized) return
        rendered.showPage(if (isEnglishMode) "english" else rendered.layout.defaultPage)
        applyModeLabels()
        if (!isEnglishMode) {
            updateBuffer()
            updateCandidates(alignToSelection)
        }
        refreshStatusLamps()
    }

    /**
     * Any event that can change a lamp's input (buffer, candidates, mode, latch)
     * calls this: it polls the physical modifiers and repaints the strip. Kept
     * cheap — a few TextView colour writes, only on state changes.
     */
    private fun refreshStatusLamps() {
        if (!::rendered.isInitialized) return
        updateStatusLamps()
    }
    /**
     * `label_zh` / `label_en` (and the sub-label equivalents) let one key change
     * its text when the 中/英 mode flips — the action stays whatever DSL said.
     */
    private fun applyModeLabels() {
        for (page in rendered.pages.values) {
            for (kv in page.keyViews) {
                val spec = kv.spec
                // A label can be several glyphs (`g → ㄍㄐㄜ`). `label_fit:` says how
                // it fits the cell: :wrap breaks every `label_wrap` glyphs, :shrink
                // keeps one line (handled in fitShrinkLabels), :none clips. Table
                // `%keyname` labels default to :wrap; DSL labels default to :none so
                // "TAB"/"DT" never break.
                val dictLabel = dictLabelFor(spec)
                val raw = dictLabel ?: spec.textFor(isEnglishMode)
                val fit = spec.labelFit?.lowercase() ?: if (dictLabel != null) "wrap" else "none"
                val perLine = spec.labelWrap.coerceAtLeast(1)
                val text = if (fit == "wrap" && raw.length > perLine) {
                    raw.chunked(perLine).joinToString("\n")
                } else {
                    raw
                }
                if (kv.label.text.toString() != text) kv.label.text = text
                val multi = text.indexOf('\n') >= 0
                if (kv.label.isSingleLine == multi) {
                    kv.label.isSingleLine = !multi
                    kv.label.maxLines = if (multi) 4 else 1
                }
                val subView = kv.subLabelView ?: continue
                val subText = spec.subTextFor(isEnglishMode) ?: continue
                if (subView.text.toString() != subText) subView.text = subText
            }
        }
        // The wrapped line count decides the size, so re-apply it after the text.
        updateKeyFontSizes()
        // Cell width is only known after the layout pass, so shrink then too.
        post { fitShrinkLabels() }
    }

    /** The active table's `%keyname` label for a key, or null when there is none. */
    private fun dictLabelFor(spec: KeySpec): String? {
        if (isEnglishMode || dictKeyLabels.isEmpty()) return null
        val key = keyChar(spec) ?: return null
        val label = dictKeyLabels[key] ?: return null
        return if (isTrivialLabel(key, label)) null else label
    }

    /**
     * `label_fit: :shrink` — after layout, reduce a one-line label until it fits
     * the cell width. Runs from `onLayout` because the cell width is unknown
     * before measurement; it only ever shrinks, so layout settles in one pass.
     */
    private fun fitShrinkLabels() {
        if (!::rendered.isInitialized) return
        for (page in rendered.pages.values) {
            for (kv in page.keyViews) {
                if (!"shrink".equals(kv.spec.labelFit, ignoreCase = true)) continue
                val tv = kv.label
                val cell = if (tv.width > 0) tv.width else tv.measuredWidth
                val avail = cell - tv.paddingLeft - tv.paddingRight
                val text = tv.text.toString()
                if (avail <= 0 || text.isEmpty() || text.indexOf('\n') >= 0) continue
                val width = tv.paint.measureText(text)
                if (width <= avail || width <= 0f) continue
                val target = (tv.textSize * avail / width).coerceAtLeast(tv.textSize * 0.5f)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
            }
        }
    }

    /** The single ASCII key a cap stands for (`action.value` first, then label). */
    private fun keyChar(spec: KeySpec): String? {
        val raw = spec.action.value ?: spec.label
        if (raw.length != 1) return null
        val c = raw[0]
        if (c.code !in 0x20..0x7E) return null
        return c.lowercaseChar().toString()
    }

    /** True when a `%keyname` label is just the key itself (A→Ａ, ,→，, '→’, …). */
    private fun isTrivialLabel(key: String, label: String): Boolean {
        if (key.length != 1 || label.length != 1) return false
        return normalizeKeyChar(label[0]).uppercaseChar() ==
            normalizeKeyChar(key[0]).uppercaseChar()
    }

    private fun normalizeKeyChar(c: Char): Char = when (c) {
        '’', '‘', '′' -> '\''
        '“', '”' -> '"'
        '，' -> ','
        '。' -> '.'
        '、' -> ','
        '〔' -> '['
        '〕' -> ']'
        else -> if (c.code in 0xFF01..0xFF5E) (c.code - 0xFEE0).toChar() else c
    }

    /**
     * Hardware D-pad ↑ / ↓ — moves the candidate row one page (the pages loop) and
     * highlights the first chip of the page that is now visible. The row keeps the
     * paged position (`alignToSelection = false`) instead of jumping back.
     */
    fun stepCandidatePage(forward: Boolean) {
        if (isEnglishMode || candidates.isEmpty()) return
        val container = candidateContainer ?: return
        val scroll = candidateScroll ?: return
        if (container.childCount == 0) return
        val page = scroll.stepPage(forward)
        val visibleFrom = scroll.targetOfPage(page)
        var index = candidates.size - 1
        for (i in candidates.indices) {
            val chip = container.getChildAt(i) ?: break
            if (chip.left + chip.width > visibleFrom + 1) {
                index = i
                break
            }
        }
        selectedCandidateIndex = index
        render(alignToSelection = false)
    }

    private fun updateCandidateFontSizes() {
        if (!::rendered.isInitialized) return
        bufferView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, bufferTextSizeSp)
        val padH = dp(chipSpec?.padH ?: 3f)
        val reference = chipSpec?.minWidthRef ?: PLACEHOLDER_REFERENCE
        val container = candidateContainer ?: return
        for (i in 0 until container.childCount) {
            val chip = container.getChildAt(i) as? TextView ?: continue
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, chipTextSizeSp)
            chip.setPadding(padH, 0, padH, 0)
            chip.minimumWidth = ceil(chip.paint.measureText(reference)).toInt() + padH * 2
        }
    }

    private fun updateKeyFontSizes() {
        if (!::rendered.isInitialized) return
        for (page in rendered.pages.values) {
            for (kv in page.keyViews) {
                val base = kv.spec.font?.size ?: 18f
                // A wrapped `%keyname` label (`ㄍㄐㄜ` → 2 lines) keeps full size;
                // beyond two lines shrink so it still fits the key height.
                val lines = kv.label.text.count { it == '\n' } + 1
                val lineFit = if (lines > 2) 2f / lines else 1f
                val sp = base * kv.fontScale * lineFit
                kv.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                kv.subLabelView?.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    (sp * 0.5f).coerceAtLeast(7f)
                )
            }
        }
        bufferView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, bufferTextSizeSp)
    }

    /**
     * How the layout splits `bar` between the code box and the candidate row.
     * Captured from the IR so the 編碼區寬度 slider only redistributes their
     * combined share and never grows the bar past `columns` (which used to
     * shrink the `▼` key down to whatever was left).
     */
    private fun captureBufferWidthBase() {
        val slots = runCatching { bufferPage?.spec?.bar?.slots }.getOrNull() ?: return
        val buf = slots.filterIsInstance<BufferSlot>().firstOrNull()
        val cand = slots.filterIsInstance<CandidatesSlot>().firstOrNull()
        barUsesWeight = (buf?.weight ?: 0f) > 0f && (cand?.weight ?: 0f) > 0f
        barBufferWeight = buf?.weight ?: 0f
        barCandidatesWeight = cand?.weight ?: 0f
        barBufferSpan = buf?.span ?: 0f
        barCandidatesSpan = cand?.span ?: 0f
    }

    private fun updateBufferWidthWeight() {
        if (!::rendered.isInitialized) return
        val bar = bufferPage?.barRow ?: return
        val buf = bufferView ?: return
        val cand = candidateScroll ?: return
        // `bufferWidthWeight <= 0` = follow the layout: restore the IR weights
        // (a previously applied override must be undone, not just skipped).
        if (barUsesWeight) {
            // Weight-based bar (e.g. buffer 2 : candidates 9 : hide 1).
            val budget = barBufferWeight + barCandidatesWeight
            val weight = if (bufferWidthWeight > 0f) bufferWidthWeight else barBufferWeight
            bar.setWeight(buf, weight)
            bar.setWeight(cand, (budget - weight).coerceAtLeast(1f))
        } else {
            // Span-based bar: keep buffer + candidates at the layout's total.
            val budget = barBufferSpan + barCandidatesSpan
            val span = if (bufferWidthWeight > 0f) bufferWidthWeight else barBufferSpan
            bar.setSpan(buf, span)
            bar.setSpan(cand, (budget - span).coerceAtLeast(1f))
        }
    }

    private fun updateBuffer() {
        val view = bufferView ?: return
        val slot = bufferSlot
        val text = if (slot?.format == "uppercase") bufferText.uppercase() else bufferText
        view.visibility = View.VISIBLE
        val max = slot?.maxChars ?: 6
        val shown = if (text.length > max) text.takeLast(max) else text
        if (view.text.toString() != shown) view.text = shown
    }

    private fun updateCandidates(alignToSelection: Boolean = true) {
        val container = candidateContainer ?: return
        val scroll = candidateScroll ?: return
        if (isEnglishMode || candidates.isEmpty()) {
            val framed = !isEnglishMode
            val stale = !showingPlaceholder || container.childCount != 1 ||
                placeholderFramed != framed
            if (stale) {
                container.removeAllViews()
                container.addView(createPlaceholderChip(framed))
                showingPlaceholder = true
                placeholderFramed = framed
                styledSelection = -2
                styledTheme = true
            }
            if (scroll.scrollX != 0) {
                scroll.post { scroll.scrollTo(0, 0) }
            }
            return
        }
        if (showingPlaceholder) {
            container.removeAllViews()
            showingPlaceholder = false
            styledSelection = -2
        }

        // Chips are reused, never rebuilt: rebuilding on every keystroke leaves
        // the row blank for one frame and flashes the e-ink panel.
        while (container.childCount > candidates.size) {
            container.removeViewAt(container.childCount - 1)
        }
        while (container.childCount < candidates.size) {
            val chip = createChip()
            paintChip(chip, selected = false)
            container.addView(chip)
        }

        val restyle = styledSelection != selectedCandidateIndex
        var selectedChip: TextView? = null
        for (i in candidates.indices) {
            val chip = container.getChildAt(i) as TextView
            // Only the first %selkey.length candidates have a selection key.
            // Beyond that the chip shows the word alone (no "10字" label): that
            // keeps every chip at the same minimum width, and selection is by
            // tap / D-pad / scrolling anyway.
            val label = if (i < selkeys.length) selkeys[i].toString() else ""
            val chipText = "$label${candidates[i]}"
            if (chip.text.toString() != chipText) chip.text = chipText
            chip.tag = i
            val selected = i == selectedCandidateIndex
            if (selected) selectedChip = chip
            if (restyle) {
                paintChip(chip, selected)
                // Selected chip: bold; others: normal weight
                val weight = if (selected) Typeface.BOLD else Typeface.NORMAL
                chip.setTypeface(chip.typeface, weight)
            }
        }
        styledSelection = selectedCandidateIndex
        styledTheme = true

        if (alignToSelection) {
            scroll.post {
                val maxScroll = (container.width - scroll.width).coerceAtLeast(0)
                val target = ((selectedChip?.left ?: 0) - dp(2)).coerceIn(0, maxScroll)
                if (scroll.scrollX != target) scroll.scrollTo(target, 0)
            }
        }
    }

    private fun createPlaceholderChip(framed: Boolean): TextView = createChip().apply {
        text = ""
        isClickable = false
        paintChip(this, selected = framed)
    }

    private fun createChip(): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, chipTextSizeSp)
        val chipFont = chipSpec?.font
        // Default to normal weight; selected chip will be bold via updateCandidates
        val chipWeight = if (chipFont?.weight.equals("normal", true)) Typeface.NORMAL else Typeface.NORMAL
        setTypeface(FontStore.typefaceFor(context, chipFont?.family), chipWeight)
        gravity = Gravity.CENTER
        isSingleLine = true
        includeFontPadding = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setFallbackLineSpacing(false)
        }
        val padH = dp(chipSpec?.padH ?: 3f)
        setPadding(padH, 0, padH, 0)
        val reference = chipSpec?.minWidthRef ?: PLACEHOLDER_REFERENCE
        minimumWidth = ceil(paint.measureText(reference)).toInt() + padH * 2
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { marginStart = dp(chipSpec?.gap ?: 0.5f) }
        setOnClickListener { v ->
            val index = v.tag as? Int ?: return@setOnClickListener
            if (index in candidates.indices) {
                listener?.onCandidateSelected(index, candidates[index])
            }
        }
    }

    /** Paints a chip's plate / ink from the active theme and selection state.
     * Background is always transparent - selection is indicated by bold text only. */
    private fun paintChip(chip: TextView, selected: Boolean) {
        val style = chipSpec?.style
        // Transparent background for both selected and unselected
        val fill = 0x00000000.toInt() // Transparent
        val ink = themed(
            INK_FALLBACK,
            style?.ink,
            if (selected) "chipSelectedInk" else "chipInk",
            "ink"
        )
        // `border:` applies to every chip (dotted seam).
        // Selection is indicated by **bold text only**, not by a border.
        // So we always use the normal border, never selectedBorder.
        val border = style?.border
        chip.background = if (border != null) chipBorderDrawable(fill, border, ink) else solid(fill)
        chip.setTextColor(ink)
    }

    /** Chip plate with a `border:` stroke (all four sides). */
    private fun chipBorderDrawable(fill: Int, border: BorderSpec, ink: Int): Drawable =
        borderedPlate(fill, border, ink, chipSpec?.style?.radius ?: 0f, "chipMark")

    /** Press-and-hold on ⌫ deletes once immediately, then repeats. */
    private fun attachRepeatDelete(view: View, repeat: RepeatSpec?) {
        val delay = repeat?.delayMs ?: 400L
        val interval = repeat?.intervalMs ?: 80L
        var runnable: Runnable? = null
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    listener?.onBackspace()
                    val tick = object : Runnable {
                        override fun run() {
                            listener?.onBackspace()
                            handler.postDelayed(this, interval)
                        }
                    }
                    runnable = tick
                    handler.postDelayed(tick, delay)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    runnable?.let { handler.removeCallbacks(it) }
                    runnable = null
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    runnable?.let { handler.removeCallbacks(it) }
                    runnable = null
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Space gestures: tap = commit, swipe down (>30 dp) = toggle Chinese/English,
     * swipe left/right = move the text cursor one character per 24 dp travelled.
     */
    private fun attachSpaceGesture(view: View) {
        var startY = 0f
        var startX = 0f
        var swiped = false
        val step = dp(24)
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    startY = event.y
                    startX = event.x
                    swiped = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (!swiped && dy > dp(30) && abs(dy) > abs(dx)) {
                        swiped = true
                        v.isPressed = false
                        listener?.onToggleMode()
                    } else if (abs(dx) > abs(dy) && abs(dx) >= step) {
                        swiped = true
                        v.isPressed = false
                        listener?.onMoveCursor(dx > 0)
                        startX = event.x
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (!swiped) listener?.onSpace()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                else -> true
            }
        }
    }

    /**
     * Swipe up (>16 dp, mostly vertical) on a letter key commits the uppercase
     * letter — English mode only.
     */
    /**
     * Swipe up on the code box commits exactly what the inline composing region
     * shows (per `inline_composing_mode`), e.g. `會 ＡＤ`; a plain tap keeps the
     * classic behaviour of committing the raw code (`onCommitBuffer`).
     */
    private fun attachBufferSwipeUp(view: View?) {
        view ?: return
        var startX = 0f
        var startY = 0f
        var fired = false
        val threshold = dp(16)
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    startX = event.x
                    startY = event.y
                    fired = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (!fired && dy <= -threshold && abs(dy) >= abs(dx)) {
                        fired = true
                        v.isPressed = false
                        listener?.onCommitInlineComposing()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (!fired) v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private fun attachLetterSwipeUp(view: View, ch: Char) {
        var startX = 0f
        var startY = 0f
        var swipedUp = false
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    startX = event.x
                    startY = event.y
                    swipedUp = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (!swipedUp && isEnglishMode && dy < -dp(16) && abs(dy) > abs(dx)) {
                        swipedUp = true
                        v.isPressed = false
                        listener?.onKey(ch.uppercaseChar())
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (!swipedUp) v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()
}
