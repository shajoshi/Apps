package com.sj.bkgtracker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

                if (showUsageDialog) {
                    val usage = UsageTracker.getTodayUsage(this@MainActivity)
                    val cacheStats = UnifiedLocationCache.cacheStats.collectAsState().value
                    AlertDialog(
                        onDismissRequest = { showUsageDialog = false },
                        title = { Text("Cloud Usage", fontWeight = FontWeight.SemiBold) },
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
                                HorizontalDivider()
                                Text(
                                    text = "Est. cost: ${usage.estimatedCost()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showUsageDialog = false }) { Text("Close") }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    UnifiedLocationCache.clearCache()
                                    Toast.makeText(this@MainActivity, "Cache cleared", Toast.LENGTH_SHORT).show()
                                }
                            ) { Text("Clear Cache") }
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
                                }
                            )
                            1 -> MapScreen(
                                state         = mapState,
                                onRefresh     = { mapViewModel.refresh() },
                                onToggleUser  = { mapViewModel.toggleUser(it) },
                                onSetTimeWindow = { mapViewModel.setTimeWindowHours(it) },
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
        viewModel.refreshAuthState()
        viewModel.refreshPermissions()
        requestPermissionsIfNeeded()
        startTrackingIfPermitted()
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
