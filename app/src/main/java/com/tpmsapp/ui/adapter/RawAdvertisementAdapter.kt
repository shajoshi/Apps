package com.tpmsapp.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tpmsapp.ble.RawAdvertisement
import com.tpmsapp.ble.TpmsPacketParser
import com.tpmsapp.databinding.ItemRawAdvertisementBinding

class RawAdvertisementAdapter(
    private val onAssignClick: (RawAdvertisement) -> Unit
) : ListAdapter<RawAdvertisement, RawAdvertisementAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RawAdvertisement>() {
            override fun areItemsTheSame(old: RawAdvertisement, new: RawAdvertisement) =
                old.macAddress == new.macAddress
            override fun areContentsTheSame(old: RawAdvertisement, new: RawAdvertisement) =
                old == new
        }
    }

    inner class ViewHolder(private val binding: ItemRawAdvertisementBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RawAdvertisement) {
            binding.tvMac.text = item.macAddress
            binding.tvName.text = item.deviceName
            val dist = estimateDistance(item.rssi)
            binding.tvRssi.text = "~$dist  ${item.rssi} dBm"
            binding.tvRawData.text = item.manufacturerDataHex()
            binding.btnAssign.setOnClickListener { onAssignClick(item) }

            val parsed = TpmsPacketParser.parse(item.macAddress, item.manufacturerData)
            if (parsed != null) {
                val pressurePsi = parsed.pressureKpa * 0.14503773f
                binding.tvParsed.visibility = View.VISIBLE
                binding.tvParsed.text = buildString {
                    append("🌡 ${"%.0f".format(parsed.temperatureCelsius)}°C  ")
                    append("💨 ${"%.1f".format(pressurePsi)} psi  ")
                    append("🔋 ${parsed.batteryPercent}%")
                    if (parsed.isAlarmLow || parsed.isAlarmHigh || parsed.isAlarmTemp) {
                        append("  ⚠️")
                        if (parsed.isAlarmLow)  append(" LOW")
                        if (parsed.isAlarmHigh) append(" HIGH")
                        if (parsed.isAlarmTemp) append(" TEMP")
                    }
                }
            } else {
                binding.tvParsed.visibility = View.GONE
            }
        }
    }

    private fun estimateDistance(rssi: Int): String {
        val txPower = -59
        val n = 2.5
        val metres = Math.pow(10.0, (txPower - rssi) / (10.0 * n))
        return when {
            metres < 1.0  -> "<1 m"
            metres < 10.0 -> "${ "%.1f".format(metres) } m"
            else          -> "${ "%.0f".format(metres) } m"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRawAdvertisementBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
