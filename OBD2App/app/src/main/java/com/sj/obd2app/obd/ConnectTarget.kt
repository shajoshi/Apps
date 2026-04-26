package com.sj.obd2app.obd

import android.bluetooth.BluetoothDevice

/**
 * Lightweight value object that represents the target of a connection attempt.
 *
 * Introduced to decouple [BluetoothObd2Service.connect] from the final
 * [BluetoothDevice] Android class so that the connection flow can be unit-tested
 * on the pure JVM (no Robolectric, no Android framework).
 *
 * Production code still accepts [BluetoothDevice] through the existing
 * [Obd2Service.connect] API; the service translates it to a [ConnectTarget]
 * internally via [fromDevice].
 */
data class ConnectTarget(
    val address: String,
    val name: String?
) {
    companion object {
        @Suppress("MissingPermission")
        fun fromDevice(device: BluetoothDevice): ConnectTarget =
            ConnectTarget(device.address, device.name)
    }
}
