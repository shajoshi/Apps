package com.sj.obd2app.ui.dashboard

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

/**
 * Handles touch events to drag a widget around the dashboard canvas,
 * snapping it to a virtual grid upon release.
 *
 * [onContextMenu] is invoked (with the anchor view) when the user taps a widget
 * that is already selected — used to show the "Edit Widget / Delete / …" popup.
 */
class WidgetTouchHandler(
    private val viewModel: DashboardEditorViewModel,
    private val widgetId: String,
    private val gridSizePx: Int = 100,
    private val onContextMenu: ((anchor: View) -> Unit)? = null,
    private val getCanvasScale: (() -> Float)? = null,
    private val isMoveResizeMode: Boolean = false               // drag only when explicitly enabled
) : View.OnTouchListener {

    private var downRawX = 0f       // raw screen X when finger went down
    private var downRawY = 0f       // raw screen Y when finger went down
    private var startViewX = 0f     // view.x at the moment of ACTION_DOWN
    private var startViewY = 0f     // view.y at the moment of ACTION_DOWN
    private var lastAction = 0
    private var wasAlreadySelected = false
    private var dragStarted = false

    companion object {
        private const val DRAG_THRESHOLD_DP = 10f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val density = view.resources.displayMetrics.density
        val dragThresholdPx = DRAG_THRESHOLD_DP * density
        val scale = getCanvasScale?.invoke() ?: 1f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wasAlreadySelected = (viewModel.selectedWidgetId.value == widgetId)
                viewModel.selectWidget(widgetId)

                // Capture raw screen position and current view position in canvas space.
                downRawX  = event.rawX
                downRawY  = event.rawY
                startViewX = view.x
                startViewY = view.y
                lastAction = MotionEvent.ACTION_DOWN
                dragStarted = false
                view.elevation = 20f
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isMoveResizeMode) return true  // drag disabled unless in move/resize mode
                val totalDx = event.rawX - downRawX
                val totalDy = event.rawY - downRawY
                if (!dragStarted) {
                    if (Math.sqrt((totalDx * totalDx + totalDy * totalDy).toDouble()) < dragThresholdPx) {
                        return true
                    }
                    dragStarted = true
                }
                // The finger moved (totalDx, totalDy) in screen pixels.
                // Divide by scale to convert to canvas-space pixels, then offset from start.
                view.x = startViewX + totalDx / scale
                view.y = startViewY + totalDy / scale
                lastAction = MotionEvent.ACTION_MOVE
                return true
            }

            MotionEvent.ACTION_UP -> {
                view.elevation = 0f

                if (lastAction == MotionEvent.ACTION_DOWN) {
                    if (wasAlreadySelected && onContextMenu != null) {
                        onContextMenu.invoke(view)
                    }
                } else if (lastAction == MotionEvent.ACTION_MOVE && isMoveResizeMode) {
                    // Snap to nearest grid intersection; only clamp lower bound (no negative coords)
                    val gridX = Math.round(view.x / gridSizePx).coerceAtLeast(0)
                    val gridY = Math.round(view.y / gridSizePx).coerceAtLeast(0)

                    view.x = (gridX * gridSizePx).toFloat()
                    view.y = (gridY * gridSizePx).toFloat()

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
