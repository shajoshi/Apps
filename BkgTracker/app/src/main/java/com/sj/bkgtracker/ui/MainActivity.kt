package com.sj.bkgtracker.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import java.io.File
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sj.bkgtracker.data.local.ActivityLogCache
import com.sj.bkgtracker.data.local.UsageTracker
import com.sj.bkgtracker.data.local.UnifiedLocationCache
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.sj.bkgtracker.BuildConfig
import com.sj.bkgtracker.service.LocationForegroundService
import com.sj.bkgtracker.ui.theme.BkgTrackerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val viewModel: MainViewModel by viewModels()
    private val mapViewModel: MapViewModel by viewModels()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var credentialManager: CredentialManager

    private val fineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        viewModel.refreshPermissions()
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startTrackingIfPermitted()
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
        startTrackingIfPermitted()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
    }

    private val activityRecognitionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
        startTrackingIfPermitted()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialManager = CredentialManager.create(this)

        setContent {
            BkgTrackerTheme {
                val state    by viewModel.state.collectAsState()
                val mapState by mapViewModel.state.collectAsState()
                var selectedTab by rememberSaveable { mutableIntStateOf(0) }

                var showMenu by remember { mutableStateOf(false) }
                var showSignOutDialog by remember { mutableStateOf(false) }
                var showUsageDialog by remember { mutableStateOf(false) }
                var showActivityLogDialog by remember { mutableStateOf(false) }

                if (showUsageDialog) {
                    val usage = UsageTracker.getTodayUsage(this@MainActivity)
                    val cacheStats = UnifiedLocationCache.cacheStats.collectAsState().value
                    AlertDialog(
                        onDismissRequest = { showUsageDialog = false },
                        title = { Text("Usage", fontWeight = FontWeight.SemiBold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Date: ${usage.date}", style = MaterialTheme.typography.bodySmall)
                                HorizontalDivider()
                                UsageRow("Points uploaded", "${usage.pointsUploaded}")
                                UsageRow("Firestore writes", "${usage.firestoreWrites} / 20,000 daily (${usage.writeUtilisation()}%)")
                                UsageRow("Firestore reads", "${usage.firestoreReads} / 50,000 daily (${usage.readUtilisation()}%)")
                                UsageRow("FCM messages", "${usage.fcmMessages} / 100,000 monthly (~${usage.fcmUtilisation()}%)")
                                UsageRow("Cloud Functions", "${usage.cloudFunctionInvocations} / 2,000,000 monthly (~${usage.cfUtilisation()}%)")
                                HorizontalDivider()
                                UsageRow("Cached users", "${cacheStats.cachedUserCount}")
                                UsageRow("Cached points", "${cacheStats.totalCachedPoints}")
                                Text(
                                    text = UnifiedLocationCache.getCacheInfo(),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                UsageRow("Firestore local cache", formatBytes(getFirestoreCacheSizeBytes(this@MainActivity)))
                                HorizontalDivider()
                                Text(
                                    text = "Est. cost: ${usage.estimatedCost()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        },
                        confirmButton = { },
                        dismissButton = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Clear",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(
                                        onClick = {
                                            UnifiedLocationCache.clearCache()
                                            Toast.makeText(this@MainActivity, "Cache cleared", Toast.LENGTH_SHORT).show()
                                        }
                                    ) { Text("Cache") }
                                    TextButton(
                                        onClick = {
                                            viewModel.onIntent(MainIntent.ClearFirestoreCache)
                                        }
                                    ) { Text("Firestore") }
                                }
                                TextButton(
                                    onClick = { showUsageDialog = false },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Close") }
                            }
                        }
                    )
                }

                if (showSignOutDialog) {
                    AlertDialog(
                        onDismissRequest = { showSignOutDialog = false },
                        title = { Text("Sign Out") },
                        text = { Text("Do you really want to sign out? Tracking will stop.") },
                        confirmButton = {
                            TextButton(onClick = {
                                showSignOutDialog = false
                                viewModel.onIntent(MainIntent.SignOut)
                            }) { Text("Sign Out") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
                        }
                    )
                }

                if (showActivityLogDialog) {
                    val activityLogs = remember { ActivityLogCache.logs(this@MainActivity) }

                    // data class to hold a paired row: startTime, name, endTime, durationMs (-1 if still open)
                    data class ActivityRow(val startTime: String, val activityName: String, val endTime: String, val durationMs: Long)

                    val pairedRows = remember(activityLogs) {
                        val sorted = activityLogs.sortedBy { it.timestamp }
                        val rows = mutableListOf<ActivityRow>()
                        val openActivities = mutableMapOf<String, Long>() // name -> startTimestamp
                        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        val now = System.currentTimeMillis()
                        for (entry in sorted) {
                            if (entry.isStart) {
                                openActivities[entry.activityName] = entry.timestamp
                            } else {
                                val startTs = openActivities.remove(entry.activityName)
                                val durationMs = if (startTs != null) entry.timestamp - startTs else -1L
                                val startTime = if (startTs != null) sdf.format(java.util.Date(startTs)) else "?"
                                rows.add(ActivityRow(startTime, entry.activityName, sdf.format(java.util.Date(entry.timestamp)), durationMs))
                            }
                        }
                        // Still-open activities
                        for ((name, ts) in openActivities) {
                            rows.add(ActivityRow(sdf.format(java.util.Date(ts)), name, "…", now - ts))
                        }
                        rows.reversed() // newest first
                    }

                    // Summarise total duration per activity type
                    val durationSummary = remember(pairedRows) {
                        pairedRows
                            .filter { it.durationMs > 0 }
                            .groupBy { it.activityName }
                            .mapValues { (_, rows) -> rows.sumOf { it.durationMs } }
                            .entries
                            .sortedByDescending { it.value }
                            .joinToString("  ") { (name, ms) ->
                                val totalMin = ms / 60_000
                                if (totalMin >= 60) "$name ${totalMin / 60}h${if (totalMin % 60 > 0) "${totalMin % 60}m" else ""}"
                                else "$name ${totalMin}m"
                            }
                    }

                    fun formatDuration(ms: Long): String {
                        val totalMin = ms / 60_000
                        return if (totalMin >= 60) "${totalMin / 60}h${if (totalMin % 60 > 0) "${totalMin % 60}m" else ""}"
                        else "${totalMin}m"
                    }

                    AlertDialog(
                        onDismissRequest = { showActivityLogDialog = false },
                        title = { Text("Activity Log (last 24 hours)", fontWeight = FontWeight.SemiBold) },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (pairedRows.isEmpty()) {
                                    Text(
                                        "No activity detected in last 24 hours",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                } else {
                                    // Duration summary header
                                    if (durationSummary.isNotEmpty()) {
                                        Text(
                                            durationSummary,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        HorizontalDivider()
                                    }
                                    // Detail rows: startTime  ActivityName  endTime  (duration)
                                    pairedRows.take(50).forEach { row ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                row.startTime,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                            Text(
                                                row.activityName,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                "${row.endTime}${if (row.durationMs > 0) " (${formatDuration(row.durationMs)})" else ""}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showActivityLogDialog = false }) { Text("Close") }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    ActivityLogCache.clear(this@MainActivity)
                                    Toast.makeText(this@MainActivity, "Activity log cleared", Toast.LENGTH_SHORT).show()
                                }
                            ) { Text("Clear Log") }
                        }
                    )
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("BkgTracker", fontWeight = FontWeight.SemiBold) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor    = MaterialTheme.colorScheme.primary,
                                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                                actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            actions = {
                                if (state.isSignedIn) {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Usage") },
                                            onClick = {
                                                showMenu = false
                                                showUsageDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Activity Log") },
                                            onClick = {
                                                showMenu = false
                                                showActivityLogDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Sign Out") },
                                            onClick = {
                                                showMenu = false
                                                showSignOutDialog = true
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick  = { selectedTab = 0 },
                                icon     = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                                label    = { Text("Home") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick  = { selectedTab = 1 },
                                icon     = { Icon(Icons.Filled.Map, contentDescription = "Map") },
                                label    = { Text("Map") }
                            )
                        }
                    }
                ) { padding ->
                    Box(
                        modifier = Modifier.padding(padding)
                    ) {
                        when (selectedTab) {
                            0 -> MainScreen(
                                state = state,
                                onSignIn = ::launchGoogleSignIn,
                                onSignOut = { viewModel.onIntent(MainIntent.SignOut) },
                                onManualSync = { viewModel.manualSync() },
                                onExpressSync = { viewModel.onIntent(MainIntent.ExpressSync) },
                                onStopExpressSync = { viewModel.onIntent(MainIntent.StopExpressSync) },
                                onRequestFineLocation = {
                                    fineLocationLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                },
                                onRequestBackgroundLocation = {
                                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                },
                                onRequestNotificationPermission = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                onRequestActivityRecognition = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        activityRecognitionPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                                    }
                                },
                                onClearFirestoreCache = { viewModel.onIntent(MainIntent.ClearFirestoreCache) }
                            )
                            1 -> MapScreen(
                                state         = mapState,
                                onRefresh     = { mapViewModel.refresh() },
                                onToggleUser  = { mapViewModel.toggleUser(it) },
                                onSetStartDate = { mapViewModel.setStartDate(it) },
                                onSetTimeWindow = { mapViewModel.setTimeWindowHours(it) },
                                onExport      = { mapViewModel.exportGpx() },
                                onExportRaw   = { mapViewModel.exportRawCsv() },
                                onEnableTimeline = { mapViewModel.enableTimeline() },
                                onDisableTimeline = { mapViewModel.disableTimeline() },
                                onSetTimelineIndex = { mapViewModel.setTimelineIndex(it) },
                                onGoToStart = { mapViewModel.goToStart() },
                                onGoToEnd = { mapViewModel.goToEnd() },
                                onGoToPrevious = { mapViewModel.goToPrevious() },
                                onGoToNext = { mapViewModel.goToNext() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.sj.bkgtracker.data.local.AppForegroundState.isInForeground = true
        viewModel.refreshAuthState()
        viewModel.refreshPermissions()
        requestPermissionsIfNeeded()
        startTrackingIfPermitted()
    }

    override fun onPause() {
        super.onPause()
        com.sj.bkgtracker.data.local.AppForegroundState.isInForeground = false
    }

    private fun requestPermissionsIfNeeded() {
        // Request location permissions on first launch
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            fineLocationLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
            return
        }

        // Request background location after fine is granted
        val bgGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!bgGranted) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return
        }

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val activityGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
                    PackageManager.PERMISSION_GRANTED
            if (!activityGranted) {
                activityRecognitionPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                return
            }
        }

        // Exact alarm permission is required on Android 12/12L (API 31-32) for setExactAndAllowWhileIdle.
        // On Android 13+ (API 33+) USE_EXACT_ALARM is a normal install-time permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                return
            }
        }
    }

    private fun launchGoogleSignIn() {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.FIREBASE_WEB_CLIENT_ID)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = credentialManager.getCredential(this@MainActivity, request)
                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                    auth.signInWithCredential(firebaseCredential)
                        .addOnSuccessListener {
                            val email = auth.currentUser?.email.orEmpty()
                            Log.d(TAG, "Firebase sign-in success: $email")
                            viewModel.onSignInSuccess(email)
                            startTrackingIfPermitted()
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Firebase sign-in failed", e)
                            Toast.makeText(this@MainActivity, "Sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                }
            } catch (e: GetCredentialException) {
                Log.e(TAG, "GetCredential failed: ${e.message}", e)
                Toast.makeText(this@MainActivity, "Google sign-in failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getFirestoreCacheSizeBytes(context: Context): Long {
        var size = 0L
        firestoreCacheRoots(context).forEach { dir ->
            if (!dir.exists()) return@forEach
            dir.walkTopDown().forEach { file ->
                if (file.isFile && isFirestoreCachePath(file.absolutePath)) {
                    size += file.length()
                }
            }
        }
        return size
    }

    private fun firestoreCacheRoots(context: Context): List<File> {
        val roots = mutableListOf<File>()
        context.filesDir?.let { roots.add(it) }
        context.cacheDir?.let { roots.add(it) }
        context.getDatabasePath("_dummy_")?.parentFile?.let { roots.add(it) }
        val dataDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.dataDir else context.filesDir?.parentFile
        dataDir?.let { roots.add(it) }
        return roots.distinct()
    }

    private fun isFirestoreCachePath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("firestore") ||
               lower.contains("leveldb") ||
               lower.contains("com.google.firebase.firestore") ||
               lower.contains("firebase.firestore") ||
               (lower.endsWith(".ldb") && lower.contains("firestore"))
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun startTrackingIfPermitted() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val activityGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (auth.currentUser != null && fineGranted && activityGranted) {
            Log.d(TAG, "Starting location service")
            LocationForegroundService.start(this)
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
