package com.tpmsapp.ui

import android.app.Dialog
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.tpmsapp.R
import com.tpmsapp.model.SensorConfig
import com.tpmsapp.model.TyrePosition
import com.tpmsapp.viewmodel.TpmsViewModel

class AssignSensorDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_MAC      = "mac_address"
        private const val ARG_NAME     = "device_name"
        private const val ARG_POSITION = "position"

        fun newInstance(mac: String?, name: String?, position: TyrePosition? = null) =
            AssignSensorDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MAC, mac)
                    putString(ARG_NAME, name)
                    position?.let { putString(ARG_POSITION, it.name) }
                }
            }
    }

    private val viewModel: TpmsViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val mac  = arguments?.getString(ARG_MAC)
        val name = arguments?.getString(ARG_NAME)
        val presetPosition = arguments?.getString(ARG_POSITION)?.let { TyrePosition.valueOf(it) }

        val positions = TyrePosition.values()
        val labels = positions.map { it.label }.toTypedArray()

        val presetIndex = presetPosition?.let { positions.indexOf(it) } ?: 0

        return AlertDialog.Builder(requireContext())
            .setTitle(
                if (mac != null) getString(R.string.assign_sensor_title, name ?: mac)
                else getString(R.string.assign_sensor_manual_title)
            )
            .setSingleChoiceItems(labels, presetIndex, null)
            .setPositiveButton(R.string.assign) { dialog, _ ->
                val selectedIndex = (dialog as AlertDialog).listView.checkedItemPosition
                if (selectedIndex >= 0) {
                    val position = positions[selectedIndex]
                    val resolvedMac = mac ?: "MANUAL_${position.name}"
                    val config = SensorConfig(
                        macAddress = resolvedMac,
                        position = position,
                        nickname = name ?: position.label
                    )
                    viewModel.saveSensorConfig(config)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }
}
