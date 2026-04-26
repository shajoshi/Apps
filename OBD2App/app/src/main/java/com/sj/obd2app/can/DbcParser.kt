package com.sj.obd2app.can

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * Minimal DBC parser for OBD2App.
 *
 * Supported grammar:
 *  - `VERSION "..."`
 *  - `BU_: node1 node2 ...`
 *  - `BO_ <id> <name>: <dlc> <transmitter>`
 *     (extended id encoded as `id | 0x80000000`)
 *  - ` SG_ <name>[ M|m<n>] : <start>|<len>@<order><sign> (<factor>,<offset>) [<min>|<max>] "<unit>" <rcv>,<rcv>`
 *
 * Anything else is ignored. Unsupported-but-recognised lines (CM_, VAL_, BA_, EV_, …) are
 * silently skipped. Genuinely malformed SG_/BO_ lines are recorded as warnings on the
 * returned [DbcDatabase] so the UI can tell the user.
 *
 * DBC files are commonly Windows-1252 / Latin-1. We default to ISO-8859-1 decoding so that
 * high-byte characters never throw; callers may override via [parse] / [parseText].
 */
object DbcParser {

    private const val EXTENDED_FLAG = 0x80000000L.toInt()

    fun parse(
        input: InputStream,
        sourceFileName: String,
        charset: Charset = Charsets.ISO_8859_1
    ): DbcDatabase {
        val reader = BufferedReader(InputStreamReader(input, charset))
        return parseLines(reader.lineSequence(), sourceFileName)
    }

    fun parseText(text: String, sourceFileName: String): DbcDatabase {
        return parseLines(text.lineSequence(), sourceFileName)
    }

    private fun parseLines(lines: Sequence<String>, sourceFileName: String): DbcDatabase {
        var version: String? = null
        val nodes = mutableListOf<String>()
        val messages = mutableListOf<MutableCanMessage>()
        val warnings = mutableListOf<String>()

        var currentMsg: MutableCanMessage? = null

        for ((lineNo0, raw) in lines.withIndex()) {
            val lineNo = lineNo0 + 1
            val line = raw.trimEnd()
            if (line.isBlank()) continue

            val trimmed = line.trimStart()

            // SG_ lines are indented and belong to the last BO_
            if (trimmed.startsWith("SG_ ")) {
                if (currentMsg == null) {
                    warnings += "Line $lineNo: SG_ with no preceding BO_"
                    continue
                }
                try {
                    currentMsg.signals += parseSignal(trimmed)
                } catch (e: Exception) {
                    warnings += "Line $lineNo: unparseable SG_ (${e.message}): ${trimmed.take(120)}"
                }
                continue
            }

            // New top-level definition → close out any previous BO_
            if (trimmed.startsWith("BO_ ")) {
                currentMsg?.let { messages += it }
                currentMsg = null
                try {
                    currentMsg = parseMessage(trimmed)
                } catch (e: Exception) {
                    warnings += "Line $lineNo: unparseable BO_ (${e.message}): ${trimmed.take(120)}"
                }
                continue
            }

            if (trimmed.startsWith("VERSION ")) {
                version = trimmed.removePrefix("VERSION").trim().trim('"').ifEmpty { null }
                continue
            }

            if (trimmed.startsWith("BU_:")) {
                val rest = trimmed.removePrefix("BU_:").trim()
                if (rest.isNotEmpty()) {
                    nodes += rest.split(Regex("\\s+"))
                }
                continue
            }

            // Everything else (NS_, BS_, CM_, BA_, VAL_, EV_, SIG_GROUP_, etc.) → ignore.
            // If a non-SG_ line appears after a BO_, we close the current message.
            if (currentMsg != null && !trimmed.startsWith(" ") && !trimmed.startsWith("\t")) {
                // Line is at column 0 and isn't SG_ → BO_ block is done.
                messages += currentMsg!!
                currentMsg = null
            }
        }

        currentMsg?.let { messages += it }

        return DbcDatabase(
            version = version,
            nodes = nodes,
            messages = messages.map { it.toImmutable() },
            sourceFileName = sourceFileName,
            warnings = warnings
        )
    }

    // ── BO_ <id> <name>: <dlc> <transmitter> ─────────────────────────────────
    private fun parseMessage(line: String): MutableCanMessage {
        // "BO_ 523 ABS_P_20B: 8 ABS"
        val body = line.removePrefix("BO_").trim()
        val colonIdx = body.indexOf(':')
        require(colonIdx > 0) { "missing ':'" }
        val head = body.substring(0, colonIdx).trim()
        val tail = body.substring(colonIdx + 1).trim()

        val headParts = head.split(Regex("\\s+"))
        require(headParts.size >= 2) { "bad head" }
        val rawId = headParts[0].toLong()
        val name = headParts[1]

        val extended = (rawId.toInt() and EXTENDED_FLAG) != 0
        val id = (rawId.toInt() and EXTENDED_FLAG.inv())

        val tailParts = tail.split(Regex("\\s+"))
        require(tailParts.isNotEmpty()) { "missing dlc" }
        val dlc = tailParts[0].toInt()
        val transmitter = tailParts.getOrNull(1) ?: "Vector__XXX"

        return MutableCanMessage(id, extended, name, dlc, transmitter, mutableListOf())
    }

    // SG_ <name>[ M|m<val>] : <start>|<len>@<order><sign> (<factor>,<offset>) [<min>|<max>] "<unit>" <rcv1>,<rcv2>
    private val SIGNAL_REGEX = Regex(
        """SG_\s+(\S+?)(?:\s+(M|m\d+))?\s*:\s*""" +
            """(\d+)\|(\d+)@([01])([+\-])\s*""" +
            """\(([-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?\d+)?),([-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?\d+)?)\)\s*""" +
            """\[([-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?\d+)?)\|([-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?\d+)?)\]\s*""" +
            """"([^"]*)"\s*(.*)"""
    )

    private fun parseSignal(line: String): CanSignal {
        val m = SIGNAL_REGEX.find(line) ?: throw IllegalArgumentException("regex mismatch")
        val g = m.groupValues
        val name = g[1]
        val muxTag = g[2]
        val startBit = g[3].toInt()
        val length = g[4].toInt()
        val byteOrder = g[5]            // "1" = little-endian (Intel), "0" = big-endian (Motorola)
        val sign = g[6]                  // "+" or "-"
        val factor = g[7].toDouble()
        val offset = g[8].toDouble()
        val min = g[9].toDouble()
        val max = g[10].toDouble()
        val unit = sanitizeUnit(g[11])
        val receiversField = g[12].trim()

        val receivers = receiversField
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val isMux = muxTag == "M"
        val muxVal = if (muxTag.startsWith("m")) muxTag.substring(1).toIntOrNull() else null

        return CanSignal(
            name = name,
            startBit = startBit,
            length = length,
            littleEndian = byteOrder == "1",
            signed = sign == "-",
            factor = factor,
            offset = offset,
            min = min,
            max = max,
            unit = unit,
            receivers = receivers,
            isMultiplexor = isMux,
            multiplexValue = muxVal
        )
    }

    /**
     * Cleans a raw DBC unit string. DBC files sometimes embed range/formula notes
     * inside the unit field (e.g. "0..8191 rpm, E = N * 1"). This function tries to
     * extract just the actual unit token, falling back to blank if it's too messy.
     */
    internal fun sanitizeUnit(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s
        if (!s.contains(',') && !s.contains('=')) return s
        val unitToken = s.split(Regex("[,\\s]+"))
            .map { it.trim() }
            .firstOrNull { tok ->
                tok.isNotEmpty() &&
                (tok[0].isLetter() || tok[0] == '°') &&
                tok.all { c -> c.isLetterOrDigit() || c == '°' || c == '%' || c == '/' || c == '_' }
            }
        return unitToken ?: ""
    }

    private data class MutableCanMessage(
        val id: Int,
        val extended: Boolean,
        val name: String,
        val dlc: Int,
        val transmitter: String,
        val signals: MutableList<CanSignal>
    ) {
        fun toImmutable() = CanMessage(id, extended, name, dlc, transmitter, signals.toList())
    }
}
