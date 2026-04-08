package com.sj.obd2app.ui.dashboard.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A 180° arc temperature gauge — ideal for Coolant Temp, Oil Temp, Intake Air Temp.
 * The arc spans from left (180°) to right (0°) across the top half of the view.
 *
 * Colour zones:
 *   - Blue  : rangeMin → 1/3 of range (cold)
 *   - Green : 1/3 → 2/3 of range (normal)
 *   - Red   : 2/3 → rangeMax  (hot) — or above warningThreshold if set
 *
 * Major/minor tick marks are drawn at the arc edge.
 */
class TemperatureGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DashboardGaugeView(context, attrs, defStyleAttr) {

    private val startAngleDeg = 180f
    private val sweepAngleDeg = 180f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val arcRect = RectF()

    // Fixed zone colours (independent of colour scheme)
    private val coldColor  = 0xFF2196F3.toInt()  // Material Blue
    private val normalColor = 0xFF4CAF50.toInt() // Material Green
    private val hotColor   = 0xFFF44336.toInt()  // Material Red

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val range = (rangeMax - rangeMin).takeIf { it > 0f } ?: 1f

        // Arc centre optimized - minimal padding
        val cx = width / 2f
        val cy = height * 0.50f
        val radius = min(width / 2f, cy) * 0.92f
        val strokeW = radius * 0.09f

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        trackPaint.strokeWidth = strokeW
        zonePaint.strokeWidth = strokeW
        needlePaint.strokeWidth = strokeW * 0.32f
        tickPaint.strokeWidth = strokeW * 0.12f

        val trackTint = (colorScheme.text and 0x00FFFFFF) or 0x22000000.toInt()
        val labelTint = (colorScheme.text and 0x00FFFFFF) or 0xB0000000.toInt()

        // ── Background track ──────────────────────────────────────
        trackPaint.color = colorScheme.surface
        canvas.drawArc(arcRect, startAngleDeg, sweepAngleDeg, false, trackPaint)

        // ── Colour zone arcs ──────────────────────────────────────
        val warnFrac = warningThreshold?.let {
            ((it - rangeMin) / range).coerceIn(0f, 1f)
        } ?: (2f / 3f)

        val coldEnd   = 1f / 3f
        val normalEnd = warnFrac

        // Cold zone (blue)
        zonePaint.color = (coldColor and 0x00FFFFFF) or 0xCC000000.toInt()
        canvas.drawArc(arcRect, startAngleDeg, sweepAngleDeg * coldEnd, false, zonePaint)

        // Normal zone (green)
        zonePaint.color = (normalColor and 0x00FFFFFF) or 0xC0000000.toInt()
        canvas.drawArc(
            arcRect,
            startAngleDeg + sweepAngleDeg * coldEnd,
            sweepAngleDeg * (normalEnd - coldEnd),
            false, zonePaint
        )

        // Hot zone (red)
        zonePaint.color = (hotColor and 0x00FFFFFF) or 0xCC000000.toInt()
        canvas.drawArc(
            arcRect,
            startAngleDeg + sweepAngleDeg * normalEnd,
            sweepAngleDeg * (1f - normalEnd),
            false, zonePaint
        )

        // ── Tick marks ────────────────────────────────────────────
        val majorTickLen = radius * 0.15f
        val minorTickLen = radius * 0.07f
        val tickOuterR   = radius - strokeW * 0.55f
        val majorInnerR  = tickOuterR - majorTickLen
        val minorInnerR  = tickOuterR - minorTickLen
        val labelR       = majorInnerR - radius * 0.12f

        tickPaint.color = trackTint
        tickLabelPaint.color = labelTint
        tickLabelPaint.textSize = radius * 0.11f

        val totalMinorSteps = ((range / majorTickInterval) * (minorTickCount + 1)).toInt()
            .coerceAtLeast(1)
        val minorStep = range / totalMinorSteps

        var v = rangeMin
        while (v <= rangeMax + minorStep * 0.01f) {
            val frac = ((v - rangeMin) / range).coerceIn(0f, 1f)
            val angleDeg = startAngleDeg + sweepAngleDeg * frac
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()

            val isMajor = (((v - rangeMin) / majorTickInterval) % 1f) < 0.01f ||
                          (((v - rangeMin) / majorTickInterval) % 1f) > 0.99f

            val innerR = if (isMajor) majorInnerR else minorInnerR
            tickPaint.strokeWidth = if (isMajor) strokeW * 0.18f else strokeW * 0.09f
            canvas.drawLine(
                cx + tickOuterR * cosA, cy + tickOuterR * sinA,
                cx + innerR * cosA, cy + innerR * sinA,
                tickPaint
            )

            if (isMajor) {
                val labelVal = v.toInt()
                canvas.drawText(
                    labelVal.toString(),
                    cx + labelR * cosA,
                    cy + labelR * sinA + tickLabelPaint.textSize / 3,
                    tickLabelPaint
                )
            }
            v += minorStep
        }

        // ── Needle ────────────────────────────────────────────────
        val valueFrac = ((currentValue - rangeMin) / range).coerceIn(0f, 1f)
        val needleAngleDeg = startAngleDeg + sweepAngleDeg * valueFrac
        val needleRad = Math.toRadians(needleAngleDeg.toDouble())
        val nCos = cos(needleRad).toFloat()
        val nSin = sin(needleRad).toFloat()
        val isHot = warningThreshold?.let { currentValue >= it } ?: (valueFrac >= 2f / 3f)
        needlePaint.color = if (isHot) hotColor else colorScheme.accent
        val needleLen = radius * 0.68f
        val oppRad = Math.toRadians((needleAngleDeg + 180.0))
        canvas.drawLine(
            cx + radius * 0.12f * cos(oppRad).toFloat(),
            cy + radius * 0.12f * sin(oppRad).toFloat(),
            cx + needleLen * nCos,
            cy + needleLen * nSin,
            needlePaint
        )

        // ── Pivot ─────────────────────────────────────────────────
        pivotPaint.color = (colorScheme.accent and 0x00FFFFFF) or 0xCC000000.toInt()
        canvas.drawCircle(cx, cy, strokeW * 0.52f, pivotPaint)

        // ── Value readout ─────────────────────────────────────────
        val valueSize = radius * 0.38f
        valuePaint.color = if (isHot) hotColor else colorScheme.accent
        valuePaint.textSize = valueSize
        val safeDecimals = decimalPlaces.coerceIn(0, 4)
        val fmt = "%.${safeDecimals}f"
        val valueStr = String.format(fmt, currentValue)
        val valueBaseline = cy + radius * 0.20f
        drawTextWithGlow(canvas, valueStr, cx, valueBaseline, valuePaint)

        // ── Unit superscript (top-right of value block) ─────────────
        val unitSize = valueSize * 0.32f
        if (metricUnit.isNotEmpty()) {
            val valueBlockW = valuePaint.measureText(valueStr)
            val unitX = cx + valueBlockW / 2f + unitSize * 0.18f
            val unitBaseline = valueBaseline - valueSize * 0.50f
            labelPaint.textAlign = Paint.Align.LEFT
            labelPaint.color = labelTint
            labelPaint.textSize = unitSize
            canvas.drawText(metricUnit, unitX, unitBaseline, labelPaint)
        }

        // ── Metric name (compact, below value) ───────────────────
        val nameSize = radius * 0.11f
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = labelTint
        labelPaint.textSize = nameSize
        canvas.drawText(metricName.uppercase(), cx, valueBaseline + nameSize * 1.45f, labelPaint)
    }
}
