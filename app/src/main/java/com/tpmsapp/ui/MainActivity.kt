package com.tpmsapp.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayoutMediator
import com.tpmsapp.R
import com.tpmsapp.databinding.ActivityMainBinding
import com.tpmsapp.service.TpmsScanService
import com.tpmsapp.viewmodel.TpmsViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TpmsViewModel by viewModels()

    private var scanService: TpmsScanService? = null
    private var serviceBound = false
    private var scanMenuItem: MenuItem? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? TpmsScanService.LocalBinder ?: return
            scanService = localBinder.getService()
            serviceBound = true
            scanService?.let { svc ->
                viewModel.collectTyreData(svc.tyreDataFlow)
                viewModel.collectRawAdvertisements(svc.rawAdvertisementFlow)
            }
            updateScanIcon()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            scanService = null
            serviceBound = false
            updateScanIcon()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) bindToService()
        else Toast.makeText(this, R.string.bluetooth_required, Toast.LENGTH_LONG).show()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) checkBluetoothAndBind()
        else Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupViewPager()
        observeViewModel()
        requestPermissionsAndBind()
    }

    private fun setupViewPager() {
        val fragments = listOf(
            DashboardFragment(),
            ScanFragment(),
            SensorsFragment()
        )
        val titles = listOf(
            getString(R.string.tab_dashboard),
            getString(R.string.tab_scan),
            getString(R.string.tab_sensors)
        )

        binding.viewPager.adapter = MainPagerAdapter(this, fragments)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = titles[position]
        }.attach()
    }

    private fun observeViewModel() {
        viewModel.errorMessage.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }
    }

    private fun requestPermissionsAndBind() {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                required.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                required.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (required.isEmpty()) checkBluetoothAndBind()
        else permissionLauncher.launch(required.toTypedArray())
    }

    private fun checkBluetoothAndBind() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null) {
            Toast.makeText(this, R.string.no_bluetooth, Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            bindToService()
        }
    }

    private fun bindToService() {
        val intent = Intent(this, TpmsScanService::class.java).apply {
            action = TpmsScanService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        bindService(Intent(this, TpmsScanService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun toggleScan() {
        val svc = scanService ?: return
        if (svc.isScanActive) {
            svc.stopScan()
            Toast.makeText(this, R.string.action_stop_scan, Toast.LENGTH_SHORT).show()
        } else {
            svc.startScan()
            Toast.makeText(this, R.string.action_start_scan, Toast.LENGTH_SHORT).show()
        }
        updateScanIcon()
    }

    private fun updateScanIcon() {
        val scanning = scanService?.isScanActive == true
        scanMenuItem?.apply {
            icon = ContextCompat.getDrawable(
                this@MainActivity,
                if (scanning) R.drawable.ic_scan_stop else R.drawable.ic_scan_start
            )
            title = getString(
                if (scanning) R.string.action_stop_scan else R.string.action_start_scan
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (!serviceBound && hasBluetoothPermissions()) {
            bindService(Intent(this, TpmsScanService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        scanMenuItem = menu.findItem(R.id.action_toggle_scan)
        updateScanIcon()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_toggle_scan -> {
            toggleScan()
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        else
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
