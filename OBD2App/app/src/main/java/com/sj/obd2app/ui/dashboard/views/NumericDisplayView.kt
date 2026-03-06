package com.sj.obd2app.ui.dashboard.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet

/**
 * A simple large numeric text display with an optional title.
 * For example: displays "14.2" with "Volt" below it.
 */
class NumericDisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DashboardGaugeView(context, attrs, defStyleAttr) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    
    // Configurable precision for the display
    var decimalPlaces: Int = 1
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        // Draw background
        textPaint.color = colorScheme.surface
        textPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 15f, 15f, textPaint)

        // Draw value
        textPaint.color = colorScheme.accent
        textPaint.textSize = minOf(width, height) * 0.5f
        
        val formatStr = "%.${decimalPlaces}f"
        val valueStr = String.format(formatStr, currentValue)
        
        val valueOffset = (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(valueStr, cx, cy - valueOffset - 15f, textPaint)

        // Draw Metric Name & Unit at the bottom
        textPaint.color = colorScheme.text
        textPaint.textSize = minOf(width, height) * 0.15f
        canvas.drawText("$metricName ($metricUnit)", cx, height - 20f, textPaint)
    }
}
