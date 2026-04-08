package com.sj.obd2app.ui.dashboard.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet

/**
 * A large numeric readout — works for any metric.
 * Features:
 *  - Coloured value text when above warningThreshold
 *  - Trend arrow (↑ / ↓ / —) derived from the last two readings
 *  - decimalPlaces and displayUnit driven from DashboardWidget
 */
class NumericDisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DashboardGaugeView(context, attrs, defStyleAttr) {

    // Track the previous value to compute trend
    private var previousValue: Float = Float.NaN

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val arrowPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val minDim = minOf(width, height).toFloat()

        // ── Background ────────────────────────────────────────
        bgPaint.color = colorScheme.surface
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint)

        val isWarning = warningThreshold?.let { currentValue >= it } ?: false
        val safeDecimals = decimalPlaces.coerceIn(0, 4)
        val fmt = "%.${safeDecimals}f"
        val valueStr = String.format(fmt, currentValue)

        // ── Compact layout ──────────────────────────────────────
        val nameSize  = minDim * 0.12f
        val valueSize = minDim * 0.60f
        val unitSize  = valueSize * 0.25f

        // Name at top - tight top margin
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize  = nameSize
        val nameBaseline = nameSize * 0.75f

        // Value baseline: centered vertically
        valuePaint.textSize = valueSize
        val valueBaseline = cy + valueSize * 0.20f

        // Unit: smaller, aligned to left side of widget
        val pad = minDim * 0.05f
        val unitX = pad + unitSize * 0.2f
        val unitBaseline = valueBaseline - valueSize * 0.60f

        // Name below value - tight bottom margin
        val nameBottomBaseline = height - nameSize * 0.20f

        // ── Trend arrow — top-left corner, smaller ────────────────────
        val trend = when {
            previousValue.isNaN() -> 0
            currentValue > previousValue + 0.01f ->  1
            currentValue < previousValue - 0.01f -> -1
            else -> 0
        }
        if (trend != 0) {
            val as_ = minDim * 0.08f          // Smaller arrow
            val ax  = as_ * 0.3f              // Reduced left margin
            val ay  = nameBaseline - as_ * 0.6f
            arrowPaint.color = if (trend > 0) colorScheme.accent else colorScheme.warning
            arrowPath.reset()
            if (trend > 0) {
                arrowPath.moveTo(ax,            ay + as_)
                arrowPath.lineTo(ax + as_ / 2f, ay)
                arrowPath.lineTo(ax + as_,      ay + as_)
            } else {
                arrowPath.moveTo(ax,            ay)
                arrowPath.lineTo(ax + as_ / 2f, ay + as_)
                arrowPath.lineTo(ax + as_,      ay)
            }
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowPaint)
        }

        // ── Value ────────────────────────────────────────
        valuePaint.textAlign = Paint.Align.CENTER
        valuePaint.color     = if (isWarning) colorScheme.warning else colorScheme.accent
        valuePaint.textSize  = valueSize
        drawTextWithGlow(canvas, valueStr, cx, valueBaseline, valuePaint)

        // ── Unit superscript ─────────────────────────────────
        if (metricUnit.isNotEmpty()) {
            labelPaint.textAlign = Paint.Align.LEFT
            labelPaint.textSize  = unitSize
            labelPaint.color     = colorScheme.text
            canvas.drawText(metricUnit, unitX, unitBaseline, labelPaint)
        }

        // ── Metric name (below value, muted) ────────────────────
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize  = nameSize
        labelPaint.color     = (colorScheme.text and 0x00FFFFFF) or 0xCC000000.toInt()
        canvas.drawText(metricName.uppercase(), cx, nameBottomBaseline, labelPaint)
    }

    /** Called by the fragment when a new data point arrives — saves previous value for trend. */
    fun updateValue(newValue: Float) {
        previousValue = if (currentValue == 0f && previousValue.isNaN()) newValue else currentValue
        setValue(newValue)
    }
}
