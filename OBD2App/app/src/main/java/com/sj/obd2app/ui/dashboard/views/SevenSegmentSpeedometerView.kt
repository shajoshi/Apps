package com.sj.obd2app.ui.dashboard.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet

/**
 * A thick, digital 7-segment style display typically used for speed.
 * Renders large 3-digit numbers with custom font/styling.
 */
class SevenSegmentSpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DashboardGaugeView(context, attrs, defStyleAttr) {

    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        // Load a monospace or digital font if available.
        // For default we use MONOSPACE bold to simulate a chunky display.
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 30f
        isFakeBoldText = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        // Draw background box
        digitPaint.color = colorScheme.surface
        digitPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 20f, 20f, digitPaint)

        // Draw the 3-digit speed value (e.g. "042" or " 42")
        val speedStr = String.format("%03d", currentValue.toInt().coerceIn(0, 999))
        
        digitPaint.color = colorScheme.accent
        digitPaint.textSize = minOf(width, height) * 0.6f
        
        // Vertically centre the text
        val textOffset = (digitPaint.descent() + digitPaint.ascent()) / 2
        canvas.drawText(speedStr, cx, cy - textOffset - 20f, digitPaint)

        // Draw unit label below
        labelPaint.color = colorScheme.text
        canvas.drawText(metricUnit, cx, height - 30f, labelPaint)
        
        // Draw title above
        labelPaint.textSize = 24f
        canvas.drawText(metricName.uppercase(), cx, 40f, labelPaint)
        labelPaint.textSize = 30f // reset
    }
}
