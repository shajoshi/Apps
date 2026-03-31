package com.sj.obd2app.ui.mapview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sj.obd2app.databinding.BottomSheetSampleDetailsBinding
import org.json.JSONObject

class SampleDetailsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSampleDetailsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetSampleDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val sampleJson = JSONObject(requireArguments().getString(ARG_SAMPLE_JSON) ?: "{}")
        val index = requireArguments().getInt(ARG_INDEX)
        val total = requireArguments().getInt(ARG_TOTAL)
        val formattedJson = sampleJson.toString(2)
        binding.tvTitle.text = "Sample $index of $total"
        binding.tvJson.text = formattedJson
        binding.btnCopy.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Sample JSON", formattedJson))
            Toast.makeText(requireContext(), "JSON copied", Toast.LENGTH_SHORT).show()
        }
        binding.btnClose.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SAMPLE_JSON = "sample_json"
        private const val ARG_INDEX = "index"
        private const val ARG_TOTAL = "total"

        fun newInstance(sampleJson: String, index: Int, total: Int) = SampleDetailsBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_SAMPLE_JSON, sampleJson)
                putInt(ARG_INDEX, index)
                putInt(ARG_TOTAL, total)
            }
        }
    }
}
