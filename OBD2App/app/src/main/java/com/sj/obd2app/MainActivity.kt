package com.sj.obd2app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import com.sj.obd2app.R
import com.sj.obd2app.databinding.ActivityMainBinding
import com.sj.obd2app.gps.GpsDataSource
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.metrics.TripPhase
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.settings.AppSettings
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        /**
         * Set to `true` to enable mock mode — the app will use simulated OBD2 data
         * from assets/mock_obd2_data.json instead of a real Bluetooth connection.
         * Set to `false` for production / real hardware.
         */
        private const val USE_MOCK_OBD2 = false
    }

    private lateinit var binding: ActivityMainBinding
    private var bluetoothAdapter: BluetoothAdapter? = null

    // Bluetooth enable request launcher
    @SuppressLint("MissingPermission")
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            onBluetoothEnabled()
        } else {
            Toast.makeText(this, "Bluetooth is required for OBD2 communication", Toast.LENGTH_LONG).show()
            navigateToLayoutList()
        }
    }

    // Permission request launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            ensureBluetoothEnabled()
        } else {
            Toast.makeText(this, getString(R.string.bt_permission_rationale), Toast.LENGTH_LONG).show()
            navigateToLayoutList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup notification channels
        setupNotificationChannels()

        // Start GPS tracking
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            GpsDataSource.getInstance(this).start()
        }

        // Initialise OBD2 service (mock or real)
        Obd2ServiceProvider.useMock = USE_MOCK_OBD2
        if (USE_MOCK_OBD2) {
            Obd2ServiceProvider.initMock(this)
            return // startDestination is nav_layout_list — no explicit navigation needed
        }

        // Keep screen on whenever a trip is running — managed here so it survives
        // navigation between fragments (DashboardFragment, TripFragment, etc.)
        lifecycleScope.launch {
            MetricsCalculator.getInstance(this@MainActivity).tripPhase.collect { phase ->
                if (phase == TripPhase.RUNNING) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        // Defer BT init until after NavHostFragment has attached its NavController
        val btManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter
        binding.root.post { requestBluetoothPermissions() }
    }

    private fun onBluetoothEnabled() {
        GpsDataSource.getInstance(this).start()
        navigateToConnect()
    }

    private fun navigateToConnect() {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        if (navController.currentDestination?.id != R.id.nav_connect) {
            navController.navigate(R.id.nav_connect)
        }
    }

    private fun navigateToLayoutList() {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        if (navController.currentDestination?.id != R.id.nav_layout_list) {
            navController.navigate(R.id.nav_layout_list)
        }
    }

    /**
     * Called by ConnectFragment when OBD connection is established.
     * Navigates to the default dashboard, or the layout list if none is set.
     */
    fun onObd2Connected() {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        if (navController.currentDestination?.id != R.id.nav_trip) {
            navController.navigate(R.id.nav_trip)
        }
    }

    private fun requestBluetoothPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (permissionsNeeded.isNotEmpty()) {
            permissionLauncher.launch(permissionsNeeded.toTypedArray())
        } else {
            ensureBluetoothEnabled()
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureBluetoothEnabled() {
        val adapter = bluetoothAdapter ?: run { navigateToLayoutList(); return }
        if (!adapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            onBluetoothEnabled()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    private fun setupNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // Service notification channel
            val serviceChannel = NotificationChannel(
                "obd2_service",
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_service_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }
}