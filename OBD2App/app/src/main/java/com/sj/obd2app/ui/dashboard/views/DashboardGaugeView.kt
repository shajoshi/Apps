package com.sj.obd2app.ui.dashboard.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.sj.obd2app.ui.dashboard.model.ColorScheme

/**
 * Common base class for all dashboard widgets.
 * Handles theming and a current float value.
 */
abstract class DashboardGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // The current value to display
    protected var currentValue: Float = 0f
        private set

    // Label or metric name (e.g. "Engine RPM")
    var metricName: String = ""
        set(value) { field = value; invalidate() }

    // Unit string (e.g. "rpm", "km/h")
    var metricUnit: String = ""
        set(value) { field = value; invalidate() }

    // The current colour scheme to draw with
    var colorScheme: ColorScheme = ColorScheme.DEFAULT_DARK
        set(value) { field = value; invalidate() }

    /**
     * Updates the gauge's value and triggers a redraw.
     * In a future enhancement, this could use a ValueAnimator to smoothly
     * sweep the needle/bar.
     */
    fun setValue(v: Float) {
        if (currentValue != v) {
            currentValue = v
            invalidate()
        }
    }
}
