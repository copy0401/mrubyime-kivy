package com.example.mrubyime

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Cell-sized container for one key.
 *
 * The view keeps the **full grid cell** as its touch bounds (so a tap on the
 * visual gap between two keys still lands on one of them), while its plate and
 * label are inset by half the DSL gap. The inset is applied twice:
 *
 *  * [setPadding] shrinks the label area, so text stays centred in the visible
 *    plate rather than in the full cell;
 *  * [setBackground] wraps the real plate in an [InsetDrawable], so the seam
 *    background shows through the gap.
 *
 * With `gap_h` / `gap_v` = 0 the insets are 0 and this behaves like a plain
 * `FrameLayout` (the built-in layout path).
 */
class KeyFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var rawBackground: Drawable? = null
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0

    override fun setBackground(background: Drawable?) {
        rawBackground = background
        applyBackground()
    }

    /** Insets (px) between the touch cell and the visible plate/label. */
    fun setVisualInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (insetLeft == left && insetTop == top &&
            insetRight == right && insetBottom == bottom
        ) {
            return
        }
        insetLeft = left
        insetTop = top
        insetRight = right
        insetBottom = bottom
        setPadding(left, top, right, bottom)
        applyBackground()
    }

    private fun applyBackground() {
        val bg = rawBackground
        if (bg == null) {
            super.setBackground(null)
        } else if (insetLeft == 0 && insetTop == 0 &&
            insetRight == 0 && insetBottom == 0
        ) {
            super.setBackground(bg)
        } else {
            super.setBackground(
                InsetDrawable(bg, insetLeft, insetTop, insetRight, insetBottom)
            )
        }
    }
}
