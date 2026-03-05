package com.sj.obd2app.ui.details

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sj.obd2app.databinding.ItemObd2RowBinding
import com.sj.obd2app.obd.Obd2DataItem

/**
 * RecyclerView adapter that displays OBD-II data items in a table format.
 */
class Obd2Adapter : ListAdapter<Obd2DataItem, Obd2Adapter.Obd2ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Obd2DataItem>() {
            override fun areItemsTheSame(oldItem: Obd2DataItem, newItem: Obd2DataItem): Boolean =
                oldItem.pid == newItem.pid

            override fun areContentsTheSame(oldItem: Obd2DataItem, newItem: Obd2DataItem): Boolean =
                oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Obd2ViewHolder {
        val binding = ItemObd2RowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Obd2ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: Obd2ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, position)
    }

    class Obd2ViewHolder(private val binding: ItemObd2RowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Obd2DataItem, position: Int) {
            binding.textParamName.text = item.name
            binding.textParamValue.text = item.value
            binding.textParamUnit.text = item.unit

            // Alternate row background for readability
            val bgColor = if (position % 2 == 0) 0xFF1A1A2E.toInt() else 0xFF22223A.toInt()
            binding.root.setBackgroundColor(bgColor)
        }
    }
}
