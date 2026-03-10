package com.sj.obd2app.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sj.obd2app.databinding.SheetVehicleProfileEditBinding

/**
 * BottomSheetDialogFragment for creating or editing a [VehicleProfile].
 * Pass an existing profile via [newInstance] to edit; omit for creation.
 */
class VehicleProfileEditSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_PROFILE_ID = "profile_id"

        fun newInstance(profileId: String? = null): VehicleProfileEditSheet =
            VehicleProfileEditSheet().apply {
                arguments = Bundle().apply {
                    if (profileId != null) putString(ARG_PROFILE_ID, profileId)
                }
            }
    }

    private var _binding: SheetVehicleProfileEditBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: VehicleProfileRepository
    private var editingProfile: VehicleProfile? = null

    var onSaved: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SheetVehicleProfileEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = VehicleProfileRepository.getInstance(requireContext())

        val profileId = arguments?.getString(ARG_PROFILE_ID)
        if (profileId != null) {
            editingProfile = repo.getById(profileId)
        }

        // Update hints with global defaults
        val globalPollMs = AppSettings.getGlobalPollingDelayMs(requireContext())
        val globalCmdMs  = AppSettings.getGlobalCommandDelayMs(requireContext())
        binding.tvPollingHint.text = "Global default: ${globalPollMs}ms"
        binding.tvCommandHint.text  = "Global default: ${globalCmdMs}ms"

        editingProfile?.let { p ->
            binding.tvSheetTitle.text = "Edit Profile"
            binding.etProfileName.setText(p.name)
            when (p.fuelType) {
                FuelType.PETROL -> binding.rbPetrol.isChecked = true
                FuelType.E20    -> binding.rbE20.isChecked = true
                FuelType.DIESEL -> binding.rbDiesel.isChecked = true
                FuelType.CNG    -> binding.rbCng.isChecked = true
            }
            binding.etTankCapacity.setText(if (p.tankCapacityL > 0f) p.tankCapacityL.toString() else "")
            binding.etFuelPrice.setText(if (p.fuelPricePerLitre > 0f) p.fuelPricePerLitre.toString() else "")
            binding.etEnginePower.setText(if (p.enginePowerBhp > 0f) p.enginePowerBhp.toString() else "")
            binding.etVehicleMass.setText(if (p.vehicleMassKg > 0f) p.vehicleMassKg.toString() else "")
            p.obdPollingDelayMs?.let { binding.etPollingDelay.setText(it.toString()) }
            p.obdCommandDelayMs?.let { binding.etCommandDelay.setText(it.toString()) }
            binding.btnDeleteProfile.visibility = View.VISIBLE
        } ?: run {
            binding.tvSheetTitle.text = "New Profile"
            binding.btnDeleteProfile.visibility = View.GONE
        }

        binding.btnSaveProfile.setOnClickListener { save() }
        binding.btnCancelProfile.setOnClickListener { dismiss() }
        binding.btnDeleteProfile.setOnClickListener {
            editingProfile?.let { repo.delete(it.id) }
            onSaved?.invoke()
            dismiss()
        }
    }

    private fun save() {
        val name = binding.etProfileName.text?.toString()?.trim()
        if (name.isNullOrEmpty()) {
            binding.etProfileName.error = "Required"
            return
        }

        val fuelType = when (binding.rgFuelType.checkedRadioButtonId) {
            binding.rbE20.id    -> FuelType.E20
            binding.rbDiesel.id -> FuelType.DIESEL
            binding.rbCng.id    -> FuelType.CNG
            else                -> FuelType.PETROL
        }

        val tank    = binding.etTankCapacity.text?.toString()?.toFloatOrNull() ?: 40f
        val price   = binding.etFuelPrice.text?.toString()?.toFloatOrNull() ?: 0f
        val power   = binding.etEnginePower.text?.toString()?.toFloatOrNull() ?: 0f
        val mass    = binding.etVehicleMass.text?.toString()?.toFloatOrNull() ?: 0f
        val polling = binding.etPollingDelay.text?.toString()?.toLongOrNull()
        val command = binding.etCommandDelay.text?.toString()?.toLongOrNull()

        val profile = (editingProfile ?: VehicleProfile()).copy(
            name              = name,
            fuelType          = fuelType,
            tankCapacityL     = tank,
            fuelPricePerLitre = price,
            enginePowerBhp    = power,
            vehicleMassKg     = mass,
            obdPollingDelayMs = polling,
            obdCommandDelayMs = command
        )

        repo.save(profile)
        onSaved?.invoke()
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
