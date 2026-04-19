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
                // Handle back press - clear summary if showing, otherwise use default behavior
                if (viewModel.summary.value != null) {
                    viewModel.clearSummary()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
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
        fileAdapter = TrackFileAdapter { fileItem ->
            viewModel.loadTrackFile(fileItem)
        }
        binding.rvTrackFiles.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = fileAdapter
        }
    }

    private fun setupListeners() {
        binding.btnReloadFolder.setOnClickListener {
            val uri = AppSettings.getLogFolderUri(requireContext())
            if (uri != null) {
                viewModel.listTrackFiles(Uri.parse(uri))
            } else {
                Toast.makeText(requireContext(), "No folder selected yet", Toast.LENGTH_SHORT).show()
            }
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
                binding.tvLoadingMessage.text = when (loadingType) {
                    TripSummaryLoadingType.FILE_LIST -> "Loading track files..."
                    TripSummaryLoadingType.TRIP_SUMMARY -> "Loading track summary..."
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
    private val onFileClick: (TrackFileItem) -> Unit
) : RecyclerView.Adapter<TrackFileAdapter.ViewHolder>() {

    private var files = listOf<TrackFileItem>()

    fun submitList(newFiles: List<TrackFileItem>) {
        files = newFiles
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTrackFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
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

            binding.root.setOnClickListener {
                onFileClick(file)
            }
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
