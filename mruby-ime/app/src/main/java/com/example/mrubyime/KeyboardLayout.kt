package com.example.mrubyime

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsed keyboard layout IR (schema v1). Produced by the mruby DSL
 * (`keyboard_dsl.rb` + `keyboard_layout.rb`); user-imported layouts use the
 * same schema. Keep this model dumb: styling decisions belong to the renderer,
 * behaviour decisions belong to MrubyimeIME.
 */
data class KeyboardLayout(
    val version: Int,
    val grid: GridSpec,
    val height: HeightSpec,
    val padding: PaddingSpec,
    val minTouch: Int,
    val themes: Map<String, ThemeSpec>,
    val defaultPage: String,
    val pages: List<PageSpec>,
    /** Layout-wide key sound (`sound :click, volume: 0.6`). */
    val sound: SoundSpec? = null
) {
    fun page(name: String?): PageSpec? =
        name?.let { n -> pages.firstOrNull { it.name == n } } ?: pages.firstOrNull()

    fun theme(light: Boolean): ThemeSpec? =
        themes[if (light) "light" else "dark"] ?: themes.values.firstOrNull()
}

data class GridSpec(
    val columns: Float,
    val gapH: Float,
    val gapV: Float,
    /** Inset kept at the outer left/right edge of a row (dp). */
    val outer: Float
)
data class HeightSpec(val bar: Float, val body: Float)
data class PaddingSpec(val left: Float, val right: Float, val bottom: Float)

/** Key sound: `default` is "click"/"system"/"none" or a file name; `volume` 0..1. */
data class SoundSpec(val default: String?, val volume: Float)

data class ThemeSpec(
    val colors: Map<String, String>,
    val states: Map<String, Map<String, String>>
)

data class PageSpec(
    val name: String,
    val bar: BarSpec?,
    val rows: List<RowSpec>
)

data class BarSpec(
    val height: Float?,
    val bg: String,
    val gap: Float,
    val slots: List<SlotSpec>,
    /** Only used when the slots' spans leave part of the grid unused. */
    val align: RowAlign = RowAlign.START,
    /** `bar border:` — drawn inset, so it never widens the bar. */
    val border: BorderSpec? = null
)

/**
 * One bar cell. `span` is a fixed share of the `columns` grid; slots with a
 * `weight > 0` instead share whatever the weightless slots leave over, exactly
 * like keys in a row (so `buffer weight: 2` + `candidates weight: 9` +
 * `hide weight: 1` splits the whole bar 2:9:1).
 */
sealed class SlotSpec {
    abstract val span: Float
    abstract val weight: Float
}

data class BufferSlot(
    override val span: Float,
    val role: String,
    val format: String,
    val maxChars: Int,
    val font: FontSpec?,
    val style: StyleSpec?,
    override val weight: Float = 0f
) : SlotSpec()

data class CandidatesSlot(
    override val span: Float,
    val role: String,
    val chip: ChipSpec,
    val autoscroll: Boolean,
    override val weight: Float = 0f
) : SlotSpec()

data class GroupSlot(
    val id: String,
    override val span: Float,
    val gap: Float,
    val keys: List<KeySpec>,
    override val weight: Float = 0f
) : SlotSpec()

data class KeySlot(val key: KeySpec) : SlotSpec() {
    override val span: Float get() = key.span
    override val weight: Float get() = key.weight
}

/** Read-only status lamps (CapsLock / Shift / Ctrl / Alt / Super / mode …). */
data class StatusSlot(
    val id: String,
    override val span: Float,
    val gap: Float,
    val lamps: List<LampSpec>,
    override val weight: Float = 0f
) : SlotSpec()

data class LampSpec(
    val state: String,
    val label: String,
    val span: Float,
    val onInk: String?,
    val offInk: String?
)

enum class RowAlign { START, CENTER, END, SPACE_BETWEEN }

data class RowSpec(
    val height: Float,
    val align: RowAlign,
    val gapH: Float?,
    val keys: List<KeySpec>
)

data class KeySpec(
    val id: String?,
    val role: String?,
    val span: Float,
    val weight: Float,
    val label: String,
    val subLabel: String?,
    val icon: String?,
    val desc: String?,
    val transform: String?,
    /** Text alignment inside the key: "left"/"start", "center" (default), "right"/"end". */
    val align: String?,
    /** How a long label fits its cell: "wrap" (default for table `%keyname` labels), "shrink", "none". */
    val labelFit: String? = null,
    /** Glyphs per line when `label_fit: :wrap` (default 2). */
    val labelWrap: Int = 2,
    /** Mode-dependent text: `label_zh` (Chinese mode) / `label_en` (English mode). */
    val labelZh: String? = null,
    val labelEn: String? = null,
    val subLabelZh: String? = null,
    val subLabelEn: String? = null,
    /** Per-key sound override ("click"/"none"/file name) and its volume (0..1). */
    val sound: String? = null,
    val soundVolume: Float? = null,
    val style: StyleSpec,
    val font: FontSpec?,
    val action: ActionSpec,
    val gestures: Map<String, ActionSpec>,
    val longPress: ActionSpec?,
    val repeat: RepeatSpec?,
    val haptic: String
) {
    /** Visible label for the current mode (falls back to `label`), transform applied. */
    fun textFor(english: Boolean): String {
        val raw = (if (english) labelEn else labelZh) ?: label
        return when (transform) {
            "upper" -> raw.uppercase()
            "lower" -> raw.lowercase()
            else -> raw
        }
    }

    fun subTextFor(english: Boolean): String? =
        (if (english) subLabelEn else subLabelZh) ?: subLabel
}

data class ActionSpec(val name: String, val value: String?)data class RepeatSpec(val delayMs: Long, val intervalMs: Long)

data class FontSpec(
    val size: Float?,
    val weight: String?,
    val gravity: String?,
    val ellipsize: String?,
    /** Font file name (see [FontStore]); null/empty = system typeface. */
    val family: String? = null
)

data class StyleSpec(
    val flat: Boolean,
    val invisible: Boolean,
    val bg: String?,
    val ink: String?,
    val radius: Float,
    val border: BorderSpec?,
    val selectedBorder: BorderSpec?
)

data class BorderSpec(
    val width: Float,
    val color: String,
    /** `solid` (default), `dashed` or `dotted`; anything else draws solid. */
    val style: String = "solid",
    /** `all` (default) or one of `top`/`bottom`/`left`/`right`. */
    val side: String = "all"
) {
    val dotted: Boolean get() = style.equals("dotted", ignoreCase = true)
    val dashed: Boolean get() = !dotted && style.equals("dashed", ignoreCase = true)
}


data class ChipSpec(
    val font: FontSpec,
    val padH: Float,
    val gap: Float,
    val format: String,
    val minWidthRef: String,
    val style: StyleSpec,
    val framedWhen: String?
)

/** Parses the IR JSON emitted by mruby. Returns null on malformed input. */
object KeyboardLayoutParser {

    fun parseOrNull(json: String?): KeyboardLayout? {
        if (json.isNullOrBlank()) return null
        return try {
            parse(JSONObject(json))
        } catch (t: Throwable) {
            null
        }
    }

    fun parse(root: JSONObject): KeyboardLayout {
        val grid = root.optJSONObject("grid")
        val height = root.optJSONObject("height")
        val padding = root.optJSONObject("padding")

        val themes = mutableMapOf<String, ThemeSpec>()
        val themesObj = root.optJSONObject("themes")
        if (themesObj != null) {
            for (name in themesObj.keys()) {
                themes[name] = parseTheme(themesObj.optJSONObject(name) ?: JSONObject())
            }
        }

        val pages = mutableListOf<PageSpec>()
        val pagesArr = root.optJSONArray("pages") ?: JSONArray()
        for (i in 0 until pagesArr.length()) {
            pagesArr.optJSONObject(i)?.let { pages.add(parsePage(it)) }
        }

        return KeyboardLayout(
            version = root.optInt("v", 1),
            grid = GridSpec(
                columns = grid.floatOr("columns", 10f).coerceAtLeast(1f),
                gapH = grid.floatOr("gapH", 1f),
                gapV = grid.floatOr("gapV", 1f),
                outer = grid.floatOr("outer", 0.5f)
            ),
            height = HeightSpec(
                bar = height.floatOr("bar", 48f),
                body = height.floatOr("body", 240f)
            ),
            padding = PaddingSpec(
                left = padding.floatOr("left", 0f),
                right = padding.floatOr("right", 0f),
                bottom = padding.floatOr("bottom", 0f)
            ),
            minTouch = root.optInt("minTouch", 48),
            themes = themes,
            defaultPage = root.optString("defaultPage", pages.firstOrNull()?.name ?: ""),
            pages = pages,
            sound = root.optJSONObject("sound")?.let {
                SoundSpec(
                    default = it.strOrNull("default"),
                    volume = it.floatOr("volume", 1f)
                )
            }
        )
    }

    private fun parseTheme(o: JSONObject): ThemeSpec {
        val colors = mutableMapOf<String, String>()
        o.optJSONObject("colors")?.let { c ->
            for (k in c.keys()) colors[k] = c.optString(k, "")
        }
        val states = mutableMapOf<String, Map<String, String>>()
        o.optJSONObject("states")?.let { s ->
            for (name in s.keys()) {
                val m = mutableMapOf<String, String>()
                s.optJSONObject(name)?.let { c ->
                    for (k in c.keys()) m[k] = c.optString(k, "")
                }
                states[name] = m
            }
        }
        return ThemeSpec(colors, states)
    }

    private fun parsePage(o: JSONObject): PageSpec {
        val rows = mutableListOf<RowSpec>()
        val rowsArr = o.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until rowsArr.length()) {
            rowsArr.optJSONObject(i)?.let { rows.add(parseRow(it)) }
        }
        return PageSpec(
            name = o.optString("name", "page"),
            bar = o.optJSONObject("bar")?.let { parseBar(it) },
            rows = rows
        )
    }

    private fun parseBar(o: JSONObject): BarSpec {
        val slots = mutableListOf<SlotSpec>()
        val arr = o.optJSONArray("slots") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            slots.add(parseSlot(s))
        }
        return BarSpec(
            height = if (o.has("height") && !o.isNull("height")) o.getDouble("height").toFloat() else null,
            bg = o.optString("bg", "bar"),
            gap = o.floatOr("gap", 1f),
            slots = slots,
            align = parseRowAlign(o.optString("align", "start")),
            border = o.optJSONObject("border")?.let { parseBorder(it, defaultWidth = 0f) }
        )
    }

    /**
     * `border: { width:, color:, style:, side: }` for the bar and the chips.
     * `style` is solid/dashed/dotted; `side` narrows it to one edge (chips
     * default to a bottom rule when `selected.border.bottom:` is used).
     */
    private fun parseBorder(o: JSONObject, defaultWidth: Float, defaultSide: String = "all"): BorderSpec? {
        // Legacy `selected.border: { bottom: 3 }` is a bottom rule; a new
        // `border:`/`border_selected:` hash writes `side` explicitly (default
        // `all`), so `bottom` here also implies the single bottom edge.
        val legacyBottom = o.has("bottom") && !o.has("width")
        val width = o.floatOr("width", if (o.has("bottom")) o.floatOr("bottom", defaultWidth) else defaultWidth)
        if (width <= 0f) return null
        return BorderSpec(
            width = width,
            color = o.optString("color", "ink"),
            style = o.optString("style", "solid"),
            side = o.optString("side", if (legacyBottom) "bottom" else defaultSide)
        )
    }

    /** Shared by `row align:` and `bar align:`. */
    private fun parseRowAlign(value: String): RowAlign = when (value) {
        "center", "middle" -> RowAlign.CENTER
        "end", "right" -> RowAlign.END
        "space_between", "justify", "between" -> RowAlign.SPACE_BETWEEN
        else -> RowAlign.START
    }

    private fun parseSlot(o: JSONObject): SlotSpec {
        val span = o.floatOr("span", 1f)
        val weight = o.floatOr("weight", 0f)
        return when (o.optString("type", "key")) {
            "buffer" -> BufferSlot(
                span = span,
                role = o.optString("role", "buffer"),
                format = o.optString("format", "uppercase"),
                maxChars = o.optInt("maxChars", 6),
                font = o.optJSONObject("font")?.let { parseFont(it) },
                style = o.optJSONObject("style")?.let { parseStyle(it) },
                weight = weight
            )
            "candidates" -> CandidatesSlot(
                span = span,
                role = o.optString("role", "candidate_row"),
                chip = parseChip(o.optJSONObject("chip") ?: JSONObject()),
                autoscroll = o.optBoolean("autoscroll", true),
                weight = weight
            )
            "group" -> {
                val keys = mutableListOf<KeySpec>()
                val arr = o.optJSONArray("keys") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { keys.add(parseKey(it, "g$i")) }
                }
                GroupSlot(
                    id = o.optString("id", "group"),
                    span = span,
                    gap = o.floatOr("gap", 0f),
                    keys = keys,
                    weight = weight
                )
            }
            "status" -> {
                val lamps = mutableListOf<LampSpec>()
                val arr = o.optJSONArray("lamps") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val l = arr.optJSONObject(i) ?: continue
                    lamps.add(
                        LampSpec(
                            state = l.optString("state", ""),
                            label = l.optString("label", ""),
                            span = l.floatOr("span", 1f),
                            onInk = l.strOrNull("onInk"),
                            offInk = l.strOrNull("offInk")
                        )
                    )
                }
                StatusSlot(
                    id = o.optString("id", "status"),
                    span = span,
                    gap = o.floatOr("gap", 0f),
                    lamps = lamps,
                    weight = weight
                )
            }
            else -> KeySlot(parseKey(o.optJSONObject("key") ?: JSONObject(), "k"))
        }
    }

    private fun parseRow(o: JSONObject): RowSpec {
        val keys = mutableListOf<KeySpec>()
        val arr = o.optJSONArray("keys") ?: JSONArray()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { keys.add(parseKey(it, "k$i")) }
        }
        val align = parseRowAlign(o.optString("align", "start"))
        return RowSpec(
            height = o.floatOr("height", 1f),
            align = align,
            gapH = if (o.has("gapH") && !o.isNull("gapH")) o.getDouble("gapH").toFloat() else null,
            keys = keys
        )
    }

    private fun parseKey(o: JSONObject, fallbackId: String): KeySpec {
        val gestures = mutableMapOf<String, ActionSpec>()
        o.optJSONObject("gestures")?.let { g ->
            for (k in g.keys()) {
                g.optJSONObject(k)?.let { gestures[k] = parseAction(it) }
            }
        }
        return KeySpec(
            id = o.strOrNull("id") ?: fallbackId,
            role = o.strOrNull("role"),
            span = o.floatOr("span", 1f),
            weight = o.floatOr("weight", 0f),
            label = o.optString("label", ""),
            subLabel = o.strOrNull("subLabel"),
            icon = o.strOrNull("icon"),
            desc = o.strOrNull("desc"),
            transform = o.strOrNull("transform"),
            align = o.strOrNull("align"),
            labelFit = o.strOrNull("labelFit"),
            labelWrap = o.optInt("labelWrap", 2),
            labelZh = o.strOrNull("labelZh"),
            labelEn = o.strOrNull("labelEn"),
            subLabelZh = o.strOrNull("subLabelZh"),
            subLabelEn = o.strOrNull("subLabelEn"),
            sound = o.strOrNull("sound"),
            soundVolume = if (o.has("soundVolume") && !o.isNull("soundVolume")) {
                o.getDouble("soundVolume").toFloat()
            } else {
                null
            },
            style = o.optJSONObject("style")?.let { parseStyle(it) } ?: StyleSpec(
                flat = false, invisible = false, bg = null, ink = null,
                radius = 0f, border = null, selectedBorder = null
            ),
            font = o.optJSONObject("font")?.let { parseFont(it) },
            action = o.optJSONObject("action")?.let { parseAction(it) } ?: ActionSpec("noop", null),
            gestures = gestures,
            longPress = o.optJSONObject("longPress")?.let { parseAction(it) },
            repeat = o.optJSONObject("repeat")?.let {
                RepeatSpec(
                    delayMs = it.optLong("delay", 400L),
                    intervalMs = it.optLong("interval", 80L)
                )
            },
            haptic = o.optString("haptic", "none")
        )
    }

    private fun parseFont(o: JSONObject): FontSpec = FontSpec(
        size = if (o.has("size") && !o.isNull("size")) o.getDouble("size").toFloat() else null,
        weight = o.strOrNull("weight"),
        gravity = o.strOrNull("gravity"),
        ellipsize = o.strOrNull("ellipsize"),
        family = o.strOrNull("family")
    )

    private fun parseStyle(o: JSONObject): StyleSpec {
        var selectedBorder: BorderSpec? = null
        o.optJSONObject("selected")?.optJSONObject("border")?.let {
            selectedBorder = parseBorder(it, defaultWidth = 3f)
        }
        return StyleSpec(
            flat = o.optBoolean("flat", false),
            invisible = o.optBoolean("invisible", false),
            bg = o.strOrNull("bg"),
            ink = o.strOrNull("ink"),
            radius = o.floatOr("radius", 0f),
            border = o.optJSONObject("border")?.let { parseBorder(it, defaultWidth = 1f) },
            selectedBorder = selectedBorder
        )
    }

    private fun parseChip(o: JSONObject): ChipSpec {
        val style = o.optJSONObject("style")?.let { parseStyle(it) }
        return ChipSpec(
            font = o.optJSONObject("font")?.let { parseFont(it) }
                ?: FontSpec(24f, "bold", null, null),
            padH = o.floatOr("padH", 3f),
            gap = o.floatOr("gap", 0.5f),
            format = o.optString("format", "%s%s"),
            minWidthRef = o.optString("minWidthRef", "0微"),
            style = style ?: StyleSpec(
                flat = true, invisible = false, bg = "bar", ink = "ink",
                radius = 0f, border = null, selectedBorder = null
            ),
            framedWhen = o.optJSONObject("placeholder")?.strOrNull("framedWhen")
        )
    }

    private fun parseAction(o: JSONObject): ActionSpec =
        ActionSpec(o.optString("a", "noop"), o.strOrNull("v"))
}

private fun JSONObject?.strOrNull(key: String): String? {
    if (this == null || !has(key) || isNull(key)) return null
    return getString(key)
}

private fun JSONObject?.floatOr(key: String, def: Float): Float {
    if (this == null || !has(key) || isNull(key)) return def
    return getDouble(key).toFloat()
}
