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

    // The current layout being created or edited
    private val _currentLayout = MutableStateFlow(
        DashboardLayout(
            name = "My Dashboard",
            colorScheme = ColorScheme.DEFAULT_DARK,
            widgets = listOf(
                // Start with a default RPM dial
                DashboardWidget(
                    id = UUID.randomUUID().toString(),
                    type = WidgetType.REV_COUNTER,
                    metric = DashboardMetric.Obd2Pid("010C", "Engine RPM", "rpm"),
                    gridX = 1, gridY = 1,
                    gridW = 6, gridH = 6,
                    zOrder = 0
                )
            )
        )
    )
    val currentLayout: StateFlow<DashboardLayout> = _currentLayout.asStateFlow()
    
    // Tracks the currently selected widget ID for the property inspector
    private val _selectedWidgetId = MutableStateFlow<String?>(null)
    val selectedWidgetId: StateFlow<String?> = _selectedWidgetId

    private val _isPropertiesPanelOpen = MutableStateFlow(false)
    val isPropertiesPanelOpen: StateFlow<Boolean> = _isPropertiesPanelOpen

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

    fun addWidget(type: WidgetType, metric: DashboardMetric) {
        val layout = _currentLayout.value
        val newZ = (layout.widgets.maxOfOrNull { it.zOrder } ?: -1) + 1
        
        val newWidget = DashboardWidget(
            id = UUID.randomUUID().toString(),
            type = type,
            metric = metric,
            gridX = 2, gridY = 2, // default placement
            gridW = 4, gridH = 4, // default size
            zOrder = newZ
        )
        
        _currentLayout.value = layout.copy(
            widgets = layout.widgets + newWidget
        )
        _selectedWidgetId.value = newWidget.id
    }
    
    fun removeSelectedWidget() {
        val id = _selectedWidgetId.value ?: return
        val layout = _currentLayout.value
        _currentLayout.value = layout.copy(
            widgets = layout.widgets.filter { it.id != id }
        )
        _selectedWidgetId.value = null
        _isPropertiesPanelOpen.value = false
    }

    fun updateSelectedWidgetPosition(widgetId: String, newGridX: Int, newGridY: Int) {
        val layout = _currentLayout.value
        val updatedWidgets = layout.widgets.map { w ->
            if (w.id == widgetId) {
                // Prevent going completely off-screen (basic bounds)
                w.copy(gridX = maxOf(0, newGridX), gridY = maxOf(0, newGridY))
            } else w
        }
        _currentLayout.value = layout.copy(widgets = updatedWidgets)
    }

    fun updateWidgetBounds(widgetId: String, newGridX: Int, newGridY: Int, newGridW: Int, newGridH: Int) {
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
         val id = _selectedWidgetId.value ?: return
         val layout = _currentLayout.value
         val updatedWidgets = layout.widgets.map { w ->
             if (w.id == id) w.copy(alpha = newAlpha.coerceIn(0f, 1f)) else w
         }
         _currentLayout.value = layout.copy(widgets = updatedWidgets)
    }
    
    fun bringSelectedToFront() {
        val id = _selectedWidgetId.value ?: return
        val layout = _currentLayout.value
        val maxZ = layout.widgets.maxOfOrNull { it.zOrder } ?: 0
        
        val updatedWidgets = layout.widgets.map { w ->
            if (w.id == id) w.copy(zOrder = maxZ + 1) else w
        }
        _currentLayout.value = layout.copy(widgets = updatedWidgets)
    }
    
    fun sendSelectedToBack() {
        val id = _selectedWidgetId.value ?: return
        val layout = _currentLayout.value
        val minZ = layout.widgets.minOfOrNull { it.zOrder } ?: 0
        
        val updatedWidgets = layout.widgets.map { w ->
            if (w.id == id) w.copy(zOrder = minZ - 1) else w
        }
        _currentLayout.value = layout.copy(widgets = updatedWidgets)
    }

    fun setColorScheme(scheme: ColorScheme) {
        val layout = _currentLayout.value
        _currentLayout.value = layout.copy(colorScheme = scheme)
    }
    
    fun loadLayout(layout: DashboardLayout) {
        _currentLayout.value = layout
        _selectedWidgetId.value = null
        _isPropertiesPanelOpen.value = false
    }
}
