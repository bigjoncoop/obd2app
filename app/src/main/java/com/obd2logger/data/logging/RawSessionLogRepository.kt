package com.obd2logger.data.logging

import android.content.Context
import com.obd2logger.domain.model.LogEntryType
import com.obd2logger.domain.model.TerminalLogEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the Debug Terminal raw log stream (TX, RX, ERROR, INFO) and persists
 * the last session's raw log to disk (`filesDir/logs/last_session_raw.log`).
 */
@Singleton
class RawSessionLogRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()
    private val idCounter = AtomicLong(1L)

    private val logsDir: File by lazy {
        File(context.filesDir, "logs").apply { mkdirs() }
    }

    val lastSessionLogFile: File by lazy {
        File(logsDir, "last_session_raw.log")
    }

    private val _entries = MutableStateFlow<List<TerminalLogEntry>>(emptyList())
    val entries: StateFlow<List<TerminalLogEntry>> = _entries.asStateFlow()

    init {
        loadPreviousSessionLogIfPresent()
    }

    private fun loadPreviousSessionLogIfPresent() {
        ioScope.launch {
            fileMutex.withLock {
                if (!lastSessionLogFile.exists() || lastSessionLogFile.length() == 0L) return@withLock
                try {
                    val lines = lastSessionLogFile.readLines().takeLast(MAX_ENTRIES)
                    val restored = lines.mapNotNull { parseSavedLine(it) }
                    if (restored.isNotEmpty()) {
                        _entries.value = restored
                    }
                } catch (_: Exception) {
                    // Ignore malformed historic log file
                }
            }
        }
    }

    private fun parseSavedLine(line: String): TerminalLogEntry? {
        // Format: [HH:mm:ss.SSS] [TYPE] message
        if (!line.startsWith("[")) return null
        val firstClose = line.indexOf(']')
        if (firstClose <= 1) return null
        val timeStr = line.substring(1, firstClose)
        val rest = line.substring(firstClose + 1).trim()
        if (!rest.startsWith("[")) return null
        val secondClose = rest.indexOf(']')
        if (secondClose <= 1) return null
        val typeStr = rest.substring(1, secondClose)
        val message = rest.substring(secondClose + 1).trim()
        val isHb = message.startsWith("[HB]")
        val type = runCatching { LogEntryType.valueOf(typeStr) }.getOrDefault(LogEntryType.INFO)
        return TerminalLogEntry(
            id = idCounter.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            formattedTime = timeStr,
            type = type,
            message = message,
            isHeartbeat = isHb
        )
    }

    /**
     * Resets the persisted `last_session_raw.log` file at the start of a new adapter session
     * while writing a session header.
     */
    fun startNewSession(adapterDescription: String) {
        val now = System.currentTimeMillis()
        val headerTime = formatDateTime(now)
        val entry = createEntry(
            type = LogEntryType.INFO,
            message = "=== New Session: $adapterDescription ($headerTime) ===",
            isHeartbeat = false
        )
        _entries.value = listOf(entry)
        ioScope.launch {
            fileMutex.withLock {
                runCatching {
                    FileOutputStream(lastSessionLogFile, false).bufferedWriter().use { writer ->
                        writer.write(entry.toPlainTextLine())
                        writer.newLine()
                        writer.flush()
                    }
                }
            }
        }
    }

    fun logTx(command: String, isHeartbeat: Boolean = false) {
        val sanitized = formatControlChars(command)
        val prefix = if (isHeartbeat) "[HB] TX > " else "TX > "
        appendEntry(LogEntryType.TX, "$prefix$sanitized", isHeartbeat)
    }

    fun logRx(rawResponse: String, isHeartbeat: Boolean = false) {
        val sanitized = formatControlChars(rawResponse)
        val prefix = if (isHeartbeat) "[HB] RX < " else "RX < "
        appendEntry(LogEntryType.RX, "$prefix$sanitized", isHeartbeat)
    }

    fun logError(errorMessage: String, isHeartbeat: Boolean = false) {
        val prefix = if (isHeartbeat) "[HB] ERR: " else "ERR: "
        appendEntry(LogEntryType.ERROR, "$prefix$errorMessage", isHeartbeat)
    }

    fun logInfo(infoMessage: String) {
        appendEntry(LogEntryType.INFO, infoMessage, isHeartbeat = false)
    }

    fun clearLog() {
        _entries.value = emptyList()
        ioScope.launch {
            fileMutex.withLock {
                runCatching {
                    if (lastSessionLogFile.exists()) {
                        lastSessionLogFile.writeText("")
                    }
                }
            }
        }
    }

    fun getFullLogText(includeHeartbeats: Boolean = true): String {
        val current = _entries.value
        val filtered = if (includeHeartbeats) current else current.filterNot { it.isHeartbeat }
        if (filtered.isEmpty() && lastSessionLogFile.exists()) {
            return runCatching { lastSessionLogFile.readText() }.getOrDefault("")
        }
        return filtered.joinToString("\n") { it.toPlainTextLine() }
    }

    private fun appendEntry(type: LogEntryType, message: String, isHeartbeat: Boolean) {
        val entry = createEntry(type, message, isHeartbeat)
        _entries.update { current ->
            val next = current + entry
            if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next
        }
        ioScope.launch {
            fileMutex.withLock {
                runCatching {
                    FileOutputStream(lastSessionLogFile, true).bufferedWriter().use { writer ->
                        writer.write(entry.toPlainTextLine())
                        writer.newLine()
                        writer.flush()
                    }
                }
            }
        }
    }

    private fun createEntry(
        type: LogEntryType,
        message: String,
        isHeartbeat: Boolean
    ): TerminalLogEntry {
        val now = System.currentTimeMillis()
        return TerminalLogEntry(
            id = idCounter.getAndIncrement(),
            timestampMs = now,
            formattedTime = formatTimestamp(now),
            type = type,
            message = message,
            isHeartbeat = isHeartbeat
        )
    }

    private fun formatControlChars(raw: String): String {
        return raw
            .replace("\u0000", "\\0")
            .replace("\r\n", " \\r\\n ")
            .replace("\r", " \\r ")
            .replace("\n", " \\n ")
            .trim()
    }

    private fun formatTimestamp(ms: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return sdf.format(Date(ms))
    }

    private fun formatDateTime(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return sdf.format(Date(ms))
    }

    companion object {
        private const val MAX_ENTRIES = 2000
    }
}
