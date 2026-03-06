package com.sj.obd2app.ui.dashboard.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import kotlin.math.cos
import kotlin.math.sin

/**
 * A circular dial gauge typically used for RPM.
 * Range: 0 to 8000.
 */
class RevCounterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DashboardGaugeView(context, attrs, defStyleAttr) {

    private val maxRpm = 8000f
    private val startAngle = 135f
    private val sweepAngle = 270f

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 40f
        isFakeBoldText = true
    }
    
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) * 0.8f
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        // Draw background track
        arcPaint.color = colorScheme.surface
        canvas.drawArc(rect, startAngle, sweepAngle, false, arcPaint)

        // Draw active track (accent or warning if high RPM)
        val valueAngle = sweepAngle * (currentValue.coerceIn(0f, maxRpm) / maxRpm)
        arcPaint.color = if (currentValue > 6000) colorScheme.warning else colorScheme.accent
        canvas.drawArc(rect, startAngle, valueAngle, false, arcPaint)

        // Draw standard numeric labels (0, 1, 2, ... 8)
        textPaint.color = colorScheme.text
        for (i in 0..8) {
            val angle = Math.toRadians((startAngle + (sweepAngle * (i / 8f))).toDouble())
            val textRadius = radius - 40f
            val tx = cx + textRadius * cos(angle).toFloat()
            val ty = cy + textRadius * sin(angle).toFloat() + (textPaint.textSize / 3)
            canvas.drawText(i.toString(), tx, ty, textPaint)
        }

        // Draw needle
        val needleAngle = Math.toRadians((startAngle + valueAngle).toDouble())
        needlePaint.color = arcPaint.color
        val nx = cx + (radius - 10f) * cos(needleAngle).toFloat()
        val ny = cy + (radius - 10f) * sin(needleAngle).toFloat()
        canvas.drawLine(cx, cy, nx, ny, needlePaint)
        
        // Draw centre pivot
        arcPaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, 15f, arcPaint)
        arcPaint.style = Paint.Style.STROKE

        // Draw metric name below center
        textPaint.textSize = 30f
        canvas.drawText(metricName, cx, cy + radius * 0.5f, textPaint)
        textPaint.textSize = 24f
        canvas.drawText(currentValue.toInt().toString() + " " + metricUnit, cx, cy + radius * 0.8f, textPaint)
        textPaint.textSize = 40f // restore
    }
}
