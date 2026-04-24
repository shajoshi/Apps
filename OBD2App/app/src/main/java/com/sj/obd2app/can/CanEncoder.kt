package com.sj.obd2app.can

/**
 * Inverse of [CanDecoder]: packs a raw integer value into a frame payload [ByteArray] at the
 * bit range described by a [CanSignal]. Used by the mock frame generator so that signals
 * decoded via [CanDecoder] round-trip exactly to the input value.
 */
object CanEncoder {

    /**
     * Write [rawValue] (already unscaled — i.e. the integer expected by `CanDecoder.extractRaw`)
     * into [data] at the bit range defined by [signal]. Out-of-range bits are silently clipped
     * so callers do not need to pre-check [data] size.
     */
    fun encode(signal: CanSignal, rawValue: Long, data: ByteArray) {
        val mask = if (signal.length >= 64) -1L else (1L shl signal.length) - 1L
        val raw = rawValue and mask
        if (signal.littleEndian) encodeIntel(signal, raw, data)
        else encodeMotorola(signal, raw, data)
    }

    private fun encodeIntel(signal: CanSignal, raw: Long, data: ByteArray) {
        for (i in 0 until signal.length) {
            val bit = signal.startBit + i
            val byteIdx = bit ushr 3
            val bitIdx = bit and 7
            if (byteIdx < 0 || byteIdx >= data.size) continue
            val b = ((raw ushr i) and 1L).toInt()
            writeBit(data, byteIdx, bitIdx, b)
        }
    }

    private fun encodeMotorola(signal: CanSignal, raw: Long, data: ByteArray) {
        var bit = signal.startBit
        for (i in 0 until signal.length) {
            val byteIdx = bit ushr 3
            val bitIdx = bit and 7
            if (byteIdx < 0 || byteIdx >= data.size) return
            // Bit i (MSB-first) of the output → most-significant bit first
            val b = ((raw ushr (signal.length - 1 - i)) and 1L).toInt()
            writeBit(data, byteIdx, bitIdx, b)
            bit = if (bitIdx == 0) bit + 15 else bit - 1
        }
    }

    private fun writeBit(data: ByteArray, byteIdx: Int, bitIdx: Int, bit: Int) {
        val existing = data[byteIdx].toInt() and 0xFF
        val mask = 1 shl bitIdx
        val cleared = existing and mask.inv()
        data[byteIdx] = (cleared or ((bit and 1) shl bitIdx)).toByte()
    }
}
