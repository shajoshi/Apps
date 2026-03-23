package com.sj.obd2app.ui.settings

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sj.obd2app.databinding.ItemConsoleLogBinding

/**
 * Adapter for displaying console log messages.
 */
class ConsoleAdapter : ListAdapter<String, ConsoleAdapter.VH>(ConsoleDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemConsoleLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VH(binding)
    }
    
    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
    
    class VH(private val binding: ItemConsoleLogBinding) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(message: String) {
            // Remove timestamp for display
            val displayMessage = if (message.startsWith("[") && message.contains("] ")) {
                message.substringAfter("] ")
            } else {
                message
            }
            
            binding.tvLogMessage.text = displayMessage
            
            // Color code based on message type
            val color = when {
                displayMessage.contains("ERROR", ignoreCase = true) -> "#FF5252"
                displayMessage.contains("NODATA", ignoreCase = true) -> "#FFA726"
                displayMessage.contains("VALID", ignoreCase = true) -> "#66BB6A"
                displayMessage.contains("Scanning:") -> "#4FC3F7"
                displayMessage.contains("HEADER", ignoreCase = true) -> "#AB47BC"
                displayMessage.contains("Complete", ignoreCase = true) -> "#66BB6A"
                displayMessage.contains("cancelled", ignoreCase = true) -> "#FFA726"
                else -> "#CCCCCC"
            }
            
            binding.tvLogMessage.setTextColor(Color.parseColor(color))
        }
    }
    
    private class ConsoleDiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }
        
        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }
    }
}
