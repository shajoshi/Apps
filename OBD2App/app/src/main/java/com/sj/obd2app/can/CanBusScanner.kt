package com.sj.obd2app.can

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.sj.obd2app.obd.BluetoothObd2Service
import com.sj.obd2app.obd.Elm327Transport
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.ObdStateManager
import com.sj.obd2app.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * CAN Bus sniffing engine.
 *
 * Borrows the currently-connected ELM327 transport from [BluetoothObd2Service], puts the
 * adapter into CAN monitor mode, streams frames, decodes the signals requested by a
 * [CanProfile], and logs decoded samples (and optional raw frames) to app-private storage
 * under `files/can_scans/`.
 *
 * This is a singleton because at most one scan can run on a single ELM327 adapter at a time.
 */
object CanBusScanner {

    private const val TAG = "CanBusScanner"
    private const val STREAM_READ_TIMEOUT_MS = 500L
    private const val EXIT_DRAIN_MS = 300L

    sealed class State {
        object Idle : State()
        object Starting : State()
        data class Running(
            val profileId: String,
            val profileName: String,
            val startedAtMs: Long,
            val frames: Long,
            val decoded: Long,
            val dropped: Long,
            val lastFrameAgeMs: Long
        ) : State()
        object Stopping : State()
        data class Error(val message: String) : State()
    }

    /** Latest decoded value per selected signal, keyed by [SignalRef.key]. */
    data class LatestSample(
        val signalName: String,
        val messageId: Int,
        val value: Double,
        val unit: String,
        val timestampMs: Long
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _latest = MutableStateFlow<Map<String, LatestSample>>(emptyMap())
    val latest: StateFlow<Map<String, LatestSample>> = _latest.asStateFlow()

    /** Returns a point-in-time copy of all currently decoded signal values for [CanDataOrchestrator]. */
    fun snapshotLatest(): Map<String, LatestSample> = liveRows.toMap()

    private var scanJob: Job? = null
    private var transport: Elm327Transport? = null

    /** Snapshot of decoded signal rows for UI rendering; kept alongside [_latest]. */
    private val liveRows: MutableMap<String, LatestSample> = ConcurrentHashMap()

    /** True when the scanner was started with previewMode=true (no file I/O). */
    @Volatile var isPreviewMode: Boolean = false
        private set

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Begin a scan. Fails immediately (error state) if:
     *  - another scan is already active;
     *  - no OBD connection is available;
     *  - the profile's DBC is missing or cannot be loaded.
     *
     * @param previewMode When true, file I/O (samples.jsonl / raw.jsonl) is suppressed.
     *   Use this for the pre-trip "live preview" phase. Set to false when a trip starts
     *   so decoded samples are persisted to the log folder.
     */
    fun start(context: Context, profile: CanProfile, previewMode: Boolean = false) {
        if (scanJob?.isActive == true) {
            Log.w(TAG, "start() ignored — already running")
            return
        }
        isPreviewMode = previewMode

        val service = try {
            com.sj.obd2app.obd.Obd2ServiceProvider.getService()
        } catch (e: Exception) {
            _state.value = State.Error("OBD service unavailable: ${e.message}")
            return
        }
        if (service.connectionState.value != Obd2Service.ConnectionState.CONNECTED) {
            _state.value = State.Error("Connect the OBD adapter before starting a scan.")
            return
        }

        val repo = CanProfileRepository.getInstance(context)
        val isMock = ObdStateManager.isMockMode
        val useDemo = profile.useDemoData && isMock

        // Load the DBC, or use the built-in demo when the profile requests it.
        val dbcFile = if (useDemo) null else repo.dbcFileFor(profile.id)

        if (dbcFile == null && !useDemo) {
            _state.value = State.Error("DBC file missing for profile ${profile.name}.")
            return
        }

        val dbc: DbcDatabase = if (useDemo) {
            Log.i(TAG, "useDemoData=true — using built-in demo database for mock scan")
            DemoDbcDatabase.database
        } else {
            try {
                dbcFile!!.inputStream().use { DbcParser.parse(it, profile.dbcFileName) }
            } catch (e: Exception) {
                Log.e(TAG, "DBC parse failed", e)
                _state.value = State.Error("Could not parse DBC: ${e.message}")
                return
            }
        }

        // When using demo data, substitute an effective profile that pre-selects all demo signals.
        val effectiveProfile = if (useDemo) {
            DemoDbcDatabase.demoProfile().copy(
                name = profile.name.ifBlank { "Demo" },
                samplingMs = profile.samplingMs
            )
        } else {
            profile
        }

        // Pick a frame source based on mock/real mode.
        val source: FrameSource = if (isMock) {
            val captureFile = repo.captureFileFor(profile.id)
            if (profile.playbackCaptureFileName != null && captureFile != null) {
                Log.i(TAG, "Starting CAPTURE playback for profile '${effectiveProfile.name}' " +
                    "from ${captureFile.name}")
                CaptureFrameSource(captureFile)
            } else {
                Log.i(TAG, "Starting MOCK CAN scan for profile '${effectiveProfile.name}' (synthetic)")
                val logFn: ((String) -> Unit)? = when (service) {
                    is BluetoothObd2Service -> service::appendConnectionLog
                    is com.sj.obd2app.obd.MockObd2Service -> service::appendConnectionLog
                    else -> null
                }
                MockFrameSource(effectiveProfile, dbc, logFn)
            }
        } else {
            if (service !is BluetoothObd2Service) {
                _state.value = State.Error("CAN scan requires a Bluetooth OBD adapter in real mode.")
                return
            }
            val borrowed = service.borrowTransport() ?: run {
                _state.value = State.Error("Could not borrow transport from OBD service.")
                return
            }
            transport = borrowed
            RealFrameSource(borrowed, profile, service)
        }

        _state.value = State.Starting
        liveRows.clear()
        _latest.value = emptyMap()

        scanJob = CoroutineScope(Dispatchers.IO).launch {
            runScan(context, effectiveProfile, dbc, source, previewMode)
        }
    }

    /** Request graceful termination. Safe to call even if not running. */
    fun stop() {
        val job = scanJob ?: return
        if (!job.isActive) return
        _state.value = State.Stopping
        job.cancel()
    }

    // ── Scan body ─────────────────────────────────────────────────────────────

    private suspend fun CoroutineScope.runScan(
        context: Context,
        profile: CanProfile,
        dbc: DbcDatabase,
        source: FrameSource,
        previewMode: Boolean = false
    ) {
        val signalsByMessage: Map<Int, List<CanSignal>> = profile.selectedSignals
            .mapNotNull { ref -> dbc.findSignal(ref.messageId, ref.signalName)?.second?.let { s -> ref.messageId to s } }
            .groupBy({ it.first }, { it.second })

        val watchedIds: Set<Int> = profile.canIdFilter?.toSet()
            ?: signalsByMessage.keys

        // In preview mode, suppress all file I/O until the trip starts.
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val logBase = "can_${sanitize(profile.name)}_$ts"
        val samplesFileName = "$logBase.samples.jsonl"
        val samplesFile = if (previewMode) null else buildCanLogWriter(context, samplesFileName, "application/json")
        val rawFile = if (previewMode || !profile.recordRawFrames) null else buildCanLogWriter(context, "$logBase.raw.jsonl", "application/json")

        if (!previewMode && samplesFile == null) {
            Log.e(TAG, "Failed to create samples writer")
            _state.value = State.Error("Failed to create log file")
            return
        }
        val samplesWriter: BufferedWriter? = samplesFile
        val rawWriter: BufferedWriter? = rawFile

        var frames = 0L
        var decoded = 0L
        var dropped = 0L
        var lastFrameAt = System.currentTimeMillis()
        val startedAt = System.currentTimeMillis()

        try {
            source.start()
            Log.i(TAG, "CAN monitor started [${source.label}] — watching ${watchedIds.size} ids, " +
                "decoding ${signalsByMessage.values.sumOf { it.size }} signals")

            _state.value = State.Running(
                profileId = profile.id,
                profileName = profile.name,
                startedAtMs = startedAt,
                frames = 0, decoded = 0, dropped = 0, lastFrameAgeMs = 0
            )

            var lastStateEmit = System.currentTimeMillis()
            while (isActive) {
                val result = source.next()
                when (result) {
                    is FrameResult.None -> continue
                    is FrameResult.Noise -> {
                        dropped++
                        continue
                    }
                    is FrameResult.Notice -> {
                        Log.w(TAG, "Adapter notice: ${result.message}")
                        continue
                    }
                    is FrameResult.Ok -> {
                        val frame = result.frame
                        frames++
                        lastFrameAt = System.currentTimeMillis()

                        rawWriter?.appendLine(
                            "{\"t\":$lastFrameAt," +
                                "\"id\":${frame.id}," +
                                "\"ext\":${frame.extended}," +
                                "\"data\":\"${frame.data.toHex()}\"}"
                        )

                        if (frame.id in watchedIds) {
                            val signals = signalsByMessage[frame.id]
                            if (signals != null) {
                                for (sig in signals) {
                                    val decodedRaw = CanDecoder.decode(sig, frame.data) ?: continue
                                    val decodedValue = Math.round(decodedRaw * 1000.0) / 1000.0
                                    val sample = LatestSample(
                                        signalName = sig.name,
                                        messageId = frame.id,
                                        value = decodedValue,
                                        unit = sig.unit,
                                        timestampMs = lastFrameAt
                                    )
                                    val key = SignalRef(frame.id, sig.name).key()
                                    liveRows[key] = sample
                                    samplesWriter?.appendLine(
                                        "{\"t\":$lastFrameAt," +
                                            "\"id\":${frame.id}," +
                                            "\"sig\":\"${sig.name}\"," +
                                            "\"v\":$decodedValue," +
                                            "\"u\":\"${sig.unit.replace("\"", "\\\"")}\"}"
                                    )
                                    decoded++
                                }
                            }
                        }
                    }
                }

                // Throttle state emissions to ~5 Hz to keep the UI thread quiet.
                val now = System.currentTimeMillis()
                if (now - lastStateEmit >= 200) {
                    lastStateEmit = now
                    _state.value = State.Running(
                        profileId = profile.id,
                        profileName = profile.name,
                        startedAtMs = startedAt,
                        frames = frames,
                        decoded = decoded,
                        dropped = dropped,
                        lastFrameAgeMs = now - lastFrameAt
                    )
                    _latest.value = liveRows.toMap()
                }
            }
        } catch (e: CancellationException) {
            // Expected when stop() is called - not an error
            Log.i(TAG, "CAN scan cancelled")
        } catch (e: IOException) {
            Log.e(TAG, "CAN scan IO error", e)
            _state.value = State.Error("I/O error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "CAN scan error", e)
            _state.value = State.Error("Error: ${e.message}")
        } finally {
            try { source.stop() } catch (e: Exception) { Log.w(TAG, "source.stop() failed", e) }
            try { samplesWriter?.flush(); samplesWriter?.close() } catch (_: Exception) {}
            try { rawWriter?.flush(); rawWriter?.close() } catch (_: Exception) {}

            Log.i(TAG, "CAN scan finished — frames=$frames decoded=$decoded dropped=$dropped samplesFile=$samplesFileName")

            transport = null
            // Preserve an Error state if we hit one; otherwise return to Idle.
            if (_state.value !is State.Error) _state.value = State.Idle
        }
    }

    // ── Frame sources ─────────────────────────────────────────────────────────

    /** Result wrapper so a source can distinguish "no data yet" from "noise" from a real frame. */
    private sealed class FrameResult {
        object None : FrameResult()
        object Noise : FrameResult()
        data class Notice(val message: String) : FrameResult()
        data class Ok(val frame: CanFrame) : FrameResult()
    }

    private interface FrameSource {
        val label: String
        suspend fun start()
        suspend fun next(): FrameResult
        suspend fun stop()
    }

    /**
     * Real ELM327 source. Puts the adapter into ATMA monitor mode, reads lines, parses them,
     * and restores the adapter on [stop]. Hands the transport back to the OBD service so
     * polling can resume when the user turns CAN Bus logging back off.
     */
    private class RealFrameSource(
        private val t: Elm327Transport,
        private val profile: CanProfile,
        private val service: BluetoothObd2Service
    ) : FrameSource {
        override val label: String get() = "ELM327 live"

        override suspend fun start() {
            // Drain any pending input from OBD polling that was just cancelled.
            t.drainInput(EXIT_DRAIN_MS)

            service.appendConnectionLog("CAN scan setup — verifying adapter capabilities…")

            // Critical commands: a '?' response means the adapter firmware does not support
            // CAN monitor mode (e.g. cheap ELM327 clones with pirated v2.1 firmware).
            val criticalCmds = linkedMapOf(
                "ATE0"   to "Echo off",
                "ATL0"   to "Linefeeds off",
                "ATS0"   to "Spaces off",
                "ATH1"   to "Headers on",
                "ATCAF0" to "CAN auto-formatting off"
            )
            for ((cmd, desc) in criticalCmds) {
                val resp = t.sendCommand(cmd).trim()
                service.appendConnectionLog("  $cmd → $resp")
                Log.d(TAG, "setup $cmd → $resp")
                if (resp.contains("?")) {
                    val errMsg = "Adapter rejected $cmd ($desc) — not CAN-capable. Use a vLinker or OBDLink."
                    service.appendConnectionLog("✗ $errMsg")
                    throw UnsupportedAdapterException(errMsg)
                }
                delay(30)
            }

            // Apply CAN ID filter if the profile specifies exactly one ID.
            val ids = profile.canIdFilter
            val filterCmd = if (ids != null && ids.size == 1) {
                "ATCRA${Integer.toHexString(ids.first()).uppercase()}"
            } else {
                "ATCRA" // clear any previous filter
            }
            val filterResp = t.sendCommand(filterCmd).trim()
            service.appendConnectionLog("  $filterCmd → $filterResp")
            Log.d(TAG, "setup $filterCmd → $filterResp")
            delay(30)

            service.appendConnectionLog("✓ CAN adapter ready — starting monitor stream")
            // sendCommand would block on '>'; ATMA streams without a prompt.
            t.sendRaw("ATMA")
        }

        override suspend fun next(): FrameResult {
            val line = t.readStreamLine(STREAM_READ_TIMEOUT_MS) ?: return FrameResult.None
            if (line.isEmpty()) return FrameResult.None
            if (line.contains("BUFFER FULL", ignoreCase = true) ||
                line.contains("CAN ERROR", ignoreCase = true) ||
                line.contains("STOPPED", ignoreCase = true)
            ) return FrameResult.Notice(line)
            val frame = CanFrameParser.parse(line) ?: return FrameResult.Noise
            return FrameResult.Ok(frame)
        }

        override suspend fun stop() {
            try { t.sendRaw(" ") } catch (_: Exception) {}
            delay(50)
            t.drainInput(EXIT_DRAIN_MS)
            val restore = listOf("ATCRA", "ATH0", "ATCAF1")
            for (cmd in restore) {
                try {
                    t.sendCommand(cmd)
                    delay(30)
                } catch (e: Exception) {
                    Log.w(TAG, "restore $cmd failed: ${e.message}")
                }
            }
            service.restorePolling()
        }
    }

    /**
     * Replays a previously-recorded `*.raw.jsonl` capture file, honouring the gap between
     * successive `t` timestamps so playback runs at real time. Loops when it reaches EOF so
     * users can leave a test running indefinitely.
     *
     * Expected line format (one JSON object per line):
     * ```
     * {"t":<epoch_ms>,"id":<canIdDecimal>,"ext":<bool>,"data":"<hexBytes>"}
     * ```
     */
    private class CaptureFrameSource(private val file: java.io.File) : FrameSource {
        override val label: String get() = "capture(${file.name})"

        private var reader: java.io.BufferedReader? = null
        private var firstCaptureTs: Long = -1L
        private var playbackStartWallMs: Long = -1L

        override suspend fun start() {
            openReader()
        }

        override suspend fun next(): FrameResult {
            var r = reader ?: return FrameResult.None
            // Parse next non-empty line; loop the file on EOF.
            var line: String? = null
            while (line == null) {
                val next = r.readLine() ?: run {
                    // End of file — close and re-open for seamless looping.
                    try { r.close() } catch (_: Exception) {}
                    openReader()
                    r = reader ?: return FrameResult.None
                    // Reset clocks so the next frame is delivered immediately and subsequent
                    // gaps are honoured relative to the new loop start.
                    firstCaptureTs = -1L
                    playbackStartWallMs = -1L
                    r.readLine() ?: return FrameResult.None
                }
                if (next.isBlank()) continue
                line = next
            }

            val (ts, id, ext, data) = parseLine(line) ?: return FrameResult.Noise

            // Align playback clock to real time on the very first frame.
            if (firstCaptureTs < 0L) {
                firstCaptureTs = ts
                playbackStartWallMs = System.currentTimeMillis()
            }

            val dueWallMs = playbackStartWallMs + (ts - firstCaptureTs)
            val waitMs = dueWallMs - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs.coerceAtMost(STREAM_READ_TIMEOUT_MS * 4))

            return FrameResult.Ok(CanFrame(id = id, extended = ext, data = data))
        }

        override suspend fun stop() {
            try { reader?.close() } catch (_: Exception) {}
            reader = null
        }

        private fun openReader() {
            reader = file.bufferedReader()
        }

        private data class ParsedLine(val ts: Long, val id: Int, val ext: Boolean, val data: ByteArray)

        private fun parseLine(line: String): ParsedLine? {
            return try {
                val j = org.json.JSONObject(line)
                val hex = j.getString("data")
                if (hex.length % 2 != 0) return null
                val bytes = ByteArray(hex.length / 2)
                for (i in bytes.indices) {
                    bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                ParsedLine(
                    ts = j.optLong("t", System.currentTimeMillis()),
                    id = j.getInt("id"),
                    ext = j.optBoolean("ext", false),
                    data = bytes
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Synthetic source for verifying the full CAN pipeline without a car. Emits one batch of
     * frames per [profile.samplingMs] tick using [MockCanFrameSource]; each signal sweeps
     * through its declared range so the user can see live decoded values on the dashboard.
     *
     * On [start] it simulates the exact same AT init handshake that [RealFrameSource] performs,
     * logging each command → OK exchange to the connection log so the mock flow is
     * indistinguishable from a real vLinker session on the Connect screen.
     */
    private class MockFrameSource(
        private val profile: CanProfile,
        dbc: DbcDatabase,
        private val logFn: ((String) -> Unit)?
    ) : FrameSource {
        private val gen = com.sj.obd2app.can.MockCanFrameSource(profile, dbc)
        private val queue: ArrayDeque<CanFrame> = ArrayDeque()
        private val cadenceMs: Long get() = profile.samplingMs.coerceAtLeast(50L)
        private var nextBatchAt: Long = 0L

        override val label: String get() = "mock (${cadenceMs}ms)"

        override suspend fun start() {
            simulateInitSequence()
            nextBatchAt = System.currentTimeMillis()
        }

        /** Walks through the same AT commands as [RealFrameSource.start], logging simulated
         *  OK responses so the connection log mirrors a real vLinker session. */
        private suspend fun simulateInitSequence() {
            val log = logFn ?: return
            log("CAN scan setup — verifying adapter capabilities… [SIMULATED]")
            delay(50)

            val criticalCmds = linkedMapOf(
                "ATE0"   to "Echo off",
                "ATL0"   to "Linefeeds off",
                "ATS0"   to "Spaces off",
                "ATH1"   to "Headers on",
                "ATCAF0" to "CAN auto-formatting off"
            )
            for ((cmd, _) in criticalCmds) {
                delay(30)
                log("  $cmd → OK")
            }

            val ids = profile.canIdFilter
            val filterCmd = if (ids != null && ids.size == 1) {
                "ATCRA${Integer.toHexString(ids.first()).uppercase()}"
            } else {
                "ATCRA"
            }
            delay(30)
            log("  $filterCmd → OK")
            log("✓ CAN adapter ready — starting monitor stream [SIMULATED]")
        }

        override suspend fun next(): FrameResult {
            if (queue.isEmpty()) {
                val wait = nextBatchAt - System.currentTimeMillis()
                if (wait > 0) {
                    delay(wait.coerceAtMost(STREAM_READ_TIMEOUT_MS))
                }
                if (System.currentTimeMillis() >= nextBatchAt) {
                    queue.addAll(gen.nextBatch())
                    nextBatchAt += cadenceMs
                }
            }
            val frame = queue.removeFirstOrNull() ?: return FrameResult.None
            return FrameResult.Ok(frame)
        }

        override suspend fun stop() {
            queue.clear()
        }
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "profile" }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(String.format("%02X", b.toInt() and 0xFF))
        return sb.toString()
    }

    // ── Log writer factory for Trip Log folder ───────────────────────────────────

    private fun buildCanLogWriter(context: Context, fileName: String, mimeType: String): BufferedWriter? {
        val folderUriStr = AppSettings.getLogFolderUri(context)

        return if (folderUriStr != null) {
            // SAF-selected folder
            try {
                val folderUri = Uri.parse(folderUriStr)
                val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return fallbackWriter(context, fileName)
                val file = folder.createFile(mimeType, fileName) ?: return fallbackWriter(context, fileName)
                val os = context.contentResolver.openOutputStream(file.uri, "wa") ?: return fallbackWriter(context, fileName)
                BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8))
            } catch (_: Exception) {
                fallbackWriter(context, fileName)
            }
        } else {
            fallbackWriter(context, fileName)
        }
    }

    /** Thrown when the adapter rejects a CAN-specific AT command with '?', indicating
     *  it lacks CAN monitor mode support (e.g. cheap ELM327 clone firmware). */
    class UnsupportedAdapterException(message: String) : IOException(message)

    private fun fallbackWriter(context: Context, fileName: String): BufferedWriter? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore Downloads (no permission needed on API 29+)
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                val os = context.contentResolver.openOutputStream(uri) ?: return null
                BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create MediaStore writer for file: $fileName", e)
                null
            }
        } else {
            // Direct file write to Downloads for API < 29
            try {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = File(dir, fileName)
                BufferedWriter(java.io.FileWriter(file))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create direct file writer for: $fileName", e)
                null
            }
        }
    }

}
