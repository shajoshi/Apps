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
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.navigation.NavigationView
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.RecyclerView
import com.sj.obd2app.R
import com.sj.obd2app.databinding.ActivityMainBinding
import com.sj.obd2app.gps.GpsDataSource
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.metrics.TripPhase
import com.sj.obd2app.obd.ObdStateManager
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.settings.VehicleProfileRepository
import com.sj.obd2app.storage.AppDataDirectory
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
    private var lastPageItem: Int = MainPagerAdapter.PAGE_CONNECT
    private var programmaticPageChange = false
    private var detailsSwipeStartX = 0f
    private var detailsSwipeStartY = 0f
    private var blockingDetailsSwipe = false

    // Bluetooth enable request launcher
    @SuppressLint("MissingPermission")
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            onBluetoothEnabled()
        } else {
            Toast.makeText(this, "Bluetooth is required for OBD2 communication", Toast.LENGTH_LONG).show()
            navigateToTripSummary()
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
            navigateToTripSummary()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Re-take URI permissions BEFORE any file operations
        // This fixes permission loss after cold start
        AppDataDirectory.ensureUriPermissions(this)

        // On Android 15+ edge-to-edge is forced and cannot be disabled.
        // On older Android (car players run Android 10-12) we opt out so the
        // system draws the status bar and our content starts cleanly below it.
        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15+: edge-to-edge is mandatory — apply padding from insets
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(top = bars.top, bottom = bars.bottom)
                insets
            }
        } else {
            // Android < 15: disable edge-to-edge so system handles status/nav bars
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }

        // Check for existing data in .obd directory
        DataMigration.checkExistingData(this)

        // Setup notification channels
        setupNotificationChannels()

        // Initialize centralized OBD state manager
        val obdConnectionEnabled = AppSettings.isObdConnectionEnabled(this)
        val autoConnectEnabled = AppSettings.isAutoConnect(this)
        ObdStateManager.initialize(autoConnectEnabled, obdConnectionEnabled || USE_MOCK_OBD2)
        
        // Initialise OBD2 service mode BEFORE ViewPager/fragments are created
        if (ObdStateManager.isMockMode) {
            Obd2ServiceProvider.initMock(this)
        } else {
            Obd2ServiceProvider.initBluetooth(this)
        }

        // Set up ViewPager2 with 6 pages (Map View removed from swipeable pages)
        viewPager = binding.root.findViewById(R.id.main_view_pager)
        viewPager.adapter = MainPagerAdapter(this)
        viewPager.offscreenPageLimit = 3
        blockSwipeFromDetailsPage()

        // Prevent swipe navigation to Settings during active trips and when Dashboard is in edit mode
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                // Only check when the user lifts their finger (state == 0)
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    val currentPage = viewPager.currentItem
                    val calculator = MetricsCalculator.getInstance(this@MainActivity)
                    
                    android.util.Log.d("MainActivity", "Page changed to: $currentPage (Trip Summary=${currentPage == MainPagerAdapter.PAGE_TRIP_SUMMARY}), trip phase: ${calculator.tripPhase.value}")

                    val wasProgrammatic = programmaticPageChange
                    programmaticPageChange = false

                    val swipeBlockedPage = currentPage == MainPagerAdapter.PAGE_TRIP_SUMMARY ||
                        currentPage == MainPagerAdapter.PAGE_MAP_VIEW ||
                        currentPage == MainPagerAdapter.PAGE_SETTINGS

                    if (swipeBlockedPage && !wasProgrammatic) {
                        android.util.Log.w("MainActivity", "Blocking swipe access to secondary page: $currentPage")
                        viewPager.setCurrentItem(lastPageItem, false)
                        Toast.makeText(
                            this@MainActivity,
                            "Use the 3-dot menu to open this page.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }

                    lastPageItem = currentPage
                    viewPager.isUserInputEnabled = currentPage !in setOf(
                        MainPagerAdapter.PAGE_TRIP_SUMMARY,
                        MainPagerAdapter.PAGE_MAP_VIEW,
                        MainPagerAdapter.PAGE_SETTINGS
                    )
                }
            }
        })
        
        // Observe dashboard edit mode and disable swipe when in edit mode
        lifecycleScope.launch {
            MetricsCalculator.getInstance(this@MainActivity).dashboardEditMode.collect { isEditMode ->
                viewPager.isUserInputEnabled = !isEditMode
            }
        }
        
        // Set up navigation drawer for large tablets
        setupNavigationDrawer()

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
        if (ObdStateManager.isMockMode) {
            // Respect auto-connect setting for mock mode
            if (ObdStateManager.shouldAutoConnect()) {
                Obd2ServiceProvider.getService().connect(null)
                ObdStateManager.updateConnectionState(ObdStateManager.ConnectionState.CONNECTING)
            }
            viewPager.setCurrentItem(MainPagerAdapter.PAGE_TRIP, false)  // Go directly to Trip
            return
        }

        // Default to Connect screen
        viewPager.setCurrentItem(MainPagerAdapter.PAGE_CONNECT, false)
        viewPager.isUserInputEnabled = true

        // Defer BT init until after NavHostFragment has attached its NavController
        val btManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter
        binding.root.post { requestBluetoothPermissions() }
    }

    private fun blockSwipeFromDetailsPage() {
        val recyclerView = viewPager.getChildAt(0) as? RecyclerView ?: return
        recyclerView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                if (viewPager.currentItem != MainPagerAdapter.PAGE_DETAILS) {
                    blockingDetailsSwipe = false
                    return false
                }

                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        detailsSwipeStartX = e.x
                        detailsSwipeStartY = e.y
                        blockingDetailsSwipe = false
                    }

                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = e.x - detailsSwipeStartX
                        val dy = e.y - detailsSwipeStartY
                        val horizontalSwipe = kotlin.math.abs(dx) > kotlin.math.abs(dy)

                        // Block leftward swipe from Details toward Trip Summary.
                        if (horizontalSwipe && dx < 0 && kotlin.math.abs(dx) > 12f) {
                            blockingDetailsSwipe = true
                            return true
                        }
                    }

                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        blockingDetailsSwipe = false
                    }
                }

                return blockingDetailsSwipe
            }

            override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) = Unit

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
        })
    }

    private fun onBluetoothEnabled() {
        GpsDataSource.getInstance(this).start()
        navigateToConnect()
    }

    private fun navigateToConnect() {
        viewPager.setCurrentItem(MainPagerAdapter.PAGE_CONNECT, true)
    }

    private fun navigateToTripSummary() {
        lastPageItem = viewPager.currentItem
        programmaticPageChange = true
        viewPager.setCurrentItem(MainPagerAdapter.PAGE_TRIP_SUMMARY, false)
    }
    
    private fun setupNavigationDrawer() {
        val navView: NavigationView? = binding.root.findViewById(R.id.nav_view)
        if (navView == null) return

        val isTripActive = MetricsCalculator.getInstance(this).tripPhase.value != TripPhase.IDLE
        navView.menu.findItem(R.id.nav_trip_summary)?.isEnabled = !isTripActive
        navView.menu.findItem(R.id.nav_settings)?.isEnabled = !isTripActive

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_connect -> {
                    navigateToPage(MainPagerAdapter.PAGE_CONNECT)
                    true
                }
                R.id.nav_dashboard -> {
                    navigateToPage(MainPagerAdapter.PAGE_DASHBOARDS)
                    true
                }
                R.id.nav_details -> {
                    navigateToPage(MainPagerAdapter.PAGE_DETAILS)
                    true
                }
                R.id.nav_trip_summary -> {
                    if (isTripActive) {
                        Toast.makeText(
                            this,
                            "Trip Summary is not accessible during an active trip.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        navigateToPage(MainPagerAdapter.PAGE_TRIP_SUMMARY)
                    }
                    true
                }
                R.id.nav_settings -> {
                    if (isTripActive) {
                        Toast.makeText(
                            this,
                            "Settings is not accessible during an active trip.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        navigateToPage(MainPagerAdapter.PAGE_SETTINGS)
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Called by ConnectFragment when OBD connection is established.
     * No longer auto-navigates - users have full control over navigation.
     */
    fun onObd2Connected() {
        android.util.Log.d("MainActivity", "onObd2Connected called - auto-navigation disabled")
        // Auto-navigation removed - users stay on their current page
    }

    /** Navigate to a specific page by index — used by TopBarHelper overflow menu. */
    fun navigateToPage(pageIndex: Int) {
        Log.d("MainActivity", "navigateToPage: Navigating to page $pageIndex")

        val currentPhase = MetricsCalculator.getInstance(this).tripPhase.value
        val isTripActive = currentPhase != TripPhase.IDLE

        if (pageIndex == MainPagerAdapter.PAGE_TRIP_SUMMARY ||
            pageIndex == MainPagerAdapter.PAGE_MAP_VIEW ||
            pageIndex == MainPagerAdapter.PAGE_SETTINGS) {
            if (isTripActive) {
                Toast.makeText(
                    this,
                    "This page is not accessible during an active trip.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        // Special handling for Map View - only allow access from Trip Summary
        if (pageIndex == MainPagerAdapter.PAGE_MAP_VIEW && viewPager.currentItem != MainPagerAdapter.PAGE_TRIP_SUMMARY) {
            Log.w("MainActivity", "Map View access denied - not from Trip Summary page")
            Toast.makeText(
                this,
                "Map View is only accessible when a trip log file is selected in Trip Summary",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        
        Log.d("MainActivity", "navigateToPage: Setting ViewPager to page $pageIndex")
        lastPageItem = viewPager.currentItem
        programmaticPageChange = true
        viewPager.setCurrentItem(pageIndex, true)
        Log.d("MainActivity", "navigateToPage: ViewPager current item is now ${viewPager.currentItem}")
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
        val adapter = bluetoothAdapter ?: run { navigateToTripSummary(); return }
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