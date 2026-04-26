package com.sj.obd2app.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Pure-JVM unit tests for [BluetoothObd2Service.connect].
 *
 * Exercises the full ELM327 init / protocol-detect / PID-discover pipeline
 * via the test-only entry point [BluetoothObd2Service.runConnectFlowForTest],
 * with a scripted [FakeElm327Transport] standing in for real Bluetooth.
 *
 * No `BluetoothDevice`, no Looper, no Robolectric required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothObd2ServiceTest {

    private lateinit var fake: FakeElm327Transport
    private lateinit var settings: InMemoryConnectionSettingsSource
    private lateinit var service: BluetoothObd2Service

    private val target = ConnectTarget(address = "AA:BB:CC:DD:EE:FF", name = "Test ELM")

    @Before
    fun setUp() {
        fake = FakeElm327Transport()
        settings = InMemoryConnectionSettingsSource()
        // No real context, no factory invocation, no toasts, no polling.
        service = BluetoothObd2Service(
            context = null,
            ioDispatcher = StandardTestDispatcher(),
            transportFactory = TransportFactory { _, _, _, _ ->
                error("TransportFactory should not be invoked from runConnectFlowForTest")
            },
            settingsSource = settings,
            notifier = NoOpUserNotifier
        )
    }

    // ── OBD mode ──────────────────────────────────────────────────────────────

    @Test
    fun `OBD mode with no cached protocol runs full init then locks detected protocol`() = runTest {
        settings.canBusMode = false
        settings.ignoreCachedPids = false

        // Bitmask 0x80000000: MSB set (PID 01 supported), LSB clear so loop stops
        // after the first query. ELM with ATS0 returns no spaces.
        fake.scriptCommand("0100", "410080000000")
        fake.scriptCommand("ATDPN", "6")                 // ISO 15765-4 CAN 11/500

        service.runConnectFlowForTest(target, fake)
        advanceUntilIdle()

        // State transitioned to CONNECTED
        assertEquals(Obd2Service.ConnectionState.CONNECTED, service.connectionState.value)
        // Full OBD init ran
        assertCommandsInOrder(fake.sentCommands, listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSTFF", "ATSP0"))
        // PID discovery query was issued
        assertTrue("expected 0100 query", fake.sentCommands.contains("0100"))
        // Protocol detected and locked
        assertTrue("expected ATSP6 lock", fake.sentCommands.contains("ATSP6"))
        // Protocol cached
        assertEquals("6", settings.getCachedProtocol(target.address))
    }

    @Test
    fun `OBD mode with cached protocol skips ATSP0 probing and uses ATSP6 directly`() = runTest {
        settings.canBusMode = false
        settings.savePidCache(target.address, emptyMap(), protocol = "6")
        fake.scriptCommand("0100", "410080000000")
        fake.scriptCommand("ATDPN", "6")

        service.runConnectFlowForTest(target, fake)
        advanceUntilIdle()

        assertTrue("expected ATSP6", fake.sentCommands.contains("ATSP6"))
        assertFalse("ATSP0 should NOT be sent when cache is honoured", fake.sentCommands.contains("ATSP0"))
    }

    @Test
    fun `ignoreCachedPids forces fresh detection and re-caches`() = runTest {
        settings.canBusMode = false
        settings.ignoreCachedPids = true
        // Stale cached protocol that should be ignored
        settings.savePidCache(target.address, emptyMap(), protocol = "STALE")
        fake.scriptCommand("0100", "410080000000")
        fake.scriptCommand("ATDPN", "6")

        service.runConnectFlowForTest(target, fake)
        advanceUntilIdle()

        // ATSP0 should be sent (auto-detect), not ATSPSTALE
        assertTrue("expected ATSP0 (fresh detect)", fake.sentCommands.contains("ATSP0"))
        assertFalse("must NOT use stale cached protocol", fake.sentCommands.contains("ATSPSTALE"))
        // After detection, the cache must be refreshed with the real protocol
        assertEquals("6", settings.getCachedProtocol(target.address))
    }

    @Test
    fun `OBD mode with 0 PIDs from ECU and no cache leaves supportedPids empty`() = runTest {
        settings.canBusMode = false
        // 0100 returns NODATA — no PIDs discovered; no cache to fall back to.
        fake.scriptCommand("0100", "NODATA")

        service.runConnectFlowForTest(target, fake)
        advanceUntilIdle()

        assertEquals(Obd2Service.ConnectionState.CONNECTED, service.connectionState.value)
        // No ATSP-lock should occur because we never confirmed any PIDs
        assertFalse(fake.sentCommands.any { it.startsWith("ATSP") && it != "ATSP0" })
        // No protocol cached because discovery failed
        assertEquals(null, settings.getCachedProtocol(target.address))
    }

    // ── CAN mode ──────────────────────────────────────────────────────────────

    @Test
    fun `CAN mode skips OBD PID discovery and sets headers ON with raw frames`() = runTest {
        settings.canBusMode = true
        fake.scriptCommand("ATDPN", "6")

        service.runConnectFlowForTest(target, fake)
        advanceUntilIdle()

        assertEquals(Obd2Service.ConnectionState.CONNECTED, service.connectionState.value)
        // CAN-specific init flags
        assertTrue("ATH1 (headers on) required for CAN parsing", fake.sentCommands.contains("ATH1"))
        assertTrue("ATCAF0 (raw frames) required for CAN sniffing", fake.sentCommands.contains("ATCAF0"))
        // OBD-II PID discovery must NOT happen in CAN mode
        assertFalse("0100 must NOT be queried in CAN mode", fake.sentCommands.contains("0100"))
        assertFalse("0120 must NOT be queried in CAN mode", fake.sentCommands.contains("0120"))
        // Protocol still detected and cached so future scans skip probing
        assertEquals("6", settings.getCachedProtocol(target.address))
    }

    @Test
    fun `CAN mode with cached protocol uses ATSP directly and avoids ATSP0`() = runTest {
        settings.canBusMode = true
        settings.savePidCache(target.address, emptyMap(), protocol = "6")
        fake.scriptCommand("ATDPN", "6")

        service.runConnectFlowForTest(target, fake)
        advanceUntilIdle()

        assertTrue(fake.sentCommands.contains("ATSP6"))
        assertFalse(fake.sentCommands.contains("ATSP0"))
    }

    // ── Error paths ───────────────────────────────────────────────────────────

    @Test
    fun `transport connect failure transitions to ERROR state`() = runTest {
        fake.connectThrows = IOException("socket refused")

        service.runConnectFlowForTest(target, fake)
        advanceUntilIdle()

        assertEquals(Obd2Service.ConnectionState.ERROR, service.connectionState.value)
        assertNotNull(service.errorMessage.value)
        assertTrue(service.errorMessage.value!!.contains("Connection failed"))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun assertCommandsInOrder(actual: List<String>, expected: List<String>) {
        var idx = 0
        for (cmd in actual) {
            if (idx < expected.size && cmd == expected[idx]) idx++
        }
        assertEquals(
            "expected to find $expected as a subsequence of $actual",
            expected.size, idx
        )
    }
}
