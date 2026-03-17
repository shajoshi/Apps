package com.sj.obd2app.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sj.obd2app.ui.dashboard.model.DashboardMetric

/**
 * Reusable adapter for selecting dashboard metrics in dialogs and wizards.
 * Shows metrics grouped by category with availability indicators.
 */
class MetricListAdapter(
    private val items: List<MetricListItem>,
    private val livePids: Set<String>,
    private val profileKnownPids: Set<String>,
    private val profileLastValues: Map<String, String>,
    private val hasProfileData: Boolean,
    private var selected: DashboardMetric? = null,
    private val onSelect: (DashboardMetric) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** A PID counts as supported if seen live or previously on this profile */
    private fun isSupported(pid: String) =
        pid in livePids || pid in profileKnownPids || !hasProfileData

    fun setSelected(m: DashboardMetric) {
        val old = items.indexOfFirst { it is MetricListItem.Entry && it.metric == selected }
        selected = m
        val new = items.indexOfFirst { it is MetricListItem.Entry && it.metric == m }
        if (old >= 0) notifyItemChanged(old)
        if (new >= 0) notifyItemChanged(new)
    }

    override fun getItemViewType(pos: Int) = if (items[pos] is MetricListItem.Header) 0 else 1
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0)
            HeaderVH(inf.inflate(android.R.layout.simple_list_item_1, parent, false))
        else
            EntryVH(inf.inflate(android.R.layout.simple_list_item_2, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        when (val item = items[pos]) {
            is MetricListItem.Header -> (holder as HeaderVH).bind(item.title)
            is MetricListItem.Entry  -> (holder as EntryVH).bind(item.metric)
        }
    }

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tv = v.findViewById<TextView>(android.R.id.text1)
        fun bind(title: String) {
            tv.text = title
            tv.setTextColor(0xFF888899.toInt())
            tv.textSize = 11f
            tv.setPadding(0, 16, 0, 4)
            itemView.isClickable = false
        }
    }

    inner class EntryVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvName = v.findViewById<TextView>(android.R.id.text1)
        private val tvSub  = v.findViewById<TextView>(android.R.id.text2)

        fun bind(metric: DashboardMetric) {
            val (name, unit, supported, subText) = when (metric) {
                is DashboardMetric.Obd2Pid -> {
                    val sup = isSupported(metric.pid)
                    val badge = pidBadge(
                        metric.pid, livePids, profileKnownPids,
                        profileLastValues, hasProfileData
                    )
                    // Show unit + badge together on the subtitle line
                    val sub = if (badge.isEmpty()) metric.unit
                              else if (metric.unit.isEmpty()) badge
                              else "${metric.unit}  $badge"
                    listOf(metric.name, metric.unit, sup, sub)
                }
                DashboardMetric.GpsSpeed ->
                    listOf("GPS Speed", "km/h", true, "km/h")
                DashboardMetric.GpsAltitude ->
                    listOf("GPS Altitude (MSL)", "m", true, "m")
                is DashboardMetric.DerivedMetric ->
                    listOf(metric.name, metric.unit, true, metric.unit)
            }
            @Suppress("UNCHECKED_CAST")
            val isSupported = supported as Boolean
            val isSelected  = metric == selected
            tvName.text = name as String
            tvName.setTextColor(
                when {
                    isSelected   -> 0xFF4FC3F7.toInt()
                    isSupported  -> 0xFFCCCCEE.toInt()
                    else         -> 0xFF555566.toInt()
                }
            )
            tvSub.text = subText as String
            tvSub.setTextColor(
                when {
                    isSelected                                                  -> 0xFF4FC3F7.toInt()
                    metric is DashboardMetric.Obd2Pid && !isSupported           -> 0xFF555566.toInt()
                    metric is DashboardMetric.Obd2Pid &&
                        metric.pid in profileKnownPids                          -> 0xFF66BB6A.toInt() // green
                    metric is DashboardMetric.Obd2Pid && metric.pid in livePids -> 0xFF4FC3F7.toInt() // blue
                    else                                                        -> 0xFF666677.toInt()
                }
            )
            itemView.alpha = if (isSupported) 1f else 0.45f
            itemView.setBackgroundColor(if (isSelected) 0x224FC3F7.toInt() else 0x00000000.toInt())
            itemView.setOnClickListener { onSelect(metric) }
        }
    }

    /** Availability badge text shown below a PID name */
    private fun pidBadge(
        pid: String,
        livePids: Set<String>,
        profileKnownPids: Set<String>,
        profileLastValues: Map<String, String>,
        hasProfileData: Boolean
    ): String = when {
        pid in livePids               -> "● Live"
        pid in profileKnownPids       -> "✓ Seen on this vehicle · last: ${profileLastValues[pid]}"
        hasProfileData                -> "✗ Not seen on this vehicle"
        else                          -> ""
    }
}

/**
 * Sealed class representing items in the metric selection list.
 */
sealed class MetricListItem {
    data class Header(val title: String) : MetricListItem()
    data class Entry(val metric: DashboardMetric) : MetricListItem()
}
