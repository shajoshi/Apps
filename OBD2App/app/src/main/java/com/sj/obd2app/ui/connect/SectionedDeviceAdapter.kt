package com.sj.obd2app.ui.connect

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sj.obd2app.databinding.ItemDeviceBinding

/**
 * Adapter for displaying Bluetooth devices in sections with headers.
 * Supports three sections: Potential OBD, Other Paired, Other Unpaired.
 */
@SuppressLint("MissingPermission")
class SectionedDeviceAdapter(
    private val onDeviceClick: (DeviceInfo) -> Unit,
    private val onDisconnectClick: (DeviceInfo) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_DEVICE = 1
    }

    private val items = mutableListOf<DeviceListItem>()
    private var connectedAddress: String? = null
    private var connectingAddress: String? = null
    private var errorAddress: String? = null

    fun submitList(newItems: List<DeviceListItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setConnectedDevice(macAddress: String?) {
        connectedAddress = macAddress
        notifyDataSetChanged()
    }

    fun setConnectingDevice(macAddress: String?) {
        connectingAddress = macAddress
        notifyDataSetChanged()
    }

    fun setErrorDevice(macAddress: String?) {
        errorAddress = macAddress
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is DeviceListItem.Header -> VIEW_TYPE_HEADER
            is DeviceListItem.Device -> VIEW_TYPE_DEVICE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(com.sj.obd2app.R.layout.item_device_header, parent, false)
                HeaderViewHolder(view)
            }
            VIEW_TYPE_DEVICE -> {
                val binding = ItemDeviceBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                DeviceViewHolder(binding)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DeviceListItem.Header -> {
                (holder as HeaderViewHolder).bind(item)
            }
            is DeviceListItem.Device -> {
                val deviceHolder = holder as DeviceViewHolder
                val isConnected = item.info.device.address == connectedAddress
                val isConnecting = item.info.device.address == connectingAddress
                val isError = item.info.device.address == errorAddress
                deviceHolder.bind(item.info, isConnected, isConnecting, isError)
                deviceHolder.itemView.setOnClickListener {
                    if (!isConnected && !isConnecting) onDeviceClick(item.info)
                }
                deviceHolder.binding.iconDisconnect.setOnClickListener {
                    onDisconnectClick(item.info)
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textHeader: TextView = itemView.findViewById(com.sj.obd2app.R.id.text_section_header)

        fun bind(header: DeviceListItem.Header) {
            textHeader.text = "${header.title} (${header.count})"
        }
    }

    class DeviceViewHolder(val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("MissingPermission")
        fun bind(deviceInfo: DeviceInfo, isConnected: Boolean, isConnecting: Boolean, isError: Boolean) {
            val device = deviceInfo.device
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
                    
                    // Show "PAIRED" badge for paired devices
                    if (deviceInfo.isPaired) {
                        binding.badgeConnected.text = "PAIRED"
                        binding.badgeConnected.setBackgroundColor(Color.parseColor("#4FC3F7"))
                        binding.badgeConnected.setTextColor(Color.parseColor("#1A1A2E"))
                        binding.badgeConnected.visibility = View.VISIBLE
                    } else {
                        binding.badgeConnected.visibility = View.GONE
                    }
                    binding.iconDisconnect.visibility = View.GONE
                }
            }
        }
    }
}
