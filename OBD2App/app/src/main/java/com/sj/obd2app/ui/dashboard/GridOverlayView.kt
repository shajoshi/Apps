package com.sj.obd2app.ui.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A simple View that draws a dot grid over the dashboard canvas
 * while in edit mode to help align widgets.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var gridSizePx: Int = 100
        set(value) { field = value; invalidate() }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFB74D.toInt() // amber-tinted dots, 53% opacity
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF8F00.toInt() // solid amber border
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gridSizePx <= 0) return

        val cols = width / gridSizePx
        val rows = height / gridSizePx

        for (x in 0..cols) {
            for (y in 0..rows) {
                canvas.drawCircle(
                    (x * gridSizePx).toFloat(),
                    (y * gridSizePx).toFloat(),
                    3f,
                    dotPaint
                )
            }
        }

        // Canvas bounds border
        val inset = borderPaint.strokeWidth / 2f
        canvas.drawRect(inset, inset, width - inset, height - inset, borderPaint)
    }
}
