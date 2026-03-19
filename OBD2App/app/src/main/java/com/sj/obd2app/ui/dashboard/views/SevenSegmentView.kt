package com.sj.obd2app.ui.dashboard.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet

/**
 * A digital 7-segment style display — works for any numeric metric.
 * Renders a ghost "888..." layer behind the live digits for an authentic
 * segment-display effect. Uses digital-7.ttf if available in assets/fonts/,
 * falling back to Typeface.MONOSPACE.
 */
class SevenSegmentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DashboardGaugeView(context, attrs, defStyleAttr) {

    private val digitTypeface: Typeface by lazy {
        try {
            Typeface.createFromAsset(context.assets, "fonts/digital-7.ttf")
        } catch (e: Exception) {
            Typeface.MONOSPACE
        }
    }

    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
    }

    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        ghostPaint.typeface = digitTypeface
        digitPaint.typeface = digitTypeface

        val cx = width / 2f

        // ── Background box ────────────────────────────────────────
        bgPaint.color = colorScheme.surface
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint)

        // ── Determine digit count & format ───────────────────────
        val absMax = maxOf(kotlin.math.abs(rangeMin), kotlin.math.abs(rangeMax))
        val intDigits = absMax.toInt().toString().length.coerceAtLeast(1)
        val totalChars = if (decimalPlaces > 0) intDigits + 1 + decimalPlaces else intDigits
        val ghostStr = "8".repeat(totalChars)

        val fmt = "%.${decimalPlaces}f"
        val valueStr = String.format(fmt, currentValue)

        // ── Optimized Layout - minimal padding ──────────────────────────────────────
        val digitAreaH  = height * 0.75f  // Increased from 0.62f to use more vertical space
        val digitSize   = minOf(width / (totalChars.coerceAtLeast(1) * 0.60f), digitAreaH * 0.90f)  // Optimized sizing
        val digitBaseline = height * 0.68f  // Adjusted for better centering

        // Measure the actual pixel width of the digit string at this size
        digitPaint.textSize = digitSize
        val digitBlockW = digitPaint.measureText(ghostStr)   // ghost == same width as live

        // Superscript unit: ~35% of digit size, baseline raised to top of digit cap-height
        val unitSize     = digitSize * 0.35f
        labelPaint.typeface = Typeface.DEFAULT
        labelPaint.textSize = unitSize
        // Approximate cap-height as 70% of digitSize
        val unitBaseline = digitBaseline - digitSize * 0.68f

        val nameSize = height * 0.07f

        // Centre of digit block (digits are drawn centred at cx by Paint.Align.CENTER)
        // Right edge of digit block = cx + digitBlockW/2
        // Place unit just to the right of the digit block with a small gap
        val unitX = cx + digitBlockW / 2f + unitSize * 0.3f

        // ── Ghost layer ───────────────────────────────────────────
        ghostPaint.textSize = digitSize
        ghostPaint.color = (colorScheme.accent and 0x00FFFFFF) or 0x1A000000.toInt()
        canvas.drawText(ghostStr, cx, digitBaseline, ghostPaint)

        // ── Live value ────────────────────────────────────────────
        val isWarning = warningThreshold?.let { currentValue >= it } ?: false
        digitPaint.color = if (isWarning) colorScheme.warning else colorScheme.accent
        canvas.drawText(valueStr, cx, digitBaseline, digitPaint)

        // ── Unit as superscript (top-right of digit block) ────────
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.color = colorScheme.text
        labelPaint.textSize = unitSize
        if (metricUnit.isNotEmpty()) {
            canvas.drawText(metricUnit, unitX, unitBaseline, labelPaint)
        }

        // ── Metric name — smaller, just below digit block ─────────
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = nameSize
        // Keep hue from colorScheme.text but reduce to 73% opacity for a subtle label
        labelPaint.color = (colorScheme.text and 0x00FFFFFF) or 0xBB000000.toInt()
        canvas.drawText(metricName.uppercase(), cx, digitBaseline + nameSize * 1.4f, labelPaint)
    }
}
