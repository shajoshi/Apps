package com.sj.obd2app.ui.dashboard.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.sj.obd2app.R
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.ScaleBarOverlay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dashboard widget that renders a real-time OSM map centered on the vehicle's
 * GPS position with a bearing indicator and 4 configurable corner metric overlays.
 *
 * This is NOT a [DashboardGaugeView] subclass — it hosts an osmdroid [MapView]
 * inside a [FrameLayout] and is special-cased by the dashboard editor.
 */
class LiveMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val mapView: MapView
    private val cornerTLContainer: LinearLayout
    private val valueTL: TextView
    private val unitTL: TextView
    private val labelTL: TextView
    private val cornerTRContainer: LinearLayout
    private val valueTR: TextView
    private val unitTR: TextView
    private val labelTR: TextView
    private val cornerBLContainer: LinearLayout
    private val valueBL: TextView
    private val unitBL: TextView
    private val labelBL: TextView
    private val cornerBRContainer: LinearLayout
    private val valueBR: TextView
    private val unitBR: TextView
    private val labelBR: TextView
    private val scaleBarOverlay: ScaleBarOverlay

    private var vehicleMarker: Marker? = null
    private var lastBearingDeg: Float? = null
    private var mapReady = false
    var isEditMode = false  // Set by DashboardEditorFragment when in edit mode
        set(value) {
            field = value
            updateEditMode()
        }

    /** Corner identifiers used by [updateCornerValue]. */
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_live_map, this, true)
        mapView = findViewById(R.id.live_map_view)
        cornerTLContainer = findViewById(R.id.corner_tl_container)
        valueTL = findViewById(R.id.value_tl)
        unitTL = findViewById(R.id.unit_tl)
        labelTL = findViewById(R.id.label_tl)
        cornerTRContainer = findViewById(R.id.corner_tr_container)
        valueTR = findViewById(R.id.value_tr)
        unitTR = findViewById(R.id.unit_tr)
        labelTR = findViewById(R.id.label_tr)
        cornerBLContainer = findViewById(R.id.corner_bl_container)
        valueBL = findViewById(R.id.value_bl)
        unitBL = findViewById(R.id.unit_bl)
        labelBL = findViewById(R.id.label_bl)
        cornerBRContainer = findViewById(R.id.corner_br_container)
        valueBR = findViewById(R.id.value_br)
        unitBR = findViewById(R.id.unit_br)
        labelBR = findViewById(R.id.label_br)

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(17.0)

        // Add scale bar overlay
        scaleBarOverlay = ScaleBarOverlay(mapView)
        mapView.overlays.add(scaleBarOverlay)

        updateEditMode()
    }

    private fun updateEditMode() {
        if (isEditMode) {
            // In edit mode, hide the MapView and show a placeholder to prevent touch event consumption
            mapView.visibility = View.GONE
            scaleBarOverlay.setEnabled(false)
        } else {
            // In view mode, show the MapView for map rendering and interaction
            mapView.visibility = View.VISIBLE
            scaleBarOverlay.setEnabled(true)
        }
    }

    /**
     * Updates the vehicle position and bearing on the map.
     * Centres the map on the new position without changing the user's zoom level.
     */
    fun updateLocation(lat: Double, lon: Double, bearingDeg: Float?) {
        if (lat == 0.0 && lon == 0.0) return // Ignore invalid coordinates

        val point = GeoPoint(lat, lon)
        lastBearingDeg = bearingDeg

        // Ensure the MapView repository is initialised before creating markers
        if (mapView.repository == null) {
            mapView.post { updateLocation(lat, lon, bearingDeg) }
            return
        }

        if (vehicleMarker == null) {
            mapReady = true
            vehicleMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
            }
            mapView.overlays.add(vehicleMarker)
        }

        vehicleMarker?.position = point
        vehicleMarker?.icon = createVehicleIcon(bearingDeg)
        mapView.controller.animateTo(point)
        mapView.invalidate()
    }

    /**
     * Updates the text shown in one of the four corner overlays.
     * Displays the number inside the circular badge, unit to the right, and label below.
     */
    fun updateCornerValue(corner: Corner, label: String, value: String, unit: String) {
        when (corner) {
            Corner.TOP_LEFT -> {
                valueTL.text = value
                unitTL.text = unit
                labelTL.text = label
                cornerTLContainer.visibility = if (label.isNotEmpty() || value.isNotEmpty()) View.VISIBLE else View.GONE
            }
            Corner.TOP_RIGHT -> {
                valueTR.text = value
                unitTR.text = unit
                labelTR.text = label
                cornerTRContainer.visibility = if (label.isNotEmpty() || value.isNotEmpty()) View.VISIBLE else View.GONE
            }
            Corner.BOTTOM_LEFT -> {
                valueBL.text = value
                unitBL.text = unit
                labelBL.text = label
                cornerBLContainer.visibility = if (label.isNotEmpty() || value.isNotEmpty()) View.VISIBLE else View.GONE
            }
            Corner.BOTTOM_RIGHT -> {
                valueBR.text = value
                unitBR.text = unit
                labelBR.text = label
                cornerBRContainer.visibility = if (label.isNotEmpty() || value.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ── Lifecycle pass-through ─────────────────────────────────────────────

    fun onResume() {
        mapView.onResume()
    }

    fun onPause() {
        mapView.onPause()
    }

    // ── Vehicle icon ───────────────────────────────────────────────────────

    /**
     * Creates a filled circle with a heading wedge that rotates with the bearing.
     * If bearing is null (stationary), draws just the circle without a wedge.
     */
    private fun createVehicleIcon(bearingDeg: Float?): android.graphics.drawable.Drawable {
        val size = 48
        return object : ShapeDrawable(OvalShape()) {
            init {
                intrinsicWidth = size
                intrinsicHeight = size
                paint.isAntiAlias = true
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#2979FF")
                setBounds(0, 0, size, size)
            }

            override fun draw(canvas: Canvas) {
                val cx = (intrinsicWidth / 2).toFloat()
                val cy = (intrinsicHeight / 2).toFloat()
                val radius = cx * 0.85f

                // Outer glow ring
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = Color.parseColor("#802979FF")
                }
                canvas.drawCircle(cx, cy, radius + 2f, glowPaint)

                // Filled circle
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.parseColor("#2979FF")
                }
                canvas.drawCircle(cx, cy, radius, fillPaint)

                // White border
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f
                    color = Color.WHITE
                }
                canvas.drawCircle(cx, cy, radius, borderPaint)

                // Inner dot
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.WHITE
                }
                canvas.drawCircle(cx, cy, radius * 0.28f, dotPaint)

                // Heading wedge (only if bearing available)
                if (bearingDeg != null) {
                    val wedgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = Color.parseColor("#2979FF")
                    }
                    val wedgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 2f
                        color = Color.WHITE
                    }

                    // Bearing is degrees clockwise from north; canvas 0° is east (right).
                    // Convert: canvas angle = bearing - 90
                    val angleRad = Math.toRadians((bearingDeg - 90.0).toDouble())
                    val wedgeHalfAngle = Math.toRadians(25.0)
                    val wedgeLen = radius + 8f

                    val path = Path()
                    path.moveTo(cx, cy)
                    path.lineTo(
                        cx + (wedgeLen * cos(angleRad - wedgeHalfAngle)).toFloat(),
                        cy + (wedgeLen * sin(angleRad - wedgeHalfAngle)).toFloat()
                    )
                    path.lineTo(
                        cx + (wedgeLen * cos(angleRad)).toFloat(),
                        cy + (wedgeLen * sin(angleRad)).toFloat()
                    )
                    path.lineTo(
                        cx + (wedgeLen * cos(angleRad + wedgeHalfAngle)).toFloat(),
                        cy + (wedgeLen * sin(angleRad + wedgeHalfAngle)).toFloat()
                    )
                    path.close()
                    canvas.drawPath(path, wedgePaint)
                    canvas.drawPath(path, wedgeBorderPaint)
                }
            }
        }
    }
}
