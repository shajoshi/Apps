package com.sj.bkgtracker.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.net.Uri
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.preference.PreferenceManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import androidx.compose.material3.TextButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val TRACK_COLOURS = listOf(
    Color.parseColor("#1565C0"),
    Color.parseColor("#C62828"),
    Color.parseColor("#2E7D32"),
    Color.parseColor("#F57F17"),
    Color.parseColor("#6A1B9A"),
    Color.parseColor("#00838F")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    state: MapState,
    onRefresh: () -> Unit,
    onToggleUser: (String) -> Unit,
    onSetStartDate: (Long) -> Unit,
    onSetTimeWindow: (Int) -> Unit,
    onExport: () -> Unit = {},
    onExportRaw: () -> Unit = {},
    onEnableTimeline: () -> Unit = {},
    onDisableTimeline: () -> Unit = {},
    onSetTimelineIndex: (Int) -> Unit = {},
    onGoToStart: () -> Unit = {},
    onGoToEnd: () -> Unit = {},
    onGoToPrevious: () -> Unit = {},
    onGoToNext: () -> Unit = {}
) {
    val context = LocalContext.current
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {

        Surface(
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth().zIndex(1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val dateFormatter = remember { SimpleDateFormat("dd-MMM HH:mm", Locale.getDefault()) }

                val singleUserSelected = state.visibleUsers.size == 1
                TextButton(
                    onClick = { showDatePickerDialog(context, state.fromDateMs) { onSetStartDate(it) } },
                    enabled = singleUserSelected
                ) {
                    Text(dateFormatter.format(Date(state.fromDateMs)), style = MaterialTheme.typography.labelSmall)
                }

                MapViewModel.TIME_OPTIONS.forEach { hours ->
                    val label = when (hours) {
                        1 -> "1h"; 6 -> "6h"; else -> "24h"
                    }
                    FilterChip(
                        selected = state.timeWindowHours == hours,
                        onClick = { onSetTimeWindow(hours) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }

                IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
                IconButton(
                    onClick = onExport,
                    enabled = state.users.any { it.email in state.visibleUsers && it.points.isNotEmpty() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = "Export KML")
                }
                IconButton(
                    onClick = onExportRaw,
                    enabled = state.users.any { it.email in state.visibleUsers && it.points.isNotEmpty() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.BugReport, contentDescription = "Export Raw CSV")
                }
            }
        }

        // Timeline Slider - Always visible
        Surface(
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth().zIndex(1f)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                val rangeFormatter = remember { SimpleDateFormat("dd-MMM HH:mm", Locale.getDefault()) }
                val queryEnd = state.fromDateMs
                val queryStart = queryEnd - state.timeWindowHours * 3600_000L
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Timeline",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (state.users.isNotEmpty()) {
                            if (!state.timelineEnabled) {
                                // Enable timeline button
                                IconButton(
                                    onClick = onEnableTimeline,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = "Enable Timeline",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                // Timeline navigation controls
                                IconButton(
                                    onClick = onGoToStart,
                                    enabled = state.timelineIndex > 0,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text(
                                        text = "|◀",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(2.dp)
                                    )
                                }
                                IconButton(
                                    onClick = onGoToPrevious,
                                    enabled = state.timelineIndex > 0,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text(
                                        text = "◀",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(2.dp)
                                    )
                                }
                                IconButton(
                                    onClick = onGoToNext,
                                    enabled = state.timelineIndex < state.timelineTotal - 1,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text(
                                        text = "▶",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(2.dp)
                                    )
                                }
                                IconButton(
                                    onClick = onGoToEnd,
                                    enabled = state.timelineIndex < state.timelineTotal - 1,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text(
                                        text = "▶|",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(2.dp)
                                    )
                                }
                                IconButton(
                                    onClick = onDisableTimeline,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text(
                                        text = "✕",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    text = "From ${rangeFormatter.format(Date(queryStart))}",
                    style = MaterialTheme.typography.bodySmall
                )
                
                if (state.timelineEnabled && state.timelineTotal > 0) {
                    // Timeline slider
                    Column {
                        Slider(
                            value = state.timelineIndex.toFloat(),
                            onValueChange = { value -> onSetTimelineIndex(value.toInt()) },
                            valueRange = 0f..(state.timelineTotal - 1).toFloat(),
                            steps = if (state.timelineTotal > 2) state.timelineTotal - 2 else 0,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        // Timeline info
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            state.currentTimelinePoint?.let { point ->
                                val sdf = SimpleDateFormat("dd-MMM HH:mm:ss", Locale.getDefault())
                                val timeStr = sdf.format(Date(point.timestampMs))
                                Text(
                                    text = "Point ${state.timelineIndex + 1}/${state.timelineTotal}: $timeStr",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "%.1f km/h".format(point.speedKmh),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    IconButton(
                                        onClick = {
                                            val label = Uri.encode(timeStr)
                                            val geoUri = Uri.parse(
                                                "geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}($label)"
                                            )
                                            val intent = Intent(Intent.ACTION_VIEW, geoUri)
                                            try {
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "No maps app found", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Public,
                                            contentDescription = "Open in Maps",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            } ?: run {
                                Text(
                                    text = "No data",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    Configuration.getInstance().load(
                        ctx, PreferenceManager.getDefaultSharedPreferences(ctx)
                    )
                    Configuration.getInstance().userAgentValue = ctx.packageName
                    MapView(ctx).also { mv ->
                        mv.setTileSource(TileSourceFactory.MAPNIK)
                        mv.setMultiTouchControls(true)
                        mv.controller.setZoom(5.0)
                        mv.controller.setCenter(GeoPoint(20.0, 78.0))
                        
                        // Add scale bar overlay
                        val scaleBarOverlay = ScaleBarOverlay(mv)
                        scaleBarOverlay.setCentred(true)
                        scaleBarOverlay.setScaleBarOffset(context.resources.displayMetrics.widthPixels / 2 - 100, 20)
                        mv.overlays.add(scaleBarOverlay)
                        
                        mapViewRef.value = mv
                    }
                },
                update = { mapView ->
                    // Clear overlays but preserve scale bar
                    val scaleBarOverlay = mapView.overlays.find { it is ScaleBarOverlay }
                    mapView.overlays.clear()
                    scaleBarOverlay?.let { mapView.overlays.add(it) }
                    
                    val allPoints = mutableListOf<GeoPoint>()
                    val colourMap = state.users.mapIndexed { i, u -> u.email to TRACK_COLOURS[i % TRACK_COLOURS.size] }.toMap()

                    state.users.filter { it.email in state.visibleUsers }.forEach { user ->
                        val colour = colourMap[user.email] ?: Color.BLUE
                        val geoPoints = user.points.map { GeoPoint(it.latitude, it.longitude) }
                        allPoints.addAll(geoPoints)

                        if (geoPoints.size > 1) {
                            val polyline = Polyline(mapView).apply {
                                setPoints(geoPoints)
                                outlinePaint.color = colour
                                outlinePaint.strokeWidth = 5f
                                outlinePaint.alpha = 180
                            }
                            mapView.overlays.add(polyline)
                        }

                        user.points.lastOrNull()?.let { last ->
                            val marker = Marker(mapView).apply {
                                position = GeoPoint(last.latitude, last.longitude)
                                val dot = ShapeDrawable(OvalShape()).apply {
                                    paint.color = colour
                                    paint.style = Paint.Style.FILL
                                    intrinsicWidth = 32
                                    intrinsicHeight = 32
                                }
                                icon = dot
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                val sdf = SimpleDateFormat("dd-MMM HH:mm", Locale.getDefault())
                                val ts = sdf.format(Date(last.timestampMs))
                                val spd = "%.1f km/h".format(last.speedKmh)
                                val alt = "%.0f m".format(last.altitudeM)
                                val acc = "±%.0f m".format(last.accuracyM)
                                title = user.email
                                snippet = "$ts  $spd  alt $alt  $acc"
                            }
                            mapView.overlays.add(marker)
                        }
                    }

                    // Add timeline marker if enabled
                    if (state.timelineEnabled && state.currentTimelinePoint != null) {
                        val timelineMarker = Marker(mapView).apply {
                            position = GeoPoint(
                                state.currentTimelinePoint!!.latitude,
                                state.currentTimelinePoint!!.longitude
                            )
                            val dot = ShapeDrawable(OvalShape()).apply {
                                paint.color = Color.RED
                                paint.style = Paint.Style.FILL
                                intrinsicWidth = 48
                                intrinsicHeight = 48
                            }
                            icon = dot
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            val sdf = SimpleDateFormat("dd-MMM HH:mm:ss", Locale.getDefault())
                            val ts = sdf.format(Date(state.currentTimelinePoint!!.timestampMs))
                            val spd = "%.1f km/h".format(state.currentTimelinePoint!!.speedKmh)
                            val alt = "%.0f m".format(state.currentTimelinePoint!!.altitudeM)
                            val acc = "±%.0f m".format(state.currentTimelinePoint!!.accuracyM)
                            title = "Timeline Position"
                            snippet = "$ts  $spd  alt $alt  $acc"
                        }
                        mapView.overlays.add(timelineMarker)
                    }

                    // Only auto zoom/center when NOT in timeline mode - let user control the view during timeline browsing
                    if (allPoints.size > 1 && !state.timelineEnabled) {
                        val lats = allPoints.map { it.latitude }
                        val lons = allPoints.map { it.longitude }
                        val bounds = BoundingBox(lats.max(), lons.max(), lats.min(), lons.min())
                        mapView.post {
                            try { mapView.zoomToBoundingBox(bounds, true, 60) } catch (_: Exception) {}
                        }
                    }
                    mapView.invalidate()
                },
                modifier = Modifier.fillMaxSize()
            )

            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            state.error?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }

        if (state.users.isNotEmpty()) {
            val colourMap = state.users.mapIndexed { i, u ->
                u.email to TRACK_COLOURS[i % TRACK_COLOURS.size]
            }.toMap()

            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    LazyRow(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(state.users) { user ->
                            val colour = colourMap[user.email] ?: Color.GRAY
                            val composeColour = androidx.compose.ui.graphics.Color(colour)
                            val isSelected = user.email in state.visibleUsers
                            ElevatedFilterChip(
                                selected = isSelected,
                                onClick = { onToggleUser(user.email) },
                                label = {
                                    Text(
                                        user.email.substringBefore("@"),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                modifier = if (isSelected) Modifier.border(
                                    width = 2.dp,
                                    color = composeColour,
                                    shape = RoundedCornerShape(8.dp)
                                ) else Modifier,
                                colors = FilterChipDefaults.elevatedFilterChipColors(
                                    selectedContainerColor = composeColour.copy(alpha = 0.15f),
                                    selectedLabelColor = MaterialTheme.colorScheme.onSurface
                                ),
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .padding(0.dp)
                                    ) {
                                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                            drawCircle(color = composeColour)
                                        }
                                    }
                                }
                            )
                        }
                    }
                    val totalAll = state.users.sumOf { it.points.size }
                    Text(
                        text = "$totalAll points",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { mapViewRef.value?.onDetach() }
    }
}

private fun showDatePickerDialog(context: Context, initialMs: Long, onDate: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { timeInMillis = initialMs }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val selected = Calendar.getInstance().apply {
                        set(year, month, day, hour, minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onDate(selected.timeInMillis)
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                true
            ).show()
        },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH)
    ).show()
}
