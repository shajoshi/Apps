package com.sj.obd2app.obd

import android.content.Context

/**
 * Central provider that returns either the real [BluetoothObd2Service]
 * or the [MockObd2Service] depending on the [useMock] flag.
 *
 * Toggle mock mode at app start:
 * ```
 * Obd2ServiceProvider.useMock = true
 * ```
 */
object Obd2ServiceProvider {

    /**
     * Set to `true` to use mock data for testing without a real OBD2 adapter.
     * Must be set before the first call to [getService].
     */
    var useMock: Boolean = false

    /**
     * Initialise the mock service. Call this in Application.onCreate()
     * or MainActivity.onCreate() before using mock mode.
     */
    fun initMock(context: Context) {
        MockObd2Service.init(context.applicationContext)
    }

    /**
     * Returns the active [Obd2Service] implementation.
     */
    fun getService(): Obd2Service {
        return if (useMock) {
            MockObd2Service.getInstance()
        } else {
            BluetoothObd2Service.getInstance()
        }
    }
}
