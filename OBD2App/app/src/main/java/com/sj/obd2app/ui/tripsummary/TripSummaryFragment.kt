package com.sj.obd2app.ui.tripsummary

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sj.obd2app.MainActivity
import com.sj.obd2app.MainPagerAdapter
import com.sj.obd2app.databinding.FragmentTripSummaryBinding
import com.sj.obd2app.databinding.ItemTrackFileBinding
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.ui.attachNavOverflow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TripSummaryFragment : Fragment() {

    private val TAG = "TripSummaryFragment"
    private var _binding: FragmentTripSummaryBinding? = null
    private val binding get() = _binding!!
    
    init {
        Log.d(TAG, "TripSummaryFragment created")
    }

    private lateinit var viewModel: TripSummaryViewModel
    private lateinit var fileAdapter: TrackFileAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.d(TAG, "onCreateView: Creating Trip Summary fragment")
        viewModel = ViewModelProvider(this)[TripSummaryViewModel::class.java]
        _binding = FragmentTripSummaryBinding.inflate(inflater, container, false)
        Log.d(TAG, "onCreateView: View binding created")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated: Setting up UI")
        
        // Enable back button in the activity's ActionBar
        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Handle back press - exit selection mode first, then clear summary, then navigate back
                when {
                    fileAdapter.isInSelectionMode() -> {
                        fileAdapter.clearSelection()
                    }
                    viewModel.summary.value != null -> {
                        viewModel.clearSummary()
                    }
                    else -> {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback)

        binding.topBarInclude.txtTopBarTitle.text = "Trip Summary"
        attachNavOverflow(binding.topBarInclude.btnTopOverflow)
        binding.topBarInclude.btnTopMap.visibility = View.VISIBLE
        
        // Setup back button click handler
        binding.topBarInclude.btnTopBack.setOnClickListener {
            // Clear summary to go back to file list
            viewModel.clearSummary()
        }

        binding.topBarInclude.btnTopMap.setOnClickListener {
            if (viewModel.summary.value != null) {
                (activity as? MainActivity)?.navigateToPage(MainPagerAdapter.PAGE_MAP_VIEW)
            } else {
                Toast.makeText(requireContext(), "Select a track file first", Toast.LENGTH_SHORT).show()
            }
        }

        setupRecyclerView()
        setupListeners()
        observeViewModel()

        // Try to load from existing log folder
        val existingUri = AppSettings.getLogFolderUri(requireContext())
        Log.d(TAG, "onViewCreated: Existing log folder URI: $existingUri")
        if (existingUri != null) {
            Log.d(TAG, "onViewCreated: Loading files from existing folder")
            viewModel.listTrackFiles(Uri.parse(existingUri))
        } else {
            Log.d(TAG, "onViewCreated: No existing log folder found")
        }
    }

    private fun setupRecyclerView() {
        fileAdapter = TrackFileAdapter(
            onFileClick = { fileItem ->
                viewModel.loadTrackFile(fileItem)
            },
            onSelectionChanged = { count ->
                updateSelectionUI(count)
            }
        )
        binding.rvTrackFiles.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = fileAdapter
        }
    }

    private fun setupListeners() {
        binding.btnReloadFolder.setOnClickListener {
            fileAdapter.clearSelection()
            val uri = AppSettings.getLogFolderUri(requireContext())
            if (uri != null) {
                viewModel.listTrackFiles(Uri.parse(uri))
            } else {
                Toast.makeText(requireContext(), "No folder selected yet", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAnalyze.setOnClickListener {
            val selected = fileAdapter.getSelectedFiles()
            if (selected.size >= 2) {
                viewModel.analyzeSelectedFiles(selected)
                fileAdapter.clearSelection()
            }
        }
    }

    private fun updateSelectionUI(count: Int) {
        val hasSelection = count >= 2
        binding.btnAnalyze.isEnabled = hasSelection
        binding.btnAnalyze.alpha = if (hasSelection) 1.0f else 0.4f
        binding.tvCurrentFolder.text = when (count) {
            0 -> "Track files are loaded from the Log Folder set in Settings."
            1 -> "1 file selected — long-press more files to select (max 5)"
            else -> "$count files selected — tap Analyze to combine"
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.fileList.collect { files ->
                Log.d(TAG, "observeViewModel: File list updated with ${files.size} files")
                fileAdapter.submitList(files)
                val fileListVisible = files.isNotEmpty()
                binding.cardFileList.visibility = if (fileListVisible) View.VISIBLE else View.GONE
                binding.tvNoFiles.visibility = if (files.isEmpty() && !viewModel.isLoading.value) View.VISIBLE else View.GONE
                Log.d(TAG, "observeViewModel: File list visible: $fileListVisible, files count: ${files.size}")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.summary.collect { summary ->
                Log.d(TAG, "observeViewModel: Summary updated - isNull: ${summary == null}")
                if (summary != null) {
                    Log.d(TAG, "observeViewModel: Displaying summary and showing layout")
                    displaySummary(summary)
                    binding.layoutSummary.visibility = View.VISIBLE
                    // Hide file list when showing summary
                    binding.cardFileList.visibility = View.GONE
                    // Show back button when viewing summary
                    binding.topBarInclude.btnTopBack.visibility = View.VISIBLE
                    binding.topBarInclude.btnTopMap.visibility = View.VISIBLE
                    Log.d(TAG, "observeViewModel: Set layoutSummary visibility to VISIBLE, current: ${binding.layoutSummary.visibility}")
                } else {
                    Log.d(TAG, "observeViewModel: Hiding summary layout")
                    binding.layoutSummary.visibility = View.GONE
                    // Show file list when no summary
                    if (viewModel.fileList.value.isNotEmpty()) {
                        binding.cardFileList.visibility = View.VISIBLE
                    }
                    // Hide back button when viewing file list
                    binding.topBarInclude.btnTopBack.visibility = View.GONE
                    binding.topBarInclude.btnTopMap.visibility = View.GONE
                    Log.d(TAG, "observeViewModel: Set layoutSummary visibility to GONE, current: ${binding.layoutSummary.visibility}")
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                binding.tvLoadingMessage.visibility = if (isLoading) View.VISIBLE else View.GONE
                // When loading a summary, hide the file list to show progress bar
                if (isLoading) {
                    binding.cardFileList.visibility = View.GONE
                } else if (viewModel.summary.value == null && viewModel.fileList.value.isNotEmpty()) {
                    // Show file list only if not loading and no summary is displayed
                    binding.cardFileList.visibility = View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loadingType.collect { loadingType ->
                if (loadingType != TripSummaryLoadingType.ANALYZING) {
                    binding.tvLoadingMessage.text = when (loadingType) {
                        TripSummaryLoadingType.FILE_LIST -> "Loading track files..."
                        TripSummaryLoadingType.TRIP_SUMMARY -> "Loading track summary..."
                        else -> ""
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.analysisProgress.collect { progress ->
                if (viewModel.loadingType.value != TripSummaryLoadingType.ANALYZING) return@collect
                binding.tvLoadingMessage.text = when (progress.phase) {
                    AnalysisPhase.IDLE -> "Preparing analysis..."
                    AnalysisPhase.SCANNING ->
                        "Scanning file ${progress.filesScanned + 1} of ${progress.totalFiles}:\n${progress.currentFileName}"
                    AnalysisPhase.COMBINING -> {
                        val k = progress.samplesWritten / 1000
                        if (k == 0) "Writing combined file..."
                        else "Writing combined file... ${k}k samples written"
                    }
                    AnalysisPhase.DONE -> "Analysis complete"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.error.collect { error ->
                if (error != null) {
                    binding.tvError.text = error
                    binding.tvError.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
                } else {
                    binding.tvError.visibility = View.GONE
                }
            }
        }

    }

    private fun displaySummary(summary: TripSummaryData) {
        Log.d(TAG, "displaySummary: Starting to display summary data")
        // File Name
        binding.tvFileName.text = summary.fileName
        // Vehicle Profile
        binding.tvVehicleName.text = summary.vehicleName
        binding.tvFuelType.text = summary.fuelType
        binding.tvTankCapacity.text = if (summary.tankCapacityL > 0) "${summary.tankCapacityL} L" else "-"
        binding.tvFuelPrice.text = if (summary.fuelPricePerLitre > 0) "₹${summary.fuelPricePerLitre}/L" else "-"
        binding.tvEnginePower.text = if (summary.enginePowerBhp > 0) "${summary.enginePowerBhp} bhp" else "-"
        binding.tvVehicleMass.text = if (summary.vehicleMassKg > 0) "${summary.vehicleMassKg} kg" else "-"

        // Fuel Summary
        binding.tvTripFuelUsed.text = String.format(Locale.US, "%.2f L", summary.tripFuelUsedL)
        binding.tvAvgConsumption.text = if (summary.tripAvgLper100km > 0) 
            String.format(Locale.US, "%.2f L/100km", summary.tripAvgLper100km) else "-"
        binding.tvAvgEconomy.text = if (summary.tripAvgKpl > 0) 
            String.format(Locale.US, "%.2f km/L", summary.tripAvgKpl) else "-"
        binding.tvFuelCost.text = if (summary.fuelCostEstimate > 0) 
            String.format(Locale.US, "₹%.2f", summary.fuelCostEstimate) else "-"
        binding.tvAvgCo2.text = if (summary.avgCo2gPerKm > 0) 
            String.format(Locale.US, "%.1f g/km", summary.avgCo2gPerKm) else "-"

        // Trip Summary
        binding.tvDistance.text = String.format(Locale.US, "%.2f km", summary.distanceKm)
        binding.tvDuration.text = formatTime(summary.timeSec)
        val derivedMovingTimeSec = (summary.timeSec - summary.stoppedTimeSec).coerceAtLeast(0L)
        binding.tvMovingTime.text = formatTime(derivedMovingTimeSec)
        binding.tvStoppedTime.text = formatTime(summary.stoppedTimeSec)
        binding.tvAvgSpeed.text = if (summary.avgSpeedKmh > 0) 
            String.format(Locale.US, "%.1f km/h", summary.avgSpeedKmh) else "-"
        binding.tvMaxSpeed.text = if (summary.maxSpeedKmh > 0) 
            String.format(Locale.US, "%.1f km/h", summary.maxSpeedKmh) else "-"
        binding.tvDriveMode.text = String.format(
            Locale.US, 
            "%.1f%% / %.1f%% / %.1f%%", 
            summary.pctCity, 
            summary.pctHighway, 
            summary.pctIdle
        )
    }

    private fun formatTime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> String.format(Locale.US, "%dh %dm %ds", hours, minutes, secs)
            minutes > 0 -> String.format(Locale.US, "%dm %ds", minutes, secs)
            else -> String.format(Locale.US, "%ds", secs)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class TrackFileAdapter(
    private val onFileClick: (TrackFileItem) -> Unit,
    private val onSelectionChanged: (count: Int) -> Unit
) : RecyclerView.Adapter<TrackFileAdapter.ViewHolder>() {

    private val MAX_SELECTION = 5

    private var files = listOf<TrackFileItem>()

    /** Ordered set of selected filenames (insertion order = selection order). */
    private val selectedNames = LinkedHashSet<String>()

    /** Profile prefix of the first selected file; gates subsequent selections. */
    private var anchorProfilePrefix: String? = null

    fun submitList(newFiles: List<TrackFileItem>) {
        files = newFiles
        notifyDataSetChanged()
    }

    fun isInSelectionMode(): Boolean = selectedNames.isNotEmpty()

    fun getSelectedFiles(): List<TrackFileItem> {
        val nameIndex = files.associateBy { it.name }
        return selectedNames.mapNotNull { nameIndex[it] }
    }

    fun clearSelection() {
        selectedNames.clear()
        anchorProfilePrefix = null
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    /** Extracts the profile name prefix from a filename like "Brezza_obdlog_2025-01-15_143022.json" */
    private fun profilePrefix(name: String): String =
        if ("_obdlog_" in name) name.substringBefore("_obdlog_") else name

    private fun toggleSelection(file: TrackFileItem, context: android.content.Context) {
        android.util.Log.d("TrackFileAdapter", "toggleSelection: file=${file.name} alreadySelected=${selectedNames.contains(file.name)} currentSet=$selectedNames anchor=$anchorProfilePrefix")
        val wasInSelectionMode = isInSelectionMode()
        if (selectedNames.contains(file.name)) {
            selectedNames.remove(file.name)
            if (selectedNames.isEmpty()) anchorProfilePrefix = null
            android.util.Log.d("TrackFileAdapter", "toggleSelection: DESELECTED. set now=$selectedNames")
        } else {
            val prefix = profilePrefix(file.name)
            if (anchorProfilePrefix != null && prefix != anchorProfilePrefix) {
                android.util.Log.d("TrackFileAdapter", "toggleSelection: REJECTED profile mismatch prefix=$prefix anchor=$anchorProfilePrefix")
                Toast.makeText(
                    context,
                    "Only files from '$anchorProfilePrefix' can be selected",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            if (selectedNames.size >= MAX_SELECTION) {
                android.util.Log.d("TrackFileAdapter", "toggleSelection: REJECTED max selection reached size=${selectedNames.size}")
                Toast.makeText(
                    context,
                    "Maximum $MAX_SELECTION files can be analyzed",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            if (anchorProfilePrefix == null) anchorProfilePrefix = prefix
            selectedNames.add(file.name)
            android.util.Log.d("TrackFileAdapter", "toggleSelection: SELECTED. set now=$selectedNames")
        }
        val nowInSelectionMode = isInSelectionMode()
        // Notify only the toggled item plus all others if selection mode boundary changed
        // (mode change affects dimming of ALL items). Use notifyItemChanged to avoid
        // a full layout pass that resets the touch state and turns taps into long-presses.
        val changedPos = files.indexOfFirst { it.name == file.name }
        if (wasInSelectionMode != nowInSelectionMode) {
            notifyDataSetChanged()
        } else if (changedPos >= 0) {
            notifyItemChanged(changedPos)
        }
        onSelectionChanged(selectedNames.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTrackFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        val holder = ViewHolder(binding)

        // Set listeners once here — use bindingAdapterPosition to get the live file at click time,
        // never capture a file reference from bind() which goes stale after recycling.
        binding.root.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            android.util.Log.d("TrackFileAdapter", "onClick: pos=$pos selectionMode=${isInSelectionMode()} selectedNames=$selectedNames")
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val file = files[pos]
            if (isInSelectionMode()) {
                toggleSelection(file, it.context)
            } else {
                onFileClick(file)
            }
        }

        binding.root.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            android.util.Log.d("TrackFileAdapter", "onLongClick: pos=$pos selectionMode=${isInSelectionMode()}")
            if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener true
            val file = files[pos]
            // Toggle selection on both short and long press — users naturally hold longer
            // when waiting for visual feedback, so we must handle both gesture types.
            toggleSelection(file, it.context)
            true
        }

        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(files[position])
    }

    override fun getItemCount() = files.size

    inner class ViewHolder(
        private val binding: ItemTrackFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(file: TrackFileItem) {
            binding.tvFileName.text = file.name
            binding.tvFileDate.text = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
                .format(Date(file.lastModified))
            binding.tvFileSize.text = formatFileSize(file.sizeBytes)

            val isSelected = selectedNames.contains(file.name)
            val selectionOrder = if (isSelected) selectedNames.toList().indexOf(file.name) + 1 else -1

            // Selection accent strip
            binding.viewSelectionAccent.visibility =
                if (isSelected) android.view.View.VISIBLE else android.view.View.GONE

            // Selection order badge
            if (isSelected) {
                binding.tvSelectionBadge.visibility = android.view.View.VISIBLE
                binding.tvSelectionBadge.text = selectionOrder.toString()
            } else {
                binding.tvSelectionBadge.visibility = android.view.View.GONE
            }

            // Dim unselectable files (different profile prefix when in selection mode)
            val inSelectionMode = isInSelectionMode()
            val prefix = profilePrefix(file.name)
            val isSelectable = !inSelectionMode
                || isSelected
                || anchorProfilePrefix == null
                || prefix == anchorProfilePrefix
            binding.root.alpha = if (inSelectionMode && !isSelectable) 0.4f else 1.0f
        }

        private fun formatFileSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
                else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            }
        }
    }
}
