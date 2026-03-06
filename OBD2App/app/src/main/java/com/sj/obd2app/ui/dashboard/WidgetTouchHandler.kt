package com.sj.obd2app.ui.dashboard

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * Handles touch events to drag a widget around the dashboard canvas,
 * snapping it to a virtual grid upon release.
 */
class WidgetTouchHandler(
    private val viewModel: DashboardEditorViewModel,
    private val widgetId: String,
    private val gridSizePx: Int = 100 // Example: 1 grid unit = 100 pixels
) : View.OnTouchListener {

    private var dX = 0f
    private var dY = 0f
    private var lastAction = 0
    private var lastClickTime: Long = 0
    private val DOUBLE_CLICK_TIMEOUT = 300L

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Select the widget in the ViewModel as soon as we touch it
                viewModel.selectWidget(widgetId)
                
                dX = view.x - event.rawX
                dY = view.y - event.rawY
                lastAction = MotionEvent.ACTION_DOWN
                // Elevate slightly while dragging to show it's active
                view.elevation = 20f
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                view.y = event.rawY + dY
                view.x = event.rawX + dX
                lastAction = MotionEvent.ACTION_MOVE
                return true
            }

            MotionEvent.ACTION_UP -> {
                view.elevation = 0f
                
                // Keep track of movement distance to distinguish dragging from clicking
                val clickTime = System.currentTimeMillis()
                
                if (lastAction == MotionEvent.ACTION_DOWN) {
                    // This was a click, not a drag
                    if (clickTime - lastClickTime < DOUBLE_CLICK_TIMEOUT) {
                        // Double click detected! Open the properties panel.
                        viewModel.openPropertiesPanel()
                        lastClickTime = 0L // Reset
                    } else {
                        // Just a single click (already selected in ACTION_DOWN)
                        lastClickTime = clickTime
                    }
                } else if (lastAction == MotionEvent.ACTION_MOVE) {
                    // Calculate nearest grid intersection
                    val newX = view.x
                    val newY = view.y
                    
                    val gridX = Math.round(newX / gridSizePx).toInt().coerceAtLeast(0)
                    val gridY = Math.round(newY / gridSizePx).toInt().coerceAtLeast(0)
                    
                    // Snap visually
                    view.x = (gridX * gridSizePx).toFloat()
                    view.y = (gridY * gridSizePx).toFloat()
                    
                    // Update ViewModel location
                    viewModel.updateSelectedWidgetPosition(widgetId, gridX, gridY)
                }
                return true
            }
            
            MotionEvent.ACTION_CANCEL -> {
                view.elevation = 0f
                return true
            }
            else -> return false
        }
    }
}
