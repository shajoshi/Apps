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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialManager = CredentialManager.create(this)

        setContent {
            BkgTrackerTheme {
                val state    by viewModel.state.collectAsState()
                val mapState by mapViewModel.state.collectAsState()
                var selectedTab by rememberSaveable { mutableIntStateOf(0) }

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
                                    TextButton(onClick = { viewModel.onIntent(MainIntent.SignOut) }) {
                                        Text("Sign Out", color = MaterialTheme.colorScheme.onPrimary)
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
                                }
                            )
                            1 -> MapScreen(
                                state         = mapState,
                                onRefresh     = { mapViewModel.refresh() },
                                onToggleUser  = { mapViewModel.toggleUser(it) },
                                onSetTimeWindow = { mapViewModel.setTimeWindowHours(it) }
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
        startTrackingIfPermitted()
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

        if (auth.currentUser != null && fineGranted) {
            Log.d(TAG, "Starting location service")
            LocationForegroundService.start(this)
        }
    }
}
