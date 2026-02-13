package com.sj.gpsutil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.sj.gpsutil.tracking.TrackingState
import com.sj.gpsutil.tracking.TrackingStatus
import com.sj.gpsutil.ui.SettingsScreen
import com.sj.gpsutil.ui.TrackHistoryScreen
import com.sj.gpsutil.ui.TrackingScreen
import com.sj.gpsutil.ui.theme.SJGpsUtilTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SJGpsUtilTheme {
                SJGpsUtilApp()
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun SJGpsUtilApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.TRACKING) }
    val trackingStatus by TrackingState.status.collectAsState()
    val canOpenSettings = trackingStatus == TrackingStatus.Idle

    LaunchedEffect(canOpenSettings) {
        if (!canOpenSettings && currentDestination == AppDestinations.SETTINGS) {
            currentDestination = AppDestinations.TRACKING
        }
    }

    BackHandler(enabled = currentDestination != AppDestinations.TRACKING) {
        currentDestination = AppDestinations.TRACKING
    }

    val onNavigate: (AppDestinations) -> Unit = { dest ->
        if (dest != AppDestinations.SETTINGS || canOpenSettings) {
            currentDestination = dest
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        when (currentDestination) {
            AppDestinations.TRACKING -> TrackingScreen(
                onNavigate = onNavigate,
                modifier = Modifier.padding(innerPadding)
            )
            AppDestinations.HISTORY -> TrackHistoryScreen(
                onNavigate = onNavigate,
                modifier = Modifier.padding(innerPadding)
            )
            AppDestinations.SETTINGS -> SettingsScreen(
                onNavigate = onNavigate,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

enum class AppDestinations(
    val label: String,
) {
    TRACKING("Tracking"),
    HISTORY("Tracks"),
    SETTINGS("Settings"),
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SJGpsUtilTheme {
        SJGpsUtilApp()
    }
}