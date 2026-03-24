package com.sj.obd2app.ui.dashboard.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.sj.obd2app.ui.dashboard.model.ColorScheme

/**
 * Common base class for all dashboard widgets.
 * Handles theming, gauge scale, and animated value updates.
 */
abstract class DashboardGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // The current (animated) value to display
    protected var currentValue: Float = 0f
        private set

    // Track max/min values during trip for dial and bar gauges
    protected var tripMaxValue: Float? = null
        private set
    protected var tripMinValue: Float? = null
        private set

    // Label or metric name (e.g. "Engine RPM")
    var metricName: String = ""
        set(value) { field = value; invalidate() }

    // Unit string — driven from DashboardWidget.displayUnit at render time
    var metricUnit: String = ""
        set(value) { field = value; invalidate() }

    // The current colour scheme to draw with
    var colorScheme: ColorScheme = ColorScheme.DEFAULT_DARK
        set(value) { field = value; invalidate() }

    // ── Gauge scale — set from DashboardWidget fields ─────────────
    var rangeMin: Float = 0f
        set(value) { field = value; invalidate() }
    var rangeMax: Float = 100f
        set(value) { field = value; invalidate() }
    var majorTickInterval: Float = 10f
        set(value) { field = value; invalidate() }
    var minorTickCount: Int = 4
        set(value) { field = value; invalidate() }
    /** If non-null, the gauge draws a warning zone above this threshold. */
    var warningThreshold: Float? = null
        set(value) { field = value; invalidate() }
    var decimalPlaces: Int = 1
        set(value) { field = value; invalidate() }

    // Animator — reused across setValue() calls
    private var valueAnimator: ValueAnimator? = null

    /**
     * Updates the gauge's displayed value with a smooth 200 ms animation.
     */
    fun setValue(v: Float) {
        val target = v.coerceIn(rangeMin, rangeMax)
        
        // Track max/min values during trip
        if (tripMaxValue == null || target > tripMaxValue!!) {
            tripMaxValue = target
        }
        if (tripMinValue == null || target < tripMinValue!!) {
            tripMinValue = target
        }
        
        if (currentValue == target) return
        valueAnimator?.cancel()
        valueAnimator = ValueAnimator.ofFloat(currentValue, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                currentValue = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Sets the value immediately without animation (e.g. for preview renders). */
    fun setValueImmediate(v: Float) {
        valueAnimator?.cancel()
        currentValue = v.coerceIn(rangeMin, rangeMax)
        invalidate()
    }
    
    /** Resets the trip max/min values (call when starting a new trip). */
    fun resetTripMinMax() {
        tripMaxValue = null
        tripMinValue = null
        invalidate()
    }

    // ── Glow helper paint — reused across calls ───────────────
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Draws [text] with a soft glow/shadow behind it for readability on dark backgrounds.
     * Call this instead of canvas.drawText() for value strings.
     */
    protected fun drawTextWithGlow(
        canvas: Canvas, text: String, x: Float, y: Float, paint: Paint,
        glowColor: Int = 0x66000000, glowRadius: Float = paint.textSize * 0.08f
    ) {
        glowPaint.set(paint)
        glowPaint.color = glowColor
        glowPaint.maskFilter = BlurMaskFilter(glowRadius.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        canvas.drawText(text, x, y, glowPaint)
        glowPaint.maskFilter = null
        canvas.drawText(text, x, y, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        valueAnimator?.cancel()
    }
}
