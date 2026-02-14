package com.sj.gpsutil

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
    val context = LocalContext.current
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var permissionsGranted by rememberSaveable {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = requiredPermissions.all { result[it] == true }
    }

    // Request permissions on first launch if not already granted
    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            permissionsLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    if (!permissionsGranted) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "SJ GPS Util needs location and notification permissions to function.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(
                    onClick = { permissionsLauncher.launch(requiredPermissions.toTypedArray()) },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Grant Permissions")
                }
            }
        }
        return
    }

    val navStack = rememberSaveable(
        saver = androidx.compose.runtime.saveable.listSaver(
            save = { it.map { d -> d.name } },
            restore = { it.map { n -> AppDestinations.valueOf(n) }.toMutableStateList() }
        )
    ) { mutableStateListOf(AppDestinations.TRACKING) }
    val currentDestination = navStack.last()
    val trackingStatus by TrackingState.status.collectAsState()
    val canOpenSettings = trackingStatus == TrackingStatus.Idle

    LaunchedEffect(canOpenSettings) {
        if (!canOpenSettings && currentDestination == AppDestinations.SETTINGS) {
            navStack.removeLastOrNull()
            if (navStack.isEmpty()) navStack.add(AppDestinations.TRACKING)
        }
    }

    BackHandler(enabled = navStack.size > 1) {
        navStack.removeLastOrNull()
        if (navStack.isEmpty()) navStack.add(AppDestinations.TRACKING)
    }

    val onNavigate: (AppDestinations) -> Unit = { dest ->
        if (dest != AppDestinations.SETTINGS || canOpenSettings) {
            if (dest != currentDestination) {
                navStack.add(dest)
            }
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