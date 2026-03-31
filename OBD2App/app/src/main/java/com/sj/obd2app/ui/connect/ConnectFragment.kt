package com.sj.obd2app.ui.connect

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sj.obd2app.R
import com.sj.obd2app.databinding.FragmentConnectBinding
import com.sj.obd2app.databinding.ItemDeviceBinding
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.obd.ObdStateManager
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.ui.attachNavOverflow
import kotlinx.coroutines.launch

/**
 * Connect screen — lists paired and discovered Bluetooth devices,
 * allows scan for nearby devices via the icon in the title bar,
 * and highlights the currently connected device row.
 */
class ConnectFragment : Fragment() {

    private var _binding: FragmentConnectBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: ConnectViewModel
    private var sectionedAdapter: SectionedDeviceAdapter? = null
    private var receiverRegistered = false
    private var wasScanning = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel = ViewModelProvider(this)[ConnectViewModel::class.java]
        _binding = FragmentConnectBinding.inflate(inflater, container, false)

        attachNavOverflow(binding.btnTopOverflow)

        binding.recyclerviewDevices.layoutManager = LinearLayoutManager(requireContext())

        // Set up UI based on current mock mode
        setupConnectUI(ObdStateManager.isMockMode)
        
        // Observe mock mode changes from centralized state manager
        viewLifecycleOwner.lifecycleScope.launch {
            ObdStateManager.mode.collect { mode ->
                val isMock = mode == ObdStateManager.Mode.MOCK
                setupConnectUI(isMock)
                // Reload devices when mode changes
                viewModel.loadPairedDevices(requireContext())
            }
        }
        
        // Observe connection state from centralized state manager
        viewLifecycleOwner.lifecycleScope.launch {
            ObdStateManager.connectionState.collect { state ->
                // Update UI based on connection state
                when (state) {
                    ObdStateManager.ConnectionState.CONNECTING -> {
                        binding.textConnectStatus.text = "Connecting…"
                        binding.textConnectStatus.setTextColor(android.graphics.Color.parseColor("#FFC107"))
                    }
                    ObdStateManager.ConnectionState.CONNECTED -> {
                        binding.textConnectStatus.text = "Connected"
                        binding.textConnectStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                        binding.btnDisconnect.visibility = View.VISIBLE
                    }
                    ObdStateManager.ConnectionState.DISCONNECTED -> {
                        binding.textConnectStatus.text = "Disconnected"
                        binding.textConnectStatus.setTextColor(android.graphics.Color.parseColor("#888888"))
                        binding.btnDisconnect.visibility = View.GONE
                    }
                    ObdStateManager.ConnectionState.ERROR -> {
                        binding.textConnectStatus.text = "Error"
                        binding.textConnectStatus.setTextColor(android.graphics.Color.parseColor("#CF6679"))
                    }
                }
            }
        }
        
        // Observe mock device names (only once)
        viewModel.mockDeviceNames.observe(viewLifecycleOwner) { names ->
            // Update adapter if it's a mock adapter
            val currentAdapter = binding.recyclerviewDevices.adapter as? MockDeviceAdapter
            currentAdapter?.submitList(names)
        }

        return binding.root
    }
    
    fun refreshUI() {
        setupConnectUI(viewModel.currentMockMode)
        viewModel.loadPairedDevices(requireContext())
    }
    
    private fun setupConnectUI(isMock: Boolean) {
        // Clear existing adapter but keep observers
        binding.recyclerviewDevices.adapter = null
        sectionedAdapter = null
        
        if (isMock) {
            binding.btnScan.visibility = View.GONE
            binding.panelForceBle.visibility = View.GONE

            val mockAdapter = MockDeviceAdapter { 
                viewModel.connectMock() 
            }
            binding.recyclerviewDevices.adapter = mockAdapter
            // Set initial data immediately
            viewModel.mockDeviceNames.value?.let { names ->
                mockAdapter.submitList(names)
            }

        } else {
            binding.btnScan.visibility = View.VISIBLE
            binding.panelForceBle.visibility = View.VISIBLE

            // Setup Force BLE toggle
            setupForceBleToggle()

            // Single sectioned adapter for all devices
            sectionedAdapter = SectionedDeviceAdapter(
                onDeviceClick = { deviceInfo -> 
                    // Check if we should show warning dialog
                    if (AppSettings.isForceBleConnection(requireContext()) && 
                        deviceInfo.device.type == android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                        showBleWarningDialog(deviceInfo)
                    } else {
                        viewModel.connectToDevice(deviceInfo, requireContext())
                    }
                },
                onDisconnectClick = { viewModel.disconnect() }
            )
            binding.recyclerviewDevices.adapter = sectionedAdapter
            
            // Observe unified device list
            viewModel.allDevices.observe(viewLifecycleOwner) { devices ->
                sectionedAdapter?.submitList(devices)
            }

            // Highlight the connected row
            viewModel.connectedDeviceMac.observe(viewLifecycleOwner) { mac ->
                sectionedAdapter?.setConnectedDevice(mac)
            }

            // Yellow "CONNECTING…" row while BT handshake is in progress
            viewModel.connectingDeviceMac.observe(viewLifecycleOwner) { mac ->
                sectionedAdapter?.setConnectingDevice(mac)
            }

            // Scan icon click
            binding.btnScan.setOnClickListener {
                registerDiscoveryReceiver()
                viewModel.startScan()
            }

            viewModel.isScanning.observe(viewLifecycleOwner) { scanning ->
                if (scanning && !wasScanning) {
                    Toast.makeText(requireContext(), "Bluetooth Scan started", Toast.LENGTH_SHORT).show()
                } else if (!scanning && wasScanning) {
                    Toast.makeText(requireContext(), "Bluetooth Scan ended", Toast.LENGTH_SHORT).show()
                }
                wasScanning = scanning
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
            sectionedAdapter?.setErrorDevice(mac)
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
    }

    /**
     * Reload paired devices on every resume — handles the case where the user
     * granted permissions or toggled Bluetooth while on another screen.
     */
    override fun onResume() {
        super.onResume()
        
        // Force UI refresh since ViewPager2 reuses fragments
        setupConnectUI(viewModel.currentMockMode)
        
        // Always load paired devices to ensure mock device names are populated
        viewModel.loadPairedDevices(requireContext())
    }

    private fun setupForceBleToggle() {
        // Bind switch to current setting value
        binding.switchForceBle.isChecked = AppSettings.isForceBleConnection(requireContext())
        
        // Save setting when switch changes
        binding.switchForceBle.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setForceBleConnection(requireContext(), isChecked)
        }
        
        // Info icon shows explanation dialog
        binding.btnBleInfo.setOnClickListener {
            showBleInfoDialog()
        }
    }
    
    private fun showBleInfoDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Force BLE Connection")
            .setMessage("Force BLE enables Bluetooth Low Energy protocol for connections.\n\n" +
                    "Use this for BLE-only OBD2 adapters. Note: Classic Bluetooth devices " +
                    "may fail to connect when this is enabled.")
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun showBleWarningDialog(deviceInfo: DeviceInfo) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Warning")
            .setMessage("This device appears to be Classic Bluetooth only. " +
                    "Forcing BLE connection may fail. Continue anyway?")
            .setPositiveButton("Connect Anyway") { _, _ ->
                viewModel.connectToDevice(deviceInfo, requireContext())
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        wasScanning = false
        if (receiverRegistered) {
            try { 
                requireContext().unregisterReceiver(viewModel.discoveryReceiver) 
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered, safe to ignore
            } catch (e: Exception) {
                // Log unexpected errors for debugging
                android.util.Log.e("ConnectFragment", "Error unregistering receiver", e)
            }
            receiverRegistered = false
        }
        viewModel.stopScan()
        sectionedAdapter = null
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
