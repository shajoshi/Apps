package com.sj.obd2app.ui.dashboard.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet

/**
 * A horizontal bar graph specifically tailored for Instantaneous Fuel Consumption (IFC).
 * Scales dynamically based on the current value.
 */
class IfcBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DashboardGaugeView(context, attrs, defStyleAttr) {

    private val maxValue = 30f // For example, 30 L/100km max display range
    
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 36f
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val pad = 10f
        
        // Draw background track
        barPaint.color = colorScheme.surface
        canvas.drawRoundRect(pad, pad, width - pad, height - pad, 5f, 5f, barPaint)

        // Calculate fill width (left to right)
        val value = currentValue.coerceIn(0f, maxValue)
        barPaint.color = colorScheme.accent

        val fillWidth = (width - 2 * pad) * (value / maxValue)
        
        if (fillWidth > 0) {
             canvas.drawRoundRect(pad, pad, pad + fillWidth, height - pad, 5f, 5f, barPaint)
        }

        // Overlay text value and unit
        textPaint.color = colorScheme.text
        val cy = height / 2f
        val offset = (textPaint.descent() + textPaint.ascent()) / 2
        
        canvas.drawText("${String.format("%.1f", value)} $metricUnit", width / 2f, cy - offset, textPaint)
        
        // Very small label at top left
        textPaint.textSize = 16f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(metricName, 15f, 25f, textPaint)
        
        // Reset defaults
        textPaint.textSize = 36f
        textPaint.textAlign = Paint.Align.CENTER
    }
}
