package com.sj.obd2app.obd

import android.content.Context
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.settings.CachedPidEntry

/**
 * Abstraction over the subset of [AppSettings] that the connection flow
 * needs.  Introduced as an injection seam so [BluetoothObd2Service] can
 * be unit-tested without hitting SharedPreferences.
 */
interface ConnectionSettingsSource {
    fun isCanBusMode(): Boolean
    fun ignoreCachedPids(): Boolean
    fun getCachedProtocol(address: String): String?
    fun getPidCache(address: String): Map<String, CachedPidEntry>?
    fun savePidCache(
        address: String,
        pids: Map<String, CachedPidEntry>,
        protocol: String
    )
}

/**
 * Production-default backed by [AppSettings].  If [context] is null, all
 * reads return neutral defaults and writes are no-ops (preserves behaviour
 * of code paths that constructed the service without a context).
 */
class AppSettingsConnectionSource(
    private val context: Context?
) : ConnectionSettingsSource {

    override fun isCanBusMode(): Boolean =
        context?.let { AppSettings.isCanBusLoggingEnabled(it) } == true

    override fun ignoreCachedPids(): Boolean =
        context?.let { AppSettings.isIgnoreCachedPidsEnabled(it) } == true

    override fun getCachedProtocol(address: String): String? =
        context?.let { AppSettings.getCachedProtocol(it, address) }

    override fun getPidCache(address: String): Map<String, CachedPidEntry>? =
        context?.let { AppSettings.getPidCache(it, address) }

    override fun savePidCache(
        address: String,
        pids: Map<String, CachedPidEntry>,
        protocol: String
    ) {
        context?.let { AppSettings.savePidCache(it, address, pids, protocol) }
    }
}
