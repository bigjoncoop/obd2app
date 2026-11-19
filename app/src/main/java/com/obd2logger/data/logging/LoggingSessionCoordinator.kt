package com.obd2logger.data.logging

import android.content.Context
import com.obd2logger.BuildConfig
import com.obd2logger.data.protocol.ClearCodesManager
import com.obd2logger.data.protocol.ClearCodesResult
import com.obd2logger.data.protocol.DiagnosticDecoder
import com.obd2logger.data.protocol.ElmParseResult
import com.obd2logger.data.protocol.ElmResponseParser
import com.obd2logger.data.protocol.ElmSessionManager
import com.obd2logger.data.protocol.ManufacturerPidProber
import com.obd2logger.data.protocol.ManufacturerPidRepository
import com.obd2logger.data.protocol.Mode06Runner
import com.obd2logger.data.protocol.SensorPollingEngine
import com.obd2logger.data.protocol.SessionDiagnosticsRunner
import com.obd2logger.domain.model.ConnectionState
import com.obd2logger.domain.model.LoggingState
import com.obd2logger.domain.model.ManufacturerPidDefinition
import com.obd2logger.domain.model.Mode06Phase
import com.obd2logger.domain.model.Mode06TestResult
import com.obd2logger.domain.model.SavedCsvLogSummary
import com.obd2logger.domain.model.SessionDiagnosticSnapshot
import com.obd2logger.domain.model.SessionLogGroup
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates Phase 3 & Phase 4 logging lifecycles:
 * - Foreground service + wake lock
 * - Session-start diagnostics (DTCs, MIL, readiness, freeze frame, Mode 09, ATDP)
 * - Mode 06 (Start, slow ~30s During misfire counters, End) -> `_mode06.csv`
 * - Manufacturer Mode 22 PIDs (VIN auto-detect, header-safe polling) -> `_extended.csv`
 * - Main standalone 137-column 1 Hz CSV -> `trackLog-<ts>.csv`
 * - Clear Codes (Mode 04) with pre-clear JSON snapshot guard -> `_precleared.json`
 * - Non-blocking streaming session ZIP + `manifest.json` -> `_session.zip`
 */
@Singleton
class LoggingSessionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: ElmSessionManager,
    private val sensorPollingEngine: SensorPollingEngine,
    private val diagnosticsRunner: SessionDiagnosticsRunner,
    private val mode06Runner: Mode06Runner,
    private val clearCodesManager: ClearCodesManager,
    private val manufacturerPidRepository: ManufacturerPidRepository,
    private val manufacturerPidProber: ManufacturerPidProber,
    private val csvLogStorage: CsvLogStorage,
    private val locationProvider: LocationSnapshotProvider,
    private val logRepository: RawSessionLogRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busPauseMutex = Mutex()

    private val _loggingState = MutableStateFlow<LoggingState>(LoggingState.Idle)
    val loggingState: StateFlow<LoggingState> = _loggingState.asStateFlow()

    private val _latestDiagnostics = MutableStateFlow<SessionDiagnosticSnapshot?>(null)
    val latestDiagnostics: StateFlow<SessionDiagnosticSnapshot?> = _latestDiagnostics.asStateFlow()

    private val _latestMode06Tests = MutableStateFlow<List<Mode06TestResult>>(emptyList())
    val latestMode06Tests: StateFlow<List<Mode06TestResult>> = _latestMode06Tests.asStateFlow()

    private val _savedLogs = MutableStateFlow<List<SavedCsvLogSummary>>(emptyList())
    val savedLogs: StateFlow<List<SavedCsvLogSummary>> = _savedLogs.asStateFlow()

    private val _sessionGroups = MutableStateFlow<List<SessionLogGroup>>(emptyList())
    val sessionGroups: StateFlow<List<SessionLogGroup>> = _sessionGroups.asStateFlow()

    private val _isProbingMfrPids = MutableStateFlow(false)
    val isProbingMfrPids: StateFlow<Boolean> = _isProbingMfrPids.asStateFlow()

    private val _isClearingCodes = MutableStateFlow(false)
    val isClearingCodes: StateFlow<Boolean> = _isClearingCodes.asStateFlow()

    private val _lastClearResult = MutableStateFlow<ClearCodesResult?>(null)
    val lastClearResult: StateFlow<ClearCodesResult?> = _lastClearResult.asStateFlow()

    private var activeLoggingJob: Job? = null
    private var atrvPollingJob: Job? = null
    private var extendedPollingJob: Job? = null
    private var misfireMode06Job: Job? = null

    @Volatile
    private var currentHandle: OpenCsvLogHandle? = null

    @Volatile
    private var latestAdapterVoltage: String? = null

    @Volatile
    private var latestPreclearedEntry: SessionArtifactEntry? = null

    @Volatile
    private var hasProbedMfrThisConnection = false

    init {
        refreshSavedLogs()
        observeDisconnectForReset()
    }

    private fun observeDisconnectForReset() {
        scope.launch {
            sessionManager.connectionState.collect { state ->
                if (state !is ConnectionState.Connected) {
                    manufacturerPidRepository.clearValuesOnDisconnect()
                    if (state is ConnectionState.Disconnected && _loggingState.value is LoggingState.Idle) {
                        hasProbedMfrThisConnection = false
                    }
                }
            }
        }
    }

    fun refreshSavedLogs() {
        scope.launch {
            val all = csvLogStorage.listSavedLogs()
            _savedLogs.value = all
            _sessionGroups.value = CsvLogStorage.groupSavedLogs(all)
        }
    }

    fun deleteSessionGroup(group: SessionLogGroup) {
        scope.launch {
            for (file in group.allFiles) {
                csvLogStorage.deleteLog(file.uri)
            }
            refreshSavedLogs()
        }
    }

    fun deleteSavedLog(summary: SavedCsvLogSummary) {
        scope.launch {
            csvLogStorage.deleteLog(summary.uri)
            refreshSavedLogs()
        }
    }

    /**
     * Manual trigger from the Manufacturer PIDs screen to probe candidate PIDs based on VIN.
     * Pauses Mode 01 polling gracefully, probes with ATSH, and restores the default header.
     */
    fun probeManufacturerPidsNow() {
        val connected = sessionManager.connectionState.value as? ConnectionState.Connected ?: return
        if (_isProbingMfrPids.value) return
        val activeLog = _loggingState.value
        if (activeLog is LoggingState.Preparing || activeLog is LoggingState.Logging) return

        scope.launch {
            _isProbingMfrPids.value = true
            try {
                busPauseMutex.withLock {
                    sensorPollingEngine.pausePollingGracefully()
                    try {
                        val existingDiag = _latestDiagnostics.value
                        val vin = existingDiag?.vin?.takeIf { it != "-" }
                            ?: queryVinSafe()
                            ?: "-"
                        val protocol = existingDiag?.protocol?.takeIf { it != "-" }
                            ?: queryProtocolSafe()
                            ?: "-"
                        manufacturerPidRepository.autoDetectFromVin(vin, protocol)
                        hasProbedMfrThisConnection = true
                    } finally {
                        sensorPollingEngine.setPollingEnabled(true)
                    }
                }
            } catch (e: Throwable) {
                logRepository.logError("Manufacturer PID probe failed: ${e.message}")
            } finally {
                _isProbingMfrPids.value = false
            }
        }
    }

    /**
     * Manual trigger for Mode 04 Clear Codes (only allowed when Connected and NOT logging).
     */
    fun clearCodes() {
        if (sessionManager.connectionState.value !is ConnectionState.Connected) return
        val logState = _loggingState.value
        if (logState is LoggingState.Preparing || logState is LoggingState.Logging) return
        if (_isClearingCodes.value) return

        scope.launch {
            _isClearingCodes.value = true
            try {
                val diag = _latestDiagnostics.value
                val res = busPauseMutex.withLock {
                    clearCodesManager.clearCodesWithSnapshotGuard(
                        knownVin = diag?.vin ?: "-",
                        knownProtocol = diag?.protocol ?: "-"
                    )
                }
                _lastClearResult.value = res
                when (res) {
                    is ClearCodesResult.Success -> {
                        latestPreclearedEntry = SessionArtifactEntry(
                            uri = res.snapshotUri,
                            filename = res.snapshotFilename,
                            type = SessionFileType.PRECLEARED,
                            rowCount = 1L
                        )
                        _latestDiagnostics.value = diag?.copy(
                            milOn = res.postMilOn,
                            dtcCount = res.postDtcCount,
                            storedDtcs = res.remainingStored,
                            pendingDtcs = res.remainingPending,
                            permanentDtcs = res.remainingPermanent
                        )
                        refreshSavedLogs()
                    }
                    is ClearCodesResult.CodesRemain -> {
                        latestPreclearedEntry = SessionArtifactEntry(
                            uri = res.snapshotUri,
                            filename = res.snapshotFilename,
                            type = SessionFileType.PRECLEARED,
                            rowCount = 1L
                        )
                        _latestDiagnostics.value = diag?.copy(
                            milOn = res.postMilOn,
                            storedDtcs = res.remainingStored,
                            pendingDtcs = res.remainingPending,
                            permanentDtcs = res.remainingPermanent
                        )
                        refreshSavedLogs()
                    }
                    is ClearCodesResult.Refused,
                    is ClearCodesResult.SnapshotFailed -> {
                        refreshSavedLogs()
                    }
                }
            } catch (e: Throwable) {
                logRepository.logError("Clear codes failed: ${e.message}")
                _lastClearResult.value = ClearCodesResult.Refused(null, e.message ?: "Unexpected error")
            } finally {
                _isClearingCodes.value = false
            }
        }
    }

    fun dismissClearResult() {
        _lastClearResult.value = null
    }

    fun startLogging() {
        val currentLogState = _loggingState.value
        if (currentLogState is LoggingState.Preparing || currentLogState is LoggingState.Logging) {
            return
        }
        val connectedState = sessionManager.connectionState.value as? ConnectionState.Connected ?: return

        activeLoggingJob?.cancel()
        atrvPollingJob?.cancel()
        extendedPollingJob?.cancel()
        misfireMode06Job?.cancel()

        _loggingState.value = LoggingState.Preparing("Starting foreground logging service...")
        ObdLoggingService.start(context)

        activeLoggingJob = scope.launch {
            val sessionId = UUID.randomUUID().toString()
            var mainHandle: OpenCsvLogHandle? = null
            var mode06Handle: OpenCsvLogHandle? = null
            var extendedHandle: OpenCsvLogHandle? = null
            var mode06Rows = 0L
            var extendedRows = 0L
            var rowCount = 0L
            var startTimeMs = System.currentTimeMillis()
            var diagnostics: SessionDiagnosticSnapshot? = null
            var supportedMode06Mids: Set<Int> = emptySet()
            var isCanProtocol: Boolean? = null
            var enabledMfrPids: List<ManufacturerPidDefinition> = emptyList()
            val latestMfrValues = mutableMapOf<String, String>()

            try {
                // 1. Run Phase 3 session-start sequence (pauses polling gracefully)
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
                isCanProtocol = if (diagnostics.protocol == "-") {
                    null
                } else {
                    diagnostics.protocol.contains("CAN", ignoreCase = true) ||
                        diagnostics.protocol.contains("15765")
                }

                // 2. Phase 4 Mode 06 session start scan (never aborts logging on failure)
                _loggingState.value = LoggingState.Preparing("Reading Mode 06 monitor tests...")
                val startMode06Tests = runCatching {
                    val run = mode06Runner.discoverAndReadAll(
                        isCanProtocol = isCanProtocol,
                        phase = Mode06Phase.START
                    )
                    supportedMode06Mids = run.supportedMids
                    run.tests
                }.onFailure { err ->
                    logRepository.logError("Mode 06 start scan failed (continuing): ${err.message}")
                }.getOrDefault(emptyList())
                _latestMode06Tests.value = startMode06Tests

                // 3. Phase 4 Manufacturer PID auto-detect (once per connection if not already probed)
                _loggingState.value = LoggingState.Preparing("Checking manufacturer PIDs...")
                enabledMfrPids = runCatching {
                    if (!hasProbedMfrThisConnection) {
                        hasProbedMfrThisConnection = true
                        manufacturerPidRepository.autoDetectFromVin(diagnostics.vin, diagnostics.protocol)
                    } else {
                        manufacturerPidRepository.enabledDefinitions()
                    }
                }.onFailure { err ->
                    logRepository.logError("Manufacturer PID probe failed (continuing): ${err.message}")
                }.getOrDefault(emptyList())

                // 4. Open main CSV + companion CSV files with identical timestamp stem
                _loggingState.value = LoggingState.Preparing("Creating CSV log files...")
                startTimeMs = System.currentTimeMillis()
                val mainFilename = CsvWriter.formatFilename(startTimeMs)
                val stem = mainFilename.removeSuffix(".csv")
                mainHandle = csvLogStorage.openNewCsvLog(mainFilename)
                currentHandle = mainHandle
                mainHandle.writeLine(CsvSchema.headerRowCsv())
                mainHandle.flush()

                // Companion Mode 06 CSV
                runCatching {
                    val m06Filename = "${stem}_mode06.csv"
                    val h = csvLogStorage.openCompanionCsvHandle(m06Filename)
                    h.writeLine(CompanionCsvWriters.mode06HeaderCsv())
                    for (t in startMode06Tests) {
                        h.writeLine(CompanionCsvWriters.formatMode06RowCsv(sessionId, diagnostics.vin, t))
                        mode06Rows++
                    }
                    h.flush()
                    mode06Handle = h
                }.onFailure { err ->
                    logRepository.logError("Unable to open Mode 06 companion CSV: ${err.message}")
                }

                // Companion Extended CSV (only if any manufacturer PIDs enabled)
                if (enabledMfrPids.isNotEmpty()) {
                    runCatching {
                        val extFilename = "${stem}_extended.csv"
                        val h = csvLogStorage.openCompanionCsvHandle(extFilename)
                        h.writeLine(CompanionCsvWriters.extendedHeaderCsv(enabledMfrPids))
                        h.flush()
                        extendedHandle = h
                        for (state in manufacturerPidRepository.pidStates.value) {
                            if (state.enabled && state.latestValue != "-") {
                                latestMfrValues[state.definition.id] = state.latestValue
                            }
                        }
                    }.onFailure { err ->
                        logRepository.logError("Unable to open Extended companion CSV: ${err.message}")
                    }
                }

                // 5. Start optional GPS & resume Mode 01 live polling
                withContext(Dispatchers.Main) {
                    locationProvider.startUpdatesIfPermitted()
                }
                sensorPollingEngine.setPollingEnabled(true)

                _loggingState.value = LoggingState.Logging(
                    sessionId = sessionId,
                    filename = mainFilename,
                    startTimeMs = startTimeMs,
                    elapsedMs = 0L,
                    rowCount = 0L,
                    isAdapterConnected = sessionManager.connectionState.value is ConnectionState.Connected,
                    diagnostics = diagnostics
                )
                logRepository.logInfo("CSV logging started: $mainFilename (${CsvSchema.columns.size} cols)")

                // 6a. Periodic ATRV adapter voltage read every 8s
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

                // 6b. Lower-priority manufacturer PID polling every ~2.5s (only if enabled)
                if (enabledMfrPids.isNotEmpty()) {
                    extendedPollingJob = launch {
                        while (isActive) {
                            delay(MFR_POLL_INTERVAL_MS)
                            if (sessionManager.connectionState.value !is ConnectionState.Connected) {
                                latestMfrValues.clear()
                                continue
                            }
                            runCatching {
                                busPauseMutex.withLock {
                                    sensorPollingEngine.pausePollingGracefully()
                                    try {
                                        val read = manufacturerPidProber.readEnabledPids(
                                            enabledPids = enabledMfrPids,
                                            protocol = diagnostics.protocol
                                        )
                                        val now = System.currentTimeMillis()
                                        synchronized(latestMfrValues) {
                                            latestMfrValues.clear()
                                            latestMfrValues.putAll(read)
                                        }
                                        manufacturerPidRepository.updateValues(read, now)
                                    } finally {
                                        sensorPollingEngine.setPollingEnabled(true)
                                    }
                                }
                            }.onFailure { err ->
                                logRepository.logError("Extended PID poll error (continuing): ${err.message}")
                            }
                        }
                    }
                }

                // 6c. Slow Mode 06 misfire counter polling (~30s) during logging if supported
                val hasMisfireMids = supportedMode06Mids.any { com.obd2logger.data.protocol.Mode06Tables.isMisfireMid(it) }
                if (hasMisfireMids) {
                    misfireMode06Job = launch {
                        while (isActive) {
                            delay(MISFIRE_MODE06_INTERVAL_MS)
                            if (sessionManager.connectionState.value !is ConnectionState.Connected) continue
                            runCatching {
                                busPauseMutex.withLock {
                                    sensorPollingEngine.pausePollingGracefully()
                                    try {
                                        val duringTests = mode06Runner.readMisfireMids(
                                            supportedMids = supportedMode06Mids,
                                            isCanProtocol = isCanProtocol
                                        )
                                        if (duringTests.isNotEmpty()) {
                                            mode06Handle?.let { mh ->
                                                for (t in duringTests) {
                                                    mh.writeLine(
                                                        CompanionCsvWriters.formatMode06RowCsv(
                                                            sessionId = sessionId,
                                                            vin = diagnostics.vin,
                                                            test = t
                                                        )
                                                    )
                                                    mode06Rows++
                                                }
                                                mh.flush()
                                            }
                                            mergeMode06TestsForUi(duringTests)
                                        }
                                    } finally {
                                        sensorPollingEngine.setPollingEnabled(true)
                                    }
                                }
                            }.onFailure { err ->
                                logRepository.logError("Mode 06 misfire poll failed (continuing): ${err.message}")
                            }
                        }
                    }
                }

                // 7. Main 1 Hz CSV logging loop
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
                    mainHandle.writeLine(CsvSchema.buildRowCsv(rowCtx))
                    mainHandle.flush()

                    // Write companion extended CSV row (~1 Hz, carrying forward last known values)
                    extendedHandle?.let { eh ->
                        val snapshotMfr = synchronized(latestMfrValues) { latestMfrValues.toMap() }
                        eh.writeLine(
                            CompanionCsvWriters.formatExtendedRowCsv(
                                deviceTimeMs = now,
                                sessionId = sessionId,
                                enabledPids = enabledMfrPids,
                                valuesByPidId = snapshotMfr,
                                isAdapterConnected = isConnected
                            )
                        )
                        eh.flush()
                        extendedRows++
                    }

                    rowCount++
                    val elapsed = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L)
                    _loggingState.value = LoggingState.Logging(
                        sessionId = sessionId,
                        filename = mainFilename,
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
                withContext(NonCancellable) {
                    finalizeSession(
                        mainHandle = mainHandle,
                        mode06Handle = mode06Handle,
                        extendedHandle = extendedHandle,
                        startTimeMs = startTimeMs,
                        rowCount = rowCount,
                        mode06RowsInitial = mode06Rows,
                        extendedRows = extendedRows,
                        diagnostics = diagnostics,
                        supportedMode06Mids = supportedMode06Mids,
                        isCanProtocol = isCanProtocol
                    )
                }
                throw e
            } catch (e: Throwable) {
                logRepository.logError("CSV logging failed: ${e.message}")
                withContext(NonCancellable) {
                    runCatching { mainHandle?.close() }
                    runCatching { mode06Handle?.close() }
                    runCatching { extendedHandle?.close() }
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
        extendedPollingJob?.cancel()
        extendedPollingJob = null
        misfireMode06Job?.cancel()
        misfireMode06Job = null
        activeLoggingJob?.cancel()
        activeLoggingJob = null
    }

    fun dismissPostLogScreen() {
        if (_loggingState.value is LoggingState.Stopped || _loggingState.value is LoggingState.Error) {
            _loggingState.value = LoggingState.Idle
        }
    }

    private suspend fun finalizeSession(
        mainHandle: OpenCsvLogHandle?,
        mode06Handle: OpenCsvLogHandle?,
        extendedHandle: OpenCsvLogHandle?,
        startTimeMs: Long,
        rowCount: Long,
        mode06RowsInitial: Long,
        extendedRows: Long,
        diagnostics: SessionDiagnosticSnapshot?,
        supportedMode06Mids: Set<Int>,
        isCanProtocol: Boolean?
    ) {
        atrvPollingJob?.cancel()
        atrvPollingJob = null
        extendedPollingJob?.cancel()
        extendedPollingJob = null
        misfireMode06Job?.cancel()
        misfireMode06Job = null

        var totalMode06Rows = mode06RowsInitial

        // Re-read Mode 06 on stop (Phase = End) if connected and Mode 06 handle is open
        if (diagnostics != null && mode06Handle != null &&
            sessionManager.connectionState.value is ConnectionState.Connected
        ) {
            runCatching {
                busPauseMutex.withLock {
                    sensorPollingEngine.pausePollingGracefully()
                    val endRun = mode06Runner.discoverAndReadAll(
                        isCanProtocol = isCanProtocol,
                        phase = Mode06Phase.END,
                        knownSupportedMids = supportedMode06Mids.takeIf { it.isNotEmpty() }
                    )
                    for (t in endRun.tests) {
                        mode06Handle.writeLine(
                            CompanionCsvWriters.formatMode06RowCsv(
                                sessionId = diagnostics.sessionId,
                                vin = diagnostics.vin,
                                test = t
                            )
                        )
                        totalMode06Rows++
                    }
                    if (endRun.tests.isNotEmpty()) {
                        mergeMode06TestsForUi(endRun.tests)
                    }
                }
            }.onFailure { err ->
                logRepository.logError("Mode 06 end scan failed (continuing): ${err.message}")
            }
        }

        mainHandle?.flush()
        mainHandle?.close()
        mode06Handle?.flush()
        mode06Handle?.close()
        extendedHandle?.flush()
        extendedHandle?.close()
        currentHandle = null

        withContext(Dispatchers.Main) {
            locationProvider.stopUpdates()
        }
        sensorPollingEngine.setPollingEnabled(true)
        ObdLoggingService.stop(context)

        if (mainHandle != null && diagnostics != null) {
            val endMs = System.currentTimeMillis()
            val sizeBytes = csvLogStorage.queryFileSize(mainHandle.uri)
            val summary = SavedCsvLogSummary(
                uri = mainHandle.uri,
                filename = mainHandle.filename,
                sizeBytes = sizeBytes,
                lastModifiedMs = endMs,
                durationMs = (endMs - startTimeMs).coerceAtLeast(0L),
                rowCount = rowCount
            )

            val artifacts = buildList {
                add(
                    SessionArtifactEntry(
                        uri = mainHandle.uri,
                        filename = mainHandle.filename,
                        type = SessionFileType.MAIN,
                        rowCount = rowCount
                    )
                )
                if (mode06Handle != null) {
                    add(
                        SessionArtifactEntry(
                            uri = mode06Handle.uri,
                            filename = mode06Handle.filename,
                            type = SessionFileType.MODE06,
                            rowCount = totalMode06Rows
                        )
                    )
                }
                if (extendedHandle != null) {
                    add(
                        SessionArtifactEntry(
                            uri = extendedHandle.uri,
                            filename = extendedHandle.filename,
                            type = SessionFileType.EXTENDED,
                            rowCount = extendedRows
                        )
                    )
                }
                latestPreclearedEntry?.let { add(it) }
            }

            // Show Log Saved immediately without waiting for ZIP streaming
            _loggingState.value = LoggingState.Stopped(
                summary = summary,
                diagnostics = diagnostics,
                artifacts = artifacts,
                zipUri = null,
                zipFilename = null,
                isZipBuilding = true
            )
            logRepository.logInfo("CSV log saved: ${mainHandle.filename} ($rowCount rows, $sizeBytes bytes)")
            refreshSavedLogs()

            // Build streaming session ZIP asynchronously
            val stem = mainHandle.filename.removeSuffix(".csv")
            val zipFilename = "${stem}_session.zip"
            scope.launch {
                val manifest = SessionManifest(
                    appVersion = BuildConfig.VERSION_NAME,
                    sessionId = diagnostics.sessionId,
                    vin = diagnostics.vin,
                    startTime = CsvWriter.formatDeviceTime(startTimeMs),
                    endTime = CsvWriter.formatDeviceTime(endMs),
                    files = artifacts
                )
                val zipUri = runCatching {
                    val (uri, outStream) = csvLogStorage.openCompanionOutput(zipFilename, "application/zip")
                    outStream.use { stream ->
                        SessionZipBuilder.writeStreamingZip(
                            zipOutputStream = stream,
                            manifest = manifest,
                            openInput = { entry -> csvLogStorage.openInputStream(entry.uri) }
                        )
                    }
                    uri
                }.onFailure { err ->
                    logRepository.logError("Session ZIP creation failed: ${err.message}")
                }.getOrNull()

                val current = _loggingState.value
                if (current is LoggingState.Stopped && current.summary.filename == mainHandle.filename) {
                    _loggingState.value = current.copy(
                        zipUri = zipUri,
                        zipFilename = zipUri?.let { zipFilename },
                        isZipBuilding = false
                    )
                }
                refreshSavedLogs()
            }
        } else {
            _loggingState.value = LoggingState.Idle
        }
    }

    /**
     * Builds (or returns existing) streaming session ZIP for a Past Logs [SessionLogGroup].
     */
    suspend fun ensureSessionZipForGroup(group: SessionLogGroup): Result<SavedCsvLogSummary> =
        withContext(Dispatchers.IO) {
            group.sessionZip?.let { return@withContext Result.success(it) }
            runCatching {
                val zipFilename = "${group.stem}_session.zip"
                val artifacts = buildList {
                    group.mainCsv?.let { add(SessionArtifactEntry(it.uri, it.filename, SessionFileType.MAIN, 0L)) }
                    group.mode06Csv?.let { add(SessionArtifactEntry(it.uri, it.filename, SessionFileType.MODE06, 0L)) }
                    group.extendedCsv?.let { add(SessionArtifactEntry(it.uri, it.filename, SessionFileType.EXTENDED, 0L)) }
                    group.preclearedJson?.let { add(SessionArtifactEntry(it.uri, it.filename, SessionFileType.PRECLEARED, 1L)) }
                }
                val manifest = SessionManifest(
                    appVersion = BuildConfig.VERSION_NAME,
                    sessionId = group.stem,
                    vin = _latestDiagnostics.value?.vin ?: "-",
                    startTime = CsvWriter.formatDeviceTime(group.lastModifiedMs),
                    endTime = CsvWriter.formatDeviceTime(group.lastModifiedMs),
                    files = artifacts
                )
                val (uri, outStream) = csvLogStorage.openCompanionOutput(zipFilename, "application/zip")
                outStream.use { stream ->
                    SessionZipBuilder.writeStreamingZip(
                        zipOutputStream = stream,
                        manifest = manifest,
                        openInput = { entry -> csvLogStorage.openInputStream(entry.uri) }
                    )
                }
                val size = csvLogStorage.queryFileSize(uri)
                refreshSavedLogs()
                SavedCsvLogSummary(uri, zipFilename, size, System.currentTimeMillis())
            }
        }

    private fun mergeMode06TestsForUi(newTests: List<Mode06TestResult>) {
        val current = _latestMode06Tests.value.associateBy { "${it.midHex}:${it.tidHex}" }.toMutableMap()
        for (t in newTests) {
            current["${t.midHex}:${t.tidHex}"] = t
        }
        _latestMode06Tests.value = current.values.toList()
    }

    private suspend fun queryVinSafe(): String? {
        val raw = sessionManager.sendPolledCommand("0902", 4_000L).getOrNull() ?: return null
        val parsed = ElmResponseParser.parseObdResponse(raw, 0x09, 0x02, "0902") as? ElmParseResult.Success
            ?: return null
        return DiagnosticDecoder.decodeMode09Ascii(parsed.ecuPayloads, isVin = true)
    }

    private suspend fun queryProtocolSafe(): String? {
        val raw = sessionManager.sendPolledCommand("ATDP", 2_500L).getOrNull() ?: return null
        return DiagnosticDecoder.decodeProtocol(raw)
    }

    companion object {
        private const val LOG_INTERVAL_MS = 1_000L
        private const val ATRV_POLL_INTERVAL_MS = 8_000L
        private const val MFR_POLL_INTERVAL_MS = 2_500L
        private const val MISFIRE_MODE06_INTERVAL_MS = 30_000L
    }
}
