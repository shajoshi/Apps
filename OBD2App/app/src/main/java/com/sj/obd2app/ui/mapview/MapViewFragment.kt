package com.sj.obd2app.ui.mapview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.RectF
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.sj.obd2app.MainActivity
import com.sj.obd2app.MainPagerAdapter
import com.sj.obd2app.R
import com.sj.obd2app.databinding.FragmentMapViewBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.json.JSONObject

class MapViewFragment : Fragment() {

    private var _binding: FragmentMapViewBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MapViewModel
    private val pathPoints = mutableListOf<GeoPoint>()
    private var routePolyline: Polyline? = null
    private var cursorMarker: Marker? = null
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var sampleIndex = 0
    private var loadedTrackFileName: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        viewModel = ViewModelProvider(this)[MapViewModel::class.java]
        _binding = FragmentMapViewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Configuration.getInstance().userAgentValue = requireContext().packageName
        setupMap()
        binding.topBarInclude.txtTopBarTitle.text = "Map View"
        binding.topBarInclude.btnTopBack.visibility = View.VISIBLE
        binding.topBarInclude.btnTopBack.setOnClickListener {
            (activity as? MainActivity)?.navigateToPage(MainPagerAdapter.PAGE_TRIP_SUMMARY)
        }
        binding.topBarInclude.btnTopMap.visibility = View.GONE
        binding.topBarInclude.btnTopSave.visibility = View.GONE
        binding.topBarInclude.btnTopOverflow.visibility = View.GONE

        binding.btnDetails.setOnClickListener { showCurrentSampleDetails() }
        binding.btnFirst.setOnClickListener { updateCursor(0) }
        binding.btnPrev.setOnClickListener  { updateCursor(sampleIndex - 1) }
        binding.btnNext.setOnClickListener  { updateCursor(sampleIndex + 1) }
        binding.btnLast.setOnClickListener  { updateCursor(pathPoints.lastIndex) }
        
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (childFragmentManager.backStackEntryCount > 0) {
                    childFragmentManager.popBackStack()
                    binding.sampleDetailsContainer.visibility = View.GONE
                } else {
                    (activity as? MainActivity)?.navigateToPage(MainPagerAdapter.PAGE_TRIP_SUMMARY)
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        
        bindSelectedTrack()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            binding.mapView.onResume()
            bindSelectedTrack()
        }
    }

    private fun setupMap() {
        if (_binding != null) {
            binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
            binding.mapView.setMultiTouchControls(true)
        }
    }

    private fun bindSelectedTrack() {
        if (_binding == null) return
        
        val track = viewModel.selectedTrack
        if (track == null) {
            showEmptyState("No track selected")
            return
        }

        if (loadedTrackFileName == track.fileName && pathPoints.isNotEmpty()) {
            return
        }

        loadedTrackFileName = track.fileName
        pathPoints.clear()
        sampleIndex = 0
        binding.mapView.overlays.clear()
        binding.tvEmptyState.visibility = View.GONE
        binding.seekRoute.isEnabled = true
        binding.seekRoute.max = (track.samples.size - 1).coerceAtLeast(0)
        binding.seekRoute.progress = 0
        binding.tvTrackName.text = track.fileName

        buildPath(track.samples)
        if (pathPoints.isEmpty()) {
            showEmptyState("No GPS points available in this track")
            return
        }

        binding.mapView.post {
            if (_binding == null) return@post

            renderRoute()
            setupCursor()
            setupSeekBar()
            updateCursor(0)
        }
    }

    private fun showEmptyState(message: String) {
        if (_binding == null) return
        binding.tvEmptyState.visibility = View.VISIBLE
        binding.tvEmptyState.text = message
        binding.seekRoute.isEnabled = false
    }

    private fun buildPath(samples: List<JSONObject>) {
        samples.forEach { sample ->
            val gps = sample.optJSONObject("gps") ?: return@forEach
            val lat = gps.optDouble("lat", Double.NaN)
            val lon = gps.optDouble("lon", Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) {
                pathPoints.add(GeoPoint(lat, lon))
            }
        }
    }

    private fun renderRoute() {
        if (_binding == null) return
        
        routePolyline = Polyline().apply {
            setPoints(pathPoints)
            outlinePaint.color = 0xFF2F80ED.toInt()
            outlinePaint.strokeWidth = 10f
        }
        binding.mapView.overlays.add(routePolyline)

        startMarker = Marker(binding.mapView).apply {
            position = pathPoints.first()
            title = "Start"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = createColoredMarkerIcon(Color.parseColor("#4CAF50"))
        }
        endMarker = Marker(binding.mapView).apply {
            position = pathPoints.last()
            title = "End"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = createColoredMarkerIcon(Color.parseColor("#F44336"))
        }
        binding.mapView.overlays.add(startMarker)
        binding.mapView.overlays.add(endMarker)

        val bbox = org.osmdroid.util.BoundingBox.fromGeoPoints(pathPoints)
        binding.mapView.zoomToBoundingBox(bbox, true, 80)
    }

    private fun setupCursor() {
        if (_binding == null) return
        
        cursorMarker = Marker(binding.mapView).apply {
            position = pathPoints.first()
            title = "Cursor"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = createCursorIcon()
        }
        binding.mapView.overlays.add(cursorMarker)
    }

    private fun createCursorIcon(): android.graphics.drawable.Drawable {
        return object : ShapeDrawable(OvalShape()) {
            init {
                val size = 36
                intrinsicWidth = size
                intrinsicHeight = size
                paint.isAntiAlias = true
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#202020")
                setBounds(0, 0, size, size)
            }

            override fun draw(canvas: Canvas) {
                super.draw(canvas)
                val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                    color = Color.parseColor("#D0D0D0")
                }
                val outerInset = 2f
                canvas.drawOval(
                    outerInset,
                    outerInset,
                    (intrinsicWidth - outerInset),
                    (intrinsicHeight - outerInset),
                    ringPaint
                )

                val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.BLACK
                }
                val centerInset = 11f
                canvas.drawOval(
                    centerInset,
                    centerInset,
                    (intrinsicWidth - centerInset),
                    (intrinsicHeight - centerInset),
                    centerPaint
                )
            }
        }
    }

    private fun createColoredMarkerIcon(color: Int): android.graphics.drawable.Drawable {
        return object : ShapeDrawable(OvalShape()) {
            init {
                val size = 24
                intrinsicWidth = size
                intrinsicHeight = size
                paint.isAntiAlias = true
                paint.style = Paint.Style.FILL
                paint.color = color
                setBounds(0, 0, size, size)
            }

            override fun draw(canvas: Canvas) {
                super.draw(canvas)
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }
                borderPaint.color = Color.WHITE
                canvas.drawOval(1.5f, 1.5f, (intrinsicWidth - 1.5f), (intrinsicHeight - 1.5f), borderPaint)
            }
        }
    }

    private fun setupSeekBar() {
        if (_binding == null) return
        
        binding.seekRoute.max = (pathPoints.size - 1).coerceAtLeast(0)
        binding.seekRoute.progress = 0
        binding.seekRoute.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateCursor(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun updateCursor(index: Int) {
        if (_binding == null || pathPoints.isEmpty()) return
        sampleIndex = index.coerceIn(0, pathPoints.lastIndex)
        binding.seekRoute.progress = sampleIndex
        val point = pathPoints[sampleIndex]
        cursorMarker?.position = point
        
        // Extract speed for display in cursor info
        val track = viewModel.selectedTrack
        val sample = track?.samples?.getOrNull(sampleIndex)
        val obdSpeed = sample?.optJSONObject("obd")?.optDouble("speedKmh", 0.0) ?: 0.0
        val speedText = "${obdSpeed.toInt()} km/h"
        val altMsl = sample?.optJSONObject("gps")?.optDouble("altMsl", Double.NaN) ?: Double.NaN
        val altText = if (!altMsl.isNaN()) " • ${altMsl.toInt()} m" else ""
        
        binding.mapView.invalidate()
        binding.tvCursorInfo.text = "Sample ${sampleIndex + 1} / ${pathPoints.size} • $speedText$altText"
    }

    private fun showCurrentSampleDetails() {
        val track = viewModel.selectedTrack ?: return
        if (track.samples.isEmpty()) return
        binding.sampleDetailsContainer.visibility = View.VISIBLE
        val fragment = SampleDetailsFragment.newInstance(sampleIndex)
        childFragmentManager.beginTransaction()
            .replace(R.id.sample_details_container, fragment, "sample_details")
            .addToBackStack("sample_details")
            .commit()
        childFragmentManager.addOnBackStackChangedListener {
            if (childFragmentManager.backStackEntryCount == 0) {
                binding.sampleDetailsContainer.visibility = View.GONE
            }
        }
    }

    override fun onPause() {
        if (_binding != null) {
            binding.mapView.onPause()
        }
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
