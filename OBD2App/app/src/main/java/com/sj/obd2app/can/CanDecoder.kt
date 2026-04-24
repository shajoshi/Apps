package com.sj.obd2app.can

/**
 * Decodes raw CAN frame payloads into numeric signal values using the DBC convention.
 *
 * Supports both byte orderings:
 *  - Intel (`@1`, [CanSignal.littleEndian] = true): bits are numbered LSB-first inside each
 *    byte, starting from byte 0.
 *  - Motorola (`@0`, [CanSignal.littleEndian] = false): DBC's "big endian" where
 *    [CanSignal.startBit] is the MSB position using the sawtooth bit numbering
 *    `byte*8 + bit` (bit 7 = MSB of each byte, bit 0 = LSB). Sequential signal bits walk
 *    down inside the current byte and then wrap to the MSB (bit 7) of the next byte.
 *
 * Signed signals are sign-extended when [CanSignal.signed] is true.
 */
object CanDecoder {

    /**
     * Decode [signal] from a frame [data] of length `dlc`.
     *
     * Returns `null` if the signal's bit range falls outside the frame payload.
     */
    fun decode(signal: CanSignal, data: ByteArray): Double? {
        val raw = extractRaw(signal, data) ?: return null
        return raw * signal.factor + signal.offset
    }

    /** Extract the raw integer value (signed or unsigned as declared) before scaling. */
    fun extractRaw(signal: CanSignal, data: ByteArray): Long? {
        val totalBits = data.size * 8
        if (signal.length <= 0 || signal.length > 64) return null

        val raw = if (signal.littleEndian) {
            extractIntel(signal.startBit, signal.length, data, totalBits) ?: return null
        } else {
            extractMotorola(signal.startBit, signal.length, data, totalBits) ?: return null
        }

        return if (signal.signed) signExtend(raw, signal.length) else raw
    }

    private fun extractIntel(startBit: Int, length: Int, data: ByteArray, totalBits: Int): Long? {
        if (startBit < 0 || startBit + length > totalBits) return null
        var value = 0L
        for (i in 0 until length) {
            val bit = startBit + i
            val byteIdx = bit ushr 3
            val bitIdx = bit and 7
            val b = (data[byteIdx].toInt() ushr bitIdx) and 1
            value = value or (b.toLong() shl i)
        }
        return value
    }

    /**
     * Motorola/big-endian extraction using DBC sawtooth bit numbering.
     * Successive bits walk from the start bit towards bit 0 of the same byte; when bit 0
     * is crossed we move to bit 7 of the next byte.
     */
    private fun extractMotorola(startBit: Int, length: Int, data: ByteArray, totalBits: Int): Long? {
        if (startBit < 0 || startBit >= totalBits) return null
        var value = 0L
        var bit = startBit
        for (i in 0 until length) {
            val byteIdx = bit ushr 3
            val bitIdx = bit and 7
            if (byteIdx < 0 || byteIdx >= data.size) return null
            val b = (data[byteIdx].toInt() ushr bitIdx) and 1
            // MSB-first: this bit becomes the next most-significant bit of the output.
            value = (value shl 1) or b.toLong()

            // Advance: move towards bit 0 of this byte; when we roll off bit 0, jump to
            // bit 7 of the next byte.
            bit = if (bitIdx == 0) bit + 15 else bit - 1
        }
        return value
    }

    private fun signExtend(raw: Long, length: Int): Long {
        if (length >= 64) return raw
        val signBit = 1L shl (length - 1)
        val mask = (1L shl length) - 1
        val v = raw and mask
        return if (v and signBit != 0L) v or mask.inv() else v
    }
}
