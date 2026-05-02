package com.sj.obd2app.ui.dashboard

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
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sj.obd2app.R
import com.sj.obd2app.ui.dashboard.model.DashboardWidget
import com.sj.obd2app.ui.dashboard.model.DashboardMetric
import com.sj.obd2app.ui.dashboard.model.WidgetType
import com.sj.obd2app.ui.dashboard.model.MetricDefaults
import com.sj.obd2app.ui.dashboard.wizard.SizePreset
import com.sj.obd2app.ui.dashboard.MetricListAdapter
import com.sj.obd2app.ui.dashboard.MetricListItem
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.settings.VehicleProfileRepository
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

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
    private lateinit var spinnerDisplayUnit: Spinner
    private lateinit var etCustomUnit: EditText
    private lateinit var tvSizeHint: TextView
    private lateinit var rowTicks: LinearLayout
    private lateinit var rowWarningDecimals: LinearLayout
    private lateinit var tvCurrentMetric: TextView
    private lateinit var rowCornerMetrics: LinearLayout
    private lateinit var tvCornerTL: TextView
    private lateinit var tvCornerTR: TextView
    private lateinit var tvCornerBL: TextView
    private lateinit var tvCornerBR: TextView
    private var metricSelectionDialog: androidx.appcompat.app.AlertDialog? = null
    private var cornerSelectionDialog: androidx.appcompat.app.AlertDialog? = null
    private var selectedCorner: String? = null
    
    private var unitOptions: Array<String> = emptyArray()
    
    // Working copy of the editable values
    private var currentMetric: DashboardMetric? = null

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

    // Corner metrics for LIVE_MAP
    private var cornerMetricTL: DashboardMetric? = null
    private var cornerMetricTR: DashboardMetric? = null
    private var cornerMetricBL: DashboardMetric? = null
    private var cornerMetricBR: DashboardMetric? = null

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
        spinnerDisplayUnit = view.findViewById(R.id.spinner_display_unit)
        etCustomUnit      = view.findViewById(R.id.et_custom_unit)
        tvSizeHint        = view.findViewById(R.id.tv_size_hint)
        rowTicks          = view.findViewById(R.id.row_ticks)
        rowWarningDecimals = view.findViewById(R.id.row_warning_decimals)
        tvCurrentMetric   = view.findViewById(R.id.tv_current_metric)
        rowCornerMetrics  = view.findViewById(R.id.row_corner_metrics)
        tvCornerTL        = view.findViewById(R.id.tv_corner_tl)
        tvCornerTR        = view.findViewById(R.id.tv_corner_tr)
        tvCornerBL        = view.findViewById(R.id.tv_corner_bl)
        tvCornerBR        = view.findViewById(R.id.tv_corner_br)

        // Initialise working copy from widget
        currentMetric     = widget.metric
        rangeMin          = widget.rangeMin
        rangeMax          = widget.rangeMax
        majorTickInterval = widget.majorTickInterval
        minorTickCount    = widget.minorTickCount
        warningThreshold  = widget.warningThreshold
        decimalPlaces     = widget.decimalPlaces
        displayUnit       = widget.displayUnit
        gridW             = widget.gridW
        gridH             = widget.gridH
        cornerMetricTL    = widget.cornerMetricTL
        cornerMetricTR    = widget.cornerMetricTR
        cornerMetricBL    = widget.cornerMetricBL
        cornerMetricBR    = widget.cornerMetricBR
        
        // Setup metric selection
        updateMetricDisplay()
        tvCurrentMetric.setOnClickListener { showMetricSelectionDialog() }

        // Setup corner metric selection
        updateCornerMetricDisplay()
        tvCornerTL.setOnClickListener { showCornerMetricSelectionDialog("TL") }
        tvCornerTR.setOnClickListener { showCornerMetricSelectionDialog("TR") }
        tvCornerBL.setOnClickListener { showCornerMetricSelectionDialog("BL") }
        tvCornerBR.setOnClickListener { showCornerMetricSelectionDialog("BR") }

        // Setup unit spinner
        unitOptions = resources.getStringArray(R.array.display_unit_options)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, unitOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDisplayUnit.adapter = adapter

        // Pre-fill fields
        etMin.setText(formatFloat(rangeMin))
        etMax.setText(formatFloat(rangeMax))
        etMajorTick.setText(formatFloat(majorTickInterval))
        etMinorTicks.setText(minorTickCount.toString())
        etWarning.setText(warningThreshold?.let { formatFloat(it) } ?: "")
        etDecimalPlaces.setText(decimalPlaces.toString())
        
        // Set initial unit selection
        setUnitSelection(displayUnit)

        // Wire text change listeners
        etMin.doAfterTextChanged           { rangeMin = it.toString().toFloatOrNull() ?: rangeMin }
        etMax.doAfterTextChanged           { rangeMax = it.toString().toFloatOrNull() ?: rangeMax }
        etMajorTick.doAfterTextChanged     { majorTickInterval = it.toString().toFloatOrNull() ?: majorTickInterval }
        etMinorTicks.doAfterTextChanged    { minorTickCount = it.toString().toIntOrNull() ?: minorTickCount }
        etWarning.doAfterTextChanged       { warningThreshold = it.toString().toFloatOrNull() }
        etDecimalPlaces.doAfterTextChanged { decimalPlaces = it.toString().toIntOrNull() ?: decimalPlaces }
        etCustomUnit.doAfterTextChanged    { displayUnit = it.toString() }
        
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
                        displayUnit = ""
                    }
                    else -> {
                        etCustomUnit.visibility = View.GONE
                        displayUnit = selected
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

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
        tvWidgetType?.text = widget.type.name
            .replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

        // Save / Cancel buttons
        view.findViewById<Button>(R.id.btn_edit_save).setOnClickListener {
            viewModel.updateWidgetProperties(
                widgetId      = widgetId,
                metric        = currentMetric!!,
                rangeMin      = rangeMin,
                rangeMax      = rangeMax,
                majorTickInterval = majorTickInterval,
                minorTickCount    = minorTickCount,
                warningThreshold  = warningThreshold,
                decimalPlaces     = decimalPlaces,
                displayUnit       = displayUnit,
                gridW             = gridW,
                gridH             = gridH,
                cornerMetricTL    = cornerMetricTL,
                cornerMetricTR    = cornerMetricTR,
                cornerMetricBL    = cornerMetricBL,
                cornerMetricBR    = cornerMetricBR
            )
            dismiss()
        }
        view.findViewById<Button>(R.id.btn_edit_cancel).setOnClickListener { dismiss() }
    }

    private fun applyFieldVisibility(type: WidgetType) {
        when (type) {
            WidgetType.SEVEN_SEGMENT -> {
                rowTicks.visibility           = View.GONE
                rowWarningDecimals.visibility = View.GONE  // Seven segment doesn't support warning colors
                rowCornerMetrics.visibility  = View.GONE
            }
            WidgetType.NUMERIC_DISPLAY -> {
                rowTicks.visibility           = View.GONE
                rowWarningDecimals.visibility = View.VISIBLE  // Can change font color when threshold exceeded
                rowCornerMetrics.visibility  = View.GONE
            }
            WidgetType.BAR_GAUGE_H,
            WidgetType.BAR_GAUGE_V -> {
                rowTicks.visibility           = View.GONE  // Bar gauges don't need tick marks
                rowWarningDecimals.visibility = View.VISIBLE  // Can change bar color when threshold exceeded
                rowCornerMetrics.visibility  = View.GONE
            }
            WidgetType.LIVE_MAP -> {
                rowTicks.visibility           = View.GONE
                rowWarningDecimals.visibility = View.GONE  // No gauge fields for LiveMap
                rowCornerMetrics.visibility  = View.VISIBLE  // Show corner metrics for LiveMap
            }
            else -> {
                rowTicks.visibility           = View.VISIBLE
                rowWarningDecimals.visibility = View.VISIBLE
                rowCornerMetrics.visibility  = View.GONE
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

    private fun updateMetricDisplay() {
        currentMetric?.let { metric ->
            tvCurrentMetric.text = when (metric) {
                is DashboardMetric.Obd2Pid -> "${metric.name} (${metric.pid})"
                is DashboardMetric.GpsSpeed -> "GPS Speed"
                is DashboardMetric.GpsAltitude -> "GPS Altitude"
                is DashboardMetric.DerivedMetric -> metric.name
                is DashboardMetric.CanSignal -> "${metric.name} · 0x${Integer.toHexString(metric.messageId).uppercase()}"
            }
        }
    }

    private fun updateCornerMetricDisplay() {
        tvCornerTL.text = formatMetricName(cornerMetricTL) ?: "None"
        tvCornerTR.text = formatMetricName(cornerMetricTR) ?: "None"
        tvCornerBL.text = formatMetricName(cornerMetricBL) ?: "None"
        tvCornerBR.text = formatMetricName(cornerMetricBR) ?: "None"
    }

    private fun formatMetricName(metric: DashboardMetric?): String? = when (metric) {
        is DashboardMetric.Obd2Pid -> metric.name
        is DashboardMetric.GpsSpeed -> "GPS Speed"
        is DashboardMetric.GpsAltitude -> "GPS Altitude"
        is DashboardMetric.DerivedMetric -> metric.name
        is DashboardMetric.CanSignal -> metric.name
        null -> null
    }
    
    private fun showMetricSelectionDialog() {
        val ctx = requireContext()
        val metrics = buildMetricItems()
        
        // Create dialog with RecyclerView
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_metric_selection, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.rv_metrics)
        recyclerView.layoutManager = LinearLayoutManager(ctx)
        
        // PIDs seen live in this session
        val livePids = Obd2ServiceProvider.getService().obd2Data.value.map { it.pid }.toSet()
        
        // PIDs ever confirmed for the active vehicle profile
        val repo = VehicleProfileRepository.getInstance(ctx)
        val activeProfileId = repo.activeProfile?.id
        val profileKnownPids = repo.getKnownPids(activeProfileId)
        val profileLastValues = repo.getLastPidValues(activeProfileId)
        val hasProfileData = repo.hasDiscoveredPids(activeProfileId)
        
        val adapter = MetricListAdapter(
            items = metrics,
            livePids = livePids,
            profileKnownPids = profileKnownPids,
            profileLastValues = profileLastValues,
            hasProfileData = hasProfileData,
            selected = currentMetric,
            onSelect = { metric ->
                currentMetric = metric
                updateMetricDisplay()
                
                // Update scale defaults for new metric
                val defaults = MetricDefaults.get(metric)
                rangeMin = defaults.rangeMin
                rangeMax = defaults.rangeMax
                majorTickInterval = defaults.majorTickInterval
                minorTickCount = defaults.minorTickCount
                warningThreshold = defaults.warningThreshold
                decimalPlaces = defaults.decimalPlaces
                displayUnit = defaults.displayUnit
                
                // Update UI fields
                etMin.setText(formatFloat(rangeMin))
                etMax.setText(formatFloat(rangeMax))
                etMajorTick.setText(formatFloat(majorTickInterval))
                etMinorTicks.setText(minorTickCount.toString())
                etWarning.setText(warningThreshold?.let { formatFloat(it) } ?: "")
                etDecimalPlaces.setText(decimalPlaces.toString())
                setUnitSelection(displayUnit)
                
                metricSelectionDialog?.dismiss()
            }
        )
        recyclerView.adapter = adapter
        
        metricSelectionDialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Select Data Source")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()
        
        metricSelectionDialog?.show()
    }

    private fun showCornerMetricSelectionDialog(corner: String) {
        val ctx = requireContext()
        val metrics = buildMetricItems()
        selectedCorner = corner

        // Create dialog with RecyclerView
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_metric_selection, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.rv_metrics)
        recyclerView.layoutManager = LinearLayoutManager(ctx)

        val currentCornerMetric = when (corner) {
            "TL" -> cornerMetricTL
            "TR" -> cornerMetricTR
            "BL" -> cornerMetricBL
            "BR" -> cornerMetricBR
            else -> null
        }

        val adapter = MetricListAdapter(
            items = metrics,
            livePids = emptySet(),
            profileKnownPids = emptySet(),
            profileLastValues = emptyMap(),
            hasProfileData = false,
            selected = currentCornerMetric,
            onSelect = { metric ->
                when (corner) {
                    "TL" -> cornerMetricTL = metric
                    "TR" -> cornerMetricTR = metric
                    "BL" -> cornerMetricBL = metric
                    "BR" -> cornerMetricBR = metric
                }
                updateCornerMetricDisplay()
                cornerSelectionDialog?.dismiss()
            }
        )
        recyclerView.adapter = adapter

        cornerSelectionDialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Select Corner Metric")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        cornerSelectionDialog?.show()
    }
    
    private fun buildMetricItems(): List<MetricListItem> {
        val items = mutableListOf<MetricListItem>()
        data class Group(val header: String, val metrics: List<DashboardMetric>)
        val groups = listOf(
            Group("OBD-II — Engine", listOf(
                DashboardMetric.Obd2Pid("010C", "Engine RPM", "rpm"),
                DashboardMetric.Obd2Pid("0104", "Engine Load", "%"),
                DashboardMetric.Obd2Pid("0111", "Throttle Position", "%"),
                DashboardMetric.Obd2Pid("010E", "Timing Advance", "° BTDC"),
                DashboardMetric.Obd2Pid("015C", "Oil Temperature", "°C"),
                DashboardMetric.Obd2Pid("011F", "Run Time Since Start", "sec")
            )),
            Group("OBD-II — Speed & Distance", listOf(
                DashboardMetric.Obd2Pid("010D", "Vehicle Speed", "km/h"),
                DashboardMetric.Obd2Pid("0121", "Distance with MIL On", "km"),
                DashboardMetric.Obd2Pid("0131", "Distance Since Codes Cleared", "km")
            )),
            Group("OBD-II — Fuel", listOf(
                DashboardMetric.Obd2Pid("012F", "Fuel Tank Level", "%"),
                DashboardMetric.Obd2Pid("010A", "Fuel Pressure", "kPa"),
                DashboardMetric.Obd2Pid("015E", "Engine Fuel Rate", "L/h"),
                DashboardMetric.Obd2Pid("0110", "MAF", "g/s"),
                DashboardMetric.Obd2Pid("0151", "Fuel Type", ""),
                DashboardMetric.Obd2Pid("0146", "A/F Ratio", ""),
                DashboardMetric.Obd2Pid("0144", "Commanded Equivalence Ratio", "")
            )),
            Group("OBD-II — Intake & Air", listOf(
                DashboardMetric.Obd2Pid("010B", "Intake Manifold Pressure", "kPa"),
                DashboardMetric.Obd2Pid("010F", "Intake Air Temperature", "°C"),
                DashboardMetric.Obd2Pid("0110", "MAF", "g/s"),
                DashboardMetric.Obd2Pid("0166", "Ambient Air Temperature", "°C")
            )),
            Group("OBD-II — System", listOf(
                DashboardMetric.Obd2Pid("0105", "Engine Coolant Temperature", "°C"),
                DashboardMetric.Obd2Pid("0101", "Monitor Status", ""),
                DashboardMetric.Obd2Pid("0143", "Fuel System Status", ""),
                DashboardMetric.Obd2Pid("0106", "Short Term Fuel Trim 1", "%"),
                DashboardMetric.Obd2Pid("0107", "Long Term Fuel Trim 1", "%"),
                DashboardMetric.Obd2Pid("0108", "Short Term Fuel Trim 2", "%"),
                DashboardMetric.Obd2Pid("0109", "Long Term Fuel Trim 2", "%")
            )),
            Group("GPS", listOf(
                DashboardMetric.GpsSpeed,
                DashboardMetric.GpsAltitude
            )),
            Group("Derived — Fuel Efficiency", listOf(
                DashboardMetric.DerivedMetric("DERIVED_LPK",       "Instant Consumption",   "L/100km"),
                DashboardMetric.DerivedMetric("DERIVED_KPL",       "Instant Efficiency",    "km/L"),
                DashboardMetric.DerivedMetric("DERIVED_AVG_LPK",   "Trip Avg Consumption",  "L/100km"),
                DashboardMetric.DerivedMetric("DERIVED_AVG_KPL",   "Trip Avg Efficiency",   "km/L"),
                DashboardMetric.DerivedMetric("DERIVED_FUEL_USED", "Trip Fuel Used",        "L"),
                DashboardMetric.DerivedMetric("DERIVED_RANGE",     "Range Remaining",       "km"),
                DashboardMetric.DerivedMetric("DERIVED_FUEL_COST", "Fuel Cost",             "")
            )),
            Group("Derived — Trip Computer", listOf(
                DashboardMetric.DerivedMetric("DERIVED_TRIP_DIST", "Trip Distance",         "km"),
                DashboardMetric.DerivedMetric("DERIVED_TRIP_TIME", "Trip Time",             "sec"),
                DashboardMetric.DerivedMetric("DERIVED_AVG_SPD",   "Trip Avg Speed",        "km/h"),
                DashboardMetric.DerivedMetric("DERIVED_MAX_SPD",   "Trip Max Speed",        "km/h")
            )),
            Group("Derived — Emissions & Drive", listOf(
                DashboardMetric.DerivedMetric("DERIVED_CO2",       "Avg CO₂",               "g/km"),
                DashboardMetric.DerivedMetric("DERIVED_PCT_CITY",  "% City Driving",        "%"),
                DashboardMetric.DerivedMetric("DERIVED_PCT_IDLE",  "% Idle",                "%")
            )),
            Group("Derived — Power", listOf(
                DashboardMetric.DerivedMetric("DERIVED_POWER_ACCEL", "Power (Accel)",       "kW"),
                DashboardMetric.DerivedMetric("DERIVED_POWER_THERMO", "Power (Thermo)",      "kW"),
                DashboardMetric.DerivedMetric("DERIVED_POWER_OBD",    "Power (OBD)",         "kW"),
                DashboardMetric.DerivedMetric("DERIVED_POWER_ACCEL_BHP", "Power (Accel)",    "bhp"),
                DashboardMetric.DerivedMetric("DERIVED_POWER_THERMO_BHP", "Power (Thermo)",   "bhp"),
                DashboardMetric.DerivedMetric("DERIVED_POWER_OBD_BHP",    "Power (OBD)",      "bhp")
            ))
        )

        // Dynamically-sourced CAN groups (from the starred CanProfile + its DBC); empty when
        // no default profile or the DBC cannot be loaded.
        val canGroups = com.sj.obd2app.ui.dashboard.model.CanMetricSource.buildGroups(requireContext())
            .map { Group(it.header, it.metrics) }

        for (group in groups + canGroups) {
            items.add(MetricListItem.Header(group.header))
            items.addAll(group.metrics.map { MetricListItem.Entry(it) })
        }

        return items
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
