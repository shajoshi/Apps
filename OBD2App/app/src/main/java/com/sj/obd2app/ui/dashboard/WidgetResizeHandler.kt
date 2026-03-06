package com.sj.obd2app.ui.dashboard

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.sj.obd2app.ui.dashboard.model.DashboardWidget

/**
 * Handles touch events on the 4 corner handles of a selected widget wrapper,
 * allowing the user to drag the corners to resize the widget. It updates the 
 * FrameLayout bounds smoothly and snaps to the grid on ACTION_UP.
 */
class WidgetResizeHandler(
    private val viewModel: DashboardEditorViewModel,
    private val widget: DashboardWidget,
    private val wrapperView: View, // The container FrameLayout
    private val corner: Int, // 0=TopLeft, 1=TopRight, 2=BottomLeft, 3=BottomRight
    private val gridSizePx: Int
) : View.OnTouchListener {

    private var initialRawX = 0f
    private var initialRawY = 0f
    private var initialW = 0
    private var initialH = 0
    private var initialX = 0f
    private var initialY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        // Intercept parent touches so we don't accidentally drag the whole widget
        view.parent?.requestDisallowInterceptTouchEvent(true)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialRawX = event.rawX
                initialRawY = event.rawY
                initialW = wrapperView.width
                initialH = wrapperView.height
                initialX = wrapperView.x
                initialY = wrapperView.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - initialRawX
                val deltaY = event.rawY - initialRawY

                var newW = initialW.toFloat()
                var newH = initialH.toFloat()
                var newX = initialX
                var newY = initialY

                when (corner) {
                    0 -> { // Top Left
                        newW = initialW - deltaX
                        newH = initialH - deltaY
                        newX = initialX + deltaX
                        newY = initialY + deltaY
                    }
                    1 -> { // Top Right
                        newW = initialW + deltaX
                        newH = initialH - deltaY
                        newY = initialY + deltaY
                    }
                    2 -> { // Bottom Left
                        newW = initialW - deltaX
                        newH = initialH + deltaY
                        newX = initialX + deltaX
                    }
                    3 -> { // Bottom Right
                        newW = initialW + deltaX
                        newH = initialH + deltaY
                    }
                }

                // Enforce minimum physical size to prevent flipping
                val minSize = gridSizePx.toFloat() * 1.5f 
                if (newW < minSize) {
                    if (corner == 0 || corner == 2) newX -= (minSize - newW)
                    newW = minSize
                }
                if (newH < minSize) {
                    if (corner == 0 || corner == 1) newY -= (minSize - newH)
                    newH = minSize
                }

                // Apply dynamic bounds to wrapper layout smoothly
                wrapperView.x = newX
                wrapperView.y = newY
                val lp = wrapperView.layoutParams as FrameLayout.LayoutParams
                lp.width = newW.toInt()
                lp.height = newH.toInt()
                wrapperView.layoutParams = lp

                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val finalX = wrapperView.x
                val finalY = wrapperView.y
                val finalW = wrapperView.width.toFloat()
                val finalH = wrapperView.height.toFloat()

                // Calculate the nearest grid bounds
                val gridX = Math.round(finalX / gridSizePx).coerceAtLeast(0)
                val gridY = Math.round(finalY / gridSizePx).coerceAtLeast(0)
                val gridW = Math.round(finalW / gridSizePx).coerceAtLeast(2) // Min 2x2 grid
                val gridH = Math.round(finalH / gridSizePx).coerceAtLeast(2)

                // Snap visually
                wrapperView.x = (gridX * gridSizePx).toFloat()
                wrapperView.y = (gridY * gridSizePx).toFloat()
                val lp = wrapperView.layoutParams as FrameLayout.LayoutParams
                lp.width = gridW * gridSizePx
                lp.height = gridH * gridSizePx
                wrapperView.layoutParams = lp

                // Inform the ViewModel to save the new bounds
                viewModel.updateWidgetBounds(widget.id, gridX, gridY, gridW, gridH)
                return true
            }
        }
        return false
    }
}
