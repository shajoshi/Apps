package com.sj.obd2app.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.sj.obd2app.R
import com.sj.obd2app.ui.dashboard.data.LayoutRepository
import com.sj.obd2app.ui.dashboard.model.DashboardMetric
import com.sj.obd2app.ui.dashboard.model.WidgetType
import com.sj.obd2app.ui.dashboard.views.*
import android.app.AlertDialog
import android.widget.ArrayAdapter
import android.widget.Spinner
import kotlinx.coroutines.launch

/**
 * Controller for the drag-and-drop dashboard canvas editor.
 */
class DashboardEditorFragment : Fragment() {

    private lateinit var viewModel: DashboardEditorViewModel
    private var isEditMode = false
    private val gridSizePx = 100 // Hardcoded for simplicity, could be dynamic

    private lateinit var canvasContainer: FrameLayout
    private lateinit var gridOverlay: GridOverlayView
    private lateinit var btnToggleEdit: Button
    private lateinit var btnAddWidget: Button
    private lateinit var btnSave: Button
    private lateinit var panelProperties: View

    private lateinit var btnBringFront: Button
    private lateinit var btnSendBack: Button
    private lateinit var btnDeleteWidget: Button
    private lateinit var sliderAlpha: SeekBar

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        viewModel = ViewModelProvider(this)[DashboardEditorViewModel::class.java]
        return inflater.inflate(R.layout.fragment_dashboard_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        canvasContainer = view.findViewById(R.id.canvas_container)
        gridOverlay = view.findViewById(R.id.grid_overlay)
        gridOverlay.gridSizePx = gridSizePx
        panelProperties = view.findViewById(R.id.panel_properties)
        
        btnToggleEdit = view.findViewById(R.id.btn_toggle_edit)
        btnAddWidget = view.findViewById(R.id.btn_add_widget)
        btnSave = view.findViewById(R.id.btn_save)
        
        btnBringFront = view.findViewById(R.id.btn_bring_front)
        btnSendBack = view.findViewById(R.id.btn_send_back)
        btnDeleteWidget = view.findViewById(R.id.btn_delete_widget)
        sliderAlpha = view.findViewById(R.id.slider_alpha)

        val btnCloseProperties: View = view.findViewById(R.id.btn_close_properties)
        btnCloseProperties.setOnClickListener {
            viewModel.selectWidget(null)
        }

        setupButtons()
        observeViewModel()
        
        // Check if we navigated here with a saved layout name
        val layoutName = arguments?.getString("layout_name")
        if (layoutName != null) {
            val repo = LayoutRepository(requireContext())
            val layout = repo.getSavedLayouts().find { it.name == layoutName }
            if (layout != null) {
                viewModel.loadLayout(layout)
                // When loading an existing layout, we might want saving to be possible 
                // even before toggling strictly into 'Add Widget' mode.
                btnSave.visibility = View.VISIBLE
            }
        }
    }

    private fun setupButtons() {
        btnToggleEdit.setOnClickListener {
            isEditMode = !isEditMode
            gridOverlay.visibility = if (isEditMode) View.VISIBLE else View.GONE
            btnAddWidget.visibility = if (isEditMode) View.VISIBLE else View.GONE
            btnSave.visibility = if (isEditMode) View.VISIBLE else View.GONE
            btnToggleEdit.text = if (isEditMode) "Preview" else "Edit Layout"
            
            if (!isEditMode) viewModel.selectWidget(null)
            
            // Re-render canvas to attach/detach touch listeners
            renderCanvas(viewModel.currentLayout.value)
        }

        btnAddWidget.setOnClickListener {
            showAddWidgetDialog()
        }

        btnSave.setOnClickListener {
            val layout = viewModel.currentLayout.value
            val repo = LayoutRepository(requireContext())
            repo.saveLayout(layout).onSuccess {
                Toast.makeText(context, "Saved to ${it.name}", Toast.LENGTH_SHORT).show()
                isEditMode = false
                btnToggleEdit.performClick() // Toggle back to preview
            }.onFailure {
                Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
            }
        }
        
        btnBringFront.setOnClickListener { viewModel.bringSelectedToFront() }
        btnSendBack.setOnClickListener { viewModel.sendSelectedToBack() }
        btnDeleteWidget.setOnClickListener { viewModel.removeSelectedWidget() }
        
        sliderAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewModel.updateSelectedWidgetAlpha(progress / 100f)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun showAddWidgetDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_widget, null)
        val spinnerMetric: Spinner = dialogView.findViewById(R.id.spinner_metric)
        val spinnerType: Spinner = dialogView.findViewById(R.id.spinner_type)
        val btnCancel: Button = dialogView.findViewById(R.id.btn_cancel)
        val btnAdd: Button = dialogView.findViewById(R.id.btn_add)

        // Setup Metric Options
        val metricOptions = listOf(
            "OBD2: Engine RPM",
            "OBD2: Vehicle Speed",
            "OBD2: Coolant Temp",
            "GPS: Speed",
            "GPS: Altitude (MSL)"
        )
        spinnerMetric.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, metricOptions)

        // Setup Type Options
        val typeOptions = WidgetType.values().map { it.name.replace("_", " ") }
        spinnerType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, typeOptions)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        
        btnAdd.setOnClickListener {
            val selectedType = WidgetType.values()[spinnerType.selectedItemPosition]
            val selectedMetric = when (spinnerMetric.selectedItemPosition) {
                0 -> DashboardMetric.Obd2Pid("010C", "Engine RPM", "rpm")
                1 -> DashboardMetric.Obd2Pid("010D", "Vehicle Speed", "km/h")
                2 -> DashboardMetric.Obd2Pid("0105", "Coolant Temp", "°C")
                3 -> DashboardMetric.GpsSpeed
                4 -> DashboardMetric.GpsAltitude
                else -> DashboardMetric.GpsSpeed
            }
            
            viewModel.addWidget(selectedType, selectedMetric)
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentLayout.collect { layout ->
                renderCanvas(layout)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedWidgetId.collect { id ->
                // If a widget is selected, update slider to its current value
                if (id != null) {
                    val widget = viewModel.currentLayout.value.widgets.find { it.id == id }
                    if (widget != null) {
                        sliderAlpha.progress = (widget.alpha * 100).toInt()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isPropertiesPanelOpen.collect { isOpen ->
                panelProperties.visibility = if (isOpen && isEditMode) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * Rebuilds the FrameLayout contents based on the layout model.
     * Uses the Z-order to insert views.
     */
    private fun renderCanvas(layout: com.sj.obd2app.ui.dashboard.model.DashboardLayout) {
        // Warning: full recreation is inefficient for production, 
        // better to use recycler or diffing. Sufficient for Phase 1 structure.
        canvasContainer.removeAllViews()

        val sortedWidgets = layout.widgets.sortedBy { it.zOrder }
        val selectedId = viewModel.selectedWidgetId.value

        for (widget in sortedWidgets) {
            // Inflate the wrapper that contains the handles
            val wrapper = layoutInflater.inflate(R.layout.layout_widget_wrapper, canvasContainer, false)
            val contentFrame = wrapper.findViewById<FrameLayout>(R.id.widget_content_frame)

            // Create the actual gauge
            val view = createViewForWidgetType(widget.type)
            
            // Map the metric
            when (val m = widget.metric) {
                is DashboardMetric.Obd2Pid -> {
                    view.metricName = m.name
                    view.metricUnit = m.unit
                }
                DashboardMetric.GpsSpeed -> {
                    view.metricName = "GPS SPEED"
                    view.metricUnit = "km/h"
                }
                DashboardMetric.GpsAltitude -> {
                    view.metricName = "ALTITUDE"
                    view.metricUnit = "m (MSL)"
                }
            }

            // Apply styling
            view.colorScheme = layout.colorScheme
            view.alpha = widget.alpha

            // Add gauge to inner frame
            contentFrame.addView(view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))

            // Highlight selected logic -> border and handles
            val isSelected = isEditMode && (widget.id == selectedId)
            val border = wrapper.findViewById<View>(R.id.selection_border)
            val handleTL = wrapper.findViewById<View>(R.id.handle_tl)
            val handleTR = wrapper.findViewById<View>(R.id.handle_tr)
            val handleBL = wrapper.findViewById<View>(R.id.handle_bl)
            val handleBR = wrapper.findViewById<View>(R.id.handle_br)

            border.visibility = if (isSelected) View.VISIBLE else View.GONE
            handleTL.visibility = if (isSelected) View.VISIBLE else View.GONE
            handleTR.visibility = if (isSelected) View.VISIBLE else View.GONE
            handleBL.visibility = if (isSelected) View.VISIBLE else View.GONE
            handleBR.visibility = if (isSelected) View.VISIBLE else View.GONE

            // Apply size & position to WRAPPER
            val lp = FrameLayout.LayoutParams(
                widget.gridW * gridSizePx,
                widget.gridH * gridSizePx
            )
            wrapper.layoutParams = lp
            wrapper.x = (widget.gridX * gridSizePx).toFloat()
            wrapper.y = (widget.gridY * gridSizePx).toFloat()

            // Attach drag & resize handlers if editing
            if (isEditMode) {
                wrapper.setOnTouchListener(WidgetTouchHandler(viewModel, widget.id, gridSizePx))
                
                if (isSelected) {
                    handleTL.setOnTouchListener(WidgetResizeHandler(viewModel, widget, wrapper, 0, gridSizePx))
                    handleTR.setOnTouchListener(WidgetResizeHandler(viewModel, widget, wrapper, 1, gridSizePx))
                    handleBL.setOnTouchListener(WidgetResizeHandler(viewModel, widget, wrapper, 2, gridSizePx))
                    handleBR.setOnTouchListener(WidgetResizeHandler(viewModel, widget, wrapper, 3, gridSizePx))
                }
            } else {
                wrapper.setOnTouchListener(null)
            }

            canvasContainer.addView(wrapper)
            
            // In a real app we would collect stateflow from GpsDataSource/Obd2ServiceProvider
            // here and call view.setValue() dynamically.
            // For now just set a fake demo value depending on type
            when(widget.type) {
                WidgetType.REV_COUNTER -> view.setValue(3200f)
                WidgetType.SPEEDOMETER_7SEG -> view.setValue(114f)
                WidgetType.FUEL_BAR, WidgetType.IFC_BAR -> view.setValue(45f)
                WidgetType.NUMERIC_DISPLAY -> view.setValue(12.4f)
            }
        }
    }

    private fun createViewForWidgetType(type: WidgetType): DashboardGaugeView {
        val ctx = requireContext()
        return when (type) {
            WidgetType.REV_COUNTER -> RevCounterView(ctx)
            WidgetType.SPEEDOMETER_7SEG -> SevenSegmentSpeedometerView(ctx)
            WidgetType.FUEL_BAR -> FuelBarView(ctx)
            WidgetType.IFC_BAR -> IfcBarView(ctx)
            WidgetType.NUMERIC_DISPLAY -> NumericDisplayView(ctx)
        }
    }
}
