package com.sj.bkgtracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sj.bkgtracker.domain.model.LocationRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRequestFineLocation: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!state.isSignedIn) {
            SignInCard(onSignIn = onSignIn)
        } else {
            UserCard(email = state.userEmail)

            if (!state.fineLocationGranted) {
                PermissionCard(
                    title = "Location Permission Required",
                    body = "Grant precise location access to start GPS tracking.",
                    onRequest = onRequestFineLocation
                )
            } else if (!state.backgroundLocationGranted) {
                PermissionCard(
                    title = "Background Location Required",
                    body = "Allow location access \"All the time\" so tracking continues when the app is closed.",
                    onRequest = onRequestBackgroundLocation
                )
            } else if (!state.notificationPermissionGranted) {
                PermissionCard(
                    title = "Notification Permission Required",
                    body = "Notifications are required to keep the tracking service running.",
                    onRequest = onRequestNotificationPermission
                )
            }

            TrackingStatusCard(isTracking = state.isTracking)
            LastLocationCard(location = state.lastLocation)
            CacheSyncCard(
                cacheSize       = state.cacheSize,
                lastSyncTime    = state.lastSyncTime,
                lastSyncSuccess = state.lastSyncSuccess
            )
        }
    }
}

@Composable
private fun SignInCard(onSignIn: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "Background GPS Tracker",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Sign in with your Google account to start tracking.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                Text("Sign in with Google")
            }
        }
    }
}

@Composable
private fun UserCard(email: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Signed In", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(email, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PermissionCard(title: String, body: String, onRequest: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(body, style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = onRequest) {
                Text("Grant Permission")
            }
        }
    }
}

@Composable
private fun TrackingStatusCard(isTracking: Boolean) {
    val containerColor = if (isTracking)
        Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant
    val statusColor = if (isTracking) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isTracking) Color(0xFF2E7D32) else Color(0xFF9E9E9E),
                modifier = Modifier.size(10.dp)
            ) {}
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = if (isTracking) "Tracking Active" else "Tracking Inactive",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
                Text(
                    text = if (isTracking) "GPS updates every 30 seconds" else "Start the app or reboot to resume",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun LastLocationCard(location: LocationRecord?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Last GPS Fix", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            if (location == null) {
                Text("No fix yet", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            } else {
                InfoRow("Latitude",  "%.6f°".format(location.latitude))
                InfoRow("Longitude", "%.6f°".format(location.longitude))
                InfoRow("Accuracy",  "±%.0f m".format(location.accuracyM))
                InfoRow("Speed",     "%.1f km/h".format(location.speedKmh))
                InfoRow("Altitude",  "%.0f m".format(location.altitudeM))
                InfoRow("Time",      formatTimestamp(location.timestampMs))
            }
        }
    }
}

@Composable
private fun CacheSyncCard(cacheSize: Int, lastSyncTime: Long, lastSyncSuccess: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Upload Status", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            InfoRow("Cached Points", "$cacheSize")
            if (lastSyncTime > 0L) {
                InfoRow("Last Sync", formatTimestamp(lastSyncTime))
                InfoRow("Result", if (lastSyncSuccess) "✓ Success" else "✗ Failed — will retry")
            } else {
                Text("No sync yet", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date(ms))
