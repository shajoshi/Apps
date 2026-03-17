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
import androidx.viewpager2.widget.ViewPager2
import com.sj.obd2app.R
import com.sj.obd2app.databinding.ActivityMainBinding
import com.sj.obd2app.gps.GpsDataSource
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.metrics.TripPhase
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.storage.DataMigration
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
    private lateinit var viewPager: ViewPager2
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

        // Migrate data to .obd directory if needed
        DataMigration.migrateIfNeeded(this)

        // Setup notification channels
        setupNotificationChannels()

        // Initialise OBD2 service mode BEFORE ViewPager/fragments are created,
        // so MetricsCalculator singleton picks up the correct service instance.
        Obd2ServiceProvider.useMock = USE_MOCK_OBD2 || !AppSettings.isObdConnectionEnabled(this)
        if (Obd2ServiceProvider.useMock) {
            Obd2ServiceProvider.initMock(this)
        }

        // Set up ViewPager2 with 5 pages
        viewPager = binding.root.findViewById(R.id.main_view_pager)
        viewPager.adapter = MainPagerAdapter(this)
        viewPager.offscreenPageLimit = 2

        // Prevent swipe navigation to Settings during active trips and when Dashboard is in edit mode
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                // Only check when the user lifts their finger (state == 0)
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    val currentPage = viewPager.currentItem
                    val calculator = MetricsCalculator.getInstance(this@MainActivity)
                    
                    // Check if trying to access Settings during active trip
                    if (currentPage == MainPagerAdapter.PAGE_SETTINGS) {
                        val currentPhase = calculator.tripPhase.value
                        if (currentPhase != TripPhase.IDLE) {
                            // Navigate back to Trip page and show message
                            viewPager.setCurrentItem(MainPagerAdapter.PAGE_TRIP, false)
                            Toast.makeText(
                                this@MainActivity,
                                "Settings not accessible during active trip. Please stop or complete the trip first.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        })
        
        // Observe dashboard edit mode and disable swipe when in edit mode
        lifecycleScope.launch {
            MetricsCalculator.getInstance(this@MainActivity).dashboardEditMode.collect { isEditMode ->
                viewPager.isUserInputEnabled = !isEditMode
            }
        }

        // Start GPS tracking
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            GpsDataSource.getInstance(this).start()
        }

        // Handle compile-time mock flag
        if (USE_MOCK_OBD2) {
            Obd2ServiceProvider.getService().connect(null)
            viewPager.setCurrentItem(MainPagerAdapter.PAGE_DASHBOARDS, false)
            return
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

        // If OBD Connection is disabled, use mock/simulate mode — skip BT entirely
        if (!AppSettings.isObdConnectionEnabled(this)) {
            Obd2ServiceProvider.getService().connect(null)
            viewPager.setCurrentItem(MainPagerAdapter.PAGE_TRIP, false)  // Go directly to Trip
            return
        }

        // Default to Connect screen
        viewPager.setCurrentItem(MainPagerAdapter.PAGE_CONNECT, false)

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
        viewPager.setCurrentItem(MainPagerAdapter.PAGE_CONNECT, true)
    }

    private fun navigateToLayoutList() {
        viewPager.setCurrentItem(MainPagerAdapter.PAGE_DASHBOARDS, true)
    }

    /**
     * Called by ConnectFragment when OBD connection is established.
     * Navigates to the Trip screen.
     */
    fun onObd2Connected() {
        viewPager.setCurrentItem(MainPagerAdapter.PAGE_TRIP, true)
    }

    /** Navigate to a specific page by index — used by TopBarHelper overflow menu. */
    fun navigateToPage(pageIndex: Int) {
        // Check if trying to access Settings during active trip
        if (pageIndex == MainPagerAdapter.PAGE_SETTINGS) {
            val currentPhase = MetricsCalculator.getInstance(this).tripPhase.value
            if (currentPhase != TripPhase.IDLE) {
                Toast.makeText(
                    this,
                    "Settings not accessible during active trip. Please stop or complete the trip first.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }
        viewPager.setCurrentItem(pageIndex, true)
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