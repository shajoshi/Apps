package com.sj.obd2app.ui.dashboard.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log

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

    companion object {
        private const val TAG = "SevenSegmentView"
    }

    private val digitTypeface: Typeface by lazy {
        try {
            Typeface.createFromAsset(context.assets, "fonts/digital-7.ttf")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom font 'fonts/digital-7.ttf', using MONOSPACE fallback", e)
            Typeface.MONOSPACE
        }
    }

    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Matrix for italic transformation (skew to the left)
    private val italicMatrix = Matrix().apply {
        setSkew(-0.15f, 0f) // 15% skew horizontally for italic effect (negative = left tilt)
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

        val safeDecimals = decimalPlaces.coerceIn(0, 4)
        val fmt = "%.${safeDecimals}f"
        val valueStr = String.format(fmt, currentValue)

        // ── Optimized Layout - compact ──────────────────────────────────────
        val digitAreaH  = height * 0.82f
        val digitSize   = minOf(width / (totalChars.coerceAtLeast(1) * 0.55f), digitAreaH * 0.95f)
        val digitBaseline = height * 0.68f  // Adjusted for better centering

        // Measure the actual pixel width of the digit string at this size
        digitPaint.textSize = digitSize
        val digitBlockW = digitPaint.measureText(ghostStr)   // ghost == same width as live

        // Unit: smaller (25% of digit size), aligned to left side of widget
        val unitSize     = digitSize * 0.25f
        labelPaint.typeface = Typeface.DEFAULT
        labelPaint.textSize = unitSize
        // Approximate cap-height as 70% of digitSize
        val unitBaseline = digitBaseline - digitSize * 0.68f

        val nameSize = height * 0.09f

        // Align unit to left side of widget with small padding
        val pad = minOf(width, height) * 0.05f
        val unitX = pad + unitSize * 0.2f

        // ── Ghost layer ───────────────────────────────────────────
        ghostPaint.textSize = digitSize
        ghostPaint.color = (colorScheme.accent and 0x00FFFFFF) or 0x0F000000.toInt()
        
        // Apply italic transformation for ghost digits
        canvas.save()
        canvas.concat(italicMatrix)
        canvas.drawText(ghostStr, cx, digitBaseline, ghostPaint)
        canvas.restore()

        // ── Live value ────────────────────────────────────────────
        val isWarning = warningThreshold?.let { currentValue >= it } ?: false
        digitPaint.color = if (isWarning) colorScheme.warning else colorScheme.accent
        
        // Apply italic transformation for live digits
        canvas.save()
        canvas.concat(italicMatrix)
        drawTextWithGlow(canvas, valueStr, cx, digitBaseline, digitPaint)
        canvas.restore()

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
        labelPaint.color = (colorScheme.text and 0x00FFFFFF) or 0xCC000000.toInt()
        canvas.drawText(metricName.uppercase(), cx, digitBaseline + nameSize * 1.4f, labelPaint)
    }
}
