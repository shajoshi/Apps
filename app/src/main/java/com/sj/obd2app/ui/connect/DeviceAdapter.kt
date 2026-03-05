package com.sj.obd2app.ui.connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sj.obd2app.databinding.ItemDeviceBinding

/**
 * Adapter for the list of paired / discovered Bluetooth devices.
 *
 * If [connectedAddress] matches a device's MAC, that row is highlighted
 * in green with a CONNECTED badge and a disconnect icon.
 */
@SuppressLint("MissingPermission")
class DeviceAdapter(
    private val onDeviceClick: (BluetoothDevice) -> Unit,
    private val onDisconnectClick: (BluetoothDevice) -> Unit = {}
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<BluetoothDevice>()
    private var connectedAddress: String? = null

    fun submitList(newDevices: List<BluetoothDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    /** Update the connected device MAC — highlights the matching row. */
    fun setConnectedDevice(macAddress: String?) {
        connectedAddress = macAddress
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        val isConnected = device.address == connectedAddress
        holder.bind(device, isConnected)
        holder.itemView.setOnClickListener {
            if (!isConnected) onDeviceClick(device)
        }
        holder.binding.iconDisconnect.setOnClickListener {
            onDisconnectClick(device)
        }
    }

    override fun getItemCount(): Int = devices.size

    class DeviceViewHolder(val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("MissingPermission")
        fun bind(device: BluetoothDevice, isConnected: Boolean) {
            binding.textDeviceName.text = device.name ?: "Unknown Device"
            binding.textDeviceAddress.text = device.address

            if (isConnected) {
                // Highlight: teal tinted background, green name, show badge + disconnect icon
                binding.deviceRowRoot.setBackgroundColor(Color.parseColor("#1A2E2A"))
                binding.textDeviceName.setTextColor(Color.parseColor("#4CAF50"))
                binding.iconBt.setColorFilter(Color.parseColor("#4CAF50"))
                binding.badgeConnected.visibility = View.VISIBLE
                binding.iconDisconnect.visibility = View.VISIBLE
            } else {
                // Default state
                binding.deviceRowRoot.setBackgroundColor(Color.TRANSPARENT)
                binding.textDeviceName.setTextColor(Color.parseColor("#E0E0E0"))
                binding.iconBt.setColorFilter(Color.parseColor("#4FC3F7"))
                binding.badgeConnected.visibility = View.GONE
                binding.iconDisconnect.visibility = View.GONE
            }
        }
    }
}
