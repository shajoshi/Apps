package com.sj.obd2app.can

/**
 * Parses a single ELM327 monitor-mode output line into a [CanFrame].
 *
 * Expected input (after `ATH1 ATS0 ATCAF0 ATMA`):
 *  - 11-bit IDs: `XXXDDDD…` where `XXX` is 3 hex chars (ID) and `DDDD…` is 0–16 hex chars (0–8 data bytes).
 *  - 29-bit IDs: `XXXXXXXXDDDD…` where `XXXXXXXX` is 8 hex chars.
 *
 * Spaces, tabs, and any `<CR><LF>` have already been stripped by [Elm327Transport.readStreamLine].
 * Lines that don't match (noise, `SEARCHING...`, `BUFFER FULL`, etc.) return `null`.
 */
object CanFrameParser {

    private val HEX_ONLY = Regex("^[0-9A-Fa-f]+$")

    /** Minimum useful line: 3 char ID + 0 data bytes. */
    private const val MIN_11BIT_CHARS = 3

    /** 29-bit IDs start at 8 hex chars for the ID alone. */
    private const val EXT_ID_CHARS = 8

    fun parse(raw: String): CanFrame? {
        val s = raw.trim().uppercase()
        if (s.isEmpty()) return null
        if (!HEX_ONLY.matches(s)) return null
        if (s.length < MIN_11BIT_CHARS) return null
        if (s.length % 2 == 0) {
            // Even length ⇒ 8-hex-char (29-bit) extended ID, or 2-hex-char data only (invalid).
            if (s.length < EXT_ID_CHARS + 2) return null
            return parseExtended(s)
        }
        // Odd length ⇒ 3-hex-char (11-bit) standard ID.
        return parseStandard(s)
    }

    private fun parseStandard(s: String): CanFrame? {
        val id = s.substring(0, 3).toIntOrNull(16) ?: return null
        val data = parseBytes(s.substring(3)) ?: return null
        if (data.size > 8) return null
        return CanFrame(id = id, extended = false, data = data)
    }

    private fun parseExtended(s: String): CanFrame? {
        val id = s.substring(0, EXT_ID_CHARS).toLongOrNull(16)?.toInt() ?: return null
        val data = parseBytes(s.substring(EXT_ID_CHARS)) ?: return null
        if (data.size > 8) return null
        return CanFrame(id = id, extended = true, data = data)
    }

    private fun parseBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val b = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            out[i] = b.toByte()
        }
        return out
    }
}

/** A single parsed CAN frame. */
data class CanFrame(val id: Int, val extended: Boolean, val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanFrame) return false
        return id == other.id && extended == other.extended && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var r = id
        r = 31 * r + extended.hashCode()
        r = 31 * r + data.contentHashCode()
        return r
    }
}
