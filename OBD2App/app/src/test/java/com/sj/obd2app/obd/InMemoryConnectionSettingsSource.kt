package com.sj.obd2app.obd

import com.sj.obd2app.settings.CachedPidEntry

/**
 * In-memory implementation of [ConnectionSettingsSource] for unit tests.
 * No SharedPreferences, no Context, no Android framework.
 */
class InMemoryConnectionSettingsSource(
    var canBusMode: Boolean = false,
    var ignoreCachedPids: Boolean = false
) : ConnectionSettingsSource {

    private val pidCache = mutableMapOf<String, Map<String, CachedPidEntry>>()
    private val protocolCache = mutableMapOf<String, String>()

    override fun isCanBusMode(): Boolean = canBusMode
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
