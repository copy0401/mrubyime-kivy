package com.example.mrubyime

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * Horizontal scroller for the candidate row.
 *
 * Two ways to move it:
 *  * **Swipe** — the content follows the finger and, on release, eases onto the
 *    next / previous page boundary (one visible width, the last page clamped to
 *    the end of the list).
 *  * **[stepPage]** — used by the hardware D-pad ↑ / ↓. Pages form a **loop**:
 *    stepping forward from the last page returns to the first, and backward from
 *    the first goes to the last, so every chip is reachable by repeating a key.
 *
 * A tap still reaches the chip: the row only takes over the gesture once the finger
 * travels past the touch slop, so `chip.setOnClickListener` keeps working.
 */
class StepScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var lastX = 0f
    private var dragging = false

    /** Page index the current gesture started from (stepping is relative to it). */
    private var startPage = 0

    /** One page of the loop; defaults to the visible width. */
    var pageWidth: Int = 0

    private val page: Int
        get() = if (pageWidth > 0) pageWidth else width

    private fun contentWidth(): Int = getChildAt(0)?.width ?: 0

    private fun maxScroll(): Int = (contentWidth() - width).coerceAtLeast(0)

    /** Scroll position of a page; the last page is clamped to the end of the list. */
    fun targetOfPage(index: Int): Int {
        val p = page
        if (p <= 0) return 0
        return min(index * p, maxScroll())
    }

    /** Pages in the loop — a partly visible last page counts as one. */
    fun pageCount(): Int {
        val p = page
        if (p <= 0) return 1
        return maxOf(1, ceil(contentWidth().toDouble() / p).toInt())
    }

    /** Page whose position is closest to the current scroll offset. */
    fun currentPage(): Int {
        if (page <= 0) return 0
        var best = 0
        var bestDelta = Int.MAX_VALUE
        for (i in 0 until pageCount()) {
            val delta = abs(targetOfPage(i) - scrollX)
            if (delta < bestDelta) {
                bestDelta = delta
                best = i
            }
        }
        return best
    }

    /** Scrolls to a page, wrapping the index into the loop. */
    fun goToPage(index: Int, animate: Boolean = true) {
        if (page <= 0) return
        val count = pageCount()
        val wrapped = ((index % count) + count) % count
        val target = targetOfPage(wrapped)
        if (animate) smoothScrollTo(target, 0) else scrollTo(target, 0)
    }

    /** Advances one page in the loop; returns the page index that was moved to. */
    fun stepPage(forward: Boolean): Int {
        val count = pageCount()
        val from = currentPage()
        val next = if (forward) (from + 1) % count else (from - 1 + count) % count
        goToPage(next)
        return next
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                lastX = ev.x
                dragging = false
                startPage = currentPage()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.x - downX) > touchSlop) {
                    dragging = true
                    lastX = ev.x
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) {
                // Content tracks the finger: without this the row looks frozen
                // until the finger is lifted, which reads as "not smooth".
                val dx = (lastX - ev.x).toInt()
                if (dx != 0) {
                    scrollBy(dx, 0)
                    lastX = ev.x
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) settle(ev.x - downX)
                dragging = false
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle(0f)
                dragging = false
            }
        }
        // Consume everything: the free-drag/fling path of HorizontalScrollView is
        // intentionally bypassed so a swipe always ends on a page boundary.
        return true
    }

    /**
     * Eases onto the neighbouring page when the finger travelled far enough
     * (otherwise the row goes back to the page the gesture started on). Both
     * directions wrap, so the row scrolls in a loop.
     */
    private fun settle(totalDx: Float) {
        val p = page
        if (p <= 0 || maxScroll() <= 0) return
        val moved = abs(totalDx) > p * 0.08f
        val count = pageCount()
        val target = when {
            !moved -> startPage
            totalDx < 0 -> (startPage + 1) % count
            else -> (startPage - 1 + count) % count
        }
        goToPage(target)
    }
}
