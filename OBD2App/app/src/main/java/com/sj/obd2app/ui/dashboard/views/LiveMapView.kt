package com.sj.obd2app.ui.dashboard.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
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
    private var isBearingMode = false // Map orientation: false = north-up, true = bearing-up
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
        // Increase zoom level to make street labels appear larger (17 -> 18 doubles the scale)
        mapView.controller.setZoom(22.0)

        // Add scale bar overlay
        scaleBarOverlay = ScaleBarOverlay(mapView)
        mapView.overlays.add(scaleBarOverlay)

        // Ensure map repository is initialized even in edit mode
        mapView.post {
            android.util.Log.d("LiveMapView", "Map repository initialized: ${mapView.repository != null}")
        }

        updateEditMode()
    }

    private fun updateEditMode() {
        if (isEditMode) {
            // In edit mode, hide the MapView and show a placeholder to prevent touch event consumption
            // Use INVISIBLE instead of GONE to allow repository initialization
            mapView.visibility = View.INVISIBLE
            scaleBarOverlay.setEnabled(false)
        } else {
            // In view mode, show the MapView for map rendering and interaction
            mapView.visibility = View.VISIBLE
            scaleBarOverlay.setEnabled(true)
            // Ensure repository is initialized when switching to view mode
            if (mapView.repository == null) {
                android.util.Log.d("LiveMapView", "Switching to view mode, forcing repository initialization")
                mapView.post {
                    android.util.Log.d("LiveMapView", "Repository after view mode switch: ${mapView.repository != null}")
                }
            }
        }
    }

    /**
     * Returns the appropriate zoom level for a given speed in km/h.
     * Faster speed = lower zoom (wider area visible).
     */
    private fun zoomForSpeed(speedKmh: Float): Double = when {
        speedKmh < 25f   -> 19.0  // Stationary / walking
        speedKmh < 45f  -> 18.0  // Slow / residential
        speedKmh < 70f  -> 17.0  // Urban
        speedKmh < 100f -> 16.0  // Highway
        speedKmh < 150f -> 14.0  // Fast highway
        else            -> 13.0  // Very fast
    }

    /**
     * Updates the vehicle position and bearing on the map.
     * Centres the map on the new position and adjusts zoom based on speed.
     */
    fun updateLocation(lat: Double, lon: Double, bearingDeg: Float?, speedKmh: Float? = null) {
        android.util.Log.d("LiveMapView", "updateLocation: lat=$lat, lon=$lon, bearing=$bearingDeg, speed=$speedKmh")

        if (lat == 0.0 && lon == 0.0) {
            android.util.Log.d("LiveMapView", "Ignoring invalid coordinates (0, 0)")
            return
        }

        // Force layout if view has zero dimensions
        if (width == 0 || height == 0) {
            android.util.Log.d("LiveMapView", "View has zero dimensions, forcing layout...")
            measure(
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 240, 240)
        }

        val point = GeoPoint(lat, lon)
        lastBearingDeg = bearingDeg

        // Ensure the MapView repository is initialised before creating markers
        if (mapView.repository == null) {
            android.util.Log.d("LiveMapView", "Map repository not initialized, forcing layout...")
            // Force layout to ensure repository initializes
            mapView.measure(
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
            )
            mapView.layout(0, 0, 100, 100)
            if (mapView.repository == null) {
                android.util.Log.d("LiveMapView", "Repository still null, retrying...")
                mapView.post { updateLocation(lat, lon, bearingDeg, speedKmh) }
                return
            }
        }

        if (vehicleMarker == null) {
            mapReady = true
            vehicleMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
            }
            mapView.overlays.add(0, vehicleMarker) // Add at beginning to draw on top
            android.util.Log.d("LiveMapView", "Created vehicle marker")
        }

        mapView.controller.setCenter(point)
        // Auto-zoom based on speed
        if (speedKmh != null) {
            mapView.controller.setZoom(zoomForSpeed(speedKmh))
        }
        // Use custom vehicle arrow drawable
        val arrowIcon = ContextCompat.getDrawable(context, R.drawable.ic_vehicle_arrow)
        vehicleMarker?.icon = arrowIcon
        if (bearingDeg != null) {
            if (isBearingMode) {
                // Map is rotated to follow bearing, so arrow always points straight up (direction of travel)
                vehicleMarker?.rotation = 0f
                mapView.mapOrientation = -bearingDeg
            } else {
                // North-up mode: osmdroid Marker.rotation is counter-clockwise, GPS bearing is clockwise
                // Negate bearing to convert from clockwise to counter-clockwise
                vehicleMarker?.rotation = -bearingDeg
            }
        }
        android.util.Log.d("LiveMapView", "Arrow bearing: $bearingDeg, mode: ${if (isBearingMode) "bearing-up" else "north-up"}")
        // Position marker after map is rendered to ensure projection is ready
        mapView.post {
            vehicleMarker?.position = point
            mapView.postInvalidate()
        }
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

    /**
     * Sets the map orientation mode.
     * @param bearingMode true = map rotates to follow bearing, false = north-up mode
     */
    fun setMapOrientationMode(bearingMode: Boolean) {
        isBearingMode = bearingMode
        // Reset map rotation if switching to north-up mode
        if (!bearingMode) {
            mapView.mapOrientation = 0f
            mapView.invalidate()
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
                paint.color = Color.parseColor("#FF0000") // Changed to red for visibility
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
