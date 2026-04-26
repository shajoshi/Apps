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
import com.sj.obd2app.obd.Obd2ServiceProvider
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

/**
 * Simple raw CAN trace recorder that logs all CAN frames in Socket CAN format.
 * Independent of CAN profiles - just dumps everything the ELM327 sees.
 *
 * Format: (timestamp_seconds) can0 CAN_ID#HEX_DATA
 * Example: (1682500000.123456) can0 0A0#1122334455667788
 */
object RawCanTraceRecorder {

    private const val TAG = "RawCanTraceRecorder"
    private const val STREAM_READ_TIMEOUT_MS = 500L
    private const val EXIT_DRAIN_MS = 300L

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _lineCount = MutableStateFlow(0L)
    val lineCount: StateFlow<Long> = _lineCount.asStateFlow()

    private var recordingJob: Job? = null
    private var transport: Elm327Transport? = null
    private var writer: BufferedWriter? = null

    /**
     * Start recording raw CAN traces to a file in the Trip Log folder.
     * Fails immediately if already recording or no transport available.
     */
    fun startRecording(context: Context) {
        if (recordingJob?.isActive == true) {
            Log.w(TAG, "startRecording ignored - already recording")
            return
        }

        val service = Obd2ServiceProvider.getService()
        if (service !is BluetoothObd2Service) {
            Log.e(TAG, "Service is not BluetoothObd2Service")
            return
        }

        val borrowed = service.borrowTransport()
        if (borrowed == null) {
            Log.e(TAG, "No transport available - not connected?")
            return
        }

        transport = borrowed
        _lineCount.value = 0L
        _isRecording.value = true

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "can_trace_$ts.txt"
        writer = buildTraceWriter(context, fileName)

        if (writer == null) {
            Log.e(TAG, "Failed to create trace writer")
            _isRecording.value = false
            transport = null
            service.restorePolling()
            return
        }

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                runRecordingLoop(borrowed)
            } catch (e: CancellationException) {
                Log.i(TAG, "Recording cancelled")
            } catch (e: IOException) {
                Log.e(TAG, "Recording IO error", e)
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
            } finally {
                stopRecordingInternal(service)
            }
        }
    }

    /**
     * Stop the current recording session.
     */
    fun stopRecording() {
        recordingJob?.cancel()
        // Cleanup happens in the finally block of the recording job
    }

    private suspend fun runRecordingLoop(t: Elm327Transport) {
        // Setup ELM327 for CAN monitoring (no ID filter - capture everything)
        val setup = buildList {
            add("ATE0")   // echo off
            add("ATL0")   // no linefeeds
            add("ATS0")   // no spaces
            add("ATH1")   // headers on
            add("ATCAF0") // no CAN auto-formatting
            add("ATCRA")  // clear any previous filter
        }
        for (cmd in setup) {
            val resp = t.sendCommand(cmd)
            Log.d(TAG, "setup $cmd → $resp")
            delay(30)
        }
        // Start monitor mode
        t.sendRaw("ATMA")

        var count = 0L
        while (recordingJob?.isActive == true) {
            val line = t.readStreamLine(STREAM_READ_TIMEOUT_MS)
            if (line.isNullOrEmpty()) continue

            // Skip error messages
            if (line.contains("BUFFER FULL", ignoreCase = true) ||
                line.contains("CAN ERROR", ignoreCase = true) ||
                line.contains("STOPPED", ignoreCase = true)
            ) {
                continue
            }

            val frame = CanFrameParser.parse(line)
            if (frame != null) {
                val timestampSec = System.currentTimeMillis() / 1000.0
                val formatted = formatFrame(frame, timestampSec)
                writer?.write(formatted)
                writer?.newLine()
                count++
                _lineCount.value = count
            }
        }
    }

    private suspend fun stopRecordingInternal(service: BluetoothObd2Service) {
        val t = transport ?: return

        try { t.sendRaw(" ") } catch (_: Exception) {}
        delay(50)
        t.drainInput(EXIT_DRAIN_MS)

        // Restore ELM327 to normal mode
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

        try { writer?.flush(); writer?.close() } catch (_: Exception) {}

        transport = null
        writer = null
        recordingJob = null
        _isRecording.value = false

        Log.i(TAG, "Recording stopped - total lines: ${_lineCount.value}")
    }

    /**
     * Format a CAN frame in Socket CAN format.
     * (timestamp_seconds) can0 CAN_ID#HEX_DATA
     */
    private fun formatFrame(frame: CanFrame, timestampSec: Double): String {
        val idHex = if (frame.extended) {
            String.format("%08X", frame.id)
        } else {
            String.format("%03X", frame.id)
        }
        val dataHex = frame.data.joinToString("") { String.format("%02X", it) }
        return "(%.6f) can0 ${idHex}#${dataHex}"
    }

    /**
     * Build a BufferedWriter for the trace file using the Trip Log folder.
     * Reuses the same SAF/MediaStore pattern as CanBusScanner.
     */
    private fun buildTraceWriter(context: Context, fileName: String): BufferedWriter? {
        val folderUriStr = com.sj.obd2app.settings.AppSettings.getLogFolderUri(context)

        return if (folderUriStr != null) {
            // SAF-selected folder
            try {
                val folderUri = Uri.parse(folderUriStr)
                val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return fallbackWriter(context, fileName)
                val file = folder.createFile("text/plain", fileName) ?: return fallbackWriter(context, fileName)
                val os = context.contentResolver.openOutputStream(file.uri, "wa") ?: return fallbackWriter(context, fileName)
                BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8))
            } catch (_: Exception) {
                fallbackWriter(context, fileName)
            }
        } else {
            fallbackWriter(context, fileName)
        }
    }

    private fun fallbackWriter(context: Context, fileName: String): BufferedWriter? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore Downloads (no permission needed on API 29+)
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
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
