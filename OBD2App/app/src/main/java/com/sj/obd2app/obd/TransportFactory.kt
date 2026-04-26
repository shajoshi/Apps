package com.sj.obd2app.obd

import android.bluetooth.BluetoothDevice
import android.content.Context

/**
 * Creates an [Elm327Transport] for a given Bluetooth device.
 *
 * Extracted as an injection seam so tests can supply a fake transport
 * without requiring real Bluetooth hardware.
 */
fun interface TransportFactory {
    fun create(
        device: BluetoothDevice,
        forceBle: Boolean,
        context: Context?,
        log: (String) -> Unit
    ): Elm327Transport
}

/**
 * Production factory: picks Classic vs BLE based on device type, honouring
 * the user's "Force BLE" preference.  Mirrors the original private logic
 * that previously lived inside [BluetoothObd2Service].
 */
@Suppress("MissingPermission")
object DefaultTransportFactory : TransportFactory {
    override fun create(
        device: BluetoothDevice,
        forceBle: Boolean,
        context: Context?,
        log: (String) -> Unit
    ): Elm327Transport {
        if (forceBle) {
            log("Force BLE enabled - using BLE transport")
            return BleTransport(
                context ?: throw IllegalStateException("Context required for BLE"),
                device
            )
        }
        return when (device.type) {
            BluetoothDevice.DEVICE_TYPE_LE -> {
                log("Device type: BLE only")
                BleTransport(
                    context ?: throw IllegalStateException("Context required for BLE"),
                    device
                )
            }
            BluetoothDevice.DEVICE_TYPE_DUAL -> {
                log("Device type: Dual mode (trying Classic first)")
                ClassicBluetoothTransport(device)
            }
            else -> {
                log("Device type: Classic Bluetooth")
                ClassicBluetoothTransport(device)
            }
        }
    }
}
