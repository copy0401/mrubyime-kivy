package com.example.mrubyime

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * One horizontal band of keys resolved on the `columns`-unit grid from the DSL.
 *
 * The DSL uses two width modes per key:
 *  * `span`  — fixed share of the grid (`span / columns`);
 *  * `weight` — share of the grid left over by the `span` keys.
 *
 * **Alignment rule**: positions are computed from the *grid*, never from the
 * previous key's right edge. Unit = `(width - 2*outer) / columns`, and every key
 * edge is `round(outer + cumulativeSpan * unit)`. The number of keys in a row
 * therefore never changes where a boundary lands, so a row of 10 span-1 keys and
 * a row mixing span-2/span-0.5 keys share the exact same vertical seams (this is
 * why per-key `toInt()` truncation used to drift a pixel or two per key).
 *
 * Gaps are **visual only** for key rows (`touchContiguous = true`): every child
 * keeps its full grid cell as touch bounds, and a [KeyFrameLayout] child paints
 * its plate/label inset by `gap/2`, so the seam stays centred on the grid
 * boundary and a tap on the gap still hits the nearest key. The bar/status rows
 * keep the legacy narrowed bounds. `gapOuterPx` is the inset kept at the two
 * outer edges. Both come from the DSL grid (`gap_h`, `outer`); the built-in
 * layout sets gap and outer to 0.
 */
class KeyRowLayout(context: Context) : ViewGroup(context) {

    class Item(var span: Float, var weight: Float)

    private val items = mutableListOf<Item>()

    /** Width of the virtual grid (12 for the built-in layout). */
    var columns: Float = 10f

    var gapBetweenPx: Float = 0f
    var gapOuterPx: Float = 0f

    var align: RowAlign = RowAlign.START

    /**
     * When true, children keep their **full grid cell** as touch bounds and a
     * [KeyFrameLayout] child paints its plate/label inset by half the gap. This
     * makes the visual gap touchable (a tap on the seam hits the nearest key).
     * The bar/status rows leave this false and keep the legacy narrowed bounds.
     */
    var touchContiguous: Boolean = false

    fun addItem(view: View, span: Float, weight: Float) {
        addView(view)
        items.add(Item(span, weight))
    }

    /** Updates a child's span (used live by the 編碼區寬度 slider). */
    fun setSpan(view: View, span: Float) {
        val index = indexOfChild(view)
        if (index < 0 || index >= items.size) return
        if (items[index].span == span) return
        items[index].span = span
        requestLayout()
    }

    /** Updates a child's weight (weight-based bar slots, see [setSpan]). */
    fun setWeight(view: View, weight: Float) {
        val index = indexOfChild(view)
        if (index < 0 || index >= items.size) return
        if (items[index].weight == weight) return
        items[index].weight = weight
        requestLayout()
    }

    private fun unit(width: Int): Float =
        ((width - 2f * gapOuterPx) / columns.coerceAtLeast(1f)).coerceAtLeast(0.01f)

    /** Span each item actually occupies; `weight` items share the leftover span. */
    private fun effectiveSpans(): FloatArray {
        val n = items.size
        val out = FloatArray(n)
        var fixed = 0f
        var totalWeight = 0f
        for (item in items) {
            if (item.weight > 0f) totalWeight += item.weight else fixed += item.span
        }
        val leftover = (columns - fixed).coerceAtLeast(0f)
        for (i in 0 until n) {
            val item = items[i]
            out[i] = if (item.weight > 0f && totalWeight > 0f) {
                leftover * item.weight / totalWeight
            } else {
                item.span
            }
        }
        return out
    }

    /**
     * Grid edges (left, right) in px for every item, measured from the row grid.
     * Both edges are rounded from the same cumulative span, so identical spans
     * produce identical boundaries in every row.
     */
    private fun edges(width: Int): FloatArray {
        val n = items.size
        val eff = effectiveSpans()
        val u = unit(width)
        val used = eff.sum()
        val deficit = (columns - used).coerceAtLeast(0f)
        var startSpan = 0f
        var extraPerGap = 0f
        when (align) {
            RowAlign.START -> Unit
            RowAlign.CENTER -> startSpan = deficit / 2f
            RowAlign.END -> startSpan = deficit
            RowAlign.SPACE_BETWEEN -> if (n > 1) extraPerGap = deficit / (n - 1).toFloat()
        }
        val out = FloatArray(n * 2)
        var span = startSpan
        for (i in 0 until n) {
            val left = gapOuterPx + span * u
            span += eff[i]
            val right = gapOuterPx + span * u
            out[i * 2] = left
            out[i * 2 + 1] = right
            span += extraPerGap
        }
        return out
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val e = edges(width)
        for (i in 0 until items.size) {
            val w = (e[i * 2 + 1] - e[i * 2]).toInt().coerceAtLeast(1)
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        val e = edges(width)
        val halfGap = gapBetweenPx / 2f
        val n = items.size
        for (i in 0 until n) {
            val left: Float
            val right: Float
            if (touchContiguous) {
                // Touch bounds stay on the grid; KeyFrameLayout insets the
                // plate/label instead, so the visual gap remains.
                left = e[i * 2]
                right = e[i * 2 + 1]
            } else {
                left = e[i * 2] + (if (i > 0) halfGap else 0f)
                right = e[i * 2 + 1] - (if (i < n - 1) halfGap else 0f)
            }
            val li = Math.round(left)
            val ri = Math.round(right).coerceAtLeast(li + 1)
            getChildAt(i).layout(li, 0, ri, height)
        }
    }
}
