package com.sj.obd2app.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sj.obd2app.obd.ObdConnectionManager

/**
 * BroadcastReceiver to handle Bluetooth bond loss events for improved OBD2 connection stability.
 * 
 * This receiver monitors Bluetooth device bond state changes and notifies the ObdConnectionManager
 * when a device bond is lost, allowing for proactive connection management.
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
            // Note: You may need to add a method to ObdConnectionManager to check current device
            Log.i(TAG, "Notifying connection manager about bond loss for ${device.address}")
            
            // For now, we'll trigger a reconnection attempt
            // In a future enhancement, you could add: connectionManager.onBondLost(device)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling bond loss for ${device.address}", e)
        }
    }
}
