package com.sj.obd2app.ui.mapview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.sj.obd2app.databinding.FragmentSampleDetailsBinding
import kotlinx.coroutines.launch
import org.json.JSONObject

class SampleDetailsFragment : Fragment() {

    private var _binding: FragmentSampleDetailsBinding? = null
    private val binding get() = _binding!!

    private var currentIndex = 0
    private var totalCount = 0
    private var currentSample: JSONObject? = null
    private lateinit var mapViewModel: MapViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSampleDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mapViewModel = ViewModelProvider(requireParentFragment())[MapViewModel::class.java]
        currentIndex = requireArguments().getInt(ARG_INDEX, 0)
        totalCount = requireArguments().getInt(ARG_TOTAL, mapViewModel.sampleCount)

        binding.topBarInclude.btnTopBack.visibility = View.VISIBLE
        binding.topBarInclude.btnTopBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.topBarInclude.btnTopMap.visibility = View.GONE
        binding.topBarInclude.btnTopSave.visibility = View.GONE
        binding.topBarInclude.btnTopOverflow.visibility = View.GONE

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { parentFragmentManager.popBackStack() }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            mapViewModel.fetchedSample.collect { sample ->
                currentSample = sample
                if (sample != null) displaySample(currentIndex, sample)
            }
        }

        binding.btnFirst.setOnClickListener { navigate(0) }
        binding.btnPrev.setOnClickListener  { navigate((currentIndex - 1).coerceAtLeast(0)) }
        binding.btnNext.setOnClickListener  { navigate((currentIndex + 1).coerceAtMost(totalCount - 1)) }
        binding.btnLast.setOnClickListener  { navigate(totalCount - 1) }

        binding.btnCopy.setOnClickListener {
            val text = try { currentSample?.toString(2) } catch (e: Exception) { currentSample?.toString() } ?: "{}"
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Sample JSON", text))
            Toast.makeText(requireContext(), "JSON copied", Toast.LENGTH_SHORT).show()
        }

        mapViewModel.fetchSample(currentIndex)
    }

    private fun navigate(index: Int) {
        currentIndex = index
        mapViewModel.fetchSample(currentIndex)
    }

    private fun displaySample(index: Int, sample: JSONObject) {
        if (_binding == null) return
        val speed = sample.optJSONObject("obd")?.optDouble("speedKmh", Double.NaN) ?: Double.NaN
        val altMsl = sample.optJSONObject("gps")?.optDouble("altMsl", Double.NaN) ?: Double.NaN
        val speedPart = if (!speed.isNaN()) " • ${speed.toInt()} km/h" else ""
        val altPart = if (!altMsl.isNaN()) " • ${altMsl.toInt()} m" else ""
        binding.topBarInclude.txtTopBarTitle.text = "Sample ${index + 1}/$totalCount$speedPart$altPart"
        val pretty = try { sample.toString(2) } catch (e: Exception) { sample.toString() }
        binding.tvJson.text = ""
        binding.tvJson.text = pretty
        binding.scrollJson.scrollTo(0, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_INDEX = "index"
        private const val ARG_TOTAL = "total"

        fun newInstance(index: Int, total: Int) = SampleDetailsFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_INDEX, index)
                putInt(ARG_TOTAL, total)
            }
        }
    }
}
