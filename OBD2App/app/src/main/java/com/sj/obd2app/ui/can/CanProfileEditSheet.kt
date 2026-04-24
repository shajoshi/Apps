package com.sj.obd2app.ui.can

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sj.obd2app.can.CanMessage
import com.sj.obd2app.can.CanProfile
import com.sj.obd2app.can.CanProfileRepository
import com.sj.obd2app.can.CanSignal
import com.sj.obd2app.can.DbcDatabase
import com.sj.obd2app.can.DbcParser
import com.sj.obd2app.can.SignalRef
import com.sj.obd2app.databinding.ItemCanSignalBinding
import com.sj.obd2app.databinding.SheetCanProfileEditBinding
import com.sj.obd2app.settings.AppSettings

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

        binding.btnCanSave.setOnClickListener { save() }
        binding.btnCanCancel.setOnClickListener { dismiss() }
        binding.btnCanDelete.setOnClickListener {
            editingProfile?.let { p ->
                repo.delete(p.id)
                val defaultId = AppSettings.getDefaultCanProfileId(requireContext())
                if (defaultId == p.id) AppSettings.setDefaultCanProfileId(requireContext(), null)
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
            binding.btnCanDelete.visibility = View.VISIBLE
            selectedRefs.addAll(p.selectedSignals)
            // Try to auto-load its stored DBC
            val file = repo.dbcFileFor(p.id)
            if (file != null) {
                try {
                    file.inputStream().use {
                        dbc = DbcParser.parse(it, p.dbcFileName)
                    }
                    binding.tvDbcFileLabel.text = "DBC: ${p.dbcFileName} (${dbc?.messages?.size ?: 0} msgs)"
                    signalAdapter.updateDbc(dbc)
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
            binding.btnCanDelete.visibility = View.GONE
        }

        updateSelectionCount()
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
        val dbcFileName = pendingDbcFileName ?: editingProfile?.dbcFileName
        if (dbcFileName == null) {
            Toast.makeText(requireContext(), "Please load a DBC file first.", Toast.LENGTH_SHORT).show()
            return
        }
        val samplingMs = binding.etSamplingMs.text?.toString()?.toLongOrNull()?.coerceAtLeast(10L) ?: 500L

        // Build the profile id first so we can copy the DBC into app-private storage.
        val baseProfile = editingProfile
            ?: CanProfile(
                name = name,
                dbcFileName = dbcFileName,
                selectedSignals = selectedRefs.toList(),
                samplingMs = samplingMs,
                recordRawFrames = binding.swRecordRaw.isChecked
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
            dbcFileName = dbcFileName,
            selectedSignals = selectedRefs.toList(),
            samplingMs = samplingMs,
            canIdFilter = filterIds,
            recordRawFrames = binding.swRecordRaw.isChecked,
            playbackCaptureFileName = captureName
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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
