package com.sj.obd2app.ui.dashboard.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet

/**
 * A generic bar gauge — works for any metric (fuel level, throttle, fuel rate, etc.).
 * Supports both vertical (BAR_GAUGE_V) and horizontal (BAR_GAUGE_H) orientations.
 *
 * Scale, ticks, and warning threshold are driven by base-class properties.
 * A gradient fill and tick marks are drawn for a premium look.
 */
class BarGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DashboardGaugeView(context, attrs, defStyleAttr) {

    /** True = vertical bar (fills bottom→top); False = horizontal bar (fills left→right). */
    var isVertical: Boolean = true
        set(value) { field = value; invalidate() }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val trackRect = RectF()
    private val fillRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val pad = minOf(width, height) * 0.06f
        val cornerR = minOf(width, height) * 0.06f

        val range = (rangeMax - rangeMin).takeIf { it > 0f } ?: 1f
        val fraction = ((currentValue - rangeMin) / range).coerceIn(0f, 1f)

        val isWarning = warningThreshold?.let {
            if (isVertical) currentValue <= it else currentValue >= it
        } ?: false

        // ── Track area bounds ─────────────────────────────────────
        val labelH = if (isVertical) height * 0.12f else height * 0.28f
        val trackL = if (isVertical) pad * 2.5f else pad
        val trackT = if (isVertical) pad else height - labelH - pad - (height * 0.35f)
        val trackR = if (isVertical) width - pad * 2.5f else width - pad
        val trackB = if (isVertical) height - labelH - pad else height - labelH

        trackRect.set(trackL, trackT, trackR, trackB)

        // ── Draw background track ─────────────────────────────────
        trackPaint.color = colorScheme.surface
        canvas.drawRoundRect(trackRect, cornerR, cornerR, trackPaint)

        // ── Fill gradient ─────────────────────────────────────────
        val fillColor = when {
            isWarning -> colorScheme.warning
            else -> colorScheme.accent
        }

        if (isVertical) {
            val fillH = (trackRect.height() * fraction)
            fillRect.set(trackRect.left, trackRect.bottom - fillH, trackRect.right, trackRect.bottom)
            if (fillH > 0f) {
                val gradient = LinearGradient(
                    trackRect.left, fillRect.top, trackRect.left, fillRect.bottom,
                    intArrayOf(fillColor, (fillColor and 0x00FFFFFF) or 0xAA000000.toInt()),
                    null, Shader.TileMode.CLAMP
                )
                fillPaint.shader = gradient
                canvas.drawRoundRect(fillRect, cornerR, cornerR, fillPaint)
                fillPaint.shader = null
            }
        } else {
            val fillW = trackRect.width() * fraction
            fillRect.set(trackRect.left, trackRect.top, trackRect.left + fillW, trackRect.bottom)
            if (fillW > 0f) {
                val gradient = LinearGradient(
                    fillRect.left, trackRect.top, fillRect.right, trackRect.top,
                    intArrayOf((fillColor and 0x00FFFFFF) or 0xAA000000.toInt(), fillColor),
                    null, Shader.TileMode.CLAMP
                )
                fillPaint.shader = gradient
                canvas.drawRoundRect(fillRect, cornerR, cornerR, fillPaint)
                fillPaint.shader = null
            }
        }

        // ── Tick marks ────────────────────────────────────────────
        val majorTickW = minOf(width, height) * 0.03f
        val minorTickW = minOf(width, height) * 0.015f
        val totalMinorSteps = ((range / majorTickInterval) * (minorTickCount + 1)).toInt()
            .coerceAtLeast(1)
        val minorStepValue = range / totalMinorSteps

        tickPaint.color = (colorScheme.text and 0x00FFFFFF) or 0x66000000.toInt()

        var v = rangeMin
        while (v <= rangeMax + minorStepValue * 0.01f) {
            val frac = ((v - rangeMin) / range).coerceIn(0f, 1f)
            val isMajor = (((v - rangeMin) / majorTickInterval) % 1f) < 0.01f ||
                          (((v - rangeMin) / majorTickInterval) % 1f) > 0.99f
            tickPaint.strokeWidth = if (isMajor) majorTickW else minorTickW

            if (isVertical) {
                val ty = trackRect.bottom - trackRect.height() * frac
                val tickLen = if (isMajor) trackRect.width() * 0.3f else trackRect.width() * 0.15f
                canvas.drawLine(trackRect.left, ty, trackRect.left + tickLen, ty, tickPaint)
                canvas.drawLine(trackRect.right - tickLen, ty, trackRect.right, ty, tickPaint)
            } else {
                val tx = trackRect.left + trackRect.width() * frac
                val tickLen = if (isMajor) trackRect.height() * 0.3f else trackRect.height() * 0.15f
                canvas.drawLine(tx, trackRect.top, tx, trackRect.top + tickLen, tickPaint)
                canvas.drawLine(tx, trackRect.bottom - tickLen, tx, trackRect.bottom, tickPaint)
            }
            v += minorStepValue
        }

        val fmt = "%.${decimalPlaces}f"
        val valueStr = String.format(fmt, currentValue)
        val trackCy = (trackRect.top + trackRect.bottom) / 2f
        val minDim = minOf(width, height).toFloat()

        // ── Metric name — small, top-center ───────────────────────
        val nameSize = minDim * 0.09f
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = (colorScheme.text and 0x00FFFFFF) or 0xB3000000.toInt()
        labelPaint.textSize = nameSize
        canvas.drawText(metricName.uppercase(), width / 2f, nameSize * 1.2f, labelPaint)

        // ── Value text (centred in track) ─────────────────────────
        val valueSize = if (isVertical) minDim * 0.16f else trackRect.height() * 0.52f
        textPaint.color = colorScheme.text
        textPaint.textSize = valueSize
        val valueOffset = (textPaint.descent() + textPaint.ascent()) / 2
        val valueCx = width / 2f
        canvas.drawText(valueStr, valueCx, trackCy - valueOffset, textPaint)

        // ── Unit superscript (top-right of value block) ───────────
        if (metricUnit.isNotEmpty()) {
            val unitSize = valueSize * 0.38f
            val valueBlockW = textPaint.measureText(valueStr)
            val unitX = valueCx + valueBlockW / 2f + unitSize * 0.2f
            val unitBaseline = trackCy - valueOffset - valueSize * 0.60f
            labelPaint.textAlign = Paint.Align.LEFT
            labelPaint.color = colorScheme.text
            labelPaint.textSize = unitSize
            canvas.drawText(metricUnit, unitX, unitBaseline, labelPaint)
        }
    }
}
