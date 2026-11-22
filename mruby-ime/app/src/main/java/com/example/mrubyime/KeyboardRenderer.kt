package com.example.mrubyime

import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class KeyView(
    val view: KeyFrameLayout,
    val label: TextView,
    val subLabelView: TextView?,
    val spec: KeySpec,
    val isBar: Boolean,
    val fontScale: Float,
    /**
     * Which grid sides this key paints. Only the left-most key of a row keeps its
     * left edge and only the first row keeps its top edge, so two flush neighbours
     * share a single 1 dp seam instead of stacking two borders (~2× thick).
     */
    val borderLeft: Boolean = true,
    val borderTop: Boolean = true
)

/** One read-only status lamp (`status` bar slot); no click action. */
class LampView(val view: TextView, val spec: LampSpec)

class PageViews(
    val spec: PageSpec,
    val barContainer: ViewGroup?,
    val barRow: KeyRowLayout?,
    val rowsContainer: ViewGroup,
    val keyViews: MutableList<KeyView>,
    val bufferView: TextView?,
    val candidateScroll: StepScrollView?,
    val candidateContainer: LinearLayout?,
    val chipSpec: ChipSpec?,
    val bufferSlot: BufferSlot?,
    val lamps: MutableList<LampView> = mutableListOf()
)

class RenderedKeyboard(
    val layout: KeyboardLayout,
    val barHost: ViewGroup,
    val rowsHost: ViewGroup,
    val pages: Map<String, PageViews>
) {
    fun showPage(name: String?) {
        val target = (name?.let { pages[it] }) ?: pages.values.firstOrNull() ?: return
        for (page in pages.values) {
            page.barContainer?.visibility =
                if (page === target) View.VISIBLE else View.GONE
            page.rowsContainer.visibility =
                if (page === target) View.VISIBLE else View.GONE
        }
    }
}

/**
 * Turns the DSL IR into a plain View tree. No Canvas, no custom drawing:
 * keys are TextViews, the bar is a [KeyRowLayout], candidates stay in a
 * [StepScrollView] and are managed (pooled) by [KeyboardView].
 */
class KeyboardRenderer(
    private val context: Context,
    private val layout: KeyboardLayout
) {

    private val density = context.resources.displayMetrics.density
    private fun px(dp: Float): Float = dp * density
    private fun pxInt(dp: Float): Int = (dp * density).roundToInt().coerceAtLeast(0)

    fun render(root: LinearLayout): RenderedKeyboard {
        val barHost = FrameLayout(context).apply {
            id = R.id.key_bar
            background = solid(0xFF3A3A3C.toInt())
        }
        // `bar border:` is drawn inset by KeyboardView.applyBarBorder(), which pads
        // each page's bar container; that padding must be able to show the stroke.
        barHost.clipToPadding = false
        val rowsHost = FrameLayout(context).apply { id = R.id.key_rows }

        root.addView(
            barHost,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                pxInt(layout.height.bar)
            )
        )
        root.addView(
            rowsHost,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val pages = mutableMapOf<String, PageViews>()
        for (page in layout.pages) {
            val keyViews = mutableListOf<KeyView>()
            var buffer: TextView? = null
            var candidateScroll: StepScrollView? = null
            var candidateContainer: LinearLayout? = null
            var chipSpec: ChipSpec? = null
            var bufferSlot: BufferSlot? = null
            val pageLamps = mutableListOf<LampView>()

            var barRow: KeyRowLayout? = null
            var barContainer: ViewGroup? = null
            if (page.bar != null) {
                val built = buildBar(page.bar)
                barContainer = built.container
                barRow = built.row
                buffer = built.buffer
                candidateScroll = built.candidateScroll
                candidateContainer = built.candidateContainer
                chipSpec = built.chipSpec
                bufferSlot = built.bufferSlot
                keyViews.addAll(built.keyViews)
                pageLamps.addAll(built.lamps)
                barHost.addView(
                    barContainer,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            // Rows tile the body exactly; gap_v is painted inside each key's
            // plate (KeyFrameLayout) so the seam stays touchable.
            val rowsContainer = RowsLayout(context).apply {
                gapV = px(layout.grid.gapV)
            }
            val gapV = px(layout.grid.gapV)
            val lastRow = page.rows.size - 1
            page.rows.forEachIndexed { index, row ->
                val gapH = px(row.gapH ?: layout.grid.gapH)
                val rowLayout = KeyRowLayout(context).apply {
                    columns = layout.grid.columns
                    gapBetweenPx = gapH
                    gapOuterPx = px(layout.grid.outer)
                    align = row.align
                    touchContiguous = true
                }
                val lastKey = row.keys.size - 1
                row.keys.forEachIndexed { keyIndex, key ->
                    // External edges only on the first column / first row.
                    val kv = createKeyView(
                        key,
                        isBar = false,
                        borderLeft = keyIndex == 0,
                        borderTop = index == 0
                    )
                    kv.view.setVisualInsets(
                        if (keyIndex > 0) (gapH / 2f).roundToInt() else 0,
                        if (index > 0) (gapV / 2f).roundToInt() else 0,
                        if (keyIndex < lastKey) (gapH / 2f).roundToInt() else 0,
                        if (index < lastRow) (gapV / 2f).roundToInt() else 0
                    )
                    keyViews.add(kv)
                    rowLayout.addItem(kv.view, key.span, key.weight)
                }
                rowsContainer.addRow(rowLayout, row.height)
            }
            rowsHost.addView(
                rowsContainer,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            pages[page.name] = PageViews(
                spec = page,
                barContainer = barContainer,
                barRow = barRow,
                rowsContainer = rowsContainer,
                keyViews = keyViews,
                bufferView = buffer,
                candidateScroll = candidateScroll,
                candidateContainer = candidateContainer,
                chipSpec = chipSpec,
                bufferSlot = bufferSlot,
                lamps = pageLamps
            )
        }

        val rendered = RenderedKeyboard(layout, barHost, rowsHost, pages)
        rendered.showPage(layout.defaultPage)
        return rendered
    }

    private data class BuiltBar(
        val container: ViewGroup,
        val row: KeyRowLayout,
        val buffer: TextView?,
        val candidateScroll: StepScrollView?,
        val candidateContainer: LinearLayout?,
        val chipSpec: ChipSpec?,
        val bufferSlot: BufferSlot?,
        val keyViews: List<KeyView>,
        val lamps: List<LampView>
    )

    private fun buildBar(bar: BarSpec): BuiltBar {
        val row = KeyRowLayout(context).apply {
            columns = layout.grid.columns
            gapBetweenPx = px(bar.gap)
            gapOuterPx = px(layout.grid.outer)
            align = bar.align
        }
        var buffer: TextView? = null
        var scroll: StepScrollView? = null
        var container: LinearLayout? = null
        var chip: ChipSpec? = null
        var bufferSlot: BufferSlot? = null
        val keyViews = mutableListOf<KeyView>()
        val lamps = mutableListOf<LampView>()

        for (slot in bar.slots) {
            when (slot) {
                is BufferSlot -> {
                    bufferSlot = slot
                    val tv = createBufferView(slot)
                    buffer = tv
                    row.addItem(tv, slot.span, slot.weight)
                }
                is CandidatesSlot -> {
                    val sv = StepScrollView(context).apply {
                        id = R.id.candidate_scroll
                        isFillViewport = false
                        isHorizontalScrollBarEnabled = false
                    }
                    val inner = LinearLayout(context).apply {
                        id = R.id.candidate_container
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    sv.addView(
                        inner,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    scroll = sv
                    container = inner
                    chip = slot.chip
                    row.addItem(sv, slot.span, slot.weight)
                }
                is GroupSlot -> {
                    val groupGap = px(slot.gap)
                    val group = KeyRowLayout(context).apply {
                        var total = 0f
                        for (k in slot.keys) total += k.span
                        columns = if (total > 0f) total else 1f
                        gapBetweenPx = groupGap
                        gapOuterPx = px(0f)
                        touchContiguous = true
                    }
                    group.id = when (slot.id) {
                        "punct" -> R.id.punct_container
                        "symbols" -> R.id.symbol_container
                        else -> View.generateViewId()
                    }
                    val lastKey = slot.keys.size - 1
                    slot.keys.forEachIndexed { keyIndex, key ->
                        val kv = createKeyView(key, isBar = true)
                        kv.view.setVisualInsets(
                            if (keyIndex > 0) (groupGap / 2f).roundToInt() else 0,
                            0,
                            if (keyIndex < lastKey) (groupGap / 2f).roundToInt() else 0,
                            0
                        )
                        keyViews.add(kv)
                        group.addItem(kv.view, key.span, key.weight)
                    }
                    row.addItem(group, slot.span, slot.weight)
                }
                is KeySlot -> {
                    val kv = createKeyView(slot.key, isBar = true)
                    keyViews.add(kv)
                    row.addItem(kv.view, slot.key.span, slot.key.weight)
                }
                is StatusSlot -> {
                    val strip = KeyRowLayout(context).apply {
                        var total = 0f
                        for (lamp in slot.lamps) total += lamp.span
                        columns = if (total > 0f) total else 1f
                        gapBetweenPx = px(slot.gap)
                        gapOuterPx = px(0f)
                        align = RowAlign.CENTER
                    }
                    for (lamp in slot.lamps) {
                        val lv = LampView(createLampView(lamp), lamp)
                        lamps.add(lv)
                        strip.addItem(lv.view, lamp.span, 0f)
                    }
                    row.addItem(strip, slot.span, slot.weight)
                }
            }
        }

        val frame = FrameLayout(context).apply { addView(row) }
        return BuiltBar(frame, row, buffer, scroll, container, chip, bufferSlot, keyViews, lamps)
    }

    /** Small bold, non-clickable text used by `status` lamps. */
    private fun createLampView(lamp: LampSpec): TextView = TextView(context).apply {
        id = View.generateViewId()
        text = lamp.label
        gravity = Gravity.CENTER
        includeFontPadding = false
        isSingleLine = true
        isClickable = false
        isFocusable = false
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            setFallbackLineSpacing(false)
        }
        setPadding(0, 0, 0, 0)
    }

    private fun createBufferView(slot: BufferSlot): TextView = TextView(context).apply {
        id = R.id.key_buffer
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(pxInt(6f), 0, pxInt(2f), 0)
        ellipsize = android.text.TextUtils.TruncateAt.START
        isSingleLine = true
        includeFontPadding = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setFallbackLineSpacing(false)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, slot.font?.size ?: 24f)
        setTypeface(FontStore.typefaceFor(context, slot.font?.family), weightOf(slot.font?.weight))
    }

    /** `font weight: :normal` / `:bold` (default bold, like the rest of the keyboard). */
    private fun weightOf(weight: String?): Int = when (weight?.lowercase()) {
        "normal", "regular", "400" -> android.graphics.Typeface.NORMAL
        else -> android.graphics.Typeface.BOLD
    }

    private fun createKeyView(
        spec: KeySpec,
        isBar: Boolean,
        borderLeft: Boolean = true,
        borderTop: Boolean = true
    ): KeyView {
        val scale = fontScale(spec, isBar)
        val baseSp = spec.font?.size ?: 18f
        val labelSp = baseSp * scale
        // Per-key text alignment (`align:` in the DSL); centre is the default.
        val textGravity = when (spec.align?.lowercase()) {
            "left", "start" -> Gravity.START or Gravity.CENTER_VERTICAL
            "right", "end" -> Gravity.END or Gravity.CENTER_VERTICAL
            else -> Gravity.CENTER
        }
        val padH = if (textGravity == Gravity.CENTER) 0 else pxInt(8f)
        val label = TextView(context).apply {
            gravity = textGravity
            includeFontPadding = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setFallbackLineSpacing(false)
            isSingleLine = true
            setTypeface(FontStore.typefaceFor(context, spec.font?.family), weightOf(spec.font?.weight))
            text = textFor(spec)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp)
            setPadding(padH, 0, padH, 0)
        }
        // Optional corner label (e.g. the uppercase of a letter key). Sits at the
        // top-right of the cell so the main glyph stays centred.
        val sub = (spec.subTextFor(false) ?: spec.subTextFor(true))?.takeIf { it.isNotEmpty() }?.let { text ->            TextView(context).apply {
                this.text = text
                gravity = Gravity.CENTER
                includeFontPadding = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setFallbackLineSpacing(false)
                isSingleLine = true
                setTypeface(FontStore.typefaceFor(context, spec.font?.family), weightOf(spec.font?.weight))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, (labelSp * 0.5f).coerceAtLeast(7f))
            }
        }
        // Always wrap in a clipping container sized to the cell. A `TextView`
        // measured to the cell still draws its layout past its own bounds when
        // single-line (`horizontallyScrolling`), and `clipChildren` only clips to
        // the *row*, so a long label would spill into the neighbour. This frame is
        // the cell, so `label_fit:` (:wrap / :shrink) decides how it fits and
        // anything left over is clipped here instead of overlapping.
        val view = KeyFrameLayout(context).apply {
            clipChildren = true
            addView(
                label,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    textGravity
                )
            )
            if (sub != null) {
                addView(
                    sub,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END
                    ).apply {
                        topMargin = pxInt(2f)
                        rightMargin = pxInt(3f)
                    }
                )
            }
        }
        view.contentDescription = spec.desc ?: spec.subLabel ?: spec.label
        view.isClickable = spec.action.name != "noop"
        view.isFocusable = spec.action.name != "noop"
        if (spec.style.invisible) view.visibility = View.INVISIBLE
        view.tag = actionChar(spec)
        val id = idForRole(spec.role)
        if (id != View.NO_ID) view.id = id
        return KeyView(view, label, sub, spec, isBar, scale, borderLeft, borderTop)
    }

    private fun textFor(spec: KeySpec): String = spec.textFor(false)

    /** Char emitted by a `code` action (falls back to the visible label). */
    fun actionChar(spec: KeySpec): String {
        val raw = spec.action.value ?: spec.label
        return raw
    }

    private fun fontScale(spec: KeySpec, isBar: Boolean): Float {
        if (isBar) {
            return when (spec.role) {
                "hide" -> 13f / 18f
                else -> 17f / 18f
            }
        }
        return when (spec.role) {
            "select_all", "copy", "paste" -> 12f / 18f
            "space_alt" -> 1f
            else -> if (spec.style.flat) 14f / 18f else 1f
        }
    }

    private fun idForRole(role: String?): Int = when (role) {
        "buffer" -> R.id.key_buffer
        "candidate_row" -> R.id.candidate_scroll
        "hide" -> R.id.btn_hide_keyboard
        "space" -> R.id.key_space
        "space_alt" -> R.id.key_chinese_extra_space
        "backspace" -> R.id.key_backspace
        "enter" -> R.id.key_enter
        "dpad_up" -> R.id.key_arrow_up
        "dpad_down" -> R.id.key_arrow_down
        "dpad_left" -> R.id.key_arrow_left
        "dpad_right" -> R.id.key_arrow_right
        "select_all" -> R.id.key_select_all
        "copy" -> R.id.key_copy
        "paste" -> R.id.key_paste
        else -> View.NO_ID
    }

    private fun solid(fill: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(fill)
        }
}
