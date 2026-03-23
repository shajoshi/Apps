package com.sj.obd2app.ui.connect

import android.bluetooth.BluetoothDevice

/**
 * Wrapper for BluetoothDevice with additional metadata for display and sorting.
 *
 * @param device The Bluetooth device
 * @param isPaired Whether this device is currently paired/bonded
 * @param isObd Whether this device name matches OBD adapter keywords
 * @param rssi Signal strength (RSSI) - higher values = stronger signal
 */
data class DeviceInfo(
    val device: BluetoothDevice,
    val isPaired: Boolean,
    val isObd: Boolean,
    val rssi: Int = Int.MIN_VALUE
)
