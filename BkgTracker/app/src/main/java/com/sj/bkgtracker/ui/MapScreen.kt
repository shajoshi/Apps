package com.sj.bkgtracker.ui

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import androidx.preference.PreferenceManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
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
    onSetTimeWindow: (Int) -> Unit
) {
    val context = LocalContext.current
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {

        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MapViewModel.TIME_OPTIONS.forEach { hours ->
                    val label = when (hours) {
                        1 -> "1h"; 6 -> "6h"; 24 -> "24h"; else -> "3d"
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
                        mapViewRef.value = mv
                    }
                },
                update = { mapView ->
                    mapView.overlays.clear()
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

                    if (allPoints.size > 1) {
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
                LazyRow(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.users) { user ->
                        val colour = colourMap[user.email] ?: Color.GRAY
                        val composeColour = androidx.compose.ui.graphics.Color(colour)
                        ElevatedFilterChip(
                            selected = user.email in state.visibleUsers,
                            onClick = { onToggleUser(user.email) },
                            label = { Text(user.email.substringBefore("@"), style = MaterialTheme.typography.labelSmall) },
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
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { mapViewRef.value?.onDetach() }
    }
}
