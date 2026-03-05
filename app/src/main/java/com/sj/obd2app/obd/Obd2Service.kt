package com.sj.obd2app.obd

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.StateFlow

/**
 * Common interface for OBD-II data services.
 * Implemented by [BluetoothObd2Service] (real hardware) and [MockObd2Service] (testing).
 */
interface Obd2Service {

    /** Current connection state. */
    val connectionState: StateFlow<ConnectionState>

    /** Live list of OBD-II readings, updated each polling cycle. */
    val obd2Data: StateFlow<List<Obd2DataItem>>

    /** Latest error message, or null if no error. */
    val errorMessage: StateFlow<String?>

    /** Connect to a Bluetooth device. Pass null for mock connections. */
    fun connect(device: BluetoothDevice?)

    /** Disconnect and stop polling. */
    fun disconnect()

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
}
