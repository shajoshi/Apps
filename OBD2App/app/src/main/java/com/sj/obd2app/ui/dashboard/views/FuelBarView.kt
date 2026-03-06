package com.sj.obd2app.ui.dashboard.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet

/**
 * A vertical or horizontal bar graph used for Fuel Level.
 * Range: 0 to 100%. Shows segmented warning colours (Red < 20%, Yellow < 40%, Green otherwise).
 */
class FuelBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DashboardGaugeView(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 30f
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw background track
        barPaint.color = colorScheme.surface
        val pad = 20f
        canvas.drawRoundRect(pad, pad, width - pad, height - pad, 10f, 10f, barPaint)

        // Determine active bar colour based on fuel level (0-100)
        val value = currentValue.coerceIn(0f, 100f)
        barPaint.color = when {
            value < 20f -> colorScheme.warning // Red/Warning
            value < 40f -> 0xFFFFAA00.toInt() // Orange/Yellow
            else -> colorScheme.accent // Normal (Green/Blue depending on theme)
        }

        // Calculate fill height (drawing from bottom to top)
        val fillHeight = (height - 2 * pad) * (value / 100f)
        val topY = height - pad - fillHeight

        if (fillHeight > 0) {
            canvas.drawRoundRect(pad, topY, width - pad, height - pad, 10f, 10f, barPaint)
        }

        // Draw percentage text in center
        textPaint.color = colorScheme.text
        val cy = height / 2f
        val textOffset = (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText("${value.toInt()}%", width / 2f, cy - textOffset, textPaint)
        
        // Draw icon/metric name at bottom
        textPaint.textSize = 20f
        canvas.drawText(metricName, width / 2f, height - pad - 10f, textPaint)
        textPaint.textSize = 30f // reset
    }
}
