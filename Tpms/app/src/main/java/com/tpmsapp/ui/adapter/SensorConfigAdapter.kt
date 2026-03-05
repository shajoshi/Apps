package com.tpmsapp.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tpmsapp.R
import com.tpmsapp.databinding.ItemSensorConfigBinding
import com.tpmsapp.model.SensorConfig
import com.tpmsapp.model.TyrePosition

data class SensorSlot(val position: TyrePosition, val config: SensorConfig?)

class SensorConfigAdapter(
    private val onRemove: (SensorConfig) -> Unit,
    private val onAssign: (TyrePosition) -> Unit
) : ListAdapter<SensorSlot, SensorConfigAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SensorSlot>() {
            override fun areItemsTheSame(old: SensorSlot, new: SensorSlot) =
                old.position == new.position
            override fun areContentsTheSame(old: SensorSlot, new: SensorSlot) =
                old == new
        }
    }

    inner class ViewHolder(private val binding: ItemSensorConfigBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(slot: SensorSlot) {
            binding.tvPositionLabel.text = slot.position.label
            val config = slot.config
            if (config != null) {
                binding.tvMacAddress.text = config.macAddress
                binding.tvNickname.text   = config.nickname
                binding.btnRemove.setOnClickListener { onRemove(config) }
                binding.btnAssign.setText(R.string.reassign)
            } else {
                binding.tvMacAddress.text = binding.root.context.getString(R.string.not_assigned)
                binding.tvNickname.text   = ""
                binding.btnRemove.setOnClickListener(null)
                binding.btnAssign.setText(R.string.assign)
            }
            binding.btnAssign.setOnClickListener { onAssign(slot.position) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSensorConfigBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
