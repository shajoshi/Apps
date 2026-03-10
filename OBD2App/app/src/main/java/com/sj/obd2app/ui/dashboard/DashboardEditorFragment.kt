package com.sj.obd2app.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.sj.obd2app.R
import com.sj.obd2app.gps.GpsDataSource
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.metrics.VehicleMetrics
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.ui.dashboard.data.LayoutRepository
import com.sj.obd2app.ui.dashboard.model.DashboardMetric
import com.sj.obd2app.ui.dashboard.model.DashboardWidget
import com.sj.obd2app.ui.dashboard.model.WidgetType
import com.sj.obd2app.ui.dashboard.views.*
import com.sj.obd2app.ui.dashboard.wizard.AddWidgetWizardSheet
import android.widget.PopupMenu as AndroidPopupMenu
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Controller for the dashboard canvas — supports view mode and edit mode.
 */
class DashboardEditorFragment : Fragment() {

    private enum class TripPhase { IDLE, RUNNING, PAUSED }

    private lateinit var viewModel: DashboardEditorViewModel
    private var isEditMode = false
    private var hasUnsavedChanges = false
    private var tripPhase = TripPhase.IDLE
    private val gridSizePx = 100

    private val liveDataJobs = mutableMapOf<String, Job>()

    private lateinit var canvasContainer: FrameLayout
    private lateinit var canvasHost: FrameLayout
    private lateinit var gridOverlay: GridOverlayView
    private var canvasScale = 1f
    private val minScale = 0.5f
    private val maxScale = 3.0f
    private lateinit var panelProperties: View
    private lateinit var txtDashboardName: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnTripPlay: ImageButton
    private lateinit var btnTripPause: ImageButton
    private lateinit var btnTripStop: ImageButton
    private lateinit var btnOverflow: ImageButton

    private lateinit var btnBringFront: View
    private lateinit var btnSendBack: View
    private lateinit var btnDeleteWidget: View
    private lateinit var sliderAlpha: SeekBar

    private lateinit var badgeEditing: TextView
    private lateinit var btnEditToggle: ImageButton
    private lateinit var btnSave: ImageButton
    private lateinit var btnUndo: ImageButton
    private lateinit var fabAddWidget: FloatingActionButton
    private lateinit var widgetActionBar: LinearLayout
    private lateinit var wabEdit: ImageButton
    private lateinit var wabFront: ImageButton
    private lateinit var wabBack: ImageButton
    private lateinit var wabDelete: ImageButton

    private lateinit var calculator: MetricsCalculator
    private lateinit var repo: LayoutRepository
    private var currentLayoutName: String = "Dashboard"
    private var isNewLayout = false

    // Canvas dimensions in grid units — set from screen size, recalculated on orientation change
    private var canvasGridW = 0
    private var canvasGridH = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        viewModel = ViewModelProvider(this)[DashboardEditorViewModel::class.java]
        return inflater.inflate(R.layout.fragment_dashboard_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = LayoutRepository(requireContext())
        calculator = MetricsCalculator.getInstance(requireContext())

        canvasContainer  = view.findViewById(R.id.canvas_container)
        canvasHost       = view.findViewById(R.id.canvas_scroll_h)
        gridOverlay      = view.findViewById(R.id.grid_overlay)
        gridOverlay.gridSizePx = gridSizePx
        // Re-measure canvas whenever the host dimensions change (orientation etc.)
        canvasHost.viewTreeObserver.addOnGlobalLayoutListener(::onScrollViewLayout)
        panelProperties  = view.findViewById(R.id.panel_properties)
        txtDashboardName = view.findViewById(R.id.txt_dashboard_name)
        btnBack          = view.findViewById(R.id.btn_back)
        btnTripPlay      = view.findViewById(R.id.btn_trip_play)
        btnTripPause     = view.findViewById(R.id.btn_trip_pause)
        btnTripStop      = view.findViewById(R.id.btn_trip_stop)
        btnOverflow      = view.findViewById(R.id.btn_overflow)

        btnBringFront    = view.findViewById(R.id.btn_bring_front)
        btnSendBack      = view.findViewById(R.id.btn_send_back)
        btnDeleteWidget  = view.findViewById(R.id.btn_delete_widget)
        sliderAlpha      = view.findViewById(R.id.slider_alpha)

        badgeEditing     = view.findViewById(R.id.badge_editing)
        btnEditToggle    = view.findViewById(R.id.btn_edit_toggle)
        btnSave          = view.findViewById(R.id.btn_save)
        btnUndo          = view.findViewById(R.id.btn_undo)
        fabAddWidget     = view.findViewById(R.id.fab_add_widget)
        widgetActionBar  = view.findViewById(R.id.widget_action_bar)
        wabEdit          = view.findViewById(R.id.wab_edit)
        wabFront         = view.findViewById(R.id.wab_front)
        wabBack          = view.findViewById(R.id.wab_back)
        wabDelete        = view.findViewById(R.id.wab_delete)

        val btnCloseProperties: View = view.findViewById(R.id.btn_close_properties)
        btnCloseProperties.setOnClickListener { viewModel.selectWidget(null) }

        val layoutName = arguments?.getString("layout_name")
        val mode       = arguments?.getString("mode") ?: "view"
        isNewLayout    = arguments?.getBoolean("is_new", false) ?: false

        if (layoutName != null) {
            currentLayoutName = layoutName
            txtDashboardName.text = layoutName
            if (!isNewLayout) {
                val layout = repo.getSavedLayouts().find { it.name == layoutName }
                if (layout != null) viewModel.loadLayout(layout)
            } else {
                // New dashboard — start immediately in edit mode
                isEditMode = true
                hasUnsavedChanges = true
            }
        }

        if (mode == "edit") {
            isEditMode = true
        }
        updateEditModeVisuals()

        setupTopStrip()
        setupPropertyPanel()
        setupPinchToZoom()
        observeViewModel()
        registerBackPressHandler()
    }

    // ── Top strip ─────────────────────────────────────────────────────────────

    private fun setupTopStrip() {
        btnBack.setOnClickListener { handleBackPress() }

        btnTripPlay.setOnClickListener {
            when (tripPhase) {
                TripPhase.IDLE -> {
                    calculator.startTrip()
                    tripPhase = TripPhase.RUNNING
                    Toast.makeText(context, "Trip started", Toast.LENGTH_SHORT).show()
                }
                TripPhase.PAUSED -> {
                    calculator.resumeTrip()
                    tripPhase = TripPhase.RUNNING
                    Toast.makeText(context, "Trip resumed", Toast.LENGTH_SHORT).show()
                }
                TripPhase.RUNNING -> { /* no-op */ }
            }
            updateTripControls()
        }

        btnTripPause.setOnClickListener {
            if (tripPhase == TripPhase.RUNNING) {
                calculator.pauseTrip()
                tripPhase = TripPhase.PAUSED
                Toast.makeText(context, "Trip paused", Toast.LENGTH_SHORT).show()
                updateTripControls()
            }
        }

        btnTripStop.setOnClickListener {
            if (tripPhase != TripPhase.IDLE) {
                calculator.stopTrip()
                tripPhase = TripPhase.IDLE
                Toast.makeText(context, "Trip stopped", Toast.LENGTH_SHORT).show()
                // Auto-share log if enabled and logging produced a file
                if (com.sj.obd2app.settings.AppSettings.isAutoShareLogEnabled(requireContext())) {
                    val shareUri = calculator.getLogShareUri()
                    if (shareUri != null) {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share trip log"))
                    }
                }
                updateTripControls()
            }
        }

        btnOverflow.setOnClickListener { showOverflowMenu() }

        btnEditToggle.setOnClickListener { toggleEditMode() }

        btnSave.setOnClickListener { saveLayout() }

        btnUndo.setOnClickListener {
            viewModel.undo()
            updateActionButtons()
        }

        fabAddWidget.setOnClickListener {
            AddWidgetWizardSheet.newInstance().show(childFragmentManager, AddWidgetWizardSheet.TAG)
        }

        wabEdit.setOnClickListener {
            val id = viewModel.selectedWidgetId.value ?: return@setOnClickListener
            EditWidgetSheet.newInstance(id).show(childFragmentManager, EditWidgetSheet.TAG)
        }
        wabFront.setOnClickListener {
            viewModel.bringSelectedToFront(); hasUnsavedChanges = true; updateActionButtons()
        }
        wabBack.setOnClickListener {
            viewModel.sendSelectedToBack(); hasUnsavedChanges = true; updateActionButtons()
        }
        wabDelete.setOnClickListener {
            val id = viewModel.selectedWidgetId.value ?: return@setOnClickListener
            viewModel.removeWidget(id); hasUnsavedChanges = true
            widgetActionBar.visibility = View.GONE
            updateActionButtons()
        }
    }

    private fun updateTripControls() {
        btnTripPlay.visibility  = if (tripPhase != TripPhase.RUNNING) View.VISIBLE else View.GONE
        btnTripPause.visibility = if (tripPhase == TripPhase.RUNNING) View.VISIBLE else View.GONE
        btnTripStop.visibility  = if (tripPhase != TripPhase.IDLE)    View.VISIBLE else View.GONE
    }

    private fun showOverflowMenu() {
        val popup = PopupMenu(requireContext(), btnOverflow)
        popup.menu.add(0, 1, 0, if (isEditMode) "Exit Edit Mode" else "Edit Layout")
        if (isEditMode) {
            popup.menu.add(0, 2, 1, "Add Widget")
            popup.menu.add(0, 3, 2, "Save")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> toggleEditMode()
                2 -> AddWidgetWizardSheet.newInstance().show(childFragmentManager, AddWidgetWizardSheet.TAG)
                3 -> saveLayout()
            }
            true
        }
        popup.show()
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode
        if (!isEditMode) {
            viewModel.selectWidget(null)
            widgetActionBar.visibility = View.GONE
        }
        updateEditModeVisuals()
        renderCanvas(viewModel.currentLayout.value)
    }

    private fun updateEditModeVisuals() {
        gridOverlay.visibility = if (isEditMode) View.VISIBLE else View.GONE
        badgeEditing.visibility = if (isEditMode) View.VISIBLE else View.GONE
        fabAddWidget.visibility = if (isEditMode) View.VISIBLE else View.GONE

        // Top strip tint: amber in edit mode, default in view mode
        val topStrip = view?.findViewById<View>(R.id.top_strip)
        topStrip?.setBackgroundColor(
            if (isEditMode) 0xFF3D2800.toInt() else 0xFF1A1A2E.toInt()
        )

        // Edit toggle icon switches between pencil (view mode) and checkmark (edit mode)
        btnEditToggle.setImageResource(
            if (isEditMode) android.R.drawable.checkbox_on_background
            else android.R.drawable.ic_menu_edit
        )
        btnEditToggle.setColorFilter(
            if (isEditMode) 0xFFFFC107.toInt() else 0xFFAAAAAA.toInt()
        )

        updateActionButtons()
    }

    private fun updateActionButtons() {
        val showSave = isEditMode && hasUnsavedChanges
        btnSave.visibility = if (showSave) View.VISIBLE else View.GONE
        btnUndo.visibility = if (isEditMode && viewModel.canUndo.value) View.VISIBLE else View.GONE
    }

    // ── Canvas sizing ─────────────────────────────────────────────────────────

    private var lastScrollW = -1
    private var lastScrollH = -1

    /** Called by GlobalLayoutListener every time canvasHost changes size. */
    private fun onScrollViewLayout() {
        val w = canvasHost.width
        val h = canvasHost.height
        if (w <= 0 || h <= 0 || (w == lastScrollW && h == lastScrollH)) return
        lastScrollW = w
        lastScrollH = h
        setupCanvasSize(w, h)
    }

    /**
     * Records canvas grid dimensions from the host size.
     * canvasContainer is match_parent so it already fills the host — no layoutParams change needed.
     */
    private fun setupCanvasSize(w: Int, h: Int) {
        // Snap to nearest grid multiple so widget positions align exactly to dots
        canvasGridW = w / gridSizePx
        canvasGridH = h / gridSizePx
        // Clamp widgets that now fall outside the (possibly smaller) canvas
        clampWidgetsToBounds()
    }

    /**
     * Moves any widget whose position exceeds the current canvas bounds back inside.
     * Called after orientation changes.
     */
    private fun clampWidgetsToBounds() {
        val layout = viewModel.currentLayout.value
        val clamped = layout.widgets.map { w ->
            val maxX = (canvasGridW - w.gridW).coerceAtLeast(0)
            val maxY = (canvasGridH - w.gridH).coerceAtLeast(0)
            w.copy(gridX = w.gridX.coerceIn(0, maxX), gridY = w.gridY.coerceIn(0, maxY))
        }
        if (clamped != layout.widgets) {
            viewModel.loadLayout(layout.copy(widgets = clamped))
        }
    }

    // ── Save / back guard ─────────────────────────────────────────────────────

    private fun saveLayout() {
        val layout = viewModel.currentLayout.value.copy(name = currentLayoutName)
        repo.saveLayout(layout).onSuccess {
            hasUnsavedChanges = false
            isEditMode = false
            widgetActionBar.visibility = View.GONE
            updateEditModeVisuals()
            renderCanvas(viewModel.currentLayout.value)
            Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "Save failed: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerBackPressHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { handleBackPress() }
            }
        )
    }

    private fun handleBackPress() {
        if (isEditMode && hasUnsavedChanges) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Save changes?")
                .setMessage("You have unsaved changes to \"$currentLayoutName\".")
                .setPositiveButton("Save") { _, _ -> saveLayout(); findNavController().navigateUp() }
                .setNegativeButton("Discard") { _, _ -> findNavController().navigateUp() }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            findNavController().navigateUp()
        }
    }

    // ── Property panel ────────────────────────────────────────────────────────

    private fun setupPropertyPanel() {
        btnBringFront.setOnClickListener   { viewModel.bringSelectedToFront(); hasUnsavedChanges = true }
        btnSendBack.setOnClickListener     { viewModel.sendSelectedToBack();   hasUnsavedChanges = true }
        btnDeleteWidget.setOnClickListener { viewModel.removeSelectedWidget(); hasUnsavedChanges = true }

        sliderAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) { viewModel.updateSelectedWidgetAlpha(progress / 100f); hasUnsavedChanges = true }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private var layoutLoadedOnce = false

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentLayout.collect { layout ->
                if (layoutLoadedOnce && isEditMode) hasUnsavedChanges = true
                layoutLoadedOnce = true
                renderCanvas(layout)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedWidgetId.collect { id ->
                // Update slider to the selected widget's current alpha
                if (id != null) {
                    val widget = viewModel.currentLayout.value.widgets.find { it.id == id }
                    if (widget != null) sliderAlpha.progress = (widget.alpha * 100).toInt()
                }
                // Show/hide inline action bar
                updateWidgetActionBar(id)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isPropertiesPanelOpen.collect { isOpen ->
                panelProperties.visibility = if (isOpen && isEditMode) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.canUndo.collect { updateActionButtons() }
        }
    }

    /**
     * Shows/hides/positions the inline widget action bar based on the selected widget.
     * The bar is placed at the top-left of the screen (below the strip) when a widget is selected.
     */
    private fun updateWidgetActionBar(selectedId: String?) {
        if (!isEditMode || selectedId == null) {
            widgetActionBar.visibility = View.GONE
            return
        }
        widgetActionBar.visibility = View.VISIBLE
    }

    /**
     * Rebuilds the FrameLayout contents based on the layout model.
     * Cancels all previous live-data jobs, creates new views, then wires
     * OBD2 / GPS StateFlow emissions to each gauge view.
     */
    private fun renderCanvas(layout: com.sj.obd2app.ui.dashboard.model.DashboardLayout) {
        // Cancel all previous live-data collection jobs
        liveDataJobs.values.forEach { it.cancel() }
        liveDataJobs.clear()
        canvasContainer.removeAllViews()

        val sortedWidgets = layout.widgets.sortedBy { it.zOrder }
        val selectedId = viewModel.selectedWidgetId.value

        for (widget in sortedWidgets) {
            val wrapper = layoutInflater.inflate(R.layout.layout_widget_wrapper, canvasContainer, false)
            val contentFrame = wrapper.findViewById<FrameLayout>(R.id.widget_content_frame)

            val gaugeView = createViewForWidgetType(widget.type)
            applyWidgetSettings(gaugeView, widget, layout.colorScheme)

            contentFrame.addView(gaugeView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))

            // Selection handles
            val isSelected = isEditMode && (widget.id == selectedId)
            val border    = wrapper.findViewById<View>(R.id.selection_border)
            val handleTL  = wrapper.findViewById<View>(R.id.handle_tl)
            val handleTR  = wrapper.findViewById<View>(R.id.handle_tr)
            val handleBL  = wrapper.findViewById<View>(R.id.handle_bl)
            val handleBR  = wrapper.findViewById<View>(R.id.handle_br)

            val moveIndicator = wrapper.findViewById<View>(R.id.move_indicator)
            listOf(border, handleTL, handleTR, handleBL, handleBR, moveIndicator).forEach {
                it.visibility = if (isSelected) View.VISIBLE else View.GONE
            }

            // Size & position
            val lp = FrameLayout.LayoutParams(widget.gridW * gridSizePx, widget.gridH * gridSizePx)
            wrapper.layoutParams = lp
            wrapper.x = (widget.gridX * gridSizePx).toFloat()
            wrapper.y = (widget.gridY * gridSizePx).toFloat()

            if (isEditMode) {
                wrapper.setOnTouchListener(
                    WidgetTouchHandler(
                        viewModel      = viewModel,
                        widgetId       = widget.id,
                        gridSizePx     = gridSizePx,
                        onContextMenu  = { anchor -> showWidgetContextMenu(anchor, widget.id) },
                        getCanvasScale = { canvasScale },
                        getCanvasBounds = {
                            val maxX = (canvasGridW - widget.gridW).coerceAtLeast(0)
                            val maxY = (canvasGridH - widget.gridH).coerceAtLeast(0)
                            maxX to maxY
                        }
                    )
                )
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


            // Wire live data
            liveDataJobs[widget.id] = startLiveDataJob(widget, gaugeView)
        }

        // Tap on blank canvas → deselect. Uses setOnClickListener so child widget
        // touches are consumed by the child and never reach this listener.
        if (isEditMode) {
            canvasContainer.setOnClickListener {
                viewModel.selectWidget(null)
            }
        } else {
            canvasContainer.setOnClickListener(null)
        }
    }

    /** Applies all DashboardWidget settings (scale, unit, colours) to a gauge view. */
    private fun applyWidgetSettings(
        view: DashboardGaugeView,
        widget: DashboardWidget,
        colorScheme: com.sj.obd2app.ui.dashboard.model.ColorScheme
    ) {
        // Metric label — prefer widget.displayUnit over the metric's built-in unit
        when (val m = widget.metric) {
            is DashboardMetric.Obd2Pid       -> view.metricName = m.name
            DashboardMetric.GpsSpeed         -> view.metricName = "GPS Speed"
            DashboardMetric.GpsAltitude      -> view.metricName = "Altitude"
            is DashboardMetric.DerivedMetric -> view.metricName = m.name
        }
        view.metricUnit       = widget.displayUnit
        view.colorScheme      = colorScheme
        view.alpha            = widget.alpha
        view.rangeMin         = widget.rangeMin
        view.rangeMax         = widget.rangeMax
        view.majorTickInterval = widget.majorTickInterval
        view.minorTickCount   = widget.minorTickCount
        view.warningThreshold = widget.warningThreshold
        view.decimalPlaces    = widget.decimalPlaces
        // Configure BarGaugeView orientation
        if (view is BarGaugeView) {
            view.isVertical = (widget.type == WidgetType.BAR_GAUGE_V)
        }
    }

    /**
     * Launches a coroutine that collects the appropriate StateFlow for this widget
     * and pushes new values to the gauge view.
     * OBD2 PIDs and GPS are served from MetricsCalculator; derived metrics too.
     */
    private fun startLiveDataJob(widget: DashboardWidget, view: DashboardGaugeView): Job {
        return viewLifecycleOwner.lifecycleScope.launch {
            when (val metric = widget.metric) {
                is DashboardMetric.Obd2Pid -> {
                    Obd2ServiceProvider.getService().obd2Data.collect { items ->
                        val item = items.find { it.pid == metric.pid }
                        if (item != null) {
                            val floatVal = item.value.toFloatOrNull() ?: return@collect
                            if (view is NumericDisplayView) view.updateValue(floatVal)
                            else view.setValue(floatVal)
                        }
                    }
                }
                DashboardMetric.GpsSpeed -> {
                    GpsDataSource.getInstance(requireContext()).gpsData.collect { item ->
                        item ?: return@collect
                        if (view is NumericDisplayView) view.updateValue(item.speedKmh)
                        else view.setValue(item.speedKmh)
                    }
                }
                DashboardMetric.GpsAltitude -> {
                    GpsDataSource.getInstance(requireContext()).gpsData.collect { item ->
                        item ?: return@collect
                        val alt = item.altitudeMsl.toFloat()
                        if (view is NumericDisplayView) view.updateValue(alt)
                        else view.setValue(alt)
                    }
                }
                is DashboardMetric.DerivedMetric -> {
                    calculator.metrics.collect { m ->
                        val value = derivedMetricValue(metric.key, m) ?: return@collect
                        if (view is NumericDisplayView) view.updateValue(value)
                        else view.setValue(value)
                    }
                }
            }
        }
    }

    private fun derivedMetricValue(key: String, m: VehicleMetrics): Float? = when (key) {
        "DERIVED_LPK"       -> m.instantLper100km
        "DERIVED_KPL"       -> m.instantKpl
        "DERIVED_AVG_LPK"   -> m.tripAvgLper100km
        "DERIVED_FUEL_USED" -> m.tripFuelUsedL
        "DERIVED_RANGE"     -> m.rangeRemainingKm
        "DERIVED_FUEL_COST" -> m.fuelCostEstimate
        "DERIVED_TRIP_DIST" -> m.tripDistanceKm
        "DERIVED_TRIP_TIME" -> m.tripTimeSec.toFloat()
        "DERIVED_AVG_SPD"   -> m.tripAvgSpeedKmh
        "DERIVED_MAX_SPD"   -> m.tripMaxSpeedKmh
        "DERIVED_CO2"       -> m.avgCo2gPerKm
        "DERIVED_PCT_CITY"  -> m.pctCity
        "DERIVED_PCT_IDLE"  -> m.pctIdle
        else                -> null
    }

    /**
     * Attaches a [ScaleGestureDetector] to the outer HorizontalScrollView so that
     * a two-finger pinch scales [canvasContainer] between [minScale] and [maxScale].
     * The pivot point follows the gesture focal point for natural zooming.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupPinchToZoom() {
        val scaleDetector = ScaleGestureDetector(requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val newScale = (canvasScale * detector.scaleFactor)
                        .coerceIn(minScale, maxScale)

                    // Pivot the scale at the focal point inside the canvas
                    val focusX = detector.focusX
                    val focusY = detector.focusY
                    canvasContainer.pivotX = focusX
                    canvasContainer.pivotY = focusY

                    canvasScale = newScale
                    canvasContainer.scaleX = canvasScale
                    canvasContainer.scaleY = canvasScale
                    return true
                }
            }
        )

        canvasHost.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            // Only consume the event if a scale gesture is in progress;
            // otherwise let the ScrollView handle single-finger scrolling.
            if (scaleDetector.isInProgress) {
                true
            } else {
                v.onTouchEvent(event)
            }
        }
    }

    /**
     * Shows a context popup anchored to the tapped widget wrapper.
     * Options: Edit Widget, Bring to Front, Send to Back, Delete.
     */
    private fun showWidgetContextMenu(anchor: View, widgetId: String) {
        val popup = AndroidPopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.widget_context_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            viewModel.selectWidget(widgetId)
            when (item.itemId) {
                R.id.action_edit_widget -> {
                    EditWidgetSheet.newInstance(widgetId)
                        .show(childFragmentManager, EditWidgetSheet.TAG)
                }
                R.id.action_bring_front  -> { viewModel.bringSelectedToFront(); hasUnsavedChanges = true }
                R.id.action_send_back    -> { viewModel.sendSelectedToBack();   hasUnsavedChanges = true }
                R.id.action_delete_widget -> { viewModel.removeWidget(widgetId); hasUnsavedChanges = true }
            }
            true
        }
        popup.show()
    }

    private fun createViewForWidgetType(type: WidgetType): DashboardGaugeView {
        val ctx = requireContext()
        return when (type.canonical()) {
            WidgetType.DIAL             -> DialView(ctx)
            WidgetType.SEVEN_SEGMENT    -> SevenSegmentView(ctx)
            WidgetType.BAR_GAUGE_H,
            WidgetType.BAR_GAUGE_V      -> BarGaugeView(ctx)
            WidgetType.NUMERIC_DISPLAY  -> NumericDisplayView(ctx)
            WidgetType.TEMPERATURE_ARC  -> TemperatureGaugeView(ctx)
            else                        -> NumericDisplayView(ctx)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        liveDataJobs.values.forEach { it.cancel() }
        liveDataJobs.clear()
        if (::canvasHost.isInitialized) {
            canvasHost.viewTreeObserver.removeOnGlobalLayoutListener(::onScrollViewLayout)
        }
        lastScrollW = -1
        lastScrollH = -1
    }
}
