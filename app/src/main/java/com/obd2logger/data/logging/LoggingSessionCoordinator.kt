package com.obd2logger.data.logging

import android.content.Context
import com.obd2logger.data.protocol.DiagnosticDecoder
import com.obd2logger.data.protocol.ElmSessionManager
import com.obd2logger.data.protocol.SensorPollingEngine
import com.obd2logger.data.protocol.SessionDiagnosticsRunner
import com.obd2logger.domain.model.ConnectionState
import com.obd2logger.domain.model.LoggingState
import com.obd2logger.domain.model.SavedCsvLogSummary
import com.obd2logger.domain.model.SessionDiagnosticSnapshot
import com.obd2logger.service.ObdLoggingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the entire Phase 3 logging lifecycle:
 * 1. Starts the [ObdLoggingService] foreground service (keeps process alive with screen off).
 * 2. Pauses polling and runs [SessionDiagnosticsRunner] (DTCs, MIL, readiness, freeze frame, Mode 09, ATDP).
 * 3. Opens a Torque-compatible CSV file in `Documents/OBDLogs` via [CsvLogStorage].
 * 4. Resumes polling and writes 1 row/second, flushing regularly to disk.
 * 5. Keeps the file open and writes "-" OBD cells if the adapter disconnects mid-log, resuming automatically on reconnect.
 */
@Singleton
class LoggingSessionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: ElmSessionManager,
    private val sensorPollingEngine: SensorPollingEngine,
    private val diagnosticsRunner: SessionDiagnosticsRunner,
    private val csvLogStorage: CsvLogStorage,
    private val locationProvider: LocationSnapshotProvider,
    private val logRepository: RawSessionLogRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _loggingState = MutableStateFlow<LoggingState>(LoggingState.Idle)
    val loggingState: StateFlow<LoggingState> = _loggingState.asStateFlow()

    private val _latestDiagnostics = MutableStateFlow<SessionDiagnosticSnapshot?>(null)
    val latestDiagnostics: StateFlow<SessionDiagnosticSnapshot?> = _latestDiagnostics.asStateFlow()

    private val _savedLogs = MutableStateFlow<List<SavedCsvLogSummary>>(emptyList())
    val savedLogs: StateFlow<List<SavedCsvLogSummary>> = _savedLogs.asStateFlow()

    private var activeLoggingJob: Job? = null
    private var atrvPollingJob: Job? = null

    @Volatile
    private var currentHandle: OpenCsvLogHandle? = null

    @Volatile
    private var latestAdapterVoltage: String? = null

    init {
        refreshSavedLogs()
    }

    fun refreshSavedLogs() {
        scope.launch {
            _savedLogs.value = csvLogStorage.listSavedLogs()
        }
    }

    fun deleteSavedLog(summary: SavedCsvLogSummary) {
        scope.launch {
            csvLogStorage.deleteLog(summary.uri)
            _savedLogs.value = csvLogStorage.listSavedLogs()
        }
    }

    fun startLogging() {
        val currentLogState = _loggingState.value
        if (currentLogState is LoggingState.Preparing || currentLogState is LoggingState.Logging) {
            return
        }
        val connectedState = sessionManager.connectionState.value as? ConnectionState.Connected ?: return

        activeLoggingJob?.cancel()
        atrvPollingJob?.cancel()

        _loggingState.value = LoggingState.Preparing("Starting foreground logging service...")
        ObdLoggingService.start(context)

        activeLoggingJob = scope.launch {
            val sessionId = UUID.randomUUID().toString()
            var handle: OpenCsvLogHandle? = null
            var rowCount = 0L
            var startTimeMs = System.currentTimeMillis()
            var diagnostics: SessionDiagnosticSnapshot? = null

            try {
                // 1. Run session-start sequence while polling is paused
                val startResult = diagnosticsRunner.runSessionStartSequence(
                    sessionId = sessionId,
                    connectedState = connectedState,
                    adapterConfig = sessionManager.activeAdapterConfig.value,
                    onProgress = { step ->
                        _loggingState.value = LoggingState.Preparing(step)
                    }
                )
                diagnostics = startResult.diagnostics
                latestAdapterVoltage = startResult.initialAdapterVoltage
                _latestDiagnostics.value = diagnostics

                // 2. Open CSV file via MediaStore and write header row
                _loggingState.value = LoggingState.Preparing("Creating CSV log file...")
                startTimeMs = System.currentTimeMillis()
                val filename = CsvWriter.formatFilename(startTimeMs)
                handle = csvLogStorage.openNewCsvLog(filename)
                currentHandle = handle

                handle.writeLine(CsvSchema.headerRowCsv())
                handle.flush()

                // 3. Start optional GPS & resume live Mode 01 polling
                withContext(Dispatchers.Main) {
                    locationProvider.startUpdatesIfPermitted()
                }
                sensorPollingEngine.setPollingEnabled(true)

                _loggingState.value = LoggingState.Logging(
                    sessionId = sessionId,
                    filename = filename,
                    startTimeMs = startTimeMs,
                    elapsedMs = 0L,
                    rowCount = 0L,
                    isAdapterConnected = sessionManager.connectionState.value is ConnectionState.Connected,
                    diagnostics = diagnostics
                )

                logRepository.logInfo("CSV logging started: $filename (${CsvSchema.columns.size} columns)")

                // 4. Periodic ATRV adapter voltage read every 8 seconds
                atrvPollingJob = launch {
                    while (isActive) {
                        delay(ATRV_POLL_INTERVAL_MS)
                        if (sessionManager.connectionState.value is ConnectionState.Connected) {
                            val reply = sessionManager.sendPolledCommand("ATRV").getOrNull()
                            if (reply != null) {
                                DiagnosticDecoder.decodeAdapterVoltage(reply)?.let {
                                    latestAdapterVoltage = it
                                }
                            }
                        } else {
                            latestAdapterVoltage = null
                        }
                    }
                }

                // 5. 1-second fixed-rate CSV logging loop
                while (isActive) {
                    val now = System.currentTimeMillis()
                    val isConnected = sessionManager.connectionState.value is ConnectionState.Connected
                    val liveMap = if (isConnected) {
                        sensorPollingEngine.liveValues.value.associateBy { it.pid.id }
                    } else {
                        emptyMap()
                    }
                    val gps = locationProvider.currentSnapshot()

                    val rowCtx = CsvRowContext(
                        deviceTimeMs = now,
                        gps = gps,
                        isAdapterConnected = isConnected,
                        liveByPid = liveMap,
                        adapterVoltage = if (isConnected) latestAdapterVoltage else null,
                        diagnostics = diagnostics
                    )
                    handle.writeLine(CsvSchema.buildRowCsv(rowCtx))
                    handle.flush()

                    rowCount++
                    val elapsed = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L)
                    _loggingState.value = LoggingState.Logging(
                        sessionId = sessionId,
                        filename = filename,
                        startTimeMs = startTimeMs,
                        elapsedMs = elapsed,
                        rowCount = rowCount,
                        isAdapterConnected = isConnected,
                        diagnostics = diagnostics
                    )

                    val nextTickMs = startTimeMs + (rowCount * LOG_INTERVAL_MS)
                    val waitMs = (nextTickMs - System.currentTimeMillis()).coerceIn(50L, LOG_INTERVAL_MS)
                    delay(waitMs)
                }
            } catch (e: CancellationException) {
                // Normal stop requested
                withContext(NonCancellable) {
                    finalizeSession(handle, startTimeMs, rowCount, diagnostics)
                }
                throw e
            } catch (e: Throwable) {
                logRepository.logError("CSV logging failed: ${e.message}")
                withContext(NonCancellable) {
                    runCatching { handle?.close() }
                    currentHandle = null
                    withContext(Dispatchers.Main) {
                        locationProvider.stopUpdates()
                    }
                    sensorPollingEngine.setPollingEnabled(true)
                    ObdLoggingService.stop(context)
                    _loggingState.value = LoggingState.Error(e.message ?: "Failed to write CSV log.")
                }
            }
        }
    }

    fun stopLogging() {
        atrvPollingJob?.cancel()
        atrvPollingJob = null
        activeLoggingJob?.cancel()
        activeLoggingJob = null
    }

    fun dismissPostLogScreen() {
        if (_loggingState.value is LoggingState.Stopped || _loggingState.value is LoggingState.Error) {
            _loggingState.value = LoggingState.Idle
        }
    }

    private suspend fun finalizeSession(
        handle: OpenCsvLogHandle?,
        startTimeMs: Long,
        rowCount: Long,
        diagnostics: SessionDiagnosticSnapshot?
    ) {
        atrvPollingJob?.cancel()
        atrvPollingJob = null
        handle?.flush()
        handle?.close()
        currentHandle = null

        withContext(Dispatchers.Main) {
            locationProvider.stopUpdates()
        }
        sensorPollingEngine.setPollingEnabled(true)
        ObdLoggingService.stop(context)

        if (handle != null && diagnostics != null) {
            val endMs = System.currentTimeMillis()
            val sizeBytes = csvLogStorage.queryFileSize(handle.uri)
            val summary = SavedCsvLogSummary(
                uri = handle.uri,
                filename = handle.filename,
                sizeBytes = sizeBytes,
                lastModifiedMs = endMs,
                durationMs = (endMs - startTimeMs).coerceAtLeast(0L),
                rowCount = rowCount
            )
            logRepository.logInfo("CSV log saved: ${handle.filename} ($rowCount rows, $sizeBytes bytes)")
            _savedLogs.value = csvLogStorage.listSavedLogs()
            _loggingState.value = LoggingState.Stopped(
                summary = summary,
                diagnostics = diagnostics
            )
        } else {
            _loggingState.value = LoggingState.Idle
        }
    }

    companion object {
        private const val LOG_INTERVAL_MS = 1_000L
        private const val ATRV_POLL_INTERVAL_MS = 8_000L
    }
}
