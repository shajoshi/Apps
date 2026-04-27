package com.sj.obd2app.ui.can

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sj.obd2app.can.CanDataOrchestrator
import com.sj.obd2app.can.CanMessage
import com.sj.obd2app.can.CanProfile
import com.sj.obd2app.can.CanProfileRepository
import com.sj.obd2app.can.CanSignal
import com.sj.obd2app.can.DbcDatabase
import com.sj.obd2app.can.DbcParser
import com.sj.obd2app.can.SignalRef
import com.sj.obd2app.R
import com.sj.obd2app.databinding.ItemCanSignalBinding
import com.sj.obd2app.databinding.SheetCanProfileEditBinding
import com.sj.obd2app.obd.ObdStateManager

/**
 * Bottom sheet to create or edit a [CanProfile]. Handles DBC file import via SAF and
 * signal multi-selection with a text filter.
 */
class CanProfileEditSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "CanProfileEditSheet"
        private const val ARG_PROFILE_ID = "profile_id"

        fun newInstance(profileId: String? = null): CanProfileEditSheet =
            CanProfileEditSheet().apply {
                arguments = Bundle().apply {
                    if (profileId != null) putString(ARG_PROFILE_ID, profileId)
                }
            }
    }

    private var _binding: SheetCanProfileEditBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: CanProfileRepository
    private var editingProfile: CanProfile? = null

    private var dbc: DbcDatabase? = null
    /** New DBC file that hasn't yet been attached to a saved profile. */
    private var pendingDbcSourceUri: Uri? = null
    private var pendingDbcFileName: String? = null

    /** Playback capture that hasn't yet been attached to a saved profile. */
    private var pendingCaptureSourceUri: Uri? = null
    private var pendingCaptureFileName: String? = null
    /** Set when the user clicked Clear on an existing capture and we should remove it on save. */
    private var clearExistingCapture: Boolean = false

    private val selectedRefs: MutableSet<SignalRef> = mutableSetOf()

    private lateinit var signalAdapter: SignalAdapter

    /** Current trip metric mapping: metricKey → signalRef.key(). Editable via the mapping panel. */
    private val tripMappingState: MutableMap<String, String> = mutableMapOf()
    /** Whether the mapping panel is expanded. */
    private var mappingPanelExpanded = false

    var onSaved: (() -> Unit)? = null

    private val dbcPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onDbcPicked(uri)
    }

    private val capturePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onCapturePicked(uri)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val d = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true
        d.behavior.peekHeight = resources.displayMetrics.heightPixels
        // Disable swipe-to-dismiss to prevent accidental closure when scrolling
        d.behavior.isDraggable = false
        // Intercept back button to prevent accidental dismissal (use Cancel instead)
        d.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                true // Consume the back press, do nothing
            } else {
                false
            }
        }
        return d
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SheetCanProfileEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = CanProfileRepository.getInstance(requireContext())

        val id = arguments?.getString(ARG_PROFILE_ID)
        if (id != null) editingProfile = repo.getById(id)

        signalAdapter = SignalAdapter()
        binding.rvSignals.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSignals.adapter = signalAdapter

        binding.etSignalFilter.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                signalAdapter.filter(s?.toString().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.btnPickDbc.setOnClickListener {
            dbcPickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnPickCapture.setOnClickListener {
            capturePickerLauncher.launch(arrayOf("application/json", "application/jsonl", "text/plain", "*/*"))
        }
        binding.btnClearCapture.setOnClickListener {
            pendingCaptureSourceUri = null
            pendingCaptureFileName = null
            clearExistingCapture = true
            binding.tvCaptureFileLabel.text = "Playback: none (mock uses synthetic frames)"
            binding.btnClearCapture.visibility = View.GONE
        }

        // Settings panel collapse/expand toggle
        binding.llProfileSettingsHeader.setOnClickListener { toggleSettingsPanel() }
        binding.btnSettingsToggle.setOnClickListener { toggleSettingsPanel() }

        // Show demo-data toggle only in mock mode
        if (ObdStateManager.isMockMode) {
            binding.llUseDemoData.visibility = View.VISIBLE
        }
        binding.swUseDemoData.setOnCheckedChangeListener { _, checked ->
            updateDbcSectionVisibility(checked)
        }

        binding.btnCanSave.setOnClickListener { save() }
        binding.btnCanCancel.setOnClickListener { dismiss() }

        // Trip Attribute Mapping panel toggle
        binding.llMappingHeader.setOnClickListener { toggleMappingPanel() }
        binding.btnMappingToggle.setOnClickListener { toggleMappingPanel() }
        binding.btnMappingAutoFill.setOnClickListener {
            applyHeuristicMapping()
            rebuildMappingRows()
        }
        binding.btnMappingClearAll.setOnClickListener {
            tripMappingState.clear()
            rebuildMappingRows()
        }
        binding.btnCanDelete.setOnClickListener {
            editingProfile?.let { p ->
                repo.delete(p.id)
            }
            onSaved?.invoke()
            dismiss()
        }

        // Populate from existing profile
        editingProfile?.let { p ->
            binding.tvSheetTitle.text = "Edit CAN Profile"
            binding.etCanName.setText(p.name)
            binding.etCanObjective.setText(p.objective)
            binding.etSamplingMs.setText(p.samplingMs.toString())
            binding.swRecordRaw.isChecked = p.recordRawFrames
            binding.swUseDemoData.isChecked = p.useDemoData
            updateDbcSectionVisibility(p.useDemoData)
            loadSyncTickerHz(p)
            binding.btnCanDelete.visibility = View.VISIBLE
            selectedRefs.addAll(p.selectedSignals)
            // Seed mapping state from saved profile
            tripMappingState.putAll(p.tripMetricMapping)
            // Try to auto-load its stored DBC
            val file = repo.dbcFileFor(p.id)
            if (file != null) {
                try {
                    file.inputStream().use {
                        dbc = DbcParser.parse(it, p.dbcFileName)
                    }
                    binding.tvDbcFileLabel.text = "DBC: ${p.dbcFileName} (${dbc?.messages?.size ?: 0} msgs)"
                    signalAdapter.updateDbc(dbc)
                    // Auto-fill heuristic for any unmapped keys when DBC is loaded
                    applyHeuristicMapping()
                    rebuildMappingRows()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load saved DBC for profile ${p.id}", e)
                    binding.tvDbcFileLabel.text = "DBC load failed — re-import"
                }
            } else {
                binding.tvDbcFileLabel.text = "DBC missing — re-import"
            }
            // Playback capture state
            if (p.playbackCaptureFileName != null && repo.captureFileFor(p.id) != null) {
                binding.tvCaptureFileLabel.text = "Playback: ${p.playbackCaptureFileName}"
                binding.btnClearCapture.visibility = View.VISIBLE
            }
        } ?: run {
            binding.tvSheetTitle.text = "New CAN Profile"
            binding.etSamplingMs.setText("500")
            updateDbcSectionVisibility(false)
            loadSyncTickerHz(null)
            binding.btnCanDelete.visibility = View.GONE
        }

        updateSelectionCount()
    }

    // ── Settings panel toggle ─────────────────────────────────────────────────

    private var settingsPanelExpanded = true

    private fun toggleSettingsPanel() {
        settingsPanelExpanded = !settingsPanelExpanded
        binding.llSettingsPanel.visibility = if (settingsPanelExpanded) View.VISIBLE else View.GONE
        binding.btnSettingsToggle.rotation = if (settingsPanelExpanded) 0f else 180f
        binding.btnSettingsToggle.contentDescription =
            if (settingsPanelExpanded) "Collapse settings" else "Expand settings"
    }

    // ── Demo data toggle ──────────────────────────────────────────────────────

    private fun updateDbcSectionVisibility(useDemoData: Boolean) {
        val dbcVisibility = if (useDemoData) View.GONE else View.VISIBLE
        binding.tvDbcFileLabel.visibility = dbcVisibility
        binding.btnPickDbc.visibility = dbcVisibility
    }

    // ── Sync ticker Hz ────────────────────────────────────────────────────────

    private fun loadSyncTickerHz(profile: CanProfile? = null) {
        val hz = profile?.syncTickerHz ?: 50
        binding.etSyncTickerHz.setText(hz.toString())
    }

    // ── DBC picker ────────────────────────────────────────────────────────────

    private fun onDbcPicked(uri: Uri) {
        try {
            // Read the file directly from the URI to parse before we commit to a profile id.
            val name = queryDisplayName(uri) ?: "picked.dbc"
            val db = requireContext().contentResolver.openInputStream(uri)?.use {
                DbcParser.parse(it, name)
            } ?: run {
                Toast.makeText(requireContext(), "Could not open DBC", Toast.LENGTH_SHORT).show()
                return
            }
            dbc = db
            pendingDbcSourceUri = uri
            pendingDbcFileName = name
            binding.tvDbcFileLabel.text = "DBC: $name (${db.messages.size} msgs)"
            signalAdapter.updateDbc(db)
            // Re-run heuristic when a new DBC is loaded (clear stale mappings first)
            tripMappingState.clear()
            applyHeuristicMapping()
            rebuildMappingRows()
            if (db.warnings.isNotEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "${db.warnings.size} DBC line(s) skipped — see logs",
                    Toast.LENGTH_SHORT
                ).show()
                db.warnings.take(10).forEach { Log.w(TAG, it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse DBC", e)
            Toast.makeText(requireContext(), "Failed to parse DBC: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Capture picker ────────────────────────────────────────────────────────

    private fun onCapturePicked(uri: Uri) {
        val name = queryDisplayName(uri) ?: "capture.jsonl"
        // Cheap sanity check — peek at the first non-empty line to make sure it looks like the
        // JSONL format we expect from the scanner's raw log.
        val sample: String? = try {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().useLines { seq -> seq.firstOrNull { it.isNotBlank() } }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Capture preview read failed", e); null
        }
        val looksValid = sample != null &&
            sample.contains("\"id\"") && sample.contains("\"data\"")
        if (!looksValid) {
            Toast.makeText(
                requireContext(),
                "File doesn't look like a CAN capture (expected JSONL with id/data).",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        pendingCaptureSourceUri = uri
        pendingCaptureFileName = name
        clearExistingCapture = false
        binding.tvCaptureFileLabel.text = "Playback: $name"
        binding.btnClearCapture.visibility = View.VISIBLE
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) return it.getString(idx)
        }
        return null
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun save() {
        val name = binding.etCanName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.etCanName.error = "Required"
            return
        }
        val useDemoData = binding.swUseDemoData.isChecked
        val dbcFileName = pendingDbcFileName ?: editingProfile?.dbcFileName
        if (dbcFileName == null && !useDemoData) {
            Toast.makeText(requireContext(), "Please load a DBC file first.", Toast.LENGTH_SHORT).show()
            return
        }
        val samplingMs = binding.etSamplingMs.text?.toString()?.toLongOrNull()?.coerceAtLeast(10L) ?: 500L
        val tickerHzRaw = binding.etSyncTickerHz.text?.toString()?.toIntOrNull()
        if (tickerHzRaw == null || tickerHzRaw !in 1..200) {
            binding.etSyncTickerHz.error = "Enter a value between 1 and 200"
            binding.etSyncTickerHz.requestFocus()
            return
        }
        val tickerHz = tickerHzRaw

        // Build the profile id first so we can copy the DBC into app-private storage.
        val baseProfile = editingProfile
            ?: CanProfile(
                name = name,
                dbcFileName = dbcFileName ?: "<built-in demo>",
                selectedSignals = selectedRefs.toList(),
                samplingMs = samplingMs,
                recordRawFrames = binding.swRecordRaw.isChecked,
                useDemoData = useDemoData
            )

        val filterIds = selectedRefs.map { it.messageId }.distinct().takeIf { it.isNotEmpty() }

        // Resolve playback capture name: newly picked > clear-requested > keep existing.
        val captureName: String? = when {
            pendingCaptureFileName != null -> pendingCaptureFileName
            clearExistingCapture -> null
            else -> baseProfile.playbackCaptureFileName
        }

        val profile = baseProfile.copy(
            name = name,
            objective = binding.etCanObjective.text?.toString()?.trim().orEmpty(),
            dbcFileName = dbcFileName ?: "<built-in demo>",
            selectedSignals = selectedRefs.toList(),
            samplingMs = samplingMs,
            syncTickerHz = tickerHz,
            canIdFilter = filterIds,
            recordRawFrames = binding.swRecordRaw.isChecked,
            playbackCaptureFileName = captureName,
            useDemoData = useDemoData,
            tripMetricMapping = tripMappingState.toMap()
        )

        // Import pending DBC under the now-known id.
        pendingDbcSourceUri?.let { src ->
            try {
                repo.importDbc(profile.id, src)
            } catch (e: Exception) {
                Log.e(TAG, "DBC import failed", e)
                Toast.makeText(requireContext(), "Failed to copy DBC: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        }

        // Import pending capture, or clear the existing one if user requested.
        pendingCaptureSourceUri?.let { src ->
            try {
                repo.importCapture(profile.id, src)
            } catch (e: Exception) {
                Log.e(TAG, "Capture import failed", e)
                Toast.makeText(requireContext(), "Failed to copy capture: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        }
        if (clearExistingCapture && pendingCaptureSourceUri == null) {
            repo.deleteCaptureFile(profile.id)
        }

        repo.save(profile)
        onSaved?.invoke()
        dismiss()
    }

    private fun updateSelectionCount() {
        binding.tvSelectionCount.text = "${selectedRefs.size} signals selected"
        binding.tvSelectedEmpty.visibility = if (selectedRefs.isEmpty()) View.VISIBLE else View.GONE
        binding.llSelectedSignals.visibility = if (selectedRefs.isEmpty()) View.GONE else View.VISIBLE

        // Rebuild chip views with wordwrap
        val container = binding.llSelectedSignals
        container.removeAllViews()

        if (selectedRefs.isEmpty()) return

        val ctx = requireContext()
        val displayMetrics = ctx.resources.displayMetrics
        val availableWidth = displayMetrics.widthPixels - 32.dpToPx() // subtract padding

        var currentRow: LinearLayout? = null
        var currentRowWidth = 0

        for (ref in selectedRefs) {
            val chipView = LayoutInflater.from(ctx).inflate(R.layout.item_selected_signal_chip, container, false)
            val tvName = chipView.findViewById<TextView>(R.id.tv_chip_name)
            val btnRemove = chipView.findViewById<ImageButton>(R.id.btn_chip_remove)

            tvName.text = ref.signalName
            btnRemove.setOnClickListener {
                selectedRefs.remove(ref)
                updateSelectionCount()
                signalAdapter.notifyDataSetChanged()
            }

            chipView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val chipWidth = chipView.measuredWidth + 12.dpToPx() // add margin

            if (currentRow == null || currentRowWidth + chipWidth > availableWidth) {
                currentRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 4.dpToPx() }
                }
                container.addView(currentRow)
                currentRowWidth = 0
            }

            currentRow!!.addView(chipView)
            currentRowWidth += chipWidth
        }
    }

    private fun Int.dpToPx(): Int {
        val density = requireContext().resources.displayMetrics.density
        return (this * density + 0.5f).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Trip Attribute Mapping ─────────────────────────────────────────────────

    private fun toggleMappingPanel() {
        mappingPanelExpanded = !mappingPanelExpanded
        binding.llMappingPanel.visibility = if (mappingPanelExpanded) View.VISIBLE else View.GONE
        binding.btnMappingToggle.rotation = if (mappingPanelExpanded) 0f else 180f
        binding.btnMappingToggle.contentDescription =
            if (mappingPanelExpanded) "Collapse mapping" else "Expand mapping"
        if (mappingPanelExpanded && binding.llMappingRows.childCount == 0) {
            rebuildMappingRows()
        }
    }

    /**
     * Heuristic: for each unmapped metric key, scan signal names for a substring match
     * (case-insensitive). Selected signals are checked first; all DBC signals are the fallback.
     * Existing mappings are NOT overwritten — tap "Clear all" first for a full re-run.
     */
    private fun applyHeuristicMapping() {
        val currentDbc = dbc ?: return

        // Build two candidate lists: selected signals first (preferred), then everything else.
        val selectedKeys = selectedRefs.map { it.key() }.toSet()
        val allSignals = currentDbc.messages.flatMap { m ->
            m.signals.map { s -> Pair(SignalRef(m.id, s.name).key(), s.name.lowercase()) }
        }
        val selectedSignals = allSignals.filter { it.first in selectedKeys }
        val unselectedSignals = allSignals.filter { it.first !in selectedKeys }
        val candidateSignals = selectedSignals + unselectedSignals

        val keywords = mapOf(
            CanDataOrchestrator.MetricKey.RPM to listOf("rpm", "engine_speed", "enginespeed"),
            CanDataOrchestrator.MetricKey.VEHICLE_SPEED_KMH to listOf("speed", "vspd", "vehicle_speed", "wheelspeed"),
            CanDataOrchestrator.MetricKey.COOLANT_TEMP_C to listOf("coolant", "ect", "water_temp", "engine_temp"),
            CanDataOrchestrator.MetricKey.THROTTLE_PCT to listOf("throttle", "tps", "accel_pedal", "pedal"),
            CanDataOrchestrator.MetricKey.MAF_GS to listOf("maf", "air_flow", "mass_air"),
            CanDataOrchestrator.MetricKey.INTAKE_MAP_KPA to listOf("map", "intake_pressure", "boost", "manifold"),
            CanDataOrchestrator.MetricKey.ENGINE_LOAD_PCT to listOf("load", "engine_load"),
            CanDataOrchestrator.MetricKey.FUEL_LEVEL_PCT to listOf("fuel_level", "fuel"),
            CanDataOrchestrator.MetricKey.INTAKE_TEMP_C to listOf("iat", "intake_temp", "air_temp", "intake_air")
        )
        for ((metricKey, kws) in keywords) {
            if (tripMappingState.containsKey(metricKey)) continue
            for ((signalKey, signalNameLower) in candidateSignals) {
                if (kws.any { signalNameLower.contains(it) }) {
                    tripMappingState[metricKey] = signalKey
                    break
                }
            }
        }
    }

    /** Programmatically creates one label + spinner row per metric key inside ll_mapping_rows. */
    private fun rebuildMappingRows() {
        val container = binding.llMappingRows
        container.removeAllViews()
        val ctx = requireContext()

        val currentDbc = dbc
        if (currentDbc == null) {
            binding.tvMappingEmpty.visibility = View.VISIBLE
            binding.tvMappingEmpty.text = "Load a DBC file to enable signal mapping."
            binding.btnMappingAutoFill.isEnabled = false
            binding.btnMappingAutoFill.alpha = 0.4f
            return
        }
        if (selectedRefs.isEmpty()) {
            binding.tvMappingEmpty.visibility = View.VISIBLE
            binding.tvMappingEmpty.text = "Select signals above to enable trip attribute mapping."
            binding.btnMappingAutoFill.isEnabled = false
            binding.btnMappingAutoFill.alpha = 0.4f
            return
        }
        binding.tvMappingEmpty.visibility = View.GONE
        binding.btnMappingAutoFill.isEnabled = true
        binding.btnMappingAutoFill.alpha = 1f

        val signalEntries: List<Pair<String, String>> = buildList {
            add("" to "— none —")
            selectedRefs.forEach { ref ->
                val signal = currentDbc.findSignal(ref.messageId, ref.signalName)?.second
                if (signal != null) {
                    val key = ref.key()
                    val label = "${signal.name}  (0x${Integer.toHexString(ref.messageId).uppercase()})"
                    add(key to label)
                }
            }
        }
        val spinnerLabels = signalEntries.map { it.second }

        val dpScale = ctx.resources.displayMetrics.density
        val labelWidthPx = (130 * dpScale + 0.5f).toInt()

        for (metricKey in CanDataOrchestrator.MetricKey.ALL) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * dpScale + 0.5f).toInt() }
            }

            val label = TextView(ctx).apply {
                text = CanDataOrchestrator.MetricKey.label(metricKey)
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(labelWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            }

            val spinner = Spinner(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val adapter = ArrayAdapter(ctx, R.layout.item_spinner_white, spinnerLabels)
                    .also { it.setDropDownViewResource(R.layout.item_spinner_dropdown_black) }
                this.adapter = adapter
                val currentSignalKey = tripMappingState[metricKey]
                val idx = if (currentSignalKey != null) {
                    signalEntries.indexOfFirst { it.first == currentSignalKey }.takeIf { it >= 0 } ?: 0
                } else 0
                setSelection(idx, false)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        val chosen = signalEntries[pos].first
                        if (chosen.isEmpty()) tripMappingState.remove(metricKey)
                        else tripMappingState[metricKey] = chosen
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }

            row.addView(label)
            row.addView(spinner)
            container.addView(row)
        }
    }

    // ── Signal adapter ────────────────────────────────────────────────────────

    private data class Row(val message: CanMessage, val signal: CanSignal)

    private inner class SignalAdapter : RecyclerView.Adapter<SignalAdapter.VH>() {

        private var all: List<Row> = emptyList()
        private var filtered: List<Row> = emptyList()
        private var filterText: String = ""

        fun updateDbc(db: DbcDatabase?) {
            all = db?.messages.orEmpty().flatMap { m -> m.signals.map { s -> Row(m, s) } }
            applyFilter()
        }

        fun filter(text: String) {
            filterText = text.trim().lowercase()
            applyFilter()
        }

        private fun applyFilter() {
            filtered = if (filterText.isEmpty()) all
            else all.filter {
                it.signal.name.lowercase().contains(filterText) ||
                    it.message.name.lowercase().contains(filterText)
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemCanSignalBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(filtered[position])

        override fun getItemCount(): Int = filtered.size

        inner class VH(private val b: ItemCanSignalBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(row: Row) {
                val ref = SignalRef(row.message.id, row.signal.name)
                b.tvSignalName.text = row.signal.name
                b.tvSignalDetails.text = buildString {
                    append(row.message.name)
                    append(" (0x${Integer.toHexString(row.message.id).uppercase()})")
                    append(" · ")
                    append("${row.signal.startBit}|${row.signal.length}@")
                    append(if (row.signal.littleEndian) "1" else "0")
                    append(if (row.signal.signed) "-" else "+")
                    if (row.signal.unit.isNotEmpty()) {
                        append(" · ")
                        append(row.signal.unit)
                    }
                }
                b.cbSignalSelected.setOnCheckedChangeListener(null)
                b.cbSignalSelected.isChecked = ref in selectedRefs
                b.cbSignalSelected.setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedRefs.add(ref) else selectedRefs.remove(ref)
                    updateSelectionCount()
                }
                b.root.setOnClickListener { b.cbSignalSelected.toggle() }
            }
        }
    }
}
