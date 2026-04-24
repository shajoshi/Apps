package com.sj.obd2app.ui.can

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sj.obd2app.can.CanBusScanner
import com.sj.obd2app.can.CanProfile
import com.sj.obd2app.can.CanProfileRepository
import com.sj.obd2app.databinding.FragmentCanReaderBinding
import com.sj.obd2app.databinding.ItemCanProfileBinding
import com.sj.obd2app.obd.BluetoothObd2Service
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.ui.attachNavOverflow
import kotlinx.coroutines.launch

/**
 * CAN Bus Reader screen: lists user-created [CanProfile] entries, allows adding/editing, and
 * drives the [CanBusScanner] engine via a Start/Stop button. Requires the ELM327 adapter to be
 * connected (via the Connect screen) before a scan can begin.
 */
class CanReaderFragment : Fragment() {

    private var _binding: FragmentCanReaderBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: CanProfileRepository
    private lateinit var adapter: CanProfileAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCanReaderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = CanProfileRepository.getInstance(requireContext())

        binding.topBarInclude.txtTopBarTitle.text = "CAN Bus Reader"
        attachNavOverflow(binding.topBarInclude.btnTopOverflow)

        adapter = CanProfileAdapter(
            onRowClick = { p -> openEdit(p.id) },
            onStarClick = { p ->
                repo.setDefault(p.id)
                AppSettings.setDefaultCanProfileId(requireContext(), p.id)
                Toast.makeText(requireContext(), "${p.name} set as default", Toast.LENGTH_SHORT).show()
                refresh()
            }
        )
        binding.rvCanProfiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCanProfiles.adapter = adapter

        binding.btnAddCanProfile.setOnClickListener { openEdit(null) }

        binding.btnStartCanScan.setOnClickListener { toggleScan() }

        // Observe live scan state.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CanBusScanner.state.collect { s -> renderScanState(s) }
            }
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ── Scan control ──────────────────────────────────────────────────────────

    private fun toggleScan() {
        when (val s = CanBusScanner.state.value) {
            is CanBusScanner.State.Idle,
            is CanBusScanner.State.Error -> startScan()
            is CanBusScanner.State.Running,
            is CanBusScanner.State.Starting -> CanBusScanner.stop()
            is CanBusScanner.State.Stopping -> { /* ignore — already stopping */ }
        }
    }

    private fun startScan() {
        val profile = CanProfileRepository.getInstance(requireContext()).getDefault()
        if (profile == null) {
            Toast.makeText(requireContext(), "Star a CAN profile first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (profile.selectedSignals.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "Profile '${profile.name}' has no signals selected — edit it first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val svc = Obd2ServiceProvider.getService()
        if (svc.connectionState.value != Obd2Service.ConnectionState.CONNECTED) {
            val hint = if (com.sj.obd2app.obd.ObdStateManager.isMockMode)
                "Connect the Mock OBD2 Adapter from the Connect screen first."
            else
                "Connect to the ELM327 adapter from the Connect screen first."
            Toast.makeText(requireContext(), hint, Toast.LENGTH_LONG).show()
            return
        }
        // Real mode additionally requires BluetoothObd2Service; mock path uses a synthetic source.
        if (!com.sj.obd2app.obd.ObdStateManager.isMockMode && svc !is BluetoothObd2Service) {
            Toast.makeText(
                requireContext(),
                "Real CAN scan requires a Bluetooth ELM327 adapter.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        CanBusScanner.start(requireContext(), profile)
    }

    private fun renderScanState(s: CanBusScanner.State) {
        when (s) {
            is CanBusScanner.State.Idle -> {
                binding.btnStartCanScan.text = "Start CAN Bus Scan"
                binding.btnStartCanScan.isEnabled = readyToStart()
                // tvScanStatus is refreshed by refresh() — leave the default text in place.
            }
            is CanBusScanner.State.Starting -> {
                binding.btnStartCanScan.text = "Starting…"
                binding.btnStartCanScan.isEnabled = false
                binding.tvScanStatus.text = "Starting CAN monitor…"
            }
            is CanBusScanner.State.Running -> {
                binding.btnStartCanScan.text = "Stop CAN Bus Scan"
                binding.btnStartCanScan.isEnabled = true
                val elapsedSec = ((System.currentTimeMillis() - s.startedAtMs) / 1000L).coerceAtLeast(1L)
                val fps = s.frames / elapsedSec
                binding.tvScanStatus.text = buildString {
                    append("Scanning '${s.profileName}' · ")
                    append("${s.frames} frames (~${fps}/s) · ")
                    append("${s.decoded} decoded · ${s.dropped} dropped")
                    if (s.lastFrameAgeMs > 500) append(" · silent ${s.lastFrameAgeMs} ms")
                }
            }
            is CanBusScanner.State.Stopping -> {
                binding.btnStartCanScan.text = "Stopping…"
                binding.btnStartCanScan.isEnabled = false
                binding.tvScanStatus.text = "Stopping CAN monitor…"
            }
            is CanBusScanner.State.Error -> {
                binding.btnStartCanScan.text = "Start CAN Bus Scan"
                binding.btnStartCanScan.isEnabled = readyToStart()
                binding.tvScanStatus.text = "Error: ${s.message}"
            }
        }
    }

    private fun readyToStart(): Boolean {
        val profile = CanProfileRepository.getInstance(requireContext()).getDefault()
            ?: return false
        if (profile.selectedSignals.isEmpty()) return false
        val svc = Obd2ServiceProvider.getService()
        if (svc.connectionState.value != Obd2Service.ConnectionState.CONNECTED) return false
        return com.sj.obd2app.obd.ObdStateManager.isMockMode || svc is BluetoothObd2Service
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refresh() {
        val profiles = repo.getAll()
        adapter.submit(profiles)
        binding.tvEmptyProfiles.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE

        // If a scan is in-flight, let renderScanState() own the status/button.
        if (CanBusScanner.state.value !is CanBusScanner.State.Idle &&
            CanBusScanner.state.value !is CanBusScanner.State.Error
        ) return

        val defaultProfile = profiles.firstOrNull { it.isDefault }
        binding.btnStartCanScan.isEnabled = readyToStart()
        binding.tvScanStatus.text = when {
            profiles.isEmpty() -> "No CAN profiles yet. Create one to begin."
            defaultProfile == null -> "Star one profile to use as default before scanning."
            defaultProfile.selectedSignals.isEmpty() ->
                "Profile '${defaultProfile.name}' has no signals — edit it to select signals."
            !readyToStart() ->
                "Default: ${defaultProfile.name} · ${defaultProfile.selectedSignals.size} signals. " +
                    "Connect the ELM327 adapter to enable Start."
            else ->
                "Ready · ${defaultProfile.name} · ${defaultProfile.selectedSignals.size} signals"
        }
    }

    private fun openEdit(profileId: String?) {
        CanProfileEditSheet.newInstance(profileId).also { sheet ->
            sheet.onSaved = { refresh() }
            sheet.show(parentFragmentManager, "can_profile_edit")
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private inner class CanProfileAdapter(
        val onRowClick: (CanProfile) -> Unit,
        val onStarClick: (CanProfile) -> Unit
    ) : RecyclerView.Adapter<CanProfileAdapter.VH>() {

        private var items: List<CanProfile> = emptyList()

        fun submit(list: List<CanProfile>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemCanProfileBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        override fun getItemCount(): Int = items.size

        inner class VH(private val b: ItemCanProfileBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(profile: CanProfile) {
                b.tvCanProfileName.text = profile.name
                b.tvCanProfileDetails.text = buildString {
                    append(profile.dbcFileName)
                    append(" · ")
                    append("${profile.selectedSignals.size} signals")
                    append(" · ")
                    append("${profile.samplingMs} ms")
                    if (profile.recordRawFrames) append(" · REC")
                }
                b.btnStarDefault.setImageResource(
                    if (profile.isDefault) android.R.drawable.btn_star_big_on
                    else android.R.drawable.btn_star_big_off
                )
                b.btnStarDefault.setColorFilter(
                    if (profile.isDefault) 0xFFFFD54F.toInt() else 0xFF888888.toInt()
                )
                b.btnStarDefault.setOnClickListener { onStarClick(profile) }
                b.btnEditCanProfile.setOnClickListener { onRowClick(profile) }
                b.root.setOnClickListener { onRowClick(profile) }
            }
        }
    }
}
