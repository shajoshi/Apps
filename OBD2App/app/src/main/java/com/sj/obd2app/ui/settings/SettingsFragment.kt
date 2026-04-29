package com.sj.obd2app.ui.settings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sj.obd2app.R
import com.sj.obd2app.databinding.FragmentSettingsBinding
import com.sj.obd2app.databinding.ItemVehicleProfileBinding
import com.sj.obd2app.obd.MockObd2Service
import com.sj.obd2app.obd.MockDiscoveryScenario
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.obd.ObdStateManager
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.settings.VehicleProfile
import com.sj.obd2app.settings.VehicleProfileEditSheet
import com.sj.obd2app.settings.VehicleProfileRepository
import com.sj.obd2app.storage.ExportImportManager
import com.sj.obd2app.ui.attachNavOverflow

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: VehicleProfileRepository
    private lateinit var profileAdapter: ProfileAdapter
    private var hasUnsavedChanges = false

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            
            AppSettings.setLogFolderUri(requireContext(), uri.toString())
            updateLogFolderLabel()
        }
    }

    private val exportFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Don't take persistable permissions for one-time export operations
            val exportSettings = binding.checkboxExportSettings.isChecked
            val exportProfiles = binding.checkboxExportProfiles.isChecked
            val exportLayouts = binding.checkboxExportLayouts.isChecked
            val exportCanProfiles = binding.checkboxExportCanProfiles.isChecked
            
            ExportImportManager.exportData(
                requireContext(),
                uri,
                exportSettings,
                exportProfiles,
                exportLayouts,
                exportCanProfiles
            )
        }
    }

    private val importFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Don't take persistable permissions for one-time import operations
            val result = ExportImportManager.importData(requireContext(), uri)
            
            // Refresh UI after import
            if (result.success) {
                profileAdapter.refresh()
                loadCurrentSettings()
                AppSettings.invalidateCache()
                com.sj.obd2app.can.CanProfileRepository.getInstance(requireContext()).invalidateCache()
            }
        }
    }

    private val importZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val result = ExportImportManager.importZip(requireContext(), uri)

            if (result.success) {
                profileAdapter.refresh()
                loadCurrentSettings()
                AppSettings.invalidateCache()
                com.sj.obd2app.can.CanProfileRepository.getInstance(requireContext()).invalidateCache()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        
        // Show save button only on settings screen
        binding.topBarInclude.btnTopSave.visibility = View.VISIBLE
        
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = VehicleProfileRepository.getInstance(requireContext())

        binding.topBarInclude.txtTopBarTitle.text = getString(R.string.menu_settings)
        attachNavOverflow(binding.topBarInclude.btnTopOverflow)

        setupSaveButton()
        setupProfileList()
        setupConnectionToggles()
        setupDataLogging()
        setupDebugSettings()
        setupExportImport()
        
        profileAdapter.refresh()
        loadCurrentSettings()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (hasUnsavedChanges) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Unsaved Settings")
                            .setMessage("You have unsaved changes. Save now?")
                            .setPositiveButton("Save") { _, _ ->
                                saveSettings()
                                isEnabled = false
                                requireActivity().onBackPressedDispatcher.onBackPressed()
                            }
                            .setNegativeButton("Discard") { _, _ ->
                                hasUnsavedChanges = false
                                isEnabled = false
                                requireActivity().onBackPressedDispatcher.onBackPressed()
                            }
                            .setNeutralButton("Cancel", null)
                            .show()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (ObdStateManager.isConnected) {
            AlertDialog.Builder(requireContext())
                .setTitle("Active Connection")
                .setMessage("Disconnect before changing settings.")
                .setPositiveButton("OK") { _, _ ->
                    (activity as? com.sj.obd2app.MainActivity)
                        ?.navigateToPage(com.sj.obd2app.MainPagerAdapter.PAGE_CONNECT)
                }
                .setCancelable(false)
                .show()
        }
    }

    // ── Save/Discard ─────────────────────────────────────────────────────────

    private fun setupSaveButton() {
        binding.topBarInclude.btnTopSave.setOnClickListener {
            saveSettings()
        }
        // Top save button is always visible, no need to hide/show
    }

    private fun markAsChanged() {
        hasUnsavedChanges = true
        // No need to update visibility - top save button is always visible
    }

    private fun updateSaveButtonVisibility() {
        // No longer needed - top save button is always visible
    }

    private fun loadCurrentSettings() {
        val ctx = requireContext()
        
        binding.switchObdConnection.isChecked = AppSettings.isObdConnectionEnabled(ctx)
        binding.switchAutoConnect.isChecked = AppSettings.isAutoConnect(ctx)
        binding.switchLoggingEnabled.isChecked = AppSettings.isLoggingEnabled(ctx)
        binding.switchAutoShareLog.isChecked = AppSettings.isAutoShareLogEnabled(ctx)
        binding.switchBtLoggingEnabled.isChecked = AppSettings.isBtLoggingEnabled(ctx)
        binding.switchCanBusLogging.isChecked = AppSettings.isCanBusLoggingEnabled(ctx)
        binding.switchIgnoreCachedPids.isChecked = AppSettings.isIgnoreCachedPidsEnabled(ctx)
        
        val accelAvailable = com.sj.obd2app.sensors.AccelerometerSource.getInstance(ctx).isAvailable
        if (accelAvailable) {
            binding.switchAccelerometerEnabled.isChecked = AppSettings.isAccelerometerEnabled(ctx)
        }
    }

    private fun saveSettings() {
        val ctx = requireContext()
        
        // Check if OBD connection setting is changing
        val oldObdEnabled = AppSettings.isObdConnectionEnabled(ctx)
        val newObdEnabled = binding.switchObdConnection.isChecked
        val obdSettingChanged = oldObdEnabled != newObdEnabled
        
        AppSettings.updatePendingSettings(ctx) { settings ->
            settings.obdConnectionEnabled = newObdEnabled
            settings.autoConnect = binding.switchAutoConnect.isChecked
            settings.loggingEnabled = binding.switchLoggingEnabled.isChecked
            settings.autoShareLog = binding.switchAutoShareLog.isChecked
            settings.btLoggingEnabled = binding.switchBtLoggingEnabled.isChecked
            settings.accelerometerEnabled = binding.switchAccelerometerEnabled.isChecked
            settings.useCanBusLogging = binding.switchCanBusLogging.isChecked
            settings.ignoreCachedPids = binding.switchIgnoreCachedPids.isChecked
        }
        
        AppSettings.savePendingSettings(ctx)
        hasUnsavedChanges = false
        updateSaveButtonVisibility()
        
        // Restart OBD service if setting changed
        if (obdSettingChanged) {
            restartObdService(newObdEnabled)
            Toast.makeText(ctx, "Settings saved - OBD mode updated", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun restartObdService(enableObdConnection: Boolean) {
        val ctx = requireContext()
        
        // Disconnect current service to force service refresh
        com.sj.obd2app.obd.Obd2ServiceProvider.getService().disconnect()
        
        // Switch mode using centralized state manager
        val newMode = if (enableObdConnection) ObdStateManager.Mode.REAL else ObdStateManager.Mode.MOCK
        ObdStateManager.switchMode(newMode)
        
        // Initialize mock service if needed
        if (ObdStateManager.isMockMode) {
            com.sj.obd2app.obd.Obd2ServiceProvider.initMock(ctx)
        }
        
        // Connect with new mode (only for real OBD mode)
        if (enableObdConnection) {
            // Get the refreshed service (now real OBD service) and connect
            com.sj.obd2app.obd.Obd2ServiceProvider.getService().connect(null)
        }
        // Note: For mock mode, user must manually click "Mock OBD2 Adapter" to connect
    }

    // ── Vehicle Profiles ─────────────────────────────────────────────────────

    private fun setupProfileList() {
        profileAdapter = ProfileAdapter(
            onEditClick = { profile ->
                VehicleProfileEditSheet.newInstance(profile.id).also { sheet ->
                    sheet.onSaved = { profileAdapter.refresh() }
                    sheet.show(parentFragmentManager, null)
                }
            },
            onRowClick = { profile ->
                repo.setActive(profile.id)
                profileAdapter.refresh()
                Toast.makeText(requireContext(), "${profile.name} set as active", Toast.LENGTH_SHORT).show()
            }
        )
        binding.rvProfiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvProfiles.adapter = profileAdapter
        profileAdapter.refresh()

        binding.btnAddProfile.setOnClickListener {
            VehicleProfileEditSheet.newInstance().also { sheet ->
                sheet.onSaved = { profileAdapter.refresh() }
                sheet.show(parentFragmentManager, null)
            }
        }
    }

    // ── Connection Toggles ────────────────────────────────────────────────────

    private fun setupConnectionToggles() {
        val ctx = requireContext()

        binding.switchObdConnection.setOnCheckedChangeListener { _, _ -> markAsChanged() }
        binding.switchAutoConnect.setOnCheckedChangeListener { _, _ -> markAsChanged() }
        binding.switchLoggingEnabled.setOnCheckedChangeListener { _, _ -> markAsChanged() }
        binding.switchAutoShareLog.setOnCheckedChangeListener { _, _ -> markAsChanged() }
        binding.switchBtLoggingEnabled.setOnCheckedChangeListener { _, _ -> markAsChanged() }
        binding.switchCanBusLogging.setOnCheckedChangeListener { _, _ -> markAsChanged() }
        binding.switchIgnoreCachedPids.setOnCheckedChangeListener { _, _ -> markAsChanged() }

        val accelAvailable = com.sj.obd2app.sensors.AccelerometerSource.getInstance(ctx).isAvailable
        if (!accelAvailable) {
            binding.switchAccelerometerEnabled.isChecked = false
            binding.tvAccelerometerLabel.text = "Log accelerometer data (no sensor)"
            binding.tvAccelerometerLabel.setTextColor(android.graphics.Color.parseColor("#888888"))
        } else {
            binding.switchAccelerometerEnabled.setOnCheckedChangeListener { _, _ -> markAsChanged() }
        }
    }

    // ── Data Logging ──────────────────────────────────────────────────────────

    private fun setupDataLogging() {
        updateLogFolderLabel()

        binding.btnChangeLogFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        // Auto-share switch handled in setupConnectionToggles()
    }

    private fun updateLogFolderLabel() {
        val uriStr = AppSettings.getLogFolderUri(requireContext())
        binding.tvLogFolderPath.text = if (uriStr != null) {
            Uri.parse(uriStr).lastPathSegment ?: uriStr
        } else {
            "Downloads (default)"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Hide save button when leaving settings screen
        binding?.topBarInclude?.btnTopSave?.visibility = View.GONE
        _binding = null
    }

    // ── Profile RecyclerView Adapter ──────────────────────────────────────────

    private inner class ProfileAdapter(
        private val onEditClick: (VehicleProfile) -> Unit,
        private val onRowClick: (VehicleProfile) -> Unit
    ) : RecyclerView.Adapter<ProfileAdapter.VH>() {

        private var profiles: List<VehicleProfile> = emptyList()
        private var activeId: String? = null

        fun refresh() {
            profiles = repo.getAll()
            activeId = AppSettings.getActiveProfileId(requireContext())
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemVehicleProfileBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(profiles[position])
        }

        override fun getItemCount() = profiles.size

        inner class VH(private val b: ItemVehicleProfileBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(profile: VehicleProfile) {
                b.tvProfileName.text = profile.name
                b.tvProfileDetails.text = buildString {
                    append(profile.fuelType.displayName)
                    append(" · ${profile.tankCapacityL.toInt()}L")
                    if (profile.enginePowerBhp > 0f) append(" · ${profile.enginePowerBhp.toInt()} BHP")
                }
                val isActive = profile.id == activeId
                b.tvActiveBadge.visibility = if (isActive) View.VISIBLE else View.GONE
                b.root.setCardBackgroundColor(
                    if (isActive) 0xFF1A2E3E.toInt() else 0xFF16213E.toInt()
                )
                b.btnEditProfile.setOnClickListener { onEditClick(profile) }
                b.root.setOnClickListener { onRowClick(profile) }
            }
        }
    }

    // ── Debug Settings (Mock Mode Only) ───────────────────────────────────────

    private fun setupDebugSettings() {
        // Only show debug section if mock mode is available
        val mockService = MockObd2Service.getInstance()
        if (!mockService.isEnhancedModeAvailable()) {
            binding.cardDebugSettings.visibility = View.GONE
            return
        }
        
        // Show debug section in mock mode
        binding.cardDebugSettings.visibility = View.VISIBLE
        
        // Update current scenario display
        updateScenarioDisplay()
        
        // Handle scenario change button
        binding.btnChangeScenario.setOnClickListener {
            showScenarioSelector()
        }
    }
    
    private fun setupExportImport() {
        // Export button
        binding.btnExportData.setOnClickListener {
            exportFolderLauncher.launch(null)
        }
        
        // Import button
        binding.btnImportData.setOnClickListener {
            importFolderLauncher.launch(null)
        }

        binding.btnImportZip.setOnClickListener {
            importZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
    }
    
    private fun updateScenarioDisplay() {
        val mockService = MockObd2Service.getInstance()
        val currentHeader = mockService.getCurrentHeader()
        val currentPids = mockService.getCurrentHeaderPids()
        
        // Determine current scenario based on header and PIDs
        val scenarioName = when (currentHeader) {
            "760" -> "Jaguar XF"
            "7E4" -> "Toyota Hybrid"
            "7E1" -> "Mixed Headers"
            else -> "Custom"
        }
        
        binding.tvCurrentScenario.text = buildString {
            append(scenarioName)
            append(" (Header: $currentHeader, ")
            append("${currentPids.size} PIDs)")
        }
    }
    
    private fun showScenarioSelector() {
        val scenarios = MockDiscoveryScenario.values()
        val scenarioNames = scenarios.map { it.displayName }.toTypedArray()
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Discovery Scenario")
            .setItems(scenarioNames) { _, which ->
                val selectedScenario = scenarios[which]
                applyScenario(selectedScenario)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun applyScenario(scenario: MockDiscoveryScenario) {
        val mockService = MockObd2Service.getInstance()
        mockService.setTestScenario(scenario)
        
        updateScenarioDisplay()
        
        Toast.makeText(
            requireContext(),
            "Applied scenario: ${scenario.displayName}",
            Toast.LENGTH_SHORT
        ).show()
    }
}