package com.sj.obd2app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
            
            // Check if there's an existing folder with data to migrate
            val oldFolderUri = AppSettings.getLogFolderUri(requireContext())
            if (oldFolderUri != null && oldFolderUri != uri.toString()) {
                showMigrationDialog(oldFolderUri, uri.toString())
            } else {
                AppSettings.setLogFolderUri(requireContext(), uri.toString())
                updateLogFolderLabel()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
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
    }

    override fun onResume() {
        super.onResume()
        profileAdapter.refresh()
        loadCurrentSettings()
        updateSaveButtonVisibility()
    }

    // ── Save/Discard ─────────────────────────────────────────────────────────

    private fun setupSaveButton() {
        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }
        updateSaveButtonVisibility()
    }

    private fun markAsChanged() {
        hasUnsavedChanges = true
        updateSaveButtonVisibility()
    }

    private fun updateSaveButtonVisibility() {
        binding.btnSaveSettings.visibility = if (hasUnsavedChanges) View.VISIBLE else View.GONE
    }

    private fun loadCurrentSettings() {
        val ctx = requireContext()
        
        binding.switchObdConnection.isChecked = AppSettings.isObdConnectionEnabled(ctx)
        binding.switchAutoConnect.isChecked = AppSettings.isAutoConnect(ctx)
        binding.switchLoggingEnabled.isChecked = AppSettings.isLoggingEnabled(ctx)
        binding.switchAutoShareLog.isChecked = AppSettings.isAutoShareLogEnabled(ctx)
        binding.switchBtLoggingEnabled.isChecked = AppSettings.isBtLoggingEnabled(ctx)
        
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

        val accelAvailable = com.sj.obd2app.sensors.AccelerometerSource.getInstance(ctx).isAvailable
        if (!accelAvailable) {
            binding.switchAccelerometerEnabled.isEnabled = false
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
    
    private fun showMigrationDialog(oldFolderUri: String, newFolderUri: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Migrate Data to New Folder?")
            .setMessage("Would you like to copy your settings, vehicle profiles, and dashboards from the old folder to the new folder?\n\nThis will not delete data from the old folder.")
            .setPositiveButton("Migrate") { _, _ ->
                // Set new folder first
                AppSettings.setLogFolderUri(requireContext(), newFolderUri)
                updateLogFolderLabel()
                
                // Perform migration
                migrateDataBetweenFolders(oldFolderUri, newFolderUri)
            }
            .setNegativeButton("Skip") { _, _ ->
                // Just set new folder without migration
                AppSettings.setLogFolderUri(requireContext(), newFolderUri)
                updateLogFolderLabel()
                Toast.makeText(requireContext(), "Folder changed. Old data not migrated.", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun migrateDataBetweenFolders(oldFolderUri: String, newFolderUri: String) {
        try {
            val ctx = requireContext()
            val oldUri = Uri.parse(oldFolderUri)
            val newUri = Uri.parse(newFolderUri)
            
            val oldRoot = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, oldUri)
            val newRoot = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, newUri)
            
            if (oldRoot == null || newRoot == null) {
                Toast.makeText(ctx, "Migration failed: Cannot access folders", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Find .obd directory in old folder
            val oldObdDir = oldRoot.findFile(".obd")
            if (oldObdDir == null || !oldObdDir.isDirectory) {
                Toast.makeText(ctx, "No data to migrate from old folder", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Create .obd directory in new folder
            var newObdDir = newRoot.findFile(".obd")
            if (newObdDir == null) {
                newObdDir = newRoot.createDirectory(".obd")
            }
            
            if (newObdDir == null) {
                Toast.makeText(ctx, "Migration failed: Cannot create .obd directory", Toast.LENGTH_SHORT).show()
                return
            }
            
            var filesCopied = 0
            
            // Copy all subdirectories and files
            oldObdDir.listFiles().forEach { oldItem ->
                if (oldItem.isDirectory) {
                    // Copy directory
                    var newSubDir = newObdDir.findFile(oldItem.name ?: "")
                    if (newSubDir == null) {
                        newSubDir = newObdDir.createDirectory(oldItem.name ?: "")
                    }
                    
                    if (newSubDir != null) {
                        oldItem.listFiles().forEach { oldFile ->
                            if (oldFile.isFile) {
                                copyFile(ctx, oldFile, newSubDir)
                                filesCopied++
                            }
                        }
                    }
                } else if (oldItem.isFile) {
                    // Copy file directly in .obd directory
                    copyFile(ctx, oldItem, newObdDir)
                    filesCopied++
                }
            }
            
            Toast.makeText(ctx, "Migration complete: $filesCopied file(s) copied", Toast.LENGTH_LONG).show()
            
            // Refresh profile list to show migrated profiles
            profileAdapter.refresh()
            
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Migration failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun copyFile(
        context: android.content.Context,
        sourceFile: androidx.documentfile.provider.DocumentFile,
        targetDir: androidx.documentfile.provider.DocumentFile
    ) {
        try {
            val fileName = sourceFile.name ?: return
            
            // Check if file already exists in target
            var targetFile = targetDir.findFile(fileName)
            if (targetFile == null) {
                // Create new file
                val mimeType = sourceFile.type ?: "application/octet-stream"
                targetFile = targetDir.createFile(mimeType, fileName)
            }
            
            if (targetFile != null) {
                // Copy content
                context.contentResolver.openInputStream(sourceFile.uri)?.use { input ->
                    // Use "wt" mode to truncate before writing — "w" alone does NOT truncate on Android 10+
                    context.contentResolver.openOutputStream(targetFile.uri, "wt")?.use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } catch (e: Exception) {
            // Log error but continue with other files
            android.util.Log.e("SettingsFragment", "Failed to copy file: ${sourceFile.name}", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
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