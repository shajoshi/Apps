package com.sj.obd2app.settings

import androidx.recyclerview.widget.DiffUtil
import com.sj.obd2app.obd.CustomPid

/**
 * DiffUtil callback for efficiently updating CustomPid list.
 */
class CustomPidDiff(
    private val oldList: List<CustomPid>,
    private val newList: List<CustomPid>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldList.size

    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].id == newList[newItemPosition].id
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldItem = oldList[oldItemPosition]
        val newItem = newList[newItemPosition]
        
        return oldItem == newItem
    }
}
