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
import com.sj.obd2app.metrics.TripPhase
import com.sj.obd2app.metrics.VehicleMetrics
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.ui.dashboard.data.LayoutRepository
import com.sj.obd2app.ui.dashboard.model.ColorScheme
import com.sj.obd2app.ui.dashboard.model.DashboardLayout
import com.sj.obd2app.ui.dashboard.model.DashboardMetric
import com.sj.obd2app.ui.dashboard.model.DashboardOrientation
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

    private lateinit var viewModel: DashboardEditorViewModel
    private var isEditMode = false
    private var hasUnsavedChanges = false
    private val gridSizePx = 60

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
    private lateinit var btnResetMinMax: ImageButton
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

    // Move/resize mode: only active when user explicitly picks "Move / Resize" from context menu
    private var moveResizeWidgetId: String? = null

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
        gridOverlay.invalidate()  // Force redraw with new grid size
        // Re-measure canvas whenever the host dimensions change (orientation etc.)
        canvasHost.viewTreeObserver.addOnGlobalLayoutListener(::onScrollViewLayout)
        panelProperties  = view.findViewById(R.id.panel_properties)
        txtDashboardName = view.findViewById(R.id.txt_dashboard_name)
        btnBack          = view.findViewById(R.id.btn_back)
        btnTripPlay      = view.findViewById(R.id.btn_trip_play)
        btnTripPause     = view.findViewById(R.id.btn_trip_pause)
        btnTripStop      = view.findViewById(R.id.btn_trip_stop)
        btnResetMinMax   = view.findViewById(R.id.btn_reset_minmax)
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
        btnCloseProperties.setOnClickListener { 
            viewModel.selectWidget(null)
            updateWidgetBoundsImmediate(null) // Hide bounds immediately
        }

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
                // New dashboard — start with empty canvas
                viewModel.loadLayout(
                    DashboardLayout(
                        name = layoutName,
                        colorScheme = ColorScheme.DEFAULT_DARK,
                        widgets = emptyList(),
                        orientation = DashboardOrientation.PORTRAIT
                    )
                )
                isEditMode = true
                hasUnsavedChanges = true
                // Notify MetricsCalculator of edit mode
                MetricsCalculator.getInstance(requireContext()).setDashboardEditMode(true)
            }
        }

        if (mode == "edit") {
            isEditMode = true
            hasUnsavedChanges = true
        }
        // Notify MetricsCalculator of initial edit mode
        MetricsCalculator.getInstance(requireContext()).setDashboardEditMode(isEditMode)
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

        // Observe shared trip phase and keep trip controls in sync
        viewLifecycleOwner.lifecycleScope.launch {
            calculator.tripPhase.collect { phase ->
                updateTripControls(phase)
            }
        }

        btnTripPlay.setOnClickListener {
            val phase = calculator.tripPhase.value
            when (phase) {
                com.sj.obd2app.metrics.TripPhase.IDLE -> {
                    calculator.startTrip()
                    Toast.makeText(context, "Trip started", Toast.LENGTH_SHORT).show()
                }
                com.sj.obd2app.metrics.TripPhase.PAUSED -> {
                    calculator.resumeTrip()
                    Toast.makeText(context, "Trip resumed", Toast.LENGTH_SHORT).show()
                }
                com.sj.obd2app.metrics.TripPhase.RUNNING -> { /* no-op */ }
            }
        }

        btnTripPause.setOnClickListener {
            if (calculator.tripPhase.value == com.sj.obd2app.metrics.TripPhase.RUNNING) {
                calculator.pauseTrip()
                Toast.makeText(context, "Trip paused", Toast.LENGTH_SHORT).show()
            }
        }

        btnTripStop.setOnClickListener {
            if (calculator.tripPhase.value != com.sj.obd2app.metrics.TripPhase.IDLE) {
                calculator.stopTrip()
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
            }
        }

        btnResetMinMax.setOnClickListener {
            resetAllMinMaxValues()
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

    private fun updateTripControls(phase: com.sj.obd2app.metrics.TripPhase = calculator.tripPhase.value) {
        if (isEditMode) {
            btnTripPlay.visibility  = View.GONE
            btnTripPause.visibility = View.GONE
            btnTripStop.visibility  = View.GONE
            btnResetMinMax.visibility = View.GONE
            return
        }
        btnTripPlay.visibility  = if (phase != com.sj.obd2app.metrics.TripPhase.RUNNING) View.VISIBLE else View.GONE
        btnTripPause.visibility = if (phase == com.sj.obd2app.metrics.TripPhase.RUNNING) View.VISIBLE else View.GONE
        btnTripStop.visibility  = if (phase != com.sj.obd2app.metrics.TripPhase.IDLE)    View.VISIBLE else View.GONE
        btnResetMinMax.visibility = if (phase == com.sj.obd2app.metrics.TripPhase.RUNNING) View.VISIBLE else View.GONE
    }

    private fun showOverflowMenu() {
        val popup = PopupMenu(requireContext(), btnOverflow)
        popup.menu.add(0, 1, 0, if (isEditMode) "Exit Edit Mode" else "Edit Layout")
        if (isEditMode) {
            popup.menu.add(0, 2, 1, "Add Widget")
            popup.menu.add(0, 3, 2, "Save")
        }
        popup.menu.add(0, 4, 3, "Manage Dashboards")
        popup.menu.add(0, 5, 4, "Add New Dashboard")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> toggleEditMode()
                2 -> AddWidgetWizardSheet.newInstance().show(childFragmentManager, AddWidgetWizardSheet.TAG)
                3 -> saveLayout()
                4 -> findNavController().navigateUp()  // goes back to LayoutListFragment
                5 -> showCreateNewDashboardDialog()
            }
            true
        }
        popup.show()
    }

    private fun showCreateNewDashboardDialog() {
        val ctx = requireContext()
        val inputLayout = com.google.android.material.textfield.TextInputLayout(ctx).apply {
            hint = "Dashboard name"
            setPadding(48, 16, 48, 0)
            setHintTextColor(android.content.res.ColorStateList.valueOf(0x66FFFFFF.toInt())) // 40% opacity white
        }
        val input = com.google.android.material.textfield.TextInputEditText(ctx).apply {
            setHintTextColor(0x66FFFFFF.toInt()) // 40% opacity white for input hint
        }
        inputLayout.addView(input)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("New Dashboard")
            .setView(inputLayout)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text?.toString()?.trim() ?: ""
                if (name.isBlank()) {
                    android.widget.Toast.makeText(ctx, "Name cannot be empty", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val bundle = Bundle().apply {
                        putString("layout_name", name)
                        putString("mode", "edit")
                        putBoolean("is_new", true)
                    }
                    findNavController().navigate(R.id.action_layoutList_to_editor, bundle)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode
        // Notify MetricsCalculator of edit mode change
        MetricsCalculator.getInstance(requireContext()).setDashboardEditMode(isEditMode)
        if (!isEditMode) {
            viewModel.selectWidget(null)
            updateWidgetBoundsImmediate(null) // Hide bounds immediately
            widgetActionBar.visibility = View.GONE
        }
        updateEditModeVisuals()
        renderCanvas(viewModel.currentLayout.value)
    }

    private fun updateEditModeVisuals() {
        gridOverlay.visibility = if (isEditMode) View.VISIBLE else View.GONE
        badgeEditing.visibility = if (isEditMode) View.VISIBLE else View.GONE
        fabAddWidget.visibility = if (isEditMode) View.VISIBLE else View.GONE
        updateTripControls()

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
        btnSave.visibility = if (isEditMode) View.VISIBLE else View.GONE
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

    private var maxCanvasGridW = 0
    private var maxCanvasGridH = 0
    
    /**
     * Records canvas grid dimensions from the host size.
     * canvasContainer is match_parent so it already fills the host — no layoutParams change needed.
     */
    private fun setupCanvasSize(w: Int, h: Int) {
        // Snap to nearest grid multiple so widget positions align exactly to dots
        val prevGridW = canvasGridW
        val prevGridH = canvasGridH
        canvasGridW = w / gridSizePx
        canvasGridH = h / gridSizePx
        
        // Track the maximum canvas size we've seen
        if (canvasGridW > maxCanvasGridW) maxCanvasGridW = canvasGridW
        if (canvasGridH > maxCanvasGridH) maxCanvasGridH = canvasGridH
        
        // Propagate to ViewModel so addWidget can center new widgets
        viewModel.canvasGridW = canvasGridW
        viewModel.canvasGridH = canvasGridH
        
        // For a new dashboard, lock orientation to match the current canvas aspect ratio
        if (isNewLayout) {
            val orient = if (canvasGridW >= canvasGridH) DashboardOrientation.LANDSCAPE
                         else DashboardOrientation.PORTRAIT
            viewModel.setOrientation(orient)
        }
        
        // Only clamp widgets if canvas is growing or staying the same size
        // Don't clamp when canvas temporarily shrinks (e.g., when dialogs/keyboard appear)
        val canvasShrinking = canvasGridW < prevGridW || canvasGridH < prevGridH
        
        if (!canvasShrinking && (prevGridW == 0 || canvasGridW >= prevGridW || canvasGridH >= prevGridH)) {
            clampWidgetsToBounds()
        }
    }

    /**
     * Moves any widget whose position exceeds the current canvas bounds back inside.
     * Called only when the canvas genuinely shrinks (e.g. orientation change).
     * Delegates to ViewModel which applies the clamp silently (no undo snapshot).
     */
    private fun clampWidgetsToBounds() {
        viewModel.clampAllWidgetsToBounds(canvasGridW, canvasGridH)
    }

    // ── Save / back guard ─────────────────────────────────────────────────────

    private fun saveLayout() {
        val layout = viewModel.currentLayout.value.copy(name = currentLayoutName)
        repo.saveLayout(layout).onSuccess {
            hasUnsavedChanges = false
            isEditMode = false
            // Notify MetricsCalculator of edit mode change
            MetricsCalculator.getInstance(requireContext()).setDashboardEditMode(false)
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
    private var previousLayout: com.sj.obd2app.ui.dashboard.model.DashboardLayout? = null

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentLayout.collect { layout ->
                if (layoutLoadedOnce && isEditMode) hasUnsavedChanges = true
                layoutLoadedOnce = true
                
                // Selective update logic
                val prev = previousLayout
                if (prev == null) {
                    // First load - render everything
                    renderCanvas(layout)
                } else {
                    // Check what changed
                    val prevWidgets = prev.widgets.associateBy { it.id }
                    val currWidgets = layout.widgets.associateBy { it.id }
                    
                    // Check if widgets were added/removed/reordered
                    val widgetIdsChanged = prevWidgets.keys != currWidgets.keys ||
                        prev.widgets.map { it.id } != layout.widgets.map { it.id }
                    
                    if (widgetIdsChanged) {
                        // Full re-render needed
                        renderCanvas(layout)
                    } else {
                        // Only update individual widgets that changed
                        for (widget in layout.widgets) {
                            val prevWidget = prevWidgets[widget.id]
                            if (prevWidget != widget) {
                                // Widget properties changed - update only this widget
                                updateSingleWidget(widget)
                            }
                        }
                    }
                }
                
                previousLayout = layout
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
                // Update widget bounds immediately
                updateWidgetBoundsImmediate(id)
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
        // Detect overlapping widgets
        val overlappingWidgetIds = detectOverlaps(layout.widgets)
        
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
            
            // Reset min/max values for dial and bar gauges when dashboard is displayed
            if (gaugeView is DialView || gaugeView is BarGaugeView) {
                gaugeView.resetTripMinMax()
            }

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
            
            // In edit mode, show faint border for all widgets to visualize bounds
            if (isEditMode) {
                border.setBackgroundColor(0x33FFFFFF.toInt())  // Faint white border (20% opacity)
                border.visibility = View.VISIBLE
            } else {
                border.visibility = View.GONE
            }
            
            listOf(handleTL, handleTR, handleBL, handleBR, moveIndicator).forEach {
                it.visibility = if (isSelected) View.VISIBLE else View.GONE
            }

            // Size & position
            val lp = FrameLayout.LayoutParams(widget.gridW * gridSizePx, widget.gridH * gridSizePx)
            wrapper.layoutParams = lp
            wrapper.x = (widget.gridX * gridSizePx).toFloat()
            wrapper.y = (widget.gridY * gridSizePx).toFloat()

            if (isEditMode) {
                val isMoveResize = moveResizeWidgetId == widget.id
                wrapper.setOnTouchListener(
                    WidgetTouchHandler(
                        viewModel        = viewModel,
                        widgetId         = widget.id,
                        gridSizePx       = gridSizePx,
                        onContextMenu    = { anchor -> showWidgetContextMenu(anchor, widget.id) },
                        getCanvasScale   = { canvasScale },
                        isMoveResizeMode = isMoveResize
                    )
                )
                // Resize handles only active when move/resize mode explicitly enabled for this widget
                if (isSelected) {
                    if (isMoveResize) {
                        handleTL.setOnTouchListener(WidgetResizeHandler(viewModel, widget, wrapper, 0, gridSizePx))
                        handleTR.setOnTouchListener(WidgetResizeHandler(viewModel, widget, wrapper, 1, gridSizePx))
                        handleBL.setOnTouchListener(WidgetResizeHandler(viewModel, widget, wrapper, 2, gridSizePx))
                        handleBR.setOnTouchListener(WidgetResizeHandler(viewModel, widget, wrapper, 3, gridSizePx))
                    } else {
                        listOf(handleTL, handleTR, handleBL, handleBR).forEach { it.setOnTouchListener(null) }
                    }
                }
            } else {
                wrapper.setOnTouchListener(null)
            }

            wrapper.tag = widget.id // Tag for identification during individual updates
            canvasContainer.addView(wrapper)

            // Wire live data
            liveDataJobs[widget.id] = startLiveDataJob(widget, gaugeView)
        }

        // Tap on blank canvas → deselect + exit move/resize mode.
        if (isEditMode) {
            canvasContainer.setOnClickListener {
                moveResizeWidgetId = null
                viewModel.selectWidget(null)
                updateWidgetBoundsImmediate(null) // Hide bounds immediately
            }
        } else {
            canvasContainer.setOnClickListener(null)
        }
    }

    /**
     * Updates a single widget without re-rendering the entire canvas.
     * This prevents layout perturbation when editing individual widget properties.
     */
    private fun updateSingleWidget(widget: DashboardWidget) {
        val wrapper = canvasContainer.findViewWithTag<FrameLayout>(widget.id)
        if (wrapper == null) {
            android.util.Log.w("DashWidgetMissing", "updateSingleWidget: Widget view not found for id=${widget.id.take(8)}, falling back to full render")
            renderCanvas(viewModel.currentLayout.value)
            return
        }
        wrapper.let {
            val contentFrame = it.findViewById<FrameLayout>(R.id.widget_content_frame)
            val gaugeView = contentFrame.getChildAt(0) as DashboardGaugeView
            
            // Apply updated settings to the existing gauge view
            applyWidgetSettings(gaugeView, widget, viewModel.currentLayout.value.colorScheme)
            
            // Update size and position if they changed
            val lp = it.layoutParams as FrameLayout.LayoutParams
            lp.width = widget.gridW * gridSizePx
            lp.height = widget.gridH * gridSizePx
            it.layoutParams = lp
            it.x = (widget.gridX * gridSizePx).toFloat()
            it.y = (widget.gridY * gridSizePx).toFloat()
            
            // Update z-order if needed (bring to front/back operations)
            it.z = widget.zOrder.toFloat()
            
            // Update selection state
            val isSelected = isEditMode && (widget.id == viewModel.selectedWidgetId.value)
            val border = it.findViewById<View>(R.id.selection_border)
            val handleTL = it.findViewById<View>(R.id.handle_tl)
            val handleTR = it.findViewById<View>(R.id.handle_tr)
            val handleBL = it.findViewById<View>(R.id.handle_bl)
            val handleBR = it.findViewById<View>(R.id.handle_br)
            val moveIndicator = it.findViewById<View>(R.id.move_indicator)
            
            // In edit mode, show faint border for all widgets to visualize bounds
            if (isEditMode) {
                border.setBackgroundColor(0x33FFFFFF.toInt())  // Faint white border (20% opacity)
                border.visibility = View.VISIBLE
            } else {
                border.visibility = View.GONE
            }
            
            listOf(handleTL, handleTR, handleBL, handleBR, moveIndicator).forEach { view ->
                view.visibility = if (isSelected) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * Detects overlapping widgets and returns a set of widget IDs that have overlaps.
     */
    private fun detectOverlaps(widgets: List<DashboardWidget>): Set<String> {
        val overlappingIds = mutableSetOf<String>()
        
        for (i in widgets.indices) {
            for (j in i + 1 until widgets.size) {
                val w1 = widgets[i]
                val w2 = widgets[j]
                
                // Check if rectangles overlap
                val overlaps = !(w1.gridX + w1.gridW <= w2.gridX || 
                                 w2.gridX + w2.gridW <= w1.gridX ||
                                 w1.gridY + w1.gridH <= w2.gridY ||
                                 w2.gridY + w2.gridH <= w1.gridY)
                
                if (overlaps) {
                    overlappingIds.add(w1.id)
                    overlappingIds.add(w2.id)
                }
            }
        }
        
        return overlappingIds
    }

    /**
     * Immediately updates widget bounds visibility for the selected widget.
     * This provides instant visual feedback when a widget is selected/deselected.
     */
    private fun updateWidgetBoundsImmediate(selectedId: String?) {
        // Hide all bounds first
        for (i in 0 until canvasContainer.childCount) {
            val child = canvasContainer.getChildAt(i)
            val border = child.findViewById<View>(R.id.selection_border)
            val handleTL = child.findViewById<View>(R.id.handle_tl)
            val handleTR = child.findViewById<View>(R.id.handle_tr)
            val handleBL = child.findViewById<View>(R.id.handle_bl)
            val handleBR = child.findViewById<View>(R.id.handle_br)
            val moveIndicator = child.findViewById<View>(R.id.move_indicator)
            
            listOf(border, handleTL, handleTR, handleBL, handleBR, moveIndicator).forEach {
                it.visibility = View.GONE
            }
        }
        
        // Show bounds for selected widget
        if (selectedId != null && isEditMode) {
            val wrapper = canvasContainer.findViewWithTag<FrameLayout>(selectedId)
            wrapper?.let {
                val border = it.findViewById<View>(R.id.selection_border)
                val handleTL = it.findViewById<View>(R.id.handle_tl)
                val handleTR = it.findViewById<View>(R.id.handle_tr)
                val handleBL = it.findViewById<View>(R.id.handle_bl)
                val handleBR = it.findViewById<View>(R.id.handle_br)
                val moveIndicator = it.findViewById<View>(R.id.move_indicator)
                
                listOf(border, handleTL, handleTR, handleBL, handleBR, moveIndicator).forEach {
                    it.visibility = View.VISIBLE
                }
            }
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
        "DERIVED_AVG_KPL"   -> m.tripAvgKpl
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
        "DERIVED_POWER_ACCEL" -> m.powerAccelKw
        "DERIVED_POWER_THERMO" -> m.powerThermoKw
        "DERIVED_POWER_OBD" -> m.powerOBDKw
        "DERIVED_POWER_ACCEL_BHP" -> m.powerAccelKw?.let { it * 1.34102f } // kW to BHP
        "DERIVED_POWER_THERMO_BHP" -> m.powerThermoKw?.let { it * 1.34102f } // kW to BHP
        "DERIVED_POWER_OBD_BHP" -> m.powerOBDKw?.let { it * 1.34102f } // kW to BHP
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
                R.id.action_move_resize -> {
                    moveResizeWidgetId = widgetId
                    renderCanvas(viewModel.currentLayout.value)
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
        return when (type) {
            WidgetType.DIAL             -> DialView(ctx)
            WidgetType.SEVEN_SEGMENT    -> SevenSegmentView(ctx)
            WidgetType.BAR_GAUGE_H,
            WidgetType.BAR_GAUGE_V      -> BarGaugeView(ctx)
            WidgetType.NUMERIC_DISPLAY  -> NumericDisplayView(ctx)
            WidgetType.TEMPERATURE_ARC  -> TemperatureGaugeView(ctx)
        }
    }

    /** Resets min/max values for all DialView and BarGaugeView widgets in the current dashboard */
    private fun resetAllMinMaxValues() {
        var resetCount = 0
        for (i in 0 until canvasContainer.childCount) {
            val wrapper = canvasContainer.getChildAt(i)
            val contentFrame = wrapper.findViewById<FrameLayout>(R.id.widget_content_frame)
            if (contentFrame != null && contentFrame.childCount > 0) {
                val gaugeView = contentFrame.getChildAt(0)
                if (gaugeView is DialView || gaugeView is BarGaugeView) {
                    gaugeView.resetTripMinMax()
                    resetCount++
                }
            }
        }
        
        if (resetCount > 0) {
            Toast.makeText(context, "Min/Max values reset for $resetCount gauge(s)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "No gauges with min/max tracking found", Toast.LENGTH_SHORT).show()
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
