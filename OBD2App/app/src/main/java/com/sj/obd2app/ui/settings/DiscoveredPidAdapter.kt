package com.sj.obd2app.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sj.obd2app.databinding.ItemDiscoveredPidBinding
import com.sj.obd2app.obd.DiscoveredPid

/**
 * Adapter for displaying discovered PIDs with selection support.
 */
class DiscoveredPidAdapter(
    private val onPidClick: (DiscoveredPid) -> Unit
) : ListAdapter<DiscoveredPid, DiscoveredPidAdapter.VH>(DiscoveredPidDiffCallback()) {
    
    private val selectedPids = mutableSetOf<String>()
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDiscoveredPidBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VH(binding, onPidClick)
    }
    
    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), selectedPids.contains(getItem(position).cacheKey))
    }
    
    fun toggleSelection(pid: DiscoveredPid) {
        val key = pid.cacheKey
        if (selectedPids.contains(key)) {
            selectedPids.remove(key)
        } else {
            selectedPids.add(key)
        }
        notifyItemChanged(currentList.indexOf(pid))
    }
    
    fun getSelectedPids(): List<DiscoveredPid> {
        return currentList.filter { selectedPids.contains(it.cacheKey) }
    }
    
    fun clearSelections() {
        val previouslySelected = selectedPids.toList()
        selectedPids.clear()
        currentList.forEachIndexed { index, pid ->
            if (previouslySelected.contains(pid.cacheKey)) {
                notifyItemChanged(index)
            }
        }
    }
    
    class VH(
            private val binding: ItemDiscoveredPidBinding,
            private val onPidClick: (DiscoveredPid) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(pid: DiscoveredPid, isSelected: Boolean) {
            binding.apply {
                // Basic info
                tvPidName.text = pid.suggestedName
                tvPidCommand.text = "${pid.header} ${pid.commandString}"
                tvPidResponse.text = pid.response
                tvPidFormula.text = "Formula: ${pid.suggestedFormula}"
                
                // Unit if available
                if (pid.suggestedUnit.isNotEmpty()) {
                    tvPidUnit.text = pid.suggestedUnit
                    tvPidUnit.isVisible = true
                } else {
                    tvPidUnit.isVisible = false
                }
                
                // Byte count
                tvPidBytes.text = "${pid.byteCount} bytes"
                
                // Selection state
                root.isSelected = isSelected
                checkboxSelected.isChecked = isSelected
                
                // Click listeners
                root.setOnClickListener {
                    onPidClick(pid)
                }
                
                checkboxSelected.setOnClickListener {
                    onPidClick(pid)
                }
            }
        }
    }
    
    private class DiscoveredPidDiffCallback : DiffUtil.ItemCallback<DiscoveredPid>() {
        override fun areItemsTheSame(oldItem: DiscoveredPid, newItem: DiscoveredPid): Boolean {
            return oldItem.cacheKey == newItem.cacheKey
        }
        
        override fun areContentsTheSame(oldItem: DiscoveredPid, newItem: DiscoveredPid): Boolean {
            return oldItem == newItem
        }
    }
}
