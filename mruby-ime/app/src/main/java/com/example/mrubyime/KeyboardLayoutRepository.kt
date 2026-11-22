package com.example.mrubyime

import android.content.Context
import android.graphics.Color
import android.util.Log

/** Resolves DSL colour tokens against the active (light/dark) theme. */
class ThemeResolver(private val theme: ThemeSpec?) {

    fun raw(token: String?): String? {
        var value = token
        var depth = 0
        while (value != null && !value.startsWith("#") && depth < 4) {
            value = theme?.colors?.get(value) ?: return null
            depth++
        }
        return value
    }

    fun color(token: String?, fallback: Int): Int {
        val parsed = parseColor(raw(token))
        return if (parsed != null) parsed else fallback
    }

    fun colorOrNull(token: String?): Int? = parseColor(raw(token))

    companion object {
        fun parseColor(value: String?): Int? {
            if (value.isNullOrEmpty()) return null
            val hex = when {
                value.startsWith("#") -> value.substring(1)
                else -> return null
            }
            return try {
                when (hex.length) {
                    3 -> {
                        val r = hex.substring(0, 1).toInt(16)
                        val g = hex.substring(1, 2).toInt(16)
                        val b = hex.substring(2, 3).toInt(16)
                        Color.rgb(r * 17, g * 17, b * 17)
                    }
                    6 -> (0xFF000000.toInt()) or hex.toInt(16)
                    8 -> hex.toLong(16).toInt()
                    else -> null
                }
            } catch (t: Throwable) {
                null
            }
        }
    }
}

/**
 * Loads the IR: a user-supplied `layouts/keyboard.rb` first (storage lives in
 * [KeyboardLayoutFiles]), then the runtime mruby built-in layout, then the
 * build-time JSON asset, then a tiny programmatic emergency layout.
 */
object KeyboardLayoutRepository {

    /** Context used to validate a layout from the settings screen (no editor info there). */
    const val DEFAULT_CONTEXT = "mode=chinese\ntheme=light\ninput_type=text\ndensity=2.75\n"

    /** Sentinel from `layoutFromSourceSerialized`: rest of the string is an error. */
    const val LAYOUT_ERROR_PREFIX = "\u0001"

    private const val TAG = "MrubyimeLayout"

    fun builtinFromEngine(engine: MrubyimeEngineDelegate, ctx: String): KeyboardLayout? {
        return try {
            val json = engine.layoutSerialized(ctx.toByteArray(Charsets.UTF_8))
            KeyboardLayoutParser.parseOrNull(json)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Outcome of compiling a DSL source: either the parsed layout or a
     * human-readable error (with `line N:C:` positions when the parser knows
     * them).
     */
    class LayoutSourceResult(val layout: KeyboardLayout?, val error: String?) {
        val ok: Boolean get() = layout != null
    }

    /**
     * Compiles a DSL source and keeps the error message.
     *
     * The JNI sandbox returns `"\u0001" + message` when the source does not
     * compile, so the caller can show the user which line is broken instead of
     * a bare "匯入失敗".
     */
    fun validateSource(
        engine: MrubyimeEngineDelegate,
        source: String,
        ctx: String
    ): LayoutSourceResult {
        val raw = try {
            engine.layoutFromSourceSerialized(
                source.toByteArray(Charsets.UTF_8),
                ctx.toByteArray(Charsets.UTF_8)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "layout sandbox call failed", t)
            return LayoutSourceResult(null, "無法呼叫 DSL 沙箱：${t.message}")
        }
        if (raw.startsWith(LAYOUT_ERROR_PREFIX)) {
            val message = raw.substring(LAYOUT_ERROR_PREFIX.length).trim()
            Log.e(TAG, "layout compile error: $message")
            return LayoutSourceResult(null, message.ifEmpty { "DSL 無法編譯" })
        }
        val parsed = KeyboardLayoutParser.parseOrNull(raw)
        if (parsed == null) {
            Log.e(TAG, "layout IR is not valid JSON: ${raw.take(200)}")
            return LayoutSourceResult(null, "DSL 執行完但產生的 IR 不是合法 JSON")
        }
        // A layout with no page would blank the keyboard; treat it as invalid.
        if (parsed.pages.isEmpty()) {
            Log.e(TAG, "layout IR has no page")
            return LayoutSourceResult(null, "DSL 沒有產生任何 page（至少需要一個 page :chinese do … end）")
        }
        return LayoutSourceResult(parsed, null)
    }

    fun fromSource(
        engine: MrubyimeEngineDelegate,
        source: String,
        ctx: String
    ): KeyboardLayout? = validateSource(engine, source, ctx).layout

    fun fromAssets(context: Context): KeyboardLayout? {
        return try {
            val json = context.assets.open("keyboard_layout.json").use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            KeyboardLayoutParser.parseOrNull(json)
        } catch (t: Throwable) {
            null
        }
    }

    /** Validates a DSL source without needing the engine to be initialised. */
    fun fromSourceDirect(source: String, ctx: String = DEFAULT_CONTEXT): KeyboardLayout? =
        validateSourceDirect(source, ctx).layout

    /** Like [fromSourceDirect] but keeps the error message (for the settings screen). */
    fun validateSourceDirect(source: String, ctx: String = DEFAULT_CONTEXT): LayoutSourceResult =
        validateSource(MrubyimeEngineDelegateImpl(), source, ctx)

    fun load(context: Context, engine: MrubyimeEngineDelegate?, ctx: String): KeyboardLayout {
        // 1) User layout (layouts/keyboard.rb, mirror-synced), 2) built-in runtime DSL,
        // 3) build-time JSON asset, 4) emergency layout.
        KeyboardLayoutFiles.keyboardSource(context)?.let { source ->
            if (engine != null) {
                val result = validateSource(engine, source, ctx)
                if (result.ok) {
                    Log.i(TAG, "layout loaded from ${KeyboardLayoutFiles.keyboardFile(context).absolutePath}")
                    return result.layout!!
                }
                Log.e(TAG, "user layout failed (${result.error}); falling back to built-in")
            }
        }
        engine?.let { builtinFromEngine(it, ctx) }?.let { return it }
        return fromAssets(context) ?: emergencyLayout()
    }

    // ------------------------------------------------------------------ emergency
    // Only used if both the mruby state and the generated asset are unavailable.
    // Functional but deliberately small: digits, letters, backspace, space, enter.
    fun emergencyLayout(): KeyboardLayout {
        fun key(
            label: String,
            span: Float = 1f,
            role: String? = null,
            action: String = "code",
            value: String? = null,
            flat: Boolean = false
        ) = KeySpec(
            id = null,
            role = role,
            span = span,
            weight = 0f,
            label = label,
            subLabel = null,
            icon = null,
            desc = null,
            transform = null,
            align = null,
            style = StyleSpec(
                flat = flat, invisible = false, bg = null, ink = null,
                radius = 0f, border = null, selectedBorder = null
            ),
            font = null,
            action = ActionSpec(action, value ?: if (action == "code") label else null),
            gestures = emptyMap(),
            longPress = null,
            repeat = null,
            haptic = "none"
        )

        val letters = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf("z", "x", "c", "v")
        )
        val rows = mutableListOf<RowSpec>()
        rows.add(RowSpec(1f, RowAlign.START, null, (1..9).map { key(it.toString()) } + key("0")))
        rows.add(RowSpec(1f, RowAlign.START, null, letters[0].map { key(it) }))
        rows.add(
            RowSpec(
                1f, RowAlign.START, null,
                letters[1].map { key(it) } + key("⌫", role = "backspace", action = "backspace")
            )
        )
        rows.add(
            RowSpec(
                1f, RowAlign.START, null,
                letters[2].map { key(it) } +
                    key("", span = 2f, role = "space", action = "space") +
                    listOf("b", "n", "m").map { key(it) } +
                    key("⏎", role = "enter", action = "enter")
            )
        )

        val chip = ChipSpec(
            font = FontSpec(24f, "bold", null, null),
            padH = 3f,
            gap = 0.5f,
            format = "%s%s",
            minWidthRef = "0微",
            style = StyleSpec(
                flat = true, invisible = false, bg = "bar", ink = "ink",
                radius = 0f, border = null,
                selectedBorder = BorderSpec(3f, "ink")
            ),
            framedWhen = "chinese"
        )
        val bar = BarSpec(
            height = 48f,
            bg = "bar",
            gap = 1f,
            slots = listOf(
                BufferSlot(1.3f, "buffer", "uppercase", 6, null, null),
                CandidatesSlot(5.7f, "candidate_row", chip, true),
                KeySlot(key("▼", 0.5f, role = "hide", action = "ime_hide", flat = true))
            )
        )
        val theme = ThemeSpec(
            colors = mapOf(
                "bar" to "#3A3A3C",
                "key" to "#3A3A3C",
                "keyPressed" to "#505054",
                "ink" to "#FFFFFF",
                "muted" to "#8E8E93",
                "seam" to "#1C1C1E"
            ),
            states = emptyMap()
        )
        return KeyboardLayout(
            version = 1,
            grid = GridSpec(10f, 0f, 0f, 0f),
            height = HeightSpec(48f, 240f),
            padding = PaddingSpec(0f, 0f, 0f),
            minTouch = 48,
            themes = mapOf("dark" to theme),
            defaultPage = "chinese",
            pages = listOf(PageSpec("chinese", bar, rows))
        )
    }
}
