package com.sj.obd2app.obd

/**
 * Test double for [Elm327Transport] that lets a test script per-command
 * responses, observe everything that was sent, and inject failures.
 *
 * No threads, no I/O, no Android dependencies — usable in pure JVM tests.
 */
class FakeElm327Transport : Elm327Transport {

    /** All commands seen by [sendCommand], in order. */
    val sentCommands = mutableListOf<String>()

    /** Per-command response map. Falls back to [defaultResponse] when missing. */
    private val scripted = mutableMapOf<String, String>()

    /** Response returned when no script entry matches. */
    var defaultResponse: String = "OK"

    /** If non-null, [connect] throws this exception. */
    var connectThrows: Throwable? = null

    /** Optional dynamic responder; takes precedence over [scripted] when set. */
    var responder: ((String) -> String)? = null

    private var connected = false

    fun scriptCommand(cmd: String, response: String) {
        scripted[cmd] = response
    }

    override suspend fun connect() {
        connectThrows?.let { throw it }
        connected = true
    }

    override suspend fun sendCommand(command: String): String {
        sentCommands += command
        responder?.let { return it(command) }
        return scripted[command] ?: defaultResponse
    }

    override fun isHealthy(): Boolean = connected

    override fun close() {
        connected = false
    }

    override fun getTransportType(): String = "FAKE"

    override suspend fun sendRaw(command: String) {
        sentCommands += "RAW:$command"
    }

    override suspend fun readStreamLine(timeoutMs: Long): String? = null

    override suspend fun drainInput(timeoutMs: Long) { /* no-op */ }
}
