package com.sj.obd2app.ui.dashboard.wizard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.sj.obd2app.R
import com.sj.obd2app.ui.dashboard.model.WidgetType

/**
 * Step 3 — Scale settings, unit label, and size preset.
 * Fields are pre-filled from WizardState (which was populated by MetricDefaults in Step 2).
 * All fields are editable.
 */
class Step3ConfigPage : Fragment() {

    private lateinit var etMin: EditText
    private lateinit var etMax: EditText
    private lateinit var etMajorTick: EditText
    private lateinit var etMinorTicks: EditText
    private lateinit var etWarning: EditText
    private lateinit var etDecimalPlaces: EditText
    private lateinit var spinnerDisplayUnit: Spinner
    private lateinit var etCustomUnit: EditText
    private lateinit var tvSizeHint: TextView
    private lateinit var rowTicks: LinearLayout
    private lateinit var rowWarningDecimals: LinearLayout
    
    private var unitOptions: Array<String> = emptyArray()

    private var selectedPreset: SizePreset = SizePreset.MEDIUM
    private val presetButtonIds = listOf(
        R.id.btn_size_small, R.id.btn_size_medium, R.id.btn_size_large,
        R.id.btn_size_wide, R.id.btn_size_tall
    )
    private val presets = SizePreset.entries

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.page_wizard_step3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etMin             = view.findViewById(R.id.et_range_min)
        etMax             = view.findViewById(R.id.et_range_max)
        etMajorTick       = view.findViewById(R.id.et_major_tick)
        etMinorTicks      = view.findViewById(R.id.et_minor_ticks)
        etWarning         = view.findViewById(R.id.et_warning)
        etDecimalPlaces   = view.findViewById(R.id.et_decimal_places)
        spinnerDisplayUnit = view.findViewById(R.id.spinner_display_unit)
        etCustomUnit      = view.findViewById(R.id.et_custom_unit)
        tvSizeHint        = view.findViewById(R.id.tv_size_hint)
        rowTicks          = view.findViewById(R.id.row_ticks)
        rowWarningDecimals = view.findViewById(R.id.row_warning_decimals)

        val state = (parentFragment as? AddWidgetWizardSheet)?.state ?: return

        applyFieldVisibility(state.selectedType)

        // Setup unit spinner
        unitOptions = resources.getStringArray(R.array.display_unit_options)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, unitOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDisplayUnit.adapter = adapter

        // Pre-fill from WizardState
        etMin.setText(formatFloat(state.rangeMin))
        etMax.setText(formatFloat(state.rangeMax))
        etMajorTick.setText(formatFloat(state.majorTickInterval))
        etMinorTicks.setText(state.minorTickCount.toString())
        etWarning.setText(state.warningThreshold?.let { formatFloat(it) } ?: "")
        etDecimalPlaces.setText(state.decimalPlaces.toString())
        
        // Set initial unit selection
        setUnitSelection(state.displayUnit)

        // Update state on text change
        etMin.doAfterTextChanged           { state.rangeMin = it.toString().toFloatOrNull() ?: state.rangeMin }
        etMax.doAfterTextChanged           { state.rangeMax = it.toString().toFloatOrNull() ?: state.rangeMax }
        etMajorTick.doAfterTextChanged     { state.majorTickInterval = it.toString().toFloatOrNull() ?: state.majorTickInterval }
        etMinorTicks.doAfterTextChanged    { state.minorTickCount = it.toString().toIntOrNull() ?: state.minorTickCount }
        etWarning.doAfterTextChanged       { state.warningThreshold = it.toString().toFloatOrNull() }
        etDecimalPlaces.doAfterTextChanged { state.decimalPlaces = it.toString().toIntOrNull() ?: state.decimalPlaces }
        etCustomUnit.doAfterTextChanged    { state.displayUnit = it.toString() }
        
        // Handle unit spinner selection
        spinnerDisplayUnit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = unitOptions[position]
                when (selected) {
                    "Custom..." -> {
                        etCustomUnit.visibility = View.VISIBLE
                        etCustomUnit.requestFocus()
                    }
                    "(none)" -> {
                        etCustomUnit.visibility = View.GONE
                        state.displayUnit = ""
                    }
                    else -> {
                        etCustomUnit.visibility = View.GONE
                        state.displayUnit = selected
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Wire size preset buttons
        presets.forEachIndexed { idx, preset ->
            val btnId = presetButtonIds.getOrNull(idx) ?: return@forEachIndexed
            val btn = view.findViewById<Button>(btnId) ?: return@forEachIndexed
            btn.setOnClickListener {
                selectedPreset = preset
                state.gridW = preset.gridW
                state.gridH = preset.gridH
                tvSizeHint.text = preset.hint
                updatePresetButtonStates(view)
            }
        }

        // Default selection
        selectedPreset = presets.firstOrNull {
            it.gridW == state.gridW && it.gridH == state.gridH
        } ?: SizePreset.MEDIUM
        state.gridW = selectedPreset.gridW
        state.gridH = selectedPreset.gridH
        tvSizeHint.text = selectedPreset.hint
        updatePresetButtonStates(view)
    }

    /**
     * Show/hide rows that are not relevant for the selected widget type.
     * - SEVEN_SEGMENT: tick marks and warning threshold are unused (7-segment can't change colors)
     * - NUMERIC_DISPLAY: warning threshold useful for font color changes; tick marks not needed
     * - BAR_GAUGE_H / BAR_GAUGE_V: warning threshold useful for bar color changes; tick marks not needed
     * - GAUGE types: both tick marks and warning threshold are useful
     */
    private fun applyFieldVisibility(type: WidgetType?) {
        when (type) {
            WidgetType.SEVEN_SEGMENT -> {
                rowTicks.visibility          = View.GONE
                rowWarningDecimals.visibility = View.GONE  // 7-segment can't change colors
            }
            WidgetType.NUMERIC_DISPLAY -> {
                rowTicks.visibility          = View.GONE
                rowWarningDecimals.visibility = View.VISIBLE  // Can change font color when threshold exceeded
            }
            WidgetType.BAR_GAUGE_H,
            WidgetType.BAR_GAUGE_V -> {
                rowTicks.visibility          = View.GONE  // Bar gauges don't need tick marks
                rowWarningDecimals.visibility = View.VISIBLE  // Can change bar color when threshold exceeded
            }
            else -> {
                rowTicks.visibility          = View.VISIBLE
                rowWarningDecimals.visibility = View.VISIBLE
            }
        }
    }

    private fun updatePresetButtonStates(root: View) {
        presets.forEachIndexed { idx, preset ->
            val btnId = presetButtonIds.getOrNull(idx) ?: return@forEachIndexed
            val btn = root.findViewById<Button>(btnId) ?: return@forEachIndexed
            btn.isSelected = (preset == selectedPreset)
            btn.alpha = if (preset == selectedPreset) 1f else 0.55f
        }
    }

    private fun formatFloat(f: Float): String =
        if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()
    
    private fun setUnitSelection(unit: String) {
        val index = unitOptions.indexOf(unit)
        if (index >= 0) {
            spinnerDisplayUnit.setSelection(index)
            etCustomUnit.visibility = View.GONE
        } else if (unit.isNotEmpty()) {
            // Custom unit
            spinnerDisplayUnit.setSelection(unitOptions.indexOf("Custom..."))
            etCustomUnit.setText(unit)
            etCustomUnit.visibility = View.VISIBLE
        } else {
            // Empty/none
            spinnerDisplayUnit.setSelection(0)
            etCustomUnit.visibility = View.GONE
        }
    }
}
