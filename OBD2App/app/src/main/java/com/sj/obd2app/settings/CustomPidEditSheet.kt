package com.sj.obd2app.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sj.obd2app.databinding.SheetCustomPidEditBinding
import com.sj.obd2app.obd.CustomPid

/**
 * BottomSheetDialogFragment for creating or editing a [CustomPid].
 * Pass an existing custom PID id via [newInstance] to edit; omit for creation.
 */
class CustomPidEditSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_CUSTOM_PID_ID = "custom_pid_id"

        fun newInstance(customPidId: String? = null): CustomPidEditSheet =
            CustomPidEditSheet().apply {
                arguments = Bundle().apply {
                    if (customPidId != null) putString(ARG_CUSTOM_PID_ID, customPidId)
                }
            }
    }

    private var _binding: SheetCustomPidEditBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: VehicleProfileRepository
    private var editingPid: CustomPid? = null

    var onSaved: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SheetCustomPidEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = VehicleProfileRepository.getInstance(requireContext())

        val customPidId = arguments?.getString(ARG_CUSTOM_PID_ID)
        if (customPidId != null) {
            val profile = repo.activeProfile
            editingPid = profile?.customPids?.firstOrNull { it.id == customPidId }
        }

        editingPid?.let { cp ->
            binding.tvSheetTitle.text = "Edit Custom PID"
            binding.etPidName.setText(cp.name)
            binding.etPidHeader.setText(cp.header)
            binding.etPidMode.setText(cp.mode)
            binding.etPidHex.setText(cp.pid)
            binding.etPidBytes.setText(cp.bytesReturned.toString())
            binding.etPidUnit.setText(cp.unit)
            binding.etPidFormula.setText(cp.formula)
            binding.swSigned.isChecked = cp.signed
            binding.btnDeletePid.visibility = View.VISIBLE
        } ?: run {
            binding.tvSheetTitle.text = "Add Custom PID"
            binding.btnDeletePid.visibility = View.GONE
        }

        binding.btnSavePid.setOnClickListener { save() }
        binding.btnCancelPid.setOnClickListener { dismiss() }
        binding.btnDeletePid.setOnClickListener {
            editingPid?.let { cp -> deletePid(cp.id) }
            onSaved?.invoke()
            dismiss()
        }
    }

    private fun save() {
        val name = binding.etPidName.text?.toString()?.trim()
        if (name.isNullOrEmpty()) {
            binding.etPidName.error = "Required"
            return
        }

        val pid = binding.etPidHex.text?.toString()?.trim()?.uppercase()
        if (pid.isNullOrEmpty()) {
            binding.etPidHex.error = "Required"
            return
        }

        val formula = binding.etPidFormula.text?.toString()?.trim()
        if (formula.isNullOrEmpty()) {
            binding.etPidFormula.error = "Required"
            return
        }

        val header = binding.etPidHeader.text?.toString()?.trim()?.uppercase() ?: ""
        val mode = binding.etPidMode.text?.toString()?.trim()?.uppercase() ?: "22"
        val bytes = binding.etPidBytes.text?.toString()?.toIntOrNull() ?: 2
        val unit = binding.etPidUnit.text?.toString()?.trim() ?: ""
        val signed = binding.swSigned.isChecked

        val customPid = (editingPid ?: CustomPid(name = name, pid = pid)).copy(
            name = name,
            header = header,
            mode = mode,
            pid = pid,
            bytesReturned = bytes,
            unit = unit,
            formula = formula,
            signed = signed,
            enabled = true
        )

        val profile = repo.activeProfile ?: return
        val updatedList = profile.customPids.toMutableList()
        val existingIdx = updatedList.indexOfFirst { it.id == customPid.id }
        if (existingIdx >= 0) {
            updatedList[existingIdx] = customPid
        } else {
            updatedList.add(customPid)
        }
        repo.save(profile.copy(customPids = updatedList))
        onSaved?.invoke()
        dismiss()
    }

    private fun deletePid(pidId: String) {
        val profile = repo.activeProfile ?: return
        val updatedList = profile.customPids.filter { it.id != pidId }
        repo.save(profile.copy(customPids = updatedList))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
