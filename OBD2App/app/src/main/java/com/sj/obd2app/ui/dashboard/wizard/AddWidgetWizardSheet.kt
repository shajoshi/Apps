package com.sj.obd2app.ui.dashboard.wizard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sj.obd2app.R
import com.sj.obd2app.ui.dashboard.DashboardEditorViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * 3-step bottom-sheet wizard for adding a new widget to the dashboard.
 *
 * Step 1 — Choose visual style (WidgetType)
 * Step 2 — Choose data source (DashboardMetric)
 * Step 3 — Configure scale, unit and size; then "Add" commits to the ViewModel.
 */
class AddWidgetWizardSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "AddWidgetWizard"
        fun newInstance() = AddWidgetWizardSheet()
    }

    /** Shared mutable state threaded through all three steps. */
    val state = WizardState()

    private lateinit var pager: ViewPager2
    private lateinit var btnBack: Button
    private lateinit var btnNext: Button
    private lateinit var btnCancel: Button
    private lateinit var stepLabel: TextView
    private lateinit var dot1: View
    private lateinit var dot2: View
    private lateinit var dot3: View

    private val stepTitles = listOf("Choose Style", "Choose Metric", "Configure")

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
        if (sheet != null) {
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_add_widget_wizard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pager       = view.findViewById(R.id.wizard_pager)
        btnBack     = view.findViewById(R.id.btn_wizard_back)
        btnNext     = view.findViewById(R.id.btn_wizard_next)
        btnCancel   = view.findViewById(R.id.btn_wizard_cancel)
        stepLabel   = view.findViewById(R.id.step_label)
        dot1        = view.findViewById(R.id.dot_1)
        dot2        = view.findViewById(R.id.dot_2)
        dot3        = view.findViewById(R.id.dot_3)

        pager.adapter = WizardPagerAdapter(this)
        pager.isUserInputEnabled = false // navigation only via buttons

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateStepUi(position)
            }
        })

        btnBack.setOnClickListener {
            val cur = pager.currentItem
            if (cur > 0) pager.currentItem = cur - 1
        }

        btnNext.setOnClickListener {
            val cur = pager.currentItem
            when (cur) {
                0 -> {
                    if (state.selectedType == null) {
                        Toast.makeText(requireContext(), "Please select a widget style", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    pager.currentItem = 1
                }
                1 -> {
                    if (state.selectedMetric == null) {
                        Toast.makeText(requireContext(), "Please select a data source", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    pager.currentItem = 2
                }
                2 -> {
                    commitAndDismiss()
                }
            }
        }

        btnCancel.setOnClickListener { dismiss() }

        updateStepUi(0)
    }

    private fun updateStepUi(page: Int) {
        view?.findViewById<TextView>(R.id.wizard_title)?.text = stepTitles[page]
        stepLabel.text = "Step ${page + 1} of 3"
        btnBack.visibility = if (page > 0) View.VISIBLE else View.GONE
        btnNext.text = if (page == 2) "Add" else "Next"

        // Dot indicators
        val activeColor  = 0xFF4FC3F7.toInt()
        val inactiveColor = 0xFF444456.toInt()
        val activeSize   = 10
        val inactiveSize = 8
        fun styleDot(d: View, active: Boolean) {
            val sizePx = (if (active) activeSize else inactiveSize) *
                resources.displayMetrics.density.toInt()
            val lp = d.layoutParams
            lp.width = sizePx; lp.height = sizePx
            d.layoutParams = lp
            d.setBackgroundColor(if (active) activeColor else inactiveColor)
        }
        styleDot(dot1, page == 0)
        styleDot(dot2, page == 1)
        styleDot(dot3, page == 2)
    }

    private fun commitAndDismiss() {
        val type   = state.selectedType   ?: return
        val metric = state.selectedMetric ?: return

        // Obtain the shared ViewModel from the parent fragment (DashboardEditorFragment)
        val vm = ViewModelProvider(requireParentFragment())[DashboardEditorViewModel::class.java]
        vm.addWidget(type, metric, state.gridW, state.gridH)

        // Override the auto-defaults with any user edits from Step 3
        val newId = vm.currentLayout.value.widgets.lastOrNull()?.id ?: run { dismiss(); return }
        vm.updateWidgetRangeSettings(
            widgetId        = newId,
            rangeMin        = state.rangeMin,
            rangeMax        = state.rangeMax,
            majorTickInterval = state.majorTickInterval,
            minorTickCount  = state.minorTickCount,
            warningThreshold = state.warningThreshold,
            decimalPlaces   = state.decimalPlaces,
            displayUnit     = state.displayUnit
        )
        dismiss()
    }

    // ── Pager adapter ───────────────────────────────────────────────────────

    private class WizardPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount() = 3
        override fun createFragment(position: Int): Fragment = when (position) {
            0    -> Step1WidgetTypePage()
            1    -> Step2MetricPage()
            else -> Step3ConfigPage()
        }
    }
}
