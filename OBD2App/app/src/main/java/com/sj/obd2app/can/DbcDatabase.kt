package com.sj.obd2app.can

/**
 * Decoded DBC signal definition.
 *
 * Bit-layout fields mirror the DBC file format:
 *  - [startBit] and [length] measured in bits.
 *  - [littleEndian] is true for Intel (`@1`), false for Motorola (`@0`).
 *  - [signed] is true for signed integers (`-` sign in DBC), false for unsigned (`+`).
 */
data class CanSignal(
    val name: String,
    val startBit: Int,
    val length: Int,
    val littleEndian: Boolean,
    val signed: Boolean,
    val factor: Double,
    val offset: Double,
    val min: Double,
    val max: Double,
    val unit: String,
    val receivers: List<String>,
    val isMultiplexor: Boolean = false,
    val multiplexValue: Int? = null
)

/**
 * Decoded DBC message definition.
 *
 * [id] holds the 11-bit or 29-bit CAN identifier. The extended flag is carried separately
 * (DBC encodes extended by OR-ing `0x80000000` into the id; the parser strips it and sets
 * [extended] accordingly).
 */
data class CanMessage(
    val id: Int,
    val extended: Boolean,
    val name: String,
    val dlc: Int,
    val transmitter: String,
    val signals: List<CanSignal>
)

/**
 * Parsed DBC database. Not all DBC constructs are supported — unsupported lines are
 * recorded in [warnings] so the UI can surface them to the user.
 */
data class DbcDatabase(
    val version: String?,
    val nodes: List<String>,
    val messages: List<CanMessage>,
    val sourceFileName: String,
    val warnings: List<String> = emptyList()
) {
    fun findSignal(messageId: Int, signalName: String): Pair<CanMessage, CanSignal>? {
        val msg = messages.firstOrNull { it.id == messageId } ?: return null
        val sig = msg.signals.firstOrNull { it.name == signalName } ?: return null
        return msg to sig
    }

    fun messageById(id: Int): CanMessage? = messages.firstOrNull { it.id == id }
}

/**
 * Reference to a selected signal inside a [DbcDatabase]. Persisted in [CanProfile].
 */
data class SignalRef(val messageId: Int, val signalName: String) {
    fun key(): String = "${Integer.toHexString(messageId).uppercase()}:$signalName"
}
