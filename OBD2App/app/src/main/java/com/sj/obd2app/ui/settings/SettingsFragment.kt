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

        setupProfileList()
        setupPollingSliders()
        setupConnectionToggles()
        setupDataLogging()
    }

    override fun onResume() {
        super.onResume()
        profileAdapter.refresh()
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
                if (fromUser) AppSettings.setGlobalPollingDelayMs(ctx, ms)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.seekbarCommand.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val ms = seekToCommandMs(progress)
                binding.tvCommandValue.text = "${ms}ms"
                if (fromUser) AppSettings.setGlobalCommandDelayMs(ctx, ms)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ── Connection Toggles ────────────────────────────────────────────────────

    private fun setupConnectionToggles() {
        val ctx = requireContext()

        binding.switchObdConnection.isChecked = AppSettings.isObdConnectionEnabled(ctx)
        binding.switchObdConnection.setOnCheckedChangeListener { _, checked ->
            AppSettings.setObdConnectionEnabled(ctx, checked)
        }

        binding.switchAutoConnect.isChecked = AppSettings.isAutoConnect(ctx)
        binding.switchAutoConnect.setOnCheckedChangeListener { _, checked ->
            AppSettings.setAutoConnect(ctx, checked)
        }

        binding.switchLoggingEnabled.isChecked = AppSettings.isLoggingEnabled(ctx)
        binding.switchLoggingEnabled.setOnCheckedChangeListener { _, checked ->
            AppSettings.setLoggingEnabled(ctx, checked)
        }

        binding.switchAutoShareLog.isChecked = AppSettings.isAutoShareLogEnabled(ctx)
        binding.switchAutoShareLog.setOnCheckedChangeListener { _, checked ->
            AppSettings.setAutoShareLogEnabled(ctx, checked)
        }

        val accelAvailable = com.sj.obd2app.sensors.AccelerometerSource.getInstance(ctx).isAvailable
        if (!accelAvailable) {
            binding.switchAccelerometerEnabled.isChecked = false
            binding.switchAccelerometerEnabled.isEnabled = false
            binding.tvAccelerometerLabel.text = "Log accelerometer data (no sensor)"
            binding.tvAccelerometerLabel.setTextColor(android.graphics.Color.parseColor("#888888"))
        } else {
            binding.switchAccelerometerEnabled.isChecked = AppSettings.isAccelerometerEnabled(ctx)
            binding.switchAccelerometerEnabled.setOnCheckedChangeListener { _, checked ->
                AppSettings.setAccelerometerEnabled(ctx, checked)
            }
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