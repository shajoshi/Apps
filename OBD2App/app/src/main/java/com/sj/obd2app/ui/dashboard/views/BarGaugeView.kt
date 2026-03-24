package com.sj.obd2app.ui.dashboard.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
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
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val trackRect = RectF()
    private val fillRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val pad = minOf(width, height) * 0.02f
        val cornerR = minOf(width, height) * 0.04f

        val range = (rangeMax - rangeMin).takeIf { it > 0f } ?: 1f
        val fraction = ((currentValue - rangeMin) / range).coerceIn(0f, 1f)

        val isWarning = warningThreshold?.let {
            if (isVertical) currentValue <= it else currentValue >= it
        } ?: false

        // ── Track area bounds - compact layout ─────────────────────────────────────
        val labelH = if (isVertical) height * 0.10f else height * 0.15f
        val trackL = pad
        val trackT = if (isVertical) pad else height - labelH - pad - (height * 0.40f)
        val trackR = width - pad
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
        
        // Override: Use consistent 50% major ticks with 4 minor ticks between them
        val majorTickCount = 2  // 0%, 50%, 100%
        val minorTicksPerMajor = 4
        val totalTickCount = majorTickCount * (minorTicksPerMajor + 1)
        val stepValue = range / totalTickCount

        tickPaint.color = (colorScheme.text and 0x00FFFFFF) or 0x77000000.toInt()

        var tickIndex = 0
        while (tickIndex <= totalTickCount) {
            val v = rangeMin + stepValue * tickIndex
            val frac = tickIndex.toFloat() / totalTickCount
            val isMajor = (tickIndex % (minorTicksPerMajor + 1)) == 0
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
            tickIndex++
        }

        // ── Trip max/min indicators ───────────────────────────────
        val maxMinTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = majorTickW * 2f  // Bold tick
        }
        
        // Draw red tick at max value
        tripMaxValue?.let { maxVal ->
            val maxFrac = ((maxVal - rangeMin) / range).coerceIn(0f, 1f)
            maxMinTickPaint.color = 0xFFFF0000.toInt()  // Red
            if (isVertical) {
                val ty = trackRect.bottom - trackRect.height() * maxFrac
                val tickLen = trackRect.width() * 0.4f
                canvas.drawLine(trackRect.left, ty, trackRect.left + tickLen, ty, maxMinTickPaint)
                canvas.drawLine(trackRect.right - tickLen, ty, trackRect.right, ty, maxMinTickPaint)
            } else {
                val tx = trackRect.left + trackRect.width() * maxFrac
                val tickLen = trackRect.height() * 0.4f
                canvas.drawLine(tx, trackRect.top, tx, trackRect.top + tickLen, maxMinTickPaint)
                canvas.drawLine(tx, trackRect.bottom - tickLen, tx, trackRect.bottom, maxMinTickPaint)
            }
        }
        
        // Draw blue tick at min value
        tripMinValue?.let { minVal ->
            val minFrac = ((minVal - rangeMin) / range).coerceIn(0f, 1f)
            maxMinTickPaint.color = 0xFF2196F3.toInt()  // Blue
            if (isVertical) {
                val ty = trackRect.bottom - trackRect.height() * minFrac
                val tickLen = trackRect.width() * 0.4f
                canvas.drawLine(trackRect.left, ty, trackRect.left + tickLen, ty, maxMinTickPaint)
                canvas.drawLine(trackRect.right - tickLen, ty, trackRect.right, ty, maxMinTickPaint)
            } else {
                val tx = trackRect.left + trackRect.width() * minFrac
                val tickLen = trackRect.height() * 0.4f
                canvas.drawLine(tx, trackRect.top, tx, trackRect.top + tickLen, maxMinTickPaint)
                canvas.drawLine(tx, trackRect.bottom - tickLen, tx, trackRect.bottom, maxMinTickPaint)
            }
        }

        val fmt = "%.${decimalPlaces}f"
        val valueStr = String.format(fmt, currentValue)
        val trackCy = (trackRect.top + trackRect.bottom) / 2f
        val minDim = minOf(width, height).toFloat()

        // ── Metric name — compact, positioned closer to bar ───────────────
        val nameSize = minDim * 0.14f
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = (colorScheme.text and 0x00FFFFFF) or 0xCC000000.toInt()
        labelPaint.textSize = nameSize
        val nameY = if (isVertical) trackT - nameSize * 0.2f else trackT - nameSize * 0.3f
        canvas.drawText(metricName.uppercase(), width / 2f, nameY, labelPaint)

        // ── Value text (centred in track) ─────────────────────────
        val valueSize = if (isVertical) minDim * 0.22f else trackRect.height() * 0.70f
        textPaint.color = if (isWarning) colorScheme.warning else colorScheme.accent
        textPaint.textSize = valueSize
        val valueOffset = (textPaint.descent() + textPaint.ascent()) / 2
        val valueCx = width / 2f
        drawTextWithGlow(canvas, valueStr, valueCx, trackCy - valueOffset, textPaint)

        // ── Unit superscript (top-right of value block) ───────────
        if (metricUnit.isNotEmpty()) {
            val unitSize = valueSize * 0.45f
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
