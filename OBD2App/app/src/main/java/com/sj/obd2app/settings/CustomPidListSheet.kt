package com.sj.obd2app.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sj.obd2app.databinding.SheetCustomPidListBinding
import com.sj.obd2app.databinding.ItemCustomPidBinding
import com.sj.obd2app.obd.CustomPid
import com.sj.obd2app.ui.settings.PidDiscoverySheet

/**
 * BottomSheetDialogFragment that lists all custom PIDs for the active vehicle profile.
 * Tapping a PID opens [CustomPidEditSheet] to edit it; the + Add button creates a new one.
 */
class CustomPidListSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_PROFILE_ID = "profile_id"
        
        fun newInstance(profileId: String? = null): CustomPidListSheet {
            return CustomPidListSheet().apply {
                arguments = Bundle().apply {
                    if (profileId != null) putString(ARG_PROFILE_ID, profileId)
                }
            }
        }
    }

    private var _binding: SheetCustomPidListBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: VehicleProfileRepository
    private lateinit var adapter: CustomPidAdapter
    private var profileId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SheetCustomPidListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = VehicleProfileRepository.getInstance(requireContext())
        profileId = arguments?.getString(ARG_PROFILE_ID)

        adapter = CustomPidAdapter { customPid ->
            val editSheet = CustomPidEditSheet.newInstance(customPid.id)
            editSheet.onSaved = { refreshList() }
            editSheet.show(parentFragmentManager, "edit_custom_pid")
        }

        binding.rvCustomPids.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCustomPids.adapter = adapter

        binding.btnDiscoverPids.setOnClickListener {
            val discoverySheet = PidDiscoverySheet.newInstance(profileId)
            discoverySheet.show(parentFragmentManager, "pid_discovery")
        }

        binding.btnAddCustomPid.setOnClickListener {
            val editSheet = CustomPidEditSheet.newInstance()
            editSheet.onSaved = { refreshList() }
            editSheet.show(parentFragmentManager, "add_custom_pid")
        }

        binding.btnCloseList.setOnClickListener { dismiss() }

        refreshList()
    }

    private fun refreshList() {
        val profile = if (profileId != null) repo.getById(profileId!!) else repo.activeProfile
        val customPids = profile?.customPids ?: emptyList()
        if (customPids.isEmpty()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.rvCustomPids.visibility = View.GONE
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.rvCustomPids.visibility = View.VISIBLE
            adapter.submitList(customPids)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    private class CustomPidAdapter(
        private val onClick: (CustomPid) -> Unit
    ) : RecyclerView.Adapter<CustomPidAdapter.ViewHolder>() {

        private var items: List<CustomPid> = emptyList()

        fun submitList(list: List<CustomPid>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemCustomPidBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(private val binding: ItemCustomPidBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(cp: CustomPid) {
                binding.tvPidName.text = cp.name
                val headerStr = if (cp.header.isNotEmpty()) "H:${cp.header}" else "H:7DF"
                binding.tvPidDetail.text = "Mode ${cp.mode} | PID ${cp.pid} | ${cp.bytesReturned}B | $headerStr"
                binding.tvPidFormula.text = cp.formula
                binding.root.setOnClickListener { onClick(cp) }

                val bgColor = if (bindingAdapterPosition % 2 == 0) 0xFF1A1A2E.toInt() else 0xFF22223A.toInt()
                binding.root.setBackgroundColor(bgColor)
            }
        }
    }
}
