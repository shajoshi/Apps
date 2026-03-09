package com.sj.obd2app.ui.dashboard.views

import android.animation.ValueAnimator
import android.content.Context
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        valueAnimator?.cancel()
    }
}
