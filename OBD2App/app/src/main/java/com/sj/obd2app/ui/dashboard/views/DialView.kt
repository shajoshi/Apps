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
        typeface = Typeface.DEFAULT_BOLD
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
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val arcRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f * 0.93f
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
        minorTickPaint.color = (colorScheme.text and 0x00FFFFFF) or 0x77000000.toInt()
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

        // ── Trip max/min indicators ───────────────────────────────
        val maxMinTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = strokeW * 0.5f  // Bold tick
        }
        
        // Draw red tick at max value
        tripMaxValue?.let { maxVal ->
            val maxFrac = ((maxVal - rangeMin) / range).coerceIn(0f, 1f)
            val maxAngleDeg = startAngleDeg + sweepAngleDeg * maxFrac
            val maxAngleRad = Math.toRadians(maxAngleDeg.toDouble())
            val cosA = cos(maxAngleRad).toFloat()
            val sinA = sin(maxAngleRad).toFloat()
            maxMinTickPaint.color = 0xFFFF0000.toInt()  // Red
            canvas.drawLine(
                cx + tickOuterR * cosA, cy + tickOuterR * sinA,
                cx + (majorInnerR - radius * 0.05f) * cosA, cy + (majorInnerR - radius * 0.05f) * sinA,
                maxMinTickPaint
            )
        }
        
        // Draw blue tick at min value
        tripMinValue?.let { minVal ->
            val minFrac = ((minVal - rangeMin) / range).coerceIn(0f, 1f)
            val minAngleDeg = startAngleDeg + sweepAngleDeg * minFrac
            val minAngleRad = Math.toRadians(minAngleDeg.toDouble())
            val cosA = cos(minAngleRad).toFloat()
            val sinA = sin(minAngleRad).toFloat()
            maxMinTickPaint.color = 0xFF2196F3.toInt()  // Blue
            canvas.drawLine(
                cx + tickOuterR * cosA, cy + tickOuterR * sinA,
                cx + (majorInnerR - radius * 0.05f) * cosA, cy + (majorInnerR - radius * 0.05f) * sinA,
                maxMinTickPaint
            )
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

        // ── Metric name (in upper half of dial) ────────────────────
        val nameSize = radius * 0.15f
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = (colorScheme.text and 0x00FFFFFF) or 0xCC000000.toInt()
        labelPaint.textSize = nameSize
        val nameBaseline = cy - radius * 0.25f
        canvas.drawText(metricName.uppercase(), cx, nameBaseline, labelPaint)

        // ── Value readout (in lower half of dial) ─────────────────────
        val valueSize = radius * 0.52f
        valuePaint.color = if (isWarning) colorScheme.warning else colorScheme.accent
        valuePaint.textSize = valueSize
        val fmt = "%.${decimalPlaces}f"
        val valueStr = String.format(fmt, currentValue)
        val valueBaseline = cy + radius * 0.55f

        // Dark pill behind value for separation from ticks/needle
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = (colorScheme.background and 0x00FFFFFF) or 0xAA000000.toInt()
        }
        val pillHalfW = valuePaint.measureText(valueStr) / 2f + valueSize * 0.15f
        val pillTop = valueBaseline + valuePaint.ascent() - valueSize * 0.1f
        val pillBot = valueBaseline + valuePaint.descent() + valueSize * 0.05f
        canvas.drawRoundRect(
            cx - pillHalfW, pillTop, cx + pillHalfW, pillBot,
            valueSize * 0.15f, valueSize * 0.15f, pillPaint
        )
        drawTextWithGlow(canvas, valueStr, cx, valueBaseline, valuePaint)

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
    }
}
