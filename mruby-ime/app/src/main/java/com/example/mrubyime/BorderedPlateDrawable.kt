package com.example.mrubyime

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.PathEffect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Plate (bar or candidate chip) with a border stroke drawn on top.
 *
 * GradientDrawable.setStroke only draws solid lines, so the dashed / dotted
 * styles of the layout DSL are painted here: fill the plate, then stroke the
 * same rectangle with a DashPathEffect. `side` narrows the stroke to one edge,
 * `cornerRadiusPx` rounds it, `width <= 0` degenerates to the plain fill.
 *
 * @param centerStroke if true (default), stroke is centered on the edge
 *   (half inside, half outside); if false, stroke is drawn exactly at the
 *   bounds edge (used by bar to align with keyboard keys).
 */
class BorderedPlateDrawable(
    private val fillColor: Int,
    private val strokeColor: Int?,
    private val strokeWidthPx: Int,
    private val effect: PathEffect?,
    private val side: String,
    private val cornerRadiusPx: Float,
    private val centerStroke: Boolean = true
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = strokeColor ?: fillColor
        strokeWidth = strokeWidthPx.toFloat()
        pathEffect = effect
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        // When centerStroke = true: stroke centered on edge (half inside, half outside) -> frame inset by half
        // When centerStroke = false: stroke entirely inside bounds, outer edge aligned with bounds -> frame inset by full strokeWidth
        val inset = if (centerStroke) strokeWidthPx / 2f else strokeWidthPx.toFloat()
        val frame = RectF(
            b.left + inset,
            b.top + inset,
            b.right - inset,
            b.bottom - inset
        )
        if (cornerRadiusPx > 0f) {
            canvas.drawRoundRect(frame, cornerRadiusPx, cornerRadiusPx, fill)
        } else {
            canvas.drawRect(frame, fill)
        }
        if (strokeColor == null || strokeWidthPx <= 0) return
        when (side) {
            "all" -> {
                if (cornerRadiusPx > 0f) {
                    canvas.drawRoundRect(frame, cornerRadiusPx, cornerRadiusPx, stroke)
                } else {
                    canvas.drawRect(frame, stroke)
                }
            }
            else -> {
                val path = Path()
                when (side) {
                    "top" -> { path.moveTo(frame.left, frame.top); path.lineTo(frame.right, frame.top) }
                    "bottom" -> { path.moveTo(frame.left, frame.bottom); path.lineTo(frame.right, frame.bottom) }
                    "left", "start" -> { path.moveTo(frame.left, frame.top); path.lineTo(frame.left, frame.bottom) }
                    "right", "end" -> { path.moveTo(frame.right, frame.top); path.lineTo(frame.right, frame.bottom) }
                }
                canvas.drawPath(path, stroke)
            }
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
