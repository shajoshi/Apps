package com.sj.obd2app.ui.dashboard

import androidx.lifecycle.ViewModel
import com.sj.obd2app.ui.dashboard.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Manages the state of the drag-and-drop dashboard canvas.
 */
class DashboardEditorViewModel : ViewModel() {

    // Canvas grid dimensions — set by the fragment after layout; used to center new widgets
    var canvasGridW: Int = 0
    var canvasGridH: Int = 0

    // The current layout being created or edited
    private val _currentLayout = MutableStateFlow(
        DashboardLayout(
            name = "My Dashboard",
            colorScheme = ColorScheme.DEFAULT_DARK,
            widgets = listOf(
                // Start with a default RPM dial — scale auto-applied from MetricDefaults
                run {
                    val metric = DashboardMetric.Obd2Pid("010C", "Engine RPM", "rpm")
                    val defaults = MetricDefaults.get(metric)
                    DashboardWidget(
                        id = UUID.randomUUID().toString(),
                        type = WidgetType.DIAL,
                        metric = metric,
                        gridX = 1, gridY = 1,
                        gridW = 6, gridH = 6,
                        zOrder = 0,
                        rangeMin = defaults.rangeMin,
                        rangeMax = defaults.rangeMax,
                        majorTickInterval = defaults.majorTickInterval,
                        minorTickCount = defaults.minorTickCount,
                        warningThreshold = defaults.warningThreshold,
                        decimalPlaces = defaults.decimalPlaces,
                        displayUnit = defaults.displayUnit
                    )
                }
            ),
            orientation = DashboardOrientation.PORTRAIT
        )
    )
    val currentLayout: StateFlow<DashboardLayout> = _currentLayout.asStateFlow()
    
    // Tracks the currently selected widget ID for the property inspector
    private val _selectedWidgetId = MutableStateFlow<String?>(null)
    val selectedWidgetId: StateFlow<String?> = _selectedWidgetId

    private val _isPropertiesPanelOpen = MutableStateFlow(false)
    val isPropertiesPanelOpen: StateFlow<Boolean> = _isPropertiesPanelOpen

    // Single-level undo — snapshot taken before each mutating operation
    private var _undoSnapshot: DashboardLayout? = null
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private fun snapshot() {
        _undoSnapshot = _currentLayout.value
        _canUndo.value = true
    }

    fun undo() {
        val snap = _undoSnapshot ?: return
        _currentLayout.value = snap
        _undoSnapshot = null
        _canUndo.value = false
        _selectedWidgetId.value = null
        _isPropertiesPanelOpen.value = false
    }

    fun selectWidget(id: String?) {
        if (_selectedWidgetId.value != id) {
            _selectedWidgetId.value = id
            _isPropertiesPanelOpen.value = false // Close panel when changing selection
        }
    }

    fun openPropertiesPanel() {
        if (_selectedWidgetId.value != null) {
            _isPropertiesPanelOpen.value = true
        }
    }

    fun closePropertiesPanel() {
        _isPropertiesPanelOpen.value = false
    }

    /**
     * Adds a new widget, auto-populating scale settings from MetricDefaults.
     * The widget is placed at the first free grid slot.
     * [gridW] and [gridH] are in grid units.
     */
    fun addWidget(
        type: WidgetType,
        metric: DashboardMetric,
        gridW: Int = 4,
        gridH: Int = 4
    ) {
        val layout = _currentLayout.value
        val newZ = (layout.widgets.maxOfOrNull { it.zOrder } ?: -1) + 1
        val defaults = MetricDefaults.get(metric)

        // Always place at first free slot to avoid overlaps
        val (slotX, slotY) = findFirstFreeSlot(layout, gridW, gridH)

        val newWidget = DashboardWidget(
            id = UUID.randomUUID().toString(),
            type = type,
            metric = metric,
            gridX = slotX,
            gridY = slotY,
            gridW = gridW,
            gridH = gridH,
            zOrder = newZ,
            rangeMin = defaults.rangeMin,
            rangeMax = defaults.rangeMax,
            majorTickInterval = defaults.majorTickInterval,
            minorTickCount = defaults.minorTickCount,
            warningThreshold = defaults.warningThreshold,
            decimalPlaces = defaults.decimalPlaces,
            displayUnit = defaults.displayUnit
        )

        _currentLayout.value = layout.copy(widgets = layout.widgets + newWidget)
        _selectedWidgetId.value = newWidget.id
    }

    /** Finds the first unoccupied top-left grid position for a widget of the given size. */
    private fun findFirstFreeSlot(layout: DashboardLayout, w: Int, h: Int): Pair<Int, Int> {
        val occupied = layout.widgets.flatMap { widget ->
            (widget.gridX until widget.gridX + widget.gridW).flatMap { x ->
                (widget.gridY until widget.gridY + widget.gridH).map { y -> x to y }
            }
        }.toSet()
        for (row in 0..20) {
            for (col in 0..20) {
                val fits = (col until col + w).all { x ->
                    (row until row + h).all { y -> (x to y) !in occupied }
                }
                if (fits) return col to row
            }
        }
        return 0 to 0 // fallback
    }
    
    fun removeSelectedWidget() {
        val id = _selectedWidgetId.value ?: return
        removeWidget(id)
    }

    fun removeWidget(id: String) {
        snapshot()
        val layout = _currentLayout.value
        _currentLayout.value = layout.copy(
            widgets = layout.widgets.filter { it.id != id }
        )
        if (_selectedWidgetId.value == id) {
            _selectedWidgetId.value = null
            _isPropertiesPanelOpen.value = false
        }
    }

    /**
     * Clamps all widget positions to fit within the given canvas grid dimensions.
     * Does NOT create an undo snapshot — this is a silent correction for canvas resize events.
     */
    fun clampAllWidgetsToBounds(gridW: Int, gridH: Int) {
        val layout = _currentLayout.value
        val clamped = layout.widgets.map { w ->
            val maxX = (gridW - w.gridW).coerceAtLeast(0)
            val maxY = (gridH - w.gridH).coerceAtLeast(0)
            w.copy(gridX = w.gridX.coerceIn(0, maxX), gridY = w.gridY.coerceIn(0, maxY))
        }
        if (clamped != layout.widgets) {
            _currentLayout.value = layout.copy(widgets = clamped)
        }
    }

    fun updateSelectedWidgetPosition(widgetId: String, newGridX: Int, newGridY: Int) {
        snapshot()
        val layout = _currentLayout.value
        val updatedWidgets = layout.widgets.map { w ->
            if (w.id == widgetId) {
                w.copy(gridX = maxOf(0, newGridX), gridY = maxOf(0, newGridY))
            } else w
        }
        _currentLayout.value = layout.copy(widgets = updatedWidgets)
    }

    fun updateWidgetBounds(widgetId: String, newGridX: Int, newGridY: Int, newGridW: Int, newGridH: Int) {
        snapshot()
        val layout = _currentLayout.value
        val updatedWidgets = layout.widgets.map { w ->
            if (w.id == widgetId) {
                w.copy(
                    gridX = maxOf(0, newGridX),
                    gridY = maxOf(0, newGridY),
                    gridW = maxOf(2, newGridW),
                    gridH = maxOf(2, newGridH)
                )
            } else w
        }
        _currentLayout.value = layout.copy(widgets = updatedWidgets)
    }
    
    fun updateSelectedWidgetAlpha(newAlpha: Float) {
        snapshot()
         val id = _selectedWidgetId.value ?: return
         val layout = _currentLayout.value
         val updatedWidgets = layout.widgets.map { w ->
             if (w.id == id) w.copy(alpha = newAlpha.coerceIn(0f, 1f)) else w
         }
         _currentLayout.value = layout.copy(widgets = updatedWidgets)
    }
    
    fun bringSelectedToFront() {
        snapshot()
        val id = _selectedWidgetId.value ?: return
        val layout = _currentLayout.value
        val maxZ = layout.widgets.maxOfOrNull { it.zOrder } ?: 0
        
        val updatedWidgets = layout.widgets.map { w ->
            if (w.id == id) w.copy(zOrder = maxZ + 1) else w
        }
        _currentLayout.value = layout.copy(widgets = updatedWidgets)
    }
    
    fun sendSelectedToBack() {
        snapshot()
        val id = _selectedWidgetId.value ?: return
        val layout = _currentLayout.value
        val minZ = layout.widgets.minOfOrNull { it.zOrder } ?: 0
        
        val updatedWidgets = layout.widgets.map { w ->
            if (w.id == id) w.copy(zOrder = minZ - 1) else w
        }
        _currentLayout.value = layout.copy(widgets = updatedWidgets)
    }

    /** Updates scale and unit settings for a specific widget. */
    fun updateWidgetRangeSettings(
        widgetId: String,
        rangeMin: Float,
        rangeMax: Float,
        majorTickInterval: Float,
        minorTickCount: Int,
        warningThreshold: Float?,
        decimalPlaces: Int,
        displayUnit: String
    ) {
        val layout = _currentLayout.value
        val updated = layout.widgets.map { w ->
            if (w.id == widgetId) {
                w.copy(
                    gridX = w.gridX,  // Preserve position
                    gridY = w.gridY,  // Preserve position
                    gridW = w.gridW,  // Preserve size
                    gridH = w.gridH,  // Preserve size
                    zOrder = w.zOrder,  // Preserve drawing order
                    alpha = w.alpha,  // Preserve transparency
                    rangeMin = rangeMin,
                    rangeMax = rangeMax,
                    majorTickInterval = majorTickInterval,
                    minorTickCount = minorTickCount,
                    warningThreshold = warningThreshold,
                    decimalPlaces = decimalPlaces,
                    displayUnit = displayUnit
                )
            } else w
        }
        _currentLayout.value = layout.copy(widgets = updated)
    }

    /** Updates all user-editable properties of a widget, including size preset. */
    fun updateWidgetProperties(
        widgetId: String,
        metric: DashboardMetric,
        rangeMin: Float,
        rangeMax: Float,
        majorTickInterval: Float,
        minorTickCount: Int,
        warningThreshold: Float?,
        decimalPlaces: Int,
        displayUnit: String,
        gridW: Int,
        gridH: Int
    ) {
        snapshot()  // Create undo point before editing
        val layout = _currentLayout.value
        val updated = layout.widgets.map { w ->
            if (w.id == widgetId) w.copy(
                metric            = metric,
                gridX             = w.gridX,  // Preserve position
                gridY             = w.gridY,  // Preserve position
                zOrder            = w.zOrder, // Preserve drawing order
                alpha             = w.alpha,  // Preserve transparency
                rangeMin          = rangeMin,
                rangeMax          = rangeMax,
                majorTickInterval = majorTickInterval,
                minorTickCount    = minorTickCount,
                warningThreshold  = warningThreshold,
                decimalPlaces     = decimalPlaces,
                displayUnit       = displayUnit,
                gridW             = maxOf(2, gridW),
                gridH             = maxOf(2, gridH)
            ) else w
        }
        _currentLayout.value = layout.copy(widgets = updated)
    }

    fun setColorScheme(scheme: ColorScheme) {
        val layout = _currentLayout.value
        _currentLayout.value = layout.copy(colorScheme = scheme)
    }

    fun setOrientation(orient: DashboardOrientation) {
        _currentLayout.value = _currentLayout.value.copy(orientation = orient)
    }
    
    fun loadLayout(layout: DashboardLayout) {
        _currentLayout.value = layout
        _selectedWidgetId.value = null
        _isPropertiesPanelOpen.value = false
    }
}
