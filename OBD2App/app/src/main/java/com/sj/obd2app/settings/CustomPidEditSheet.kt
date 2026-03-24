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
        private const val ARG_PROFILE_ID = "profile_id"

        fun newInstance(customPidId: String? = null, profileId: String? = null): CustomPidEditSheet =
            CustomPidEditSheet().apply {
                arguments = Bundle().apply {
                    if (customPidId != null) putString(ARG_CUSTOM_PID_ID, customPidId)
                    if (profileId != null) putString(ARG_PROFILE_ID, profileId)
                }
            }
    }

    private var _binding: SheetCustomPidEditBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: VehicleProfileRepository
    private var editingPid: CustomPid? = null
    private var profileId: String? = null

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
        profileId = arguments?.getString(ARG_PROFILE_ID)

        val customPidId = arguments?.getString(ARG_CUSTOM_PID_ID)
        val currentProfileId = profileId // Local copy to avoid smart cast issues
        android.util.Log.d("CustomPidEditSheet", "Editing PID: $customPidId, ProfileId: $currentProfileId")
        
        if (customPidId != null) {
            val profile = if (currentProfileId != null) repo.getById(currentProfileId) else repo.activeProfile
            android.util.Log.d("CustomPidEditSheet", "Profile found: ${profile?.name}, PIDs count: ${profile?.customPids?.size}")
            
            if (profile != null) {
                editingPid = profile.customPids.firstOrNull { it.id == customPidId }
                android.util.Log.d("CustomPidEditSheet", "Editing PID found: $editingPid")
            } else {
                android.util.Log.e("CustomPidEditSheet", "No profile found when trying to edit PID (profileId=$profileId)")
                dismiss()
                return
            }
        }

        // Post UI binding to ensure it happens after layout is ready
        binding.root.post {
            editingPid?.let { cp ->
                android.util.Log.d("CustomPidEditSheet", "Binding UI for PID: name=${cp.name}, header=${cp.header}, mode=${cp.mode}, pid=${cp.pid}, bytes=${cp.bytesReturned}, unit=${cp.unit}, formula=${cp.formula}, signed=${cp.signed}")
                
                // Clear all fields first to avoid layout defaults interfering
                binding.etPidName.text?.clear()
                binding.etPidHeader.text?.clear()
                binding.etPidMode.text?.clear()
                binding.etPidHex.text?.clear()
                binding.etPidBytes.text?.clear()
                binding.etPidUnit.text?.clear()
                binding.etPidFormula.text?.clear()
                
                // Now set the actual values
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
                
                // Log what's actually in the fields after binding
                android.util.Log.d("CustomPidEditSheet", "After binding - Name: ${binding.etPidName.text}, Header: ${binding.etPidHeader.text}, Mode: ${binding.etPidMode.text}, PID: ${binding.etPidHex.text}, Bytes: ${binding.etPidBytes.text}, Unit: ${binding.etPidUnit.text}, Formula: ${binding.etPidFormula.text}")
            } ?: run {
                android.util.Log.d("CustomPidEditSheet", "No editing PID found, showing add UI")
                binding.tvSheetTitle.text = "Add Custom PID"
                binding.btnDeletePid.visibility = View.GONE
            }
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
        if (!pid.matches(Regex("^[0-9A-F]{1,4}$"))) {
            binding.etPidHex.error = "Invalid hex format (1-4 characters)"
            return
        }

        val formula = binding.etPidFormula.text?.toString()?.trim()
        if (formula.isNullOrEmpty()) {
            binding.etPidFormula.error = "Required"
            return
        }
        // Basic formula validation - only allow safe characters
        if (!formula.matches(Regex("^[A-D0-9+\\-*/() ]+$"))) {
            binding.etPidFormula.error = "Invalid formula characters (only A-D, 0-9, +, -, *, /, (, ) allowed)"
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

        val currentProfileId = profileId // Local copy to avoid smart cast issues
        val profile = if (currentProfileId != null) repo.getById(currentProfileId) else repo.activeProfile
        if (profile == null) {
            android.util.Log.e("CustomPidEditSheet", "No profile found when saving PID (profileId=$currentProfileId)")
            com.sj.obd2app.ui.showToast(requireContext(), "Error: No vehicle profile found")
            return
        }
        
        val updatedList = profile.customPids.toMutableList()
        val existingIdx = updatedList.indexOfFirst { it.id == customPid.id }
        val isUpdate = existingIdx >= 0
        
        if (isUpdate) {
            updatedList[existingIdx] = customPid
        } else {
            updatedList.add(customPid)
        }
        
        android.util.Log.d("CustomPidEditSheet", "Saving profile: ${profile.name} with ${updatedList.size} PIDs")
        
        repo.save(profile.copy(customPids = updatedList))
        
        android.util.Log.d("CustomPidEditSheet", "Save completed for profile: ${profile.name}")
        
        // Show success Toast
        val message = if (isUpdate) {
            "Updated '${customPid.name}' in ${profile.name}"
        } else {
            "Added '${customPid.name}' to ${profile.name}"
        }
        com.sj.obd2app.ui.showToast(requireContext(), message)
        
        onSaved?.invoke()
        dismiss()
    }

    private fun deletePid(pidId: String) {
        val currentProfileId = profileId // Local copy to avoid smart cast issues
        val profile = if (currentProfileId != null) repo.getById(currentProfileId) else repo.activeProfile
        if (profile == null) return
        val updatedList = profile.customPids.filter { it.id != pidId }
        repo.save(profile.copy(customPids = updatedList))
        com.sj.obd2app.ui.showToast(requireContext(), "Deleted from ${profile.name}")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
