package com.sj.obd2app.ui.dashboard.wizard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sj.obd2app.R
import com.sj.obd2app.ui.dashboard.model.ColorScheme
import com.sj.obd2app.ui.dashboard.model.MetricDefaults
import com.sj.obd2app.ui.dashboard.model.WidgetType
import com.sj.obd2app.ui.dashboard.views.*

/**
 * Step 1 — Pick a visual widget style.
 * Shows a horizontal list of preview cards, each rendering a live mini-preview
 * of the widget with its colour scheme applied.
 */
class Step1WidgetTypePage : Fragment() {

    private lateinit var recyclerView: RecyclerView

    /** The canonical (non-deprecated) types to show. */
    private val widgetTypes = listOf(
        WidgetType.DIAL,
        WidgetType.SEVEN_SEGMENT,
        WidgetType.BAR_GAUGE_H,
        WidgetType.BAR_GAUGE_V,
        WidgetType.NUMERIC_DISPLAY,
        WidgetType.TEMPERATURE_ARC
    )

    private val typeLabels = mapOf(
        WidgetType.DIAL            to "Dial Gauge",
        WidgetType.SEVEN_SEGMENT   to "Digital Display",
        WidgetType.BAR_GAUGE_H     to "Bar (Horizontal)",
        WidgetType.BAR_GAUGE_V     to "Bar (Vertical)",
        WidgetType.NUMERIC_DISPLAY to "Numeric Readout",
        WidgetType.TEMPERATURE_ARC to "Temperature Arc"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.page_wizard_step1, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.rv_widget_types)
        recyclerView.layoutManager = LinearLayoutManager(
            requireContext(), LinearLayoutManager.VERTICAL, false
        )
        val wizardSheet = parentFragment as? AddWidgetWizardSheet
        recyclerView.adapter = WidgetTypeAdapter(
            types = widgetTypes,
            labels = typeLabels,
            selected = wizardSheet?.state?.selectedType,
            onSelect = { type ->
                wizardSheet?.state?.selectedType = type
                // If type has a suggested metric already selected, keep it; otherwise reset preview
                (recyclerView.adapter as WidgetTypeAdapter).setSelected(type)
            }
        )
    }

    // ── Inner Adapter ──────────────────────────────────────────────────────────

    private inner class WidgetTypeAdapter(
        private val types: List<WidgetType>,
        private val labels: Map<WidgetType, String>,
        private var selected: WidgetType?,
        private val onSelect: (WidgetType) -> Unit
    ) : RecyclerView.Adapter<WidgetTypeAdapter.VH>() {

        fun setSelected(type: WidgetType) {
            val old = types.indexOf(selected)
            selected = type
            val new = types.indexOf(type)
            if (old >= 0) notifyItemChanged(old)
            if (new >= 0) notifyItemChanged(new)
        }

        override fun getItemCount() = types.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_widget_type_card, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val type = types[position]
            holder.bind(type, type == selected)
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val previewFrame: FrameLayout = itemView.findViewById(R.id.preview_frame)
            private val tvName: TextView = itemView.findViewById(R.id.tv_widget_name)

            fun bind(type: WidgetType, isSelected: Boolean) {
                tvName.text = labels[type] ?: type.name
                itemView.isSelected = isSelected

                // Build a mini preview with demo values
                previewFrame.removeAllViews()
                val previewView = createPreviewView(type)
                previewFrame.addView(previewView, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))

                itemView.setOnClickListener { onSelect(type) }
            }

            private fun createPreviewView(type: WidgetType): DashboardGaugeView {
                val ctx = requireContext()
                val scheme = ColorScheme.DEFAULT_DARK
                val defaults = MetricDefaults.get("010C") // RPM as demo defaults

                val view: DashboardGaugeView = when (type) {
                    WidgetType.DIAL            -> DialView(ctx)
                    WidgetType.SEVEN_SEGMENT   -> SevenSegmentView(ctx)
                    WidgetType.BAR_GAUGE_H     -> BarGaugeView(ctx).also { it.isVertical = false }
                    WidgetType.BAR_GAUGE_V     -> BarGaugeView(ctx).also { it.isVertical = true }
                    WidgetType.NUMERIC_DISPLAY -> NumericDisplayView(ctx)
                    WidgetType.TEMPERATURE_ARC -> TemperatureGaugeView(ctx)
                }

                view.colorScheme       = scheme
                view.metricName        = "DEMO"
                view.metricUnit        = defaults.displayUnit
                view.rangeMin          = defaults.rangeMin
                view.rangeMax          = defaults.rangeMax
                view.majorTickInterval = defaults.majorTickInterval
                view.minorTickCount    = defaults.minorTickCount
                view.warningThreshold  = defaults.warningThreshold
                view.decimalPlaces     = defaults.decimalPlaces
                // Set a mid-range demo value immediately (no animation needed for preview)
                view.setValueImmediate(defaults.rangeMin + (defaults.rangeMax - defaults.rangeMin) * 0.45f)
                return view
            }
        }
    }
}
