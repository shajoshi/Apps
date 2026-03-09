package com.sj.obd2app.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sj.obd2app.R
import com.sj.obd2app.ui.dashboard.model.DashboardWidget
import com.sj.obd2app.ui.dashboard.model.WidgetType
import com.sj.obd2app.ui.dashboard.wizard.SizePreset

/**
 * Bottom-sheet dialog that lets the user edit an existing widget's properties:
 * scale (min/max), tick marks, warning threshold, decimal places, display unit, and size.
 *
 * Opens pre-filled from the widget's current settings. Tapping "Save" commits
 * the changes back to [DashboardEditorViewModel].
 */
class EditWidgetSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "EditWidgetSheet"
        private const val ARG_WIDGET_ID = "widget_id"

        fun newInstance(widgetId: String): EditWidgetSheet {
            return EditWidgetSheet().also { sheet ->
                sheet.arguments = Bundle().apply {
                    putString(ARG_WIDGET_ID, widgetId)
                }
            }
        }
    }

    private lateinit var viewModel: DashboardEditorViewModel

    // Editable fields
    private lateinit var etMin: EditText
    private lateinit var etMax: EditText
    private lateinit var etMajorTick: EditText
    private lateinit var etMinorTicks: EditText
    private lateinit var etWarning: EditText
    private lateinit var etDecimalPlaces: EditText
    private lateinit var etDisplayUnit: EditText
    private lateinit var tvSizeHint: TextView
    private lateinit var rowTicks: LinearLayout
    private lateinit var rowWarningDecimals: LinearLayout

    // Working copy of the editable values
    private var rangeMin = 0f
    private var rangeMax = 100f
    private var majorTickInterval = 10f
    private var minorTickCount = 4
    private var warningThreshold: Float? = null
    private var decimalPlaces = 1
    private var displayUnit = ""
    private var gridW = 4
    private var gridH = 4

    private var selectedPreset: SizePreset = SizePreset.MEDIUM
    private val presetButtonIds = listOf(
        R.id.btn_size_small, R.id.btn_size_medium, R.id.btn_size_large,
        R.id.btn_size_wide, R.id.btn_size_tall
    )

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        if (sheet != null) {
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_edit_widget, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireParentFragment())[DashboardEditorViewModel::class.java]

        val widgetId = arguments?.getString(ARG_WIDGET_ID) ?: run { dismiss(); return }
        val widget = viewModel.currentLayout.value.widgets.find { it.id == widgetId }
            ?: run { dismiss(); return }

        // Bind views
        etMin             = view.findViewById(R.id.et_range_min)
        etMax             = view.findViewById(R.id.et_range_max)
        etMajorTick       = view.findViewById(R.id.et_major_tick)
        etMinorTicks      = view.findViewById(R.id.et_minor_ticks)
        etWarning         = view.findViewById(R.id.et_warning)
        etDecimalPlaces   = view.findViewById(R.id.et_decimal_places)
        etDisplayUnit     = view.findViewById(R.id.et_display_unit)
        tvSizeHint        = view.findViewById(R.id.tv_size_hint)
        rowTicks          = view.findViewById(R.id.row_ticks)
        rowWarningDecimals = view.findViewById(R.id.row_warning_decimals)

        // Initialise working copy from widget
        rangeMin          = widget.rangeMin
        rangeMax          = widget.rangeMax
        majorTickInterval = widget.majorTickInterval
        minorTickCount    = widget.minorTickCount
        warningThreshold  = widget.warningThreshold
        decimalPlaces     = widget.decimalPlaces
        displayUnit       = widget.displayUnit
        gridW             = widget.gridW
        gridH             = widget.gridH

        // Pre-fill fields
        etMin.setText(formatFloat(rangeMin))
        etMax.setText(formatFloat(rangeMax))
        etMajorTick.setText(formatFloat(majorTickInterval))
        etMinorTicks.setText(minorTickCount.toString())
        etWarning.setText(warningThreshold?.let { formatFloat(it) } ?: "")
        etDecimalPlaces.setText(decimalPlaces.toString())
        etDisplayUnit.setText(displayUnit)

        // Wire text change listeners
        etMin.doAfterTextChanged           { rangeMin = it.toString().toFloatOrNull() ?: rangeMin }
        etMax.doAfterTextChanged           { rangeMax = it.toString().toFloatOrNull() ?: rangeMax }
        etMajorTick.doAfterTextChanged     { majorTickInterval = it.toString().toFloatOrNull() ?: majorTickInterval }
        etMinorTicks.doAfterTextChanged    { minorTickCount = it.toString().toIntOrNull() ?: minorTickCount }
        etWarning.doAfterTextChanged       { warningThreshold = it.toString().toFloatOrNull() }
        etDecimalPlaces.doAfterTextChanged { decimalPlaces = it.toString().toIntOrNull() ?: decimalPlaces }
        etDisplayUnit.doAfterTextChanged   { displayUnit = it.toString() }

        // Show/hide tick & warning rows based on widget type
        applyFieldVisibility(widget.type)

        // Size preset buttons
        SizePreset.entries.forEachIndexed { idx, preset ->
            val btnId = presetButtonIds.getOrNull(idx) ?: return@forEachIndexed
            val btn = view.findViewById<Button>(btnId) ?: return@forEachIndexed
            btn.setOnClickListener {
                selectedPreset = preset
                gridW = preset.gridW
                gridH = preset.gridH
                tvSizeHint.text = preset.hint
                updatePresetButtonStates(view)
            }
        }
        selectedPreset = SizePreset.entries.firstOrNull { it.gridW == gridW && it.gridH == gridH }
            ?: SizePreset.MEDIUM
        tvSizeHint.text = selectedPreset.hint
        updatePresetButtonStates(view)

        // Header widget-type label
        val tvWidgetType = view.findViewById<TextView>(R.id.tv_edit_widget_type)
        tvWidgetType?.text = widget.type.canonical().name
            .replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

        // Save / Cancel buttons
        view.findViewById<Button>(R.id.btn_edit_save).setOnClickListener {
            viewModel.updateWidgetProperties(
                widgetId      = widgetId,
                rangeMin      = rangeMin,
                rangeMax      = rangeMax,
                majorTickInterval = majorTickInterval,
                minorTickCount    = minorTickCount,
                warningThreshold  = warningThreshold,
                decimalPlaces     = decimalPlaces,
                displayUnit       = displayUnit,
                gridW             = gridW,
                gridH             = gridH
            )
            dismiss()
        }
        view.findViewById<Button>(R.id.btn_edit_cancel).setOnClickListener { dismiss() }
    }

    private fun applyFieldVisibility(type: WidgetType) {
        when (type.canonical()) {
            WidgetType.SEVEN_SEGMENT,
            WidgetType.NUMERIC_DISPLAY -> {
                rowTicks.visibility           = View.GONE
                rowWarningDecimals.visibility = View.GONE
            }
            WidgetType.BAR_GAUGE_H,
            WidgetType.BAR_GAUGE_V -> {
                rowTicks.visibility           = View.VISIBLE
                rowWarningDecimals.visibility = View.GONE
            }
            else -> {
                rowTicks.visibility           = View.VISIBLE
                rowWarningDecimals.visibility = View.VISIBLE
            }
        }
    }

    private fun updatePresetButtonStates(root: View) {
        SizePreset.entries.forEachIndexed { idx, preset ->
            val btnId = presetButtonIds.getOrNull(idx) ?: return@forEachIndexed
            val btn = root.findViewById<Button>(btnId) ?: return@forEachIndexed
            btn.alpha = if (preset == selectedPreset) 1f else 0.55f
        }
    }

    private fun formatFloat(f: Float): String =
        if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()
}
