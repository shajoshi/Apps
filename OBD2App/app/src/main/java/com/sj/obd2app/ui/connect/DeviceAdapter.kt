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
    private var connectingAddress: String? = null
    private var errorAddress: String? = null

    fun submitList(newDevices: List<BluetoothDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    /** Update the connected device MAC — highlights the matching row green. */
    fun setConnectedDevice(macAddress: String?) {
        connectedAddress = macAddress
        notifyDataSetChanged()
    }

    /** Update the currently-connecting device MAC — highlights row yellow. */
    fun setConnectingDevice(macAddress: String?) {
        connectingAddress = macAddress
        notifyDataSetChanged()
    }

    /** Mark a device row red after a failed connection attempt. */
    fun setErrorDevice(macAddress: String?) {
        errorAddress = macAddress
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
        val isConnected  = device.address == connectedAddress
        val isConnecting = device.address == connectingAddress
        val isError      = device.address == errorAddress
        holder.bind(device, isConnected, isConnecting, isError)
        holder.itemView.setOnClickListener {
            if (!isConnected && !isConnecting) onDeviceClick(device)
        }
        holder.binding.iconDisconnect.setOnClickListener {
            onDisconnectClick(device)
        }
    }

    override fun getItemCount(): Int = devices.size

    class DeviceViewHolder(val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("MissingPermission")
        fun bind(device: BluetoothDevice, isConnected: Boolean, isConnecting: Boolean, isError: Boolean) {
            binding.textDeviceName.text = device.name ?: "Unknown Device"
            binding.textDeviceAddress.text = device.address

            when {
                isConnected -> {
                    binding.deviceRowRoot.setBackgroundColor(Color.parseColor("#1A2E2A"))
                    binding.textDeviceName.setTextColor(Color.parseColor("#4CAF50"))
                    binding.iconBt.setColorFilter(Color.parseColor("#4CAF50"))
                    binding.badgeConnected.text = "CONNECTED"
                    binding.badgeConnected.setBackgroundColor(Color.parseColor("#4CAF50"))
                    binding.badgeConnected.visibility = View.VISIBLE
                    binding.iconDisconnect.visibility = View.VISIBLE
                }
                isConnecting -> {
                    binding.deviceRowRoot.setBackgroundColor(Color.parseColor("#2E2A10"))
                    binding.textDeviceName.setTextColor(Color.parseColor("#FFC107"))
                    binding.iconBt.setColorFilter(Color.parseColor("#FFC107"))
                    binding.badgeConnected.text = "CONNECTING…"
                    binding.badgeConnected.setBackgroundColor(Color.parseColor("#FFC107"))
                    binding.badgeConnected.setTextColor(Color.parseColor("#1A1A00"))
                    binding.badgeConnected.visibility = View.VISIBLE
                    binding.iconDisconnect.visibility = View.GONE
                }
                isError -> {
                    binding.deviceRowRoot.setBackgroundColor(Color.parseColor("#2E1A1A"))
                    binding.textDeviceName.setTextColor(Color.parseColor("#CF6679"))
                    binding.iconBt.setColorFilter(Color.parseColor("#CF6679"))
                    binding.badgeConnected.text = "FAILED"
                    binding.badgeConnected.setBackgroundColor(Color.parseColor("#CF6679"))
                    binding.badgeConnected.setTextColor(Color.parseColor("#FFFFFF"))
                    binding.badgeConnected.visibility = View.VISIBLE
                    binding.iconDisconnect.visibility = View.GONE
                }
                else -> {
                    binding.deviceRowRoot.setBackgroundColor(Color.TRANSPARENT)
                    binding.textDeviceName.setTextColor(Color.parseColor("#E0E0E0"))
                    binding.iconBt.setColorFilter(Color.parseColor("#4FC3F7"))
                    binding.badgeConnected.visibility = View.GONE
                    binding.iconDisconnect.visibility = View.GONE
                }
            }
        }
    }
}
