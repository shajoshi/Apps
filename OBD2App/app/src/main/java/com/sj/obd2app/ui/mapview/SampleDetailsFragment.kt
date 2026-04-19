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
import com.sj.obd2app.databinding.FragmentSampleDetailsBinding
import com.sj.obd2app.ui.tripsummary.TripSelectionStore
import org.json.JSONObject

class SampleDetailsFragment : Fragment() {

    private var _binding: FragmentSampleDetailsBinding? = null
    private val binding get() = _binding!!

    private var samples: List<JSONObject> = emptyList()
    private var currentIndex = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSampleDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        samples = TripSelectionStore.selectedTrack?.samples ?: emptyList()
        currentIndex = requireArguments().getInt(ARG_INDEX, 0)

        binding.topBarInclude.btnTopBack.visibility = View.VISIBLE
        binding.topBarInclude.btnTopBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.topBarInclude.btnTopMap.visibility = View.GONE
        binding.topBarInclude.btnTopSave.visibility = View.GONE
        binding.topBarInclude.btnTopOverflow.visibility = View.GONE

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { parentFragmentManager.popBackStack() }
        })

        binding.btnFirst.setOnClickListener { navigate(0) }
        binding.btnPrev.setOnClickListener  { navigate((currentIndex - 1).coerceAtLeast(0)) }
        binding.btnNext.setOnClickListener  { navigate((currentIndex + 1).coerceAtMost(samples.lastIndex)) }
        binding.btnLast.setOnClickListener  { navigate(samples.lastIndex) }

        binding.btnCopy.setOnClickListener {
            val text = try { samples.getOrNull(currentIndex)?.toString(2) } catch (e: Exception) { samples.getOrNull(currentIndex)?.toString() } ?: "{}"
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Sample JSON", text))
            Toast.makeText(requireContext(), "JSON copied", Toast.LENGTH_SHORT).show()
        }

        displaySample(currentIndex)
    }

    private fun navigate(index: Int) {
        currentIndex = index
        displaySample(currentIndex)
    }

    private fun displaySample(index: Int) {
        val sample = samples.getOrNull(index) ?: return
        val speed = sample.optJSONObject("obd")?.optDouble("speedKmh", Double.NaN) ?: Double.NaN
        val altMsl = sample.optJSONObject("gps")?.optDouble("altMsl", Double.NaN) ?: Double.NaN
        val speedPart = if (!speed.isNaN()) " • ${speed.toInt()} km/h" else ""
        val altPart = if (!altMsl.isNaN()) " • ${altMsl.toInt()} m" else ""
        binding.topBarInclude.txtTopBarTitle.text = "Sample ${index + 1}/${samples.size}$speedPart$altPart"
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

        fun newInstance(index: Int) = SampleDetailsFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_INDEX, index)
            }
        }
    }
}
