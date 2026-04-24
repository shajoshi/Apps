package com.sj.obd2app.ui.dashboard.wizard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sj.obd2app.R
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.ui.dashboard.model.DashboardMetric
import com.sj.obd2app.ui.dashboard.model.MetricDefaults
import com.sj.obd2app.settings.VehicleProfileRepository

/**
 * Step 2 — Pick a data source metric.
 * Grouped by category; unsupported PIDs are greyed out but selectable.
 */
class Step2MetricPage : Fragment() {

    private lateinit var recyclerView: RecyclerView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.page_wizard_step2, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.rv_metrics)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        val wizardSheet = parentFragment as? AddWidgetWizardSheet

        // PIDs seen live in this session
        val livePids = Obd2ServiceProvider.getService().obd2Data.value.map { it.pid }.toSet()

        // PIDs ever confirmed for the active vehicle profile (persisted across sessions)
        val ctx = requireContext()
        val repo = VehicleProfileRepository.getInstance(ctx)
        val activeProfileId = repo.activeProfile?.id
        val profileKnownPids = repo.getKnownPids(activeProfileId)
        val profileLastValues = repo.getLastPidValues(activeProfileId)
        val hasProfileData = repo.hasDiscoveredPids(activeProfileId)

        recyclerView.adapter = MetricListAdapter(
            items = buildMetricItems(),
            livePids = livePids,
            profileKnownPids = profileKnownPids,
            profileLastValues = profileLastValues,
            hasProfileData = hasProfileData,
            selected = wizardSheet?.state?.selectedMetric,
            onSelect = { metric ->
                wizardSheet?.state?.selectedMetric = metric
                val d = MetricDefaults.get(metric)
                wizardSheet?.state?.let { s ->
                    s.rangeMin = d.rangeMin; s.rangeMax = d.rangeMax
                    s.majorTickInterval = d.majorTickInterval; s.minorTickCount = d.minorTickCount
                    s.warningThreshold = d.warningThreshold; s.decimalPlaces = d.decimalPlaces
                    s.displayUnit = d.displayUnit
                }
                (recyclerView.adapter as MetricListAdapter).setSelected(metric)
            }
        )
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

    private fun buildMetricItems(): List<MetricListItem> {
        val items = mutableListOf<MetricListItem>()
        data class Group(val header: String, val metrics: List<DashboardMetric>)
        val groups = listOf(
            Group("OBD-II — Engine", listOf(
                DashboardMetric.Obd2Pid("010C", "Engine RPM", "rpm"),
                DashboardMetric.Obd2Pid("0104", "Engine Load", "%"),
                DashboardMetric.Obd2Pid("0111", "Throttle Position", "%"),
                DashboardMetric.Obd2Pid("010E", "Timing Advance", "° BTDC"),
                DashboardMetric.Obd2Pid("015C", "Oil Temperature", "°C"),
                DashboardMetric.Obd2Pid("011F", "Run Time Since Start", "sec")
            )),
            Group("OBD-II — Speed & Distance", listOf(
                DashboardMetric.Obd2Pid("010D", "Vehicle Speed", "km/h"),
                DashboardMetric.Obd2Pid("0121", "Distance with MIL On", "km"),
                DashboardMetric.Obd2Pid("0131", "Distance Since Codes Cleared", "km")
            )),
            Group("OBD-II — Fuel", listOf(
                DashboardMetric.Obd2Pid("012F", "Fuel Tank Level", "%"),
                DashboardMetric.Obd2Pid("010A", "Fuel Pressure", "kPa"),
                DashboardMetric.Obd2Pid("015E", "Engine Fuel Rate", "L/h"),
                DashboardMetric.Obd2Pid("0106", "Short-term Fuel Trim B1", "%"),
                DashboardMetric.Obd2Pid("0107", "Long-term Fuel Trim B1", "%")
            )),
            Group("OBD-II — Temperature", listOf(
                DashboardMetric.Obd2Pid("0105", "Coolant Temperature", "°C"),
                DashboardMetric.Obd2Pid("010F", "Intake Air Temperature", "°C"),
                DashboardMetric.Obd2Pid("0146", "Ambient Air Temperature", "°C")
            )),
            Group("OBD-II — Air & Intake", listOf(
                DashboardMetric.Obd2Pid("010B", "Intake Manifold Pressure", "kPa"),
                DashboardMetric.Obd2Pid("0110", "MAF Air Flow Rate", "g/s"),
                DashboardMetric.Obd2Pid("0133", "Barometric Pressure", "kPa")
            )),
            Group("OBD-II — Electrical", listOf(
                DashboardMetric.Obd2Pid("0142", "Control Module Voltage", "V"),
                DashboardMetric.Obd2Pid("0114", "O2 Sensor Voltage B1S1", "V")
            )),
            Group("GPS", listOf(DashboardMetric.GpsSpeed, DashboardMetric.GpsAltitude)),
            Group("Derived — Fuel Efficiency", listOf(
                DashboardMetric.DerivedMetric("DERIVED_LPK",       "Instant Consumption",   "L/100km"),
                DashboardMetric.DerivedMetric("DERIVED_KPL",       "Instant Efficiency",    "km/L"),
                DashboardMetric.DerivedMetric("DERIVED_AVG_LPK",   "Trip Avg Consumption",  "L/100km"),
                DashboardMetric.DerivedMetric("DERIVED_AVG_KPL",   "Trip Avg Efficiency",   "km/L"),
                DashboardMetric.DerivedMetric("DERIVED_FUEL_USED", "Trip Fuel Used",        "L"),
                DashboardMetric.DerivedMetric("DERIVED_RANGE",     "Range Remaining",       "km"),
                DashboardMetric.DerivedMetric("DERIVED_FUEL_COST", "Fuel Cost",             "")
            )),
            Group("Derived — Trip Computer", listOf(
                DashboardMetric.DerivedMetric("DERIVED_TRIP_DIST", "Trip Distance",         "km"),
                DashboardMetric.DerivedMetric("DERIVED_TRIP_TIME", "Trip Time",             "sec"),
                DashboardMetric.DerivedMetric("DERIVED_AVG_SPD",   "Trip Avg Speed",        "km/h"),
                DashboardMetric.DerivedMetric("DERIVED_MAX_SPD",   "Trip Max Speed",        "km/h")
            )),
            Group("Derived — Emissions & Drive", listOf(
                DashboardMetric.DerivedMetric("DERIVED_CO2",       "Avg CO₂",               "g/km"),
                DashboardMetric.DerivedMetric("DERIVED_PCT_CITY",  "% City Driving",        "%"),
                DashboardMetric.DerivedMetric("DERIVED_PCT_IDLE",  "% Idle",                "%")
            )),
            Group("Derived — Power", listOf(
                DashboardMetric.DerivedMetric("DERIVED_POWER_ACCEL", "Power (Accel)",       "kW"),
                DashboardMetric.DerivedMetric("DERIVED_POWER_THERMO", "Power (Thermo)",      "kW"),
                DashboardMetric.DerivedMetric("DERIVED_POWER_OBD",    "Power (OBD)",         "kW"),
                DashboardMetric.DerivedMetric("DERIVED_POWER_ACCEL_BHP", "Power (Accel)",    "bhp"),
                DashboardMetric.DerivedMetric("DERIVED_POWER_THERMO_BHP", "Power (Thermo)",   "bhp"),
                DashboardMetric.DerivedMetric("DERIVED_POWER_OBD_BHP",    "Power (OBD)",      "bhp")
            ))
        )
        // CAN Bus signals from the starred CanProfile (skipped when none is configured).
        val canGroups = com.sj.obd2app.ui.dashboard.model.CanMetricSource.buildGroups(requireContext())
            .map { Group(it.header, it.metrics) }

        for (g in groups + canGroups) {
            items.add(MetricListItem.Header(g.header))
            g.metrics.forEach { items.add(MetricListItem.Entry(it)) }
        }
        return items
    }

    sealed class MetricListItem {
        data class Header(val title: String) : MetricListItem()
        data class Entry(val metric: DashboardMetric) : MetricListItem()
    }

    private inner class MetricListAdapter(
        private val items: List<MetricListItem>,
        private val livePids: Set<String>,
        private val profileKnownPids: Set<String>,
        private val profileLastValues: Map<String, String>,
        private val hasProfileData: Boolean,
        private var selected: DashboardMetric?,
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
                    is DashboardMetric.CanSignal -> {
                        val sub = buildString {
                            append("0x${Integer.toHexString(metric.messageId).uppercase()}")
                            if (metric.unit.isNotEmpty()) append(" · ").append(metric.unit)
                        }
                        listOf(metric.name, metric.unit, true, sub)
                    }
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
    }
}
