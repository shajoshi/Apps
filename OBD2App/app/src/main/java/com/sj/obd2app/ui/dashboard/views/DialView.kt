package com.sj.obd2app.ui.dashboard.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A circular dial gauge — works for any metric (RPM, voltage, pressure, etc.).
 * Scale, ticks, and warning threshold are all driven by the base-class properties
 * set from DashboardWidget at render time.
 *
 * Arc spans 270° (135° start → 405°, i.e. bottom-left to bottom-right clockwise).
 */
class DialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DashboardGaugeView(context, attrs, defStyleAttr) {

    private val startAngleDeg = 135f
    private val sweepAngleDeg = 270f

    // ── Paints ────────────────────────────────────────────────────
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val accentArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val warningArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val arcRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f * 0.78f
        val strokeW = radius * 0.09f

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // ── Stroke widths ─────────────────────────────────────────
        trackPaint.strokeWidth = strokeW
        accentArcPaint.strokeWidth = strokeW
        warningArcPaint.strokeWidth = strokeW
        majorTickPaint.strokeWidth = strokeW * 0.25f
        minorTickPaint.strokeWidth = strokeW * 0.12f
        needlePaint.strokeWidth = strokeW * 0.45f

        val range = (rangeMax - rangeMin).takeIf { it > 0f } ?: 1f

        // ── Background track ──────────────────────────────────────
        trackPaint.color = colorScheme.surface
        canvas.drawArc(arcRect, startAngleDeg, sweepAngleDeg, false, trackPaint)

        // ── Warning zone arc (rangeThreshold → rangeMax) ──────────
        val warn = warningThreshold
        if (warn != null && warn < rangeMax) {
            val warnFraction = ((warn - rangeMin) / range).coerceIn(0f, 1f)
            val warnStartAngle = startAngleDeg + sweepAngleDeg * warnFraction
            val warnSweep = sweepAngleDeg * (1f - warnFraction)
            warningArcPaint.color = colorScheme.warning
            canvas.drawArc(arcRect, warnStartAngle, warnSweep, false, warningArcPaint)
        }

        // ── Active value arc ──────────────────────────────────────
        val valueFraction = ((currentValue - rangeMin) / range).coerceIn(0f, 1f)
        val valueAngle = sweepAngleDeg * valueFraction
        val isWarning = warn != null && currentValue >= warn
        accentArcPaint.color = if (isWarning) colorScheme.warning else colorScheme.accent
        if (valueAngle > 0f) {
            canvas.drawArc(arcRect, startAngleDeg, valueAngle, false, accentArcPaint)
        }

        // ── Tick marks ────────────────────────────────────────────
        val majorTickLen = radius * 0.16f
        val minorTickLen = radius * 0.08f
        val tickOuterR = radius - strokeW * 0.6f
        val majorInnerR = tickOuterR - majorTickLen
        val minorInnerR = tickOuterR - minorTickLen
        val labelR = majorInnerR - radius * 0.1f

        majorTickPaint.color = colorScheme.text
        minorTickPaint.color = (colorScheme.text and 0x00FFFFFF) or 0x88000000.toInt()
        tickLabelPaint.color = colorScheme.text
        tickLabelPaint.textSize = radius * 0.10f

        val totalMinorSteps = ((range / majorTickInterval) * (minorTickCount + 1)).toInt()
        val minorStepValue = range / totalMinorSteps

        var v = rangeMin
        while (v <= rangeMax + minorStepValue * 0.01f) {
            val frac = ((v - rangeMin) / range).coerceIn(0f, 1f)
            val angleDeg = startAngleDeg + sweepAngleDeg * frac
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()

            val isMajor = (((v - rangeMin) / majorTickInterval) % 1f) < 0.01f ||
                          (((v - rangeMin) / majorTickInterval) % 1f) > 0.99f

            if (isMajor) {
                canvas.drawLine(
                    cx + tickOuterR * cosA, cy + tickOuterR * sinA,
                    cx + majorInnerR * cosA, cy + majorInnerR * sinA,
                    majorTickPaint
                )
                // Label — format nicely
                val labelVal = v.toInt()
                val labelStr = if (majorTickInterval >= 1f) labelVal.toString()
                               else String.format("%.1f", v)
                canvas.drawText(
                    labelStr,
                    cx + labelR * cosA,
                    cy + labelR * sinA + tickLabelPaint.textSize / 3,
                    tickLabelPaint
                )
            } else {
                canvas.drawLine(
                    cx + tickOuterR * cosA, cy + tickOuterR * sinA,
                    cx + minorInnerR * cosA, cy + minorInnerR * sinA,
                    minorTickPaint
                )
            }
            v += minorStepValue
        }

        // ── Needle ────────────────────────────────────────────────
        val needleAngleDeg = startAngleDeg + valueAngle
        val needleRad = Math.toRadians(needleAngleDeg.toDouble())
        val needleLen = radius * 0.72f
        val needleBaseLen = radius * 0.15f
        val nCos = cos(needleRad).toFloat()
        val nSin = sin(needleRad).toFloat()
        val oppRad = Math.toRadians((needleAngleDeg + 180.0))
        needlePaint.color = if (isWarning) colorScheme.warning else colorScheme.accent
        canvas.drawLine(
            cx + needleBaseLen * cos(oppRad).toFloat(),
            cy + needleBaseLen * sin(oppRad).toFloat(),
            cx + needleLen * nCos,
            cy + needleLen * nSin,
            needlePaint
        )

        // ── Pivot circle ──────────────────────────────────────────
        pivotPaint.color = colorScheme.accent
        canvas.drawCircle(cx, cy, strokeW * 0.7f, pivotPaint)

        // ── Value readout ─────────────────────────────────────────
        val valueSize = radius * 0.32f
        valuePaint.color = if (isWarning) colorScheme.warning else colorScheme.accent
        valuePaint.textSize = valueSize
        val fmt = "%.${decimalPlaces}f"
        val valueStr = String.format(fmt, currentValue)
        val valueBaseline = cy + radius * 0.42f
        canvas.drawText(valueStr, cx, valueBaseline, valuePaint)

        // ── Unit superscript (top-right of value block) ───────────────
        val unitSize = valueSize * 0.38f
        if (metricUnit.isNotEmpty()) {
            val valueBlockW = valuePaint.measureText(valueStr)
            val unitX = cx + valueBlockW / 2f + unitSize * 0.2f
            val unitBaseline = valueBaseline - valueSize * 0.60f
            labelPaint.textAlign = Paint.Align.LEFT
            labelPaint.color = colorScheme.text
            labelPaint.textSize = unitSize
            canvas.drawText(metricUnit, unitX, unitBaseline, labelPaint)
        }

        // ── Metric name (compact, below value) ────────────────────
        val nameSize = radius * 0.09f
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = (colorScheme.text and 0x00FFFFFF) or 0xB3000000.toInt()
        labelPaint.textSize = nameSize
        canvas.drawText(metricName.uppercase(), cx, valueBaseline + nameSize * 1.6f, labelPaint)
    }
}
