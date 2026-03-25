package com.sj.obd2app.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sj.obd2app.obd.ObdConnectionManager
import com.sj.obd2app.settings.AppSettings

/**
 * BroadcastReceiver to handle Bluetooth bond loss events for improved OBD2 connection stability.
 * 
 * This receiver monitors Bluetooth device bond state changes and properly handles bond loss
 * by disconnecting and clearing the device, preventing auto-reconnection spam when users
 * tap "Forget Device" in Android settings.
 */
class BluetoothBondLossReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BluetoothBondLossReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                
                Log.d(TAG, "Bond state changed for ${device?.address}: $prevState -> $state")
                
                // Check if bond was lost (BOND_BONDED -> BOND_NONE)
                if (state == BluetoothDevice.BOND_NONE && 
                    prevState == BluetoothDevice.BOND_BONDED && 
                    device != null) {
                    
                    Log.w(TAG, "Bluetooth bond lost for device: ${device.address}")
                    
                    // Notify ObdConnectionManager about bond loss
                    handleBondLoss(context, device)
                }
            }
        }
    }
    
    private fun handleBondLoss(context: Context, device: BluetoothDevice) {
        try {
            val connectionManager = ObdConnectionManager.getInstance(context)
            
            // Check if this is the currently connected OBD device
            val lastDeviceMac = AppSettings.getLastDeviceMac(context)
            
            if (device.address == lastDeviceMac) {
                Log.w(TAG, "Bond lost for currently connected OBD device: ${device.address}")
                
                // Properly handle bond loss - disconnect and clear device
                // This prevents auto-reconnection spam when user taps "Forget Device"
                connectionManager.onBondLost()
                
                // Optional: Show user notification about bond loss
                // In a future enhancement, you could show a toast or notification
                Log.i(TAG, "Bond loss handled - user must manually reconnect in app")
            } else {
                Log.d(TAG, "Bond lost for non-active device: ${device.address} (ignoring)")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling bond loss for ${device.address}", e)
        }
    }
}
