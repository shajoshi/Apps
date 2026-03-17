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

    /** Polling seekbar: maps 0..999 → 2ms..2000ms (step 2ms) */
    private fun seekToPollingMs(progress: Int): Long = (2L + progress * 2L)
    private fun pollingMsToSeek(ms: Long): Int = ((ms - 2L) / 2L).toInt().coerceIn(0, 999)

    /** Command seekbar: maps 0..249 → 2ms..500ms (step 2ms) */
    private fun seekToCommandMs(progress: Int): Long = (2L + progress * 2L)
    private fun commandMsToSeek(ms: Long): Int = ((ms - 2L) / 2L).toInt().coerceIn(0, 249)

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
        setupPollingSliders()
        setupConnectionToggles()
        setupDataLogging()
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
        
        // Load current values without marking as changed
        val pollingMs = AppSettings.getGlobalPollingDelayMs(ctx)
        binding.seekbarPolling.progress = pollingMsToSeek(pollingMs)
        binding.tvPollingValue.text = "${pollingMs}ms"

        val commandMs = AppSettings.getGlobalCommandDelayMs(ctx)
        binding.seekbarCommand.progress = commandMsToSeek(commandMs)
        binding.tvCommandValue.text = "${commandMs}ms"

        binding.switchObdConnection.isChecked = AppSettings.isObdConnectionEnabled(ctx)
        binding.switchAutoConnect.isChecked = AppSettings.isAutoConnect(ctx)
        binding.switchLoggingEnabled.isChecked = AppSettings.isLoggingEnabled(ctx)
        binding.switchAutoShareLog.isChecked = AppSettings.isAutoShareLogEnabled(ctx)
        
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
            settings.globalPollingDelayMs = seekToPollingMs(binding.seekbarPolling.progress)
            settings.globalCommandDelayMs = seekToCommandMs(binding.seekbarCommand.progress)
            settings.obdConnectionEnabled = newObdEnabled
            settings.autoConnect = binding.switchAutoConnect.isChecked
            settings.loggingEnabled = binding.switchLoggingEnabled.isChecked
            settings.autoShareLog = binding.switchAutoShareLog.isChecked
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
        
        // Disconnect current service
        com.sj.obd2app.obd.Obd2ServiceProvider.getService().disconnect()
        
        // Update mock flag
        com.sj.obd2app.obd.Obd2ServiceProvider.useMock = !enableObdConnection
        
        // Initialize mock service if needed
        if (!enableObdConnection) {
            com.sj.obd2app.obd.Obd2ServiceProvider.initMock(ctx)
        }
        
        // Notify ConnectViewModel to update UI if it exists
        try {
            val connectViewModel = androidx.lifecycle.ViewModelProvider(requireActivity())[com.sj.obd2app.ui.connect.ConnectViewModel::class.java]
            connectViewModel.updateMockMode()
            
            // Also force a refresh if ConnectFragment is the current page
            val mainActivity = requireActivity() as? com.sj.obd2app.MainActivity
            val viewPager = mainActivity?.findViewById<androidx.viewpager2.widget.ViewPager2>(com.sj.obd2app.R.id.main_view_pager)
            if (viewPager?.currentItem == com.sj.obd2app.MainPagerAdapter.PAGE_CONNECT) {
                try {
                    // Get the current ConnectFragment instance
                    val connectFragment = mainActivity.supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
                    if (connectFragment is com.sj.obd2app.ui.connect.ConnectFragment) {
                        connectFragment.refreshUI()
                    }
                } catch (e: Exception) {
                    // ConnectFragment may not be created yet, that's fine
                }
            }
        } catch (e: Exception) {
            // ConnectFragment may not be created yet, that's fine
        }
        
        // Connect with new mode (only for real OBD mode)
        if (enableObdConnection) {
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

    // ── OBD2 Polling Sliders ─────────────────────────────────────────────────

    private fun setupPollingSliders() {
        val ctx = requireContext()

        val pollingMs = AppSettings.getGlobalPollingDelayMs(ctx)
        binding.seekbarPolling.progress = pollingMsToSeek(pollingMs)
        binding.tvPollingValue.text = "${pollingMs}ms"

        val commandMs = AppSettings.getGlobalCommandDelayMs(ctx)
        binding.seekbarCommand.progress = commandMsToSeek(commandMs)
        binding.tvCommandValue.text = "${commandMs}ms"

        binding.seekbarPolling.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val ms = seekToPollingMs(progress)
                binding.tvPollingValue.text = "${ms}ms"
                if (fromUser) markAsChanged()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.seekbarCommand.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val ms = seekToCommandMs(progress)
                binding.tvCommandValue.text = "${ms}ms"
                if (fromUser) markAsChanged()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ── Connection Toggles ────────────────────────────────────────────────────

    private fun setupConnectionToggles() {
        val ctx = requireContext()

        binding.switchObdConnection.setOnCheckedChangeListener { _, _ -> markAsChanged() }
        binding.switchAutoConnect.setOnCheckedChangeListener { _, _ -> markAsChanged() }
        binding.switchLoggingEnabled.setOnCheckedChangeListener { _, _ -> markAsChanged() }
        binding.switchAutoShareLog.setOnCheckedChangeListener { _, _ -> markAsChanged() }

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
}