package com.sj.obd2app.ui.connect

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sj.obd2app.databinding.FragmentConnectBinding
import com.sj.obd2app.databinding.ItemDeviceBinding
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.ui.attachNavOverflow

/**
 * Connect screen — lists paired and discovered Bluetooth devices,
 * allows scan for nearby devices via the icon in the title bar,
 * and highlights the currently connected device row.
 */
class ConnectFragment : Fragment() {

    private var _binding: FragmentConnectBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: ConnectViewModel
    private var pairedAdapter: DeviceAdapter? = null
    private var discoveredAdapter: DeviceAdapter? = null
    private var receiverRegistered = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel = ViewModelProvider(this)[ConnectViewModel::class.java]
        _binding = FragmentConnectBinding.inflate(inflater, container, false)

        attachNavOverflow(binding.btnTopOverflow)

        binding.recyclerviewDevices.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerviewDiscovered.layoutManager = LinearLayoutManager(requireContext())

        // NOTE: loadPairedDevices() is called after observers are registered below
        //       so that LiveData delivery is guaranteed to reach the adapter.
        if (viewModel.isMockMode) {
            binding.btnScan.visibility = View.GONE
            binding.labelDiscovered.visibility = View.GONE
            binding.recyclerviewDiscovered.visibility = View.GONE

            val mockAdapter = MockDeviceAdapter { viewModel.connectMock() }
            binding.recyclerviewDevices.adapter = mockAdapter
            viewModel.mockDeviceNames.observe(viewLifecycleOwner) { names ->
                mockAdapter.submitList(names)
            }

        } else {
            // OBD devices list (filtered — likely OBD adapters)
            pairedAdapter = DeviceAdapter(
                onDeviceClick = { device -> viewModel.connectToDevice(device, requireContext()) },
                onDisconnectClick = { viewModel.disconnect() }
            )
            binding.recyclerviewDevices.adapter = pairedAdapter
            viewModel.obdDevices.observe(viewLifecycleOwner) { devices ->
                pairedAdapter?.submitList(devices)
                binding.labelPaired.text = if (devices.isEmpty()) "Paired devices" else "OBD Adapters"
            }

            // "Other devices" section — non-OBD paired devices
            discoveredAdapter = DeviceAdapter(
                onDeviceClick = { device -> viewModel.connectToDevice(device, requireContext()) },
                onDisconnectClick = { viewModel.disconnect() }
            )
            binding.recyclerviewDiscovered.adapter = discoveredAdapter
            viewModel.otherDevices.observe(viewLifecycleOwner) { devices ->
                if (devices.isNotEmpty()) {
                    binding.labelDiscovered.text = "Other paired devices"
                    binding.labelDiscovered.visibility = View.VISIBLE
                    binding.recyclerviewDiscovered.visibility = View.VISIBLE
                } else {
                    binding.labelDiscovered.visibility = View.GONE
                    binding.recyclerviewDiscovered.visibility = View.GONE
                }
                discoveredAdapter?.submitList(devices)
            }

            // Highlight the connected row in both lists
            viewModel.connectedDeviceMac.observe(viewLifecycleOwner) { mac ->
                pairedAdapter?.setConnectedDevice(mac)
                discoveredAdapter?.setConnectedDevice(mac)
            }

            // Yellow "CONNECTING…" row while BT handshake is in progress
            viewModel.connectingDeviceMac.observe(viewLifecycleOwner) { mac ->
                pairedAdapter?.setConnectingDevice(mac)
                discoveredAdapter?.setConnectingDevice(mac)
            }

            // Scan icon click
            binding.btnScan.setOnClickListener {
                registerDiscoveryReceiver()
                viewModel.startScan()
            }

            viewModel.isScanning.observe(viewLifecycleOwner) { scanning ->
                binding.btnScan.alpha = if (scanning) 0.4f else 1.0f
                binding.btnScan.isEnabled = !scanning
            }

            // Load paired devices AFTER observers are registered
            viewModel.loadPairedDevices(requireContext())

            // Auto-connect to last device if the setting is enabled
            if (AppSettings.isAutoConnect(requireContext())) {
                viewModel.tryAutoConnect(requireContext())
            }
        }

        viewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            binding.textConnectStatus.text = status
            // Colour the status label by state
            val service = Obd2ServiceProvider.getService()
            binding.textConnectStatus.setTextColor(
                when (service.connectionState.value) {
                    Obd2Service.ConnectionState.CONNECTING   -> android.graphics.Color.parseColor("#FFC107")
                    Obd2Service.ConnectionState.CONNECTED    -> android.graphics.Color.parseColor("#4CAF50")
                    Obd2Service.ConnectionState.ERROR        -> android.graphics.Color.parseColor("#CF6679")
                    else                                     -> android.graphics.Color.parseColor("#888888")
                }
            )
        }

        // Connection log panel — show whenever there are log lines
        viewModel.connectionLog.observe(viewLifecycleOwner) { lines ->
            if (lines.isEmpty()) {
                binding.panelConnectionLog.visibility = View.GONE
            } else {
                binding.panelConnectionLog.visibility = View.VISIBLE
                binding.textConnectionLog.text = lines.joinToString("\n")
                // Auto-scroll to bottom so latest message is visible
                binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
            }
        }

        // Red "FAILED" row when a connection attempt fails
        viewModel.errorDeviceMac.observe(viewLifecycleOwner) { mac ->
            pairedAdapter?.setErrorDevice(mac)
            discoveredAdapter?.setErrorDevice(mac)
        }

        viewModel.isConnected.observe(viewLifecycleOwner) { connected ->
            binding.btnDisconnect.visibility = if (connected) View.VISIBLE else View.GONE
            if (connected) {
                // Navigate to default dashboard or layout list after successful connection
                (activity as? com.sj.obd2app.MainActivity)?.onObd2Connected()
            }
        }

        binding.btnDisconnect.setOnClickListener {
            viewModel.disconnect()
        }

        return binding.root
    }

    /**
     * Reload paired devices on every resume — handles the case where the user
     * granted permissions or toggled Bluetooth while on another screen.
     */
    override fun onResume() {
        super.onResume()
        if (!viewModel.isMockMode) {
            viewModel.loadPairedDevices(requireContext())
        }
    }

    private fun registerDiscoveryReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        requireContext().registerReceiver(viewModel.discoveryReceiver, filter)
        receiverRegistered = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (receiverRegistered) {
            try { requireContext().unregisterReceiver(viewModel.discoveryReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        viewModel.stopScan()
        pairedAdapter = null
        discoveredAdapter = null
        _binding = null
    }

    private class MockDeviceAdapter(
        private val onClick: () -> Unit
    ) : RecyclerView.Adapter<MockDeviceAdapter.MockVH>() {

        private val items = mutableListOf<String>()

        fun submitList(names: List<String>) {
            items.clear(); items.addAll(names); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MockVH {
            return MockVH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: MockVH, position: Int) {
            holder.binding.textDeviceName.text = items[position]
            holder.binding.textDeviceAddress.text = "00:00:00:00:00:00"
            holder.itemView.setOnClickListener { onClick() }
        }

        override fun getItemCount() = items.size
        class MockVH(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
