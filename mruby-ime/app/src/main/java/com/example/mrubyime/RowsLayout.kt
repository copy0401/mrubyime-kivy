package com.example.mrubyime

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/**
 * Vertical stack of key rows that tiles the available height exactly.
 *
 * `gapV` is a **visual** gap: each row's touch band includes half of the gap
 * above and below, so neighbouring rows meet in the middle of the seam. The
 * keys inside a row paint their plate/label inset by the same half-gap (see
 * [KeyFrameLayout.setVisualInsets]), therefore a tap anywhere — including the
 * seam — lands on a key instead of falling into a dead margin.
 *
 * Row heights keep the DSL `row height:` proportions: the visual heights are
 * distributed over `height - (n - 1) * gapV`, then the half-gaps are added back
 * to each band.
 */
class RowsLayout(context: Context) : ViewGroup(context) {

    private class Item(val view: View, val weight: Float)

    private val items = mutableListOf<Item>()

    var gapV: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    fun addRow(view: View, weight: Float) {
        addView(view)
        items.add(Item(view, weight.coerceAtLeast(0.01f)))
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    /** Height (px) of every row's touch band; the sum is exactly [height]. */
    private fun bands(height: Int): FloatArray {
        val n = items.size
        val out = FloatArray(n)
        if (n == 0) return out
        var totalWeight = 0f
        for (item in items) totalWeight += item.weight
        totalWeight = totalWeight.coerceAtLeast(0.01f)
        val available = (height - gapV * (n - 1)).coerceAtLeast(0f)
        for (i in 0 until n) {
            val visual = available * items[i].weight / totalWeight
            out[i] = visual +
                (if (i > 0) gapV / 2f else 0f) +
                (if (i < n - 1) gapV / 2f else 0f)
        }
        return out
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val band = bands(height)
        for (i in items.indices) {
            items[i].view.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(
                    band[i].roundToInt().coerceAtLeast(1),
                    MeasureSpec.EXACTLY
                )
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        val band = bands(height)
        var top = 0f
        for (i in items.indices) {
            val bottom = if (i == items.size - 1) height.toFloat() else top + band[i]
            items[i].view.layout(0, top.roundToInt(), width, bottom.roundToInt())
            top = bottom
        }
    }
}
