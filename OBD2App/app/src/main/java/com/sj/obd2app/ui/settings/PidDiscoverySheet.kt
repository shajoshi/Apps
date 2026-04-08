package com.sj.obd2app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sj.obd2app.R
import com.sj.obd2app.databinding.SheetPidDiscoveryBinding
import com.sj.obd2app.obd.DiscoveredPid
import com.sj.obd2app.obd.DiscoveryState
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.obd.PidDiscoveryService
import com.sj.obd2app.obd.CustomPid
import com.sj.obd2app.settings.VehicleProfileRepository
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Bottom sheet for PID discovery with console output and results.
 */
class PidDiscoverySheet : BottomSheetDialogFragment() {
    
    private var _binding: SheetPidDiscoveryBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var discoveryService: PidDiscoveryService
    private lateinit var profileRepository: VehicleProfileRepository
    private lateinit var consoleAdapter: ConsoleAdapter
    private lateinit var resultsAdapter: DiscoveredPidAdapter
    
    private var activeProfileId: String? = null
    
    companion object {
        private const val ARG_PROFILE_ID = "profile_id"
        
        fun newInstance(profileId: String? = null): PidDiscoverySheet {
            return PidDiscoverySheet().apply {
                arguments = Bundle().apply {
                    if (profileId != null) putString(ARG_PROFILE_ID, profileId)
                }
            }
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetPidDiscoveryBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        discoveryService = PidDiscoveryService.getInstance()
        profileRepository = VehicleProfileRepository.getInstance(requireContext())
        
        // Debug logging to trace profile selection
        android.util.Log.d("PidDiscoverySheet", "Arguments bundle: ${arguments}")
        android.util.Log.d("PidDiscoverySheet", "ARG_PROFILE_ID value: ${arguments?.getString(ARG_PROFILE_ID)}")
        android.util.Log.d("PidDiscoverySheet", "Bundle contents: ${arguments?.keySet()?.map { key -> "$key=${arguments?.getString(key)}" }}")
        
        // Get profile ID from arguments, or fall back to active profile
        val argProfileId = arguments?.getString(ARG_PROFILE_ID)
        if (argProfileId != null) {
            // Opened with specific profile - use it
            activeProfileId = argProfileId
            android.util.Log.d("PidDiscoverySheet", "PROFILE CONTEXT: Using specified profile $argProfileId")
        } else {
            // Opened without profile context - use active profile
            val fallbackId = com.sj.obd2app.settings.AppSettings.getActiveProfileId(requireContext())
            activeProfileId = fallbackId
            android.util.Log.d("PidDiscoverySheet", "GLOBAL CONTEXT: Using active profile $fallbackId")
        }
        
        setupUI()
        setupObservers()
        setupClickListeners()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    private fun setupUI() {
        // Setup console RecyclerView
        consoleAdapter = ConsoleAdapter()
        binding.rvConsole.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = consoleAdapter
        }
        
        // Setup results RecyclerView
        resultsAdapter = DiscoveredPidAdapter { discoveredPid ->
            // Toggle selection
            resultsAdapter.toggleSelection(discoveredPid)
            updateAddButtonState()
        }
        binding.rvResults.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = resultsAdapter
        }
        
        // Setup scan options
        setupScanOptions()
        
        // Initial UI state
        updateUIState(DiscoveryState.IDLE)
    }
    
    private fun setupScanOptions() {
        // Headers
        binding.checkboxHeader7E0.isChecked = true
        binding.checkboxHeader7E1.isChecked = true
        binding.checkboxHeader7E2.isChecked = false
        binding.checkboxHeader760.isChecked = true
        binding.checkboxHeader7E4.isChecked = true
        
        // Modes
        binding.checkboxMode21.isChecked = true
        binding.checkboxMode22.isChecked = true
        binding.checkboxMode23.isChecked = false
    }
    
    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            discoveryService.discoveryState.collect { state ->
                updateUIState(state)
            }
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            discoveryService.discoveryProgress.collect { progress ->
                updateProgress(progress)
            }
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            discoveryService.consoleOutput.collect { messages ->
                consoleAdapter.submitList(messages)
                // Auto-scroll to bottom
                binding.rvConsole.post {
                    binding.rvConsole.scrollToPosition(consoleAdapter.itemCount - 1)
                }
            }
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            discoveryService.discoveredPids.collect { pids ->
                resultsAdapter.submitList(pids)
                updateAddButtonState()
            }
        }
    }
    
    private fun setupClickListeners() {
        binding.btnStartDiscovery.setOnClickListener {
            startDiscovery()
        }
        
        binding.btnStopDiscovery.setOnClickListener {
            stopDiscovery()
        }
        
        binding.btnReset.setOnClickListener {
            resetDiscovery()
        }
        
        binding.btnAddSelected.setOnClickListener {
            addSelectedPids()
        }
        
        binding.btnClose.setOnClickListener {
            dismiss()
        }
        
        // Scan options collapse/expand
        binding.layoutScanOptionsHeader.setOnClickListener {
            val content = binding.layoutScanOptionsContent
            val caret = binding.ivScanOptionsCaret
            if (content.isVisible) {
                content.isVisible = false
                caret.animate().rotation(180f).setDuration(200).start()
            } else {
                content.isVisible = true
                caret.animate().rotation(0f).setDuration(200).start()
            }
        }
        
        // Tab switching
        binding.tabConsole.setOnClickListener {
            showConsoleTab()
        }
        
        binding.tabResults.setOnClickListener {
            showResultsTab()
        }
    }
    
    private fun startDiscovery() {
        if (activeProfileId == null) {
            com.sj.obd2app.ui.showToast(requireContext(), "No active vehicle profile selected")
            return
        }
        
        val selectedHeaders = getSelectedHeaders()
        val selectedModes = getSelectedModes()
        
        if (selectedHeaders.isEmpty() || selectedModes.isEmpty()) {
            com.sj.obd2app.ui.showToast(requireContext(), "Please select at least one header and mode")
            return
        }
        
        val obdService = Obd2ServiceProvider.getService()
        if (obdService.connectionState.value != com.sj.obd2app.obd.Obd2Service.ConnectionState.CONNECTED) {
            com.sj.obd2app.ui.showToast(requireContext(), "Please connect to OBD adapter first")
            return
        }
        
        // Reset and start
        discoveryService.reset()
        discoveryService.startDiscovery(obdService, selectedHeaders, selectedModes)
        
        // Collapse scan options to maximize console space
        binding.layoutScanOptionsContent.isVisible = false
        binding.ivScanOptionsCaret.animate().rotation(180f).setDuration(200).start()
        
        // Switch to console tab
        showConsoleTab()
    }
    
    private fun stopDiscovery() {
        discoveryService.stopDiscovery()
    }
    
    private fun resetDiscovery() {
        discoveryService.reset()
        resultsAdapter.clearSelections()
        showConsoleTab()
    }
    
    private fun addSelectedPids() {
        val selectedPids = resultsAdapter.getSelectedPids()
        if (selectedPids.isEmpty()) {
            com.sj.obd2app.ui.showToast(requireContext(), "No PIDs selected")
            return
        }
        
        if (activeProfileId == null) {
            com.sj.obd2app.ui.showToast(requireContext(), "No active vehicle profile")
            return
        }
        
        var customPids: List<CustomPid> = emptyList()
        try {
            android.util.Log.d("PidDiscoverySheet", "Saving PIDs to profileId=$activeProfileId (from args=${arguments?.getString(ARG_PROFILE_ID)})")
            
            // Convert DiscoveredPid to CustomPid
            customPids = selectedPids.map { discovered ->
                CustomPid(
                    name = discovered.suggestedName,
                    header = discovered.header,
                    mode = discovered.mode,
                    pid = discovered.pid,
                    bytesReturned = discovered.byteCount,
                    unit = discovered.suggestedUnit,
                    formula = discovered.suggestedFormula,
                    signed = false
                )
            }
            
            // Add to profile
            val profile = profileRepository.getById(activeProfileId!!)
            android.util.Log.d("PidDiscoverySheet", "Resolved profile: name=${profile?.name}, id=${profile?.id}, existingCustomPids=${profile?.customPids?.size}")
            if (profile != null) {
                val updatedPids = profile.customPids + customPids
                val updatedProfile = profile.copy(customPids = updatedPids)
                android.util.Log.d("PidDiscoverySheet", "Saving ${updatedPids.size} customPids to profile '${updatedProfile.name}' (id=${updatedProfile.id})")
                profileRepository.save(updatedProfile)
                
                com.sj.obd2app.ui.showToast(
                    requireContext(),
                    "Added ${customPids.size} custom PIDs to ${profile.name}"
                )
                
                // Clear selections
                resultsAdapter.clearSelections()
            } else {
                android.util.Log.e("PidDiscoverySheet", "Profile NOT found for id=$activeProfileId! All profiles: ${profileRepository.getAll().map { "${it.name}(${it.id})" }}")
                com.sj.obd2app.ui.showToast(requireContext(), "Error: profile not found for id=$activeProfileId")
            }
        } catch (e: Exception) {
            android.util.Log.e("PidDiscoverySheet", "Failed to add ${customPids.size} custom PIDs to profile id=$activeProfileId", e)
            com.sj.obd2app.ui.showToast(
                requireContext(),
                "Failed to add PIDs: ${e.message}"
            )
        }
    }
    
    private fun getSelectedHeaders(): List<String> {
        val headers = mutableListOf<String>()
        if (binding.checkboxHeader7E0.isChecked) headers.add("7E0")
        if (binding.checkboxHeader7E1.isChecked) headers.add("7E1")
        if (binding.checkboxHeader7E2.isChecked) headers.add("7E2")
        if (binding.checkboxHeader760.isChecked) headers.add("760")
        if (binding.checkboxHeader7E4.isChecked) headers.add("7E4")
        return headers
    }
    
    private fun getSelectedModes(): List<String> {
        val modes = mutableListOf<String>()
        if (binding.checkboxMode21.isChecked) modes.add("21")
        if (binding.checkboxMode22.isChecked) modes.add("22")
        if (binding.checkboxMode23.isChecked) modes.add("23")
        return modes
    }
    
    private fun updateUIState(state: DiscoveryState) {
        when (state) {
            DiscoveryState.IDLE -> {
                binding.btnStartDiscovery.isVisible = true
                binding.btnStopDiscovery.isVisible = false
                binding.btnReset.isVisible = false
                binding.progressDiscoveryBar.isVisible = false
                binding.layoutScanOptions.isEnabled = true
            }
            DiscoveryState.SCANNING -> {
                binding.btnStartDiscovery.isVisible = false
                binding.btnStopDiscovery.isVisible = true
                binding.btnReset.isVisible = false
                binding.progressDiscoveryBar.isVisible = true
                binding.layoutScanOptions.isEnabled = false
            }
            DiscoveryState.COMPLETED -> {
                binding.btnStartDiscovery.isVisible = false
                binding.btnStopDiscovery.isVisible = false
                binding.btnReset.isVisible = true
                binding.progressDiscoveryBar.isVisible = false
                binding.layoutScanOptions.isEnabled = true
                
                // Show results tab if any PIDs found
                val pids = discoveryService.discoveredPids.value
                if (pids.isNotEmpty()) {
                    showResultsTab()
                }
            }
            DiscoveryState.CANCELLED -> {
                binding.btnStartDiscovery.isVisible = true
                binding.btnStopDiscovery.isVisible = false
                binding.btnReset.isVisible = true
                binding.progressDiscoveryBar.isVisible = false
                binding.layoutScanOptions.isEnabled = true
            }
            DiscoveryState.ERROR -> {
                binding.btnStartDiscovery.isVisible = true
                binding.btnStopDiscovery.isVisible = false
                binding.btnReset.isVisible = true
                binding.progressDiscoveryBar.isVisible = false
                binding.layoutScanOptions.isEnabled = true
            }
        }
    }
    
    private fun updateProgress(progress: com.sj.obd2app.obd.DiscoveryProgress) {
        binding.progressDiscoveryBar.apply {
            max = progress.total
            this.progress = progress.scanned
        }
        
        binding.tvProgress.text = buildString {
            append("Scanning: ${progress.currentHeader} ")
            append("${progress.currentMode} ${progress.currentPid}")
            append(" (${progress.scanned}/${progress.total})")
        }
    }
    
    private fun updateAddButtonState() {
        val selectedCount = resultsAdapter.getSelectedPids().size
        binding.btnAddSelected.apply {
            text = "Add Selected ($selectedCount)"
            isEnabled = selectedCount > 0
        }
    }
    
    private fun showConsoleTab() {
        binding.tabConsole.isSelected = true
        binding.tabResults.isSelected = false
        binding.layoutConsole.isVisible = true
        binding.layoutResults.isVisible = false
    }
    
    private fun showResultsTab() {
        binding.tabConsole.isSelected = false
        binding.tabResults.isSelected = true
        binding.layoutConsole.isVisible = false
        binding.layoutResults.isVisible = true
    }
    
}
