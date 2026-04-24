package com.sj.obd2app.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanDecoderTest {

    private val DELTA = 1e-9

    private fun sig(
        startBit: Int,
        length: Int,
        little: Boolean,
        signed: Boolean = false,
        factor: Double = 1.0,
        offset: Double = 0.0
    ) = CanSignal(
        name = "t",
        startBit = startBit,
        length = length,
        littleEndian = little,
        signed = signed,
        factor = factor,
        offset = offset,
        min = 0.0,
        max = 0.0,
        unit = "",
        receivers = emptyList()
    )

    // ── Intel / little-endian ─────────────────────────────────────────────────

    @Test
    fun `intel 8 bit byte0`() {
        val raw = CanDecoder.extractRaw(sig(0, 8, little = true), byteArrayOf(0x7F, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(0x7FL, raw)
    }

    @Test
    fun `intel 16 bit spanning bytes`() {
        // startBit 0, length 16, little-endian -> bytes[0..1] as LE u16
        val data = byteArrayOf(0x34, 0x12, 0, 0, 0, 0, 0, 0)
        val raw = CanDecoder.extractRaw(sig(0, 16, little = true), data)
        assertEquals(0x1234L, raw)
    }

    @Test
    fun `intel signed negative`() {
        // startBit 0, length 8, signed, 0xFF -> -1
        val raw = CanDecoder.extractRaw(sig(0, 8, little = true, signed = true), byteArrayOf(0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0))
        assertEquals(-1L, raw)
    }

    @Test
    fun `intel factor offset scaling`() {
        val s = sig(0, 16, little = true, factor = 0.01, offset = -40.0)
        val data = byteArrayOf(0x10, 0x27, 0, 0, 0, 0, 0, 0) // 0x2710 = 10000
        val decoded = CanDecoder.decode(s, data)!!
        assertEquals(10000 * 0.01 - 40.0, decoded, DELTA)
    }

    // ── Motorola / big-endian ─────────────────────────────────────────────────

    @Test
    fun `motorola 8 bit aligned`() {
        // Motorola: startBit 7, length 8 → byte 0, MSB-first
        val raw = CanDecoder.extractRaw(sig(7, 8, little = false), byteArrayOf(0xAB.toByte(), 0, 0, 0, 0, 0, 0, 0))
        assertEquals(0xABL, raw)
    }

    @Test
    fun `motorola 16 bit crosses byte boundary`() {
        // startBit 7 length 16 → bytes 0..1 big-endian
        val data = byteArrayOf(0x12, 0x34, 0, 0, 0, 0, 0, 0)
        val raw = CanDecoder.extractRaw(sig(7, 16, little = false), data)
        assertEquals(0x1234L, raw)
    }

    @Test
    fun `motorola sawtooth start 15 len 16 reads bytes 1 and 2`() {
        // X150 DBC uses startBit e.g. 15|8@0+ which is the MSB of byte 1.
        // For length 16: bits walk byte1[7..0] then byte2[7..0] → bytes 1..2 big-endian.
        val data = byteArrayOf(0, 0xDE.toByte(), 0xAD.toByte(), 0, 0, 0, 0, 0)
        val raw = CanDecoder.extractRaw(sig(15, 16, little = false), data)
        assertEquals(0xDEADL, raw)
    }

    // ── Bounds ────────────────────────────────────────────────────────────────

    @Test
    fun `returns null when out of range intel`() {
        val raw = CanDecoder.extractRaw(sig(60, 8, little = true), ByteArray(8))
        assertNull(raw)
    }

    @Test
    fun `returns null when length invalid`() {
        val raw = CanDecoder.extractRaw(sig(0, 0, little = true), ByteArray(8))
        assertNull(raw)
    }
}
