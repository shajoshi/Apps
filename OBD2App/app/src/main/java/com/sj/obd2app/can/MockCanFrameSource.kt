package com.sj.obd2app.can

import kotlin.math.PI
import kotlin.math.sin

/**
 * Produces synthetic [CanFrame]s for a [CanProfile] so users can verify the full CAN pipeline
 * (decode → log → UI) without a real car.
 *
 * For every message that has at least one selected signal we emit one frame per sampling tick,
 * with each selected signal filled in via [CanEncoder] using a smooth sinusoidal sweep across
 * the signal's full raw range. The decoder will then recover the same pattern.
 */
class MockCanFrameSource(
    private val profile: CanProfile,
    private val dbc: DbcDatabase
) {
    /** Message id → list of signals from the DBC that are selected in the profile. */
    private val grouped: Map<Int, MessageBundle> = buildBundles()

    private data class MessageBundle(val msg: CanMessage, val signals: List<CanSignal>)

    /** UTC epoch ms at which the source was constructed — drives the sweep phase per signal. */
    private val t0 = System.currentTimeMillis()

    /** Monotonic tick counter, used to stagger signals with different periods. */
    private var tick: Long = 0

    /**
     * Emit one synthetic batch — one [CanFrame] per message that has selected signals.
     * Frames are produced with realistic DLC and encoded payloads that round-trip through
     * [CanDecoder] to the values implied by the sinusoidal sweep.
     */
    fun nextBatch(): List<CanFrame> {
        val nowMs = System.currentTimeMillis()
        val t = (nowMs - t0) / 1000.0
        tick++

        val out = ArrayList<CanFrame>(grouped.size)
        for ((msgId, bundle) in grouped) {
            val dlc = bundle.msg.dlc.coerceIn(1, 8)
            val data = ByteArray(dlc)
            for (sig in bundle.signals) {
                val raw = synthRawValue(sig, t)
                CanEncoder.encode(sig, raw, data)
            }
            out += CanFrame(id = msgId, extended = bundle.msg.extended, data = data)
        }
        return out
    }

    /**
     * Map each signal to a raw integer that sweeps across its declared range with a
     * per-signal period (derived from the signal's name hash) to avoid all channels moving
     * in lockstep. For 1-bit signals we toggle every second.
     */
    private fun synthRawValue(sig: CanSignal, tSec: Double): Long {
        if (sig.length <= 0) return 0L
        if (sig.length == 1) return (tSec.toLong() and 1L)

        val maxRaw = if (sig.length >= 63) Long.MAX_VALUE else (1L shl sig.length) - 1L
        // Vary the period a bit per signal so the dashboard shows activity.
        val period = 4.0 + (sig.name.hashCode().toLong().and(0xF)).toDouble() // 4..19s
        val phase = (sig.startBit % 8).toDouble() * (PI / 4.0)
        val fraction = 0.5 + 0.45 * sin(2.0 * PI * tSec / period + phase)
        val raw = (fraction * maxRaw.toDouble()).toLong().coerceIn(0L, maxRaw)
        return if (sig.signed) {
            // Centre signed signals around 0 instead of mid-range.
            val half = maxRaw / 2L
            (raw - half)
        } else {
            raw
        }
    }

    private fun buildBundles(): Map<Int, MessageBundle> {
        val byId = profile.selectedSignals.groupBy { it.messageId }
        val out = HashMap<Int, MessageBundle>(byId.size)
        for ((msgId, refs) in byId) {
            val msg = dbc.messageById(msgId) ?: continue
            val sigs = refs.mapNotNull { r -> msg.signals.firstOrNull { it.name == r.signalName } }
            if (sigs.isNotEmpty()) out[msgId] = MessageBundle(msg, sigs)
        }
        return out
    }
}
