package com.sj.obd2app.obd

import com.sj.obd2app.settings.AppMode
import com.sj.obd2app.settings.CachedPidEntry

/**
 * In-memory implementation of [ConnectionSettingsSource] for unit tests.
 * No SharedPreferences, no Context, no Android framework.
 */
class InMemoryConnectionSettingsSource(
    var appMode: AppMode = AppMode.OBD,
    var ignoreCachedPids: Boolean = false
) : ConnectionSettingsSource {

    private val pidCache = mutableMapOf<String, Map<String, CachedPidEntry>>()
    private val protocolCache = mutableMapOf<String, String>()

    override fun getAppMode(): AppMode = appMode
    override fun ignoreCachedPids(): Boolean = ignoreCachedPids

    override fun getCachedProtocol(address: String): String? = protocolCache[address]

    override fun getPidCache(address: String): Map<String, CachedPidEntry>? = pidCache[address]

    override fun savePidCache(
        address: String,
        pids: Map<String, CachedPidEntry>,
        protocol: String
    ) {
        pidCache[address] = pids
        protocolCache[address] = protocol
    }
}
