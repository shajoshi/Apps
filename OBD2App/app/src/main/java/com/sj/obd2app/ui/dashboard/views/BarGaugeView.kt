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

        val pad = minOf(width, height) * 0.03f
        val cornerR = minOf(width, height) * 0.05f

        val range = (rangeMax - rangeMin).let { 
            if (it > 0f) it else {
                // Invalid range: rangeMax must be greater than rangeMin
                android.util.Log.w("BarGaugeView", "Invalid range: min=$rangeMin, max=$rangeMax")
                1f
            }
        }
        val fraction = ((currentValue - rangeMin) / range).coerceIn(0f, 1f)

        val isWarning = warningThreshold?.let {
            if (isVertical) currentValue <= it else currentValue >= it
        } ?: false

        // ── Track area bounds - compact layout ─────────────────────────────────────
        val labelH = if (isVertical) height * 0.11f else height * 0.16f
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
                    intArrayOf(fillColor, (fillColor and 0x00FFFFFF) or 0xA0000000.toInt()),
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
                    intArrayOf((fillColor and 0x00FFFFFF) or 0xA0000000.toInt(), fillColor),
                    null, Shader.TileMode.CLAMP
                )
                fillPaint.shader = gradient
                canvas.drawRoundRect(fillRect, cornerR, cornerR, fillPaint)
                fillPaint.shader = null
            }
        }

        // ── Tick marks ────────────────────────────────────────────
        val majorTickW = minOf(width, height) * 0.024f
        val minorTickW = minOf(width, height) * 0.010f
        
        // Override: Use consistent 50% major ticks with 4 minor ticks between them
        val majorTickCount = 2  // 0%, 50%, 100%
        val minorTicksPerMajor = 4
        val totalTickCount = majorTickCount * (minorTicksPerMajor + 1)
        val stepValue = range / totalTickCount

        tickPaint.color = (colorScheme.text and 0x00FFFFFF) or 0x50000000.toInt()

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
            strokeWidth = majorTickW * 1.4f
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

        val safeDecimals = decimalPlaces.coerceIn(0, 4)
        val fmt = "%.${safeDecimals}f"
        val valueStr = String.format(fmt, currentValue)
        val trackCy = (trackRect.top + trackRect.bottom) / 2f
        val minDim = minOf(width, height).toFloat()

        // ── Metric name — compact, positioned closer to bar ───────────────
        val nameSize = minDim * 0.11f
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = (colorScheme.text and 0x00FFFFFF) or 0xB0000000.toInt()
        labelPaint.textSize = nameSize
        val nameY = if (isVertical) trackT - nameSize * 0.25f else trackT - nameSize * 0.32f
        canvas.drawText(metricName.uppercase(), width / 2f, nameY, labelPaint)

        // ── Value text (centred in track) ─────────────────────────
        val valueSize = if (isVertical) minDim * 0.18f else trackRect.height() * 0.62f
        textPaint.color = if (isWarning) colorScheme.warning else colorScheme.accent
        textPaint.textSize = valueSize
        val valueOffset = (textPaint.descent() + textPaint.ascent()) / 2
        val valueCx = width / 2f
        
        // Draw dark pill background behind value for visibility
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = (colorScheme.background and 0x00FFFFFF) or 0x88000000.toInt()
        }
        val pillHalfW = textPaint.measureText(valueStr) / 2f + valueSize * 0.12f
        val pillHalfH = valueSize * 0.48f
        val pillRect = RectF(
            valueCx - pillHalfW, trackCy - valueOffset - pillHalfH,
            valueCx + pillHalfW, trackCy - valueOffset + pillHalfH
        )
        canvas.drawRoundRect(pillRect, pillHalfH * 0.35f, pillHalfH * 0.35f, pillPaint)
        
        drawTextWithGlow(canvas, valueStr, valueCx, trackCy - valueOffset, textPaint)

        // ── Unit superscript (top-right of value block) ───────────
        if (metricUnit.isNotEmpty()) {
            val unitSize = valueSize * 0.36f
            val valueBlockW = textPaint.measureText(valueStr) / 2f
            val unitX = valueCx + valueBlockW / 2f + unitSize * 0.18f
            val unitBaseline = trackCy - valueOffset - valueSize * 0.50f
            labelPaint.textAlign = Paint.Align.LEFT
            labelPaint.color = (colorScheme.text and 0x00FFFFFF) or 0xB0000000.toInt()
            labelPaint.textSize = unitSize
            canvas.drawText(metricUnit, unitX, unitBaseline, labelPaint)
        }
        
        // ── Max value display at 87.5% position ───────────────────
        tripMaxValue?.let { maxVal ->
            val maxValueStr = String.format(fmt, maxVal)
            val maxValueSize = valueSize * 0.52f
            val maxValuePaint = Paint(textPaint).apply {
                textSize = maxValueSize
                color = 0xFFFF0000.toInt()  // Red
                textAlign = Paint.Align.CENTER
            }
            
            val maxValueOffset = (maxValuePaint.descent() + maxValuePaint.ascent()) / 2
            
            // Calculate position at 87.5% (midpoint between 75-100%)
            val maxX = if (isVertical) width / 2f else trackRect.left + trackRect.width() * 0.875f
            val maxY = if (isVertical) trackRect.bottom - trackRect.height() * 0.875f else trackCy
            
            // Draw pill background for max value
            val maxPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = (colorScheme.background and 0x00FFFFFF) or 0xAA000000.toInt()
            }
            val maxPillHalfW = maxValuePaint.measureText(maxValueStr) / 2f + maxValueSize * 0.12f
            val maxPillHalfH = maxValueSize * 0.48f
            val maxPillRect = RectF(
                maxX - maxPillHalfW, maxY - maxValueOffset - maxPillHalfH,
                maxX + maxPillHalfW, maxY - maxValueOffset + maxPillHalfH
            )
            canvas.drawRoundRect(maxPillRect, maxPillHalfH * 0.35f, maxPillHalfH * 0.35f, maxPillPaint)
            
            // Draw max value text
            drawTextWithGlow(canvas, maxValueStr, maxX, maxY - maxValueOffset, maxValuePaint)
        }
    }
}
