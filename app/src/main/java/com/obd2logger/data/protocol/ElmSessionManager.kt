package com.obd2logger.data.protocol

import android.content.Context
import com.obd2logger.data.logging.RawSessionLogRepository
import com.obd2logger.data.preferences.AdapterPreferencesRepository
import com.obd2logger.data.transport.BleTransport
import com.obd2logger.data.transport.BluetoothClassicTransport
import com.obd2logger.data.transport.BluetoothDeviceScanner
import com.obd2logger.data.transport.SimulatedObdTransport
import com.obd2logger.data.transport.WifiTransport
import com.obd2logger.domain.model.AdapterConfig
import com.obd2logger.domain.model.ConnectionState
import com.obd2logger.domain.transport.ObdTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the active [ObdTransport], enforces the single-command-at-a-time queue,
 * runs the ELM327 initialization and PID support discovery sequence, maintains the
 * 3-second idle heartbeat, and handles transport drops + auto-reconnect.
 */
@Singleton
class ElmSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothScanner: BluetoothDeviceScanner,
    private val logRepository: RawSessionLogRepository,
    private val preferencesRepository: AdapterPreferencesRepository
) {
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Enforces the single command queue: send one command, read until '>' (or timeout), then the next.
     * Never sends overlapping commands.
     */
    private val commandMutex = Mutex()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _supportedPids = MutableStateFlow<Set<Int>>(emptySet())
    val supportedPids: StateFlow<Set<Int>> = _supportedPids.asStateFlow()

    private val _activeAdapterConfig = MutableStateFlow<AdapterConfig?>(null)
    val activeAdapterConfig: StateFlow<AdapterConfig?> = _activeAdapterConfig.asStateFlow()

    @Volatile
    private var activeTransport: ObdTransport? = null
    private var connectJob: Job? = null
    private var transportMonitorJob: Job? = null
    private var heartbeatJob: Job? = null
    private var autoReconnectJob: Job? = null

    private val userRequestedDisconnect = AtomicBoolean(false)
    private val lastCommandTimestampMs = AtomicLong(0L)

    /**
     * Initiates connection to the specified [config] and runs the full ELM327 initialization sequence.
     */
    fun connect(config: AdapterConfig, isAutoReconnect: Boolean = false) {
        userRequestedDisconnect.set(false)
        autoReconnectJob?.cancel()
        connectJob?.cancel()

        connectJob = sessionScope.launch {
            performConnectAndInitialize(config, isAutoReconnect)
        }
    }

    /**
     * Disconnects cleanly and cancels any active heartbeat or auto-reconnect jobs.
     */
    fun disconnect(reason: String = "Disconnected by user.") {
        userRequestedDisconnect.set(true)
        autoReconnectJob?.cancel()
        connectJob?.cancel()
        heartbeatJob?.cancel()
        transportMonitorJob?.cancel()

        sessionScope.launch {
            val transport = activeTransport
            activeTransport = null
            runCatching { transport?.close() }
            _supportedPids.value = emptySet()
            _connectionState.value = ConnectionState.Disconnected(
                statusMessage = reason,
                isError = false,
                isSimulator = false
            )
            logRepository.logInfo(reason)
        }
    }

    /**
     * Retries vehicle ECU handshake (0100 + bitmask discovery) when already in [ConnectionState.AdapterReady].
     */
    fun retryVehicleConnection() {
        val currentState = _connectionState.value
        val config = _activeAdapterConfig.value ?: return
        if (currentState is ConnectionState.AdapterReady && activeTransport?.isOpen == true) {
            connectJob?.cancel()
            connectJob = sessionScope.launch {
                probeVehicleAndSupportedPids(
                    config = config,
                    elmVersion = currentState.elmVersion,
                    transportDetails = currentState.transportDetails
                )
            }
        } else {
            connect(config)
        }
    }

    /**
     * Sends a manual AT or OBD command from the Debug Terminal through the single command queue.
     */
    suspend fun sendManualCommand(rawInput: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Command cannot be empty."))
        }
        val transport = activeTransport
        if (transport == null || !transport.isOpen) {
            val msg = "Cannot send '$trimmed': Not connected to an adapter."
            logRepository.logError(msg)
            return@withContext Result.failure(IOException(msg))
        }

        val timeoutMs = if (trimmed.replace(" ", "").equals("0100", ignoreCase = true)) {
            ECU_SEARCH_TIMEOUT_MS
        } else {
            MANUAL_COMMAND_TIMEOUT_MS
        }

        try {
            val rawReply = executeCommandQueued(
                command = trimmed,
                timeoutMs = timeoutMs,
                isHeartbeat = false
            )

            // If currently in AdapterReady and user manually sent 0100 that returned a valid 41 00, promote to Connected
            val currentState = _connectionState.value
            if (currentState is ConnectionState.AdapterReady &&
                trimmed.replace(" ", "").equals("0100", ignoreCase = true)
            ) {
                val parsed = ElmResponseParser.parseObdResponse(
                    rawResponse = rawReply,
                    expectedService = 0x01,
                    expectedPid = 0x00,
                    sentCommand = "0100"
                )
                if (parsed is ElmParseResult.Success && parsed.dataBytes.size >= 4) {
                    val initialPids = ElmResponseParser.decodeSupportedPidsBitmask(0x00, parsed.dataBytes)
                    _supportedPids.value = initialPids
                    _connectionState.value = ConnectionState.Connected(
                        adapterName = currentState.adapterName,
                        elmVersion = currentState.elmVersion,
                        supportedPids = initialPids,
                        transportDetails = currentState.transportDetails,
                        isSimulator = currentState.isSimulator
                    )
                    startHeartbeatLoop()
                }
            }

            Result.success(rawReply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Sends a live-sensor polling command through the single command queue with raw TX/RX logging.
     */
    suspend fun sendPolledCommand(
        command: String,
        timeoutMs: Long = POLL_COMMAND_TIMEOUT_MS
    ): Result<String> = withContext(Dispatchers.IO) {
        val transport = activeTransport
        if (transport == null || !transport.isOpen) {
            return@withContext Result.failure(IOException("Transport is not open."))
        }
        try {
            val rawReply = executeCommandQueued(
                command = command,
                timeoutMs = timeoutMs,
                isHeartbeat = false
            )
            Result.success(rawReply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private suspend fun performConnectAndInitialize(
        config: AdapterConfig,
        isAutoReconnect: Boolean
    ) {
        bluetoothScanner.stopAllScans()
        heartbeatJob?.cancel()
        transportMonitorJob?.cancel()
        runCatching { activeTransport?.close() }
        activeTransport = null
        _activeAdapterConfig.value = config
        _supportedPids.value = emptySet()

        if (!isAutoReconnect) {
            logRepository.startNewSession(config.displayName)
        } else {
            logRepository.logInfo("Auto-reconnect attempt for ${config.displayName}...")
        }

        _connectionState.value = ConnectionState.Connecting(
            adapterName = config.displayName,
            statusMessage = "Opening ${config.transportType.label} transport...",
            isSimulator = config.isSimulator
        )

        val transport = createTransport(config)
        activeTransport = transport

        // 1. Open underlying transport (RFCOMM / BLE GATT / Wi-Fi TCP / Simulator)
        try {
            transport.open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val errMsg = e.message ?: "Failed to open connection to ${config.displayName}."
            logRepository.logError(errMsg)
            runCatching { transport.close() }
            activeTransport = null
            _connectionState.value = ConnectionState.Disconnected(
                statusMessage = errMsg,
                isError = true,
                isSimulator = false
            )
            if (isAutoReconnect && !userRequestedDisconnect.get()) {
                scheduleAutoReconnect(config, errMsg)
            }
            return
        }

        // Start monitoring transport drops immediately
        startTransportDropMonitor(transport, config)

        // Save adapter selection so one-tap reconnect is available even if car ignition is off
        runCatching { preferencesRepository.saveLastAdapter(config) }

        // 2. Run ELM327 ATZ reset (wait up to 2s; expect "ELM327" in reply, note version)
        _connectionState.value = ConnectionState.Connecting(
            adapterName = config.displayName,
            statusMessage = "Resetting adapter (ATZ)...",
            isSimulator = config.isSimulator
        )

        val elmVersion = try {
            val firstAtzReply = runCatching {
                executeCommandQueued("ATZ", ATZ_TIMEOUT_MS, isHeartbeat = false)
            }.getOrNull()

            val extractedFirst = firstAtzReply?.let { ElmResponseParser.extractElmVersion(it) }
            if (extractedFirst != null) {
                extractedFirst
            } else {
                // Retry ATZ once if initial buffer contained residual bytes
                delay(250)
                val secondAtzReply = executeCommandQueued("ATZ", ATZ_TIMEOUT_MS, isHeartbeat = false)
                ElmResponseParser.extractElmVersion(secondAtzReply)
                    ?: throw IOException(
                        "Adapter did not return 'ELM327' in response to ATZ (received: ${secondAtzReply.trim()})."
                    )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val errMsg = "ELM327 handshake (ATZ) failed: ${e.message}"
            logRepository.logError(errMsg)
            runCatching { transport.close() }
            activeTransport = null
            _connectionState.value = ConnectionState.Disconnected(
                statusMessage = errMsg,
                isError = true,
                isSimulator = false
            )
            return
        }

        val transportDetails = transport.connectionDetails.value
        logRepository.logInfo("Adapter identified: $elmVersion")

        // Transition to AdapterReady (yellow: ELM327 responded to ATZ, car unconfirmed)
        _connectionState.value = ConnectionState.AdapterReady(
            adapterName = config.displayName,
            elmVersion = elmVersion,
            statusMessage = "Adapter ready ($elmVersion). Configuring protocol...",
            isCarUnresponsive = false,
            transportDetails = transportDetails,
            isSimulator = config.isSimulator
        )

        // 3. Send init sequence: ATE0 -> ATL0 -> ATS0 -> ATH0 -> ATAT1 -> ATSP0
        val initCommands = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")
        for (cmd in initCommands) {
            if (!transport.isOpen) return
            _connectionState.value = ConnectionState.AdapterReady(
                adapterName = config.displayName,
                elmVersion = elmVersion,
                statusMessage = "Adapter ready ($elmVersion). Sending $cmd...",
                isCarUnresponsive = false,
                transportDetails = transportDetails,
                isSimulator = config.isSimulator
            )
            try {
                executeCommandQueued(cmd, AT_COMMAND_TIMEOUT_MS, isHeartbeat = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logRepository.logError("Warning during $cmd: ${e.message}")
            }
        }

        // 4. Probe vehicle ECU with 0100 (up to 10s timeout, retry once on failure)
        probeVehicleAndSupportedPids(
            config = config,
            elmVersion = elmVersion,
            transportDetails = transportDetails
        )
    }

    private suspend fun probeVehicleAndSupportedPids(
        config: AdapterConfig,
        elmVersion: String,
        transportDetails: String?
    ) {
        _connectionState.value = ConnectionState.AdapterReady(
            adapterName = config.displayName,
            elmVersion = elmVersion,
            statusMessage = "Searching for vehicle protocol (0100, up to 10s)...",
            isCarUnresponsive = false,
            transportDetails = transportDetails,
            isSimulator = config.isSimulator
        )

        var pid0100Success: ElmParseResult.Success? = null

        for (attempt in 1..2) {
            if (attempt == 2) {
                logRepository.logInfo("0100 attempt 1 did not connect to ECU. Retrying 0100 once (attempt 2/2)...")
                _connectionState.value = ConnectionState.AdapterReady(
                    adapterName = config.displayName,
                    elmVersion = elmVersion,
                    statusMessage = "Retrying vehicle ECU connection (0100, attempt 2/2)...",
                    isCarUnresponsive = false,
                    transportDetails = transportDetails,
                    isSimulator = config.isSimulator
                )
                delay(400)
            }

            val rawReply = try {
                executeCommandQueued("0100", ECU_SEARCH_TIMEOUT_MS, isHeartbeat = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logRepository.logError("0100 attempt $attempt failed: ${e.message}")
                null
            }

            if (rawReply != null) {
                val parseResult = ElmResponseParser.parseObdResponse(
                    rawResponse = rawReply,
                    expectedService = 0x01,
                    expectedPid = 0x00,
                    sentCommand = "0100"
                )
                when (parseResult) {
                    is ElmParseResult.Success -> {
                        if (parseResult.dataBytes.size >= 4) {
                            pid0100Success = parseResult
                            break
                        } else {
                            logRepository.logError(
                                "0100 returned fewer than 4 bitmask bytes (${parseResult.dataBytes.size})."
                            )
                        }
                    }
                    is ElmParseResult.Error -> {
                        logRepository.logError(
                            "0100 attempt $attempt returned error: ${parseResult.errorType.displayMessage}"
                        )
                    }
                    is ElmParseResult.InvalidResponse -> {
                        logRepository.logError(
                            "0100 attempt $attempt invalid response: ${parseResult.reason}"
                        )
                    }
                }
            }
        }

        if (pid0100Success == null) {
            val carNotRespondingMessage =
                "Adapter connected, but the car is not responding. Make sure ignition is ON."
            logRepository.logError(carNotRespondingMessage)
            _connectionState.value = ConnectionState.AdapterReady(
                adapterName = config.displayName,
                elmVersion = elmVersion,
                statusMessage = carNotRespondingMessage,
                isCarUnresponsive = true,
                transportDetails = transportDetails,
                isSimulator = config.isSimulator
            )
            // Start heartbeat in AdapterReady using ATRV so adapter disconnect is still monitored
            startHeartbeatLoop()
            return
        }

        // Valid 41 00 received! Query all supported PID bitmasks: 0100, 0120, 0140, 0160, 0180, 01A0
        val allSupported = linkedSetOf<Int>()
        allSupported += ElmResponseParser.decodeSupportedPidsBitmask(0x00, pid0100Success.dataBytes)

        val additionalMaskCommands = listOf(
            0x20 to "0120",
            0x40 to "0140",
            0x60 to "0160",
            0x80 to "0180",
            0xA0 to "01A0"
        )

        for ((basePid, cmd) in additionalMaskCommands) {
            // Query if the previous bitmask indicated support for this range's bitmask PID, or query all 6 ranges
            if (basePid in allSupported) {
                val rawMaskReply = runCatching {
                    executeCommandQueued(cmd, BITMASK_QUERY_TIMEOUT_MS, isHeartbeat = false)
                }.getOrNull()
                if (rawMaskReply != null) {
                    val parsed = ElmResponseParser.parseObdResponse(
                        rawResponse = rawMaskReply,
                        expectedService = 0x01,
                        expectedPid = basePid,
                        sentCommand = cmd
                    )
                    if (parsed is ElmParseResult.Success && parsed.dataBytes.size >= 4) {
                        allSupported += ElmResponseParser.decodeSupportedPidsBitmask(basePid, parsed.dataBytes)
                    }
                }
            }
        }

        _supportedPids.value = allSupported
        val pidHexSummary = allSupported.joinToString(", ") { "%02X".format(it) }
        logRepository.logInfo(
            "Vehicle ECU connected! Supported Mode 01 PIDs (${allSupported.size}): $pidHexSummary"
        )

        _connectionState.value = ConnectionState.Connected(
            adapterName = config.displayName,
            elmVersion = elmVersion,
            supportedPids = allSupported,
            transportDetails = transportDetails,
            isSimulator = config.isSimulator
        )

        startHeartbeatLoop()
    }

    /**
     * Executes a single command under [commandMutex]:
     * - Sends `command + "\r"`
     * - Collects incoming bytes until '>' arrives or [timeoutMs] elapses
     * - Logs TX and RX to [RawSessionLogRepository]
     */
    private suspend fun executeCommandQueued(
        command: String,
        timeoutMs: Long,
        isHeartbeat: Boolean
    ): String = commandMutex.withLock {
        val transport = activeTransport
        if (transport == null || !transport.isOpen) {
            throw IOException("Transport is not open.")
        }

        val cleanCmd = command.trim()
        logRepository.logTx(cleanCmd, isHeartbeat = isHeartbeat)

        val responseComplete = CompletableDeferred<String>()
        val buffer = ByteArrayOutputStream()

        // Subscribe to incomingBytes BEFORE writing the command so we never miss fast replies
        val collectorReady = CompletableDeferred<Unit>()
        val collectJob = sessionScope.launch {
            collectorReady.complete(Unit)
            transport.incomingBytes.collect { chunk ->
                if (chunk.isNotEmpty()) {
                    buffer.write(chunk)
                    val currentText = buffer.toString(Charsets.US_ASCII.name())
                    if (currentText.contains('>')) {
                        responseComplete.complete(currentText)
                    }
                }
            }
        }

        try {
            collectorReady.await()
            val payloadBytes = "$cleanCmd\r".toByteArray(Charsets.US_ASCII)
            transport.write(payloadBytes)

            val fullResponse = withTimeoutOrNull(timeoutMs) {
                responseComplete.await()
            }

            lastCommandTimestampMs.set(System.currentTimeMillis())

            if (fullResponse != null) {
                logRepository.logRx(fullResponse, isHeartbeat = isHeartbeat)
                return@withLock fullResponse
            } else {
                val partial = buffer.toString(Charsets.US_ASCII.name())
                if (partial.isNotEmpty()) {
                    logRepository.logRx("$partial [TIMEOUT]", isHeartbeat = isHeartbeat)
                }
                val timeoutMsg = "Timeout (${timeoutMs}ms) waiting for '>' prompt after '$cleanCmd'."
                logRepository.logError(timeoutMsg, isHeartbeat = isHeartbeat)
                throw IOException(timeoutMsg)
            }
        } finally {
            collectJob.cancel()
        }
    }

    /**
     * Monitors the active transport for unexpected socket/GATT disconnects and updates state immediately.
     */
    private fun startTransportDropMonitor(transport: ObdTransport, config: AdapterConfig) {
        transportMonitorJob?.cancel()
        transportMonitorJob = sessionScope.launch {
            launch {
                transport.transportErrors.collect { reason ->
                    if (!userRequestedDisconnect.get()) {
                        onUnexpectedTransportDrop(config, reason)
                    }
                }
            }
            launch {
                transport.isOpenFlow.collect { isOpen ->
                    if (!isOpen && activeTransport === transport && !userRequestedDisconnect.get()) {
                        onUnexpectedTransportDrop(config, "Adapter transport closed unexpectedly.")
                    }
                }
            }
        }
    }

    private fun onUnexpectedTransportDrop(config: AdapterConfig, reason: String) {
        heartbeatJob?.cancel()
        val oldTransport = activeTransport
        activeTransport = null
        sessionScope.launch {
            runCatching { oldTransport?.close() }
        }
        logRepository.logError("Transport drop detected: $reason")
        _supportedPids.value = emptySet()
        _connectionState.value = ConnectionState.Disconnected(
            statusMessage = "Disconnected: $reason",
            isError = true,
            isSimulator = false
        )
        if (!userRequestedDisconnect.get()) {
            scheduleAutoReconnect(config, reason)
        }
    }

    /**
     * Heartbeat every ~3s while idle.
     * If it fails 3 times in a row, sets state to Disconnected automatically and attempts auto-reconnect.
     */
    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = sessionScope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val transport = activeTransport ?: break
                if (!transport.isOpen || userRequestedDisconnect.get()) break

                // Only run heartbeat when idle (no command in flight and >= 2.5s since last command)
                val idleMs = System.currentTimeMillis() - lastCommandTimestampMs.get()
                if (commandMutex.isLocked || idleMs < 2_500L) {
                    continue
                }

                val state = _connectionState.value
                val hbCommand = if (state is ConnectionState.Connected) "0100" else "ATRV"

                val hbSuccess = try {
                    val reply = executeCommandQueued(
                        command = hbCommand,
                        timeoutMs = HEARTBEAT_TIMEOUT_MS,
                        isHeartbeat = true
                    )
                    if (hbCommand == "0100") {
                        val parsed = ElmResponseParser.parseObdResponse(
                            rawResponse = reply,
                            expectedService = 0x01,
                            expectedPid = 0x00,
                            sentCommand = "0100"
                        )
                        parsed is ElmParseResult.Success
                    } else {
                        val lines = ElmResponseParser.cleanLines(reply, "ATRV")
                        lines.isNotEmpty() && ElmResponseParser.detectError(lines) == null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    false
                }

                if (hbSuccess) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    logRepository.logError(
                        "Heartbeat ($hbCommand) failed ($consecutiveFailures/$MAX_HEARTBEAT_FAILURES).",
                        isHeartbeat = true
                    )
                    if (consecutiveFailures >= MAX_HEARTBEAT_FAILURES) {
                        val config = _activeAdapterConfig.value
                        val dropReason = "Heartbeat failed $MAX_HEARTBEAT_FAILURES times in a row."
                        if (config != null) {
                            onUnexpectedTransportDrop(config, dropReason)
                        } else {
                            disconnect(dropReason)
                        }
                        break
                    }
                }
            }
        }
    }

    private fun scheduleAutoReconnect(config: AdapterConfig, lastReason: String) {
        autoReconnectJob?.cancel()
        autoReconnectJob = sessionScope.launch {
            delay(AUTO_RECONNECT_DELAY_MS)
            if (userRequestedDisconnect.get()) return@launch
            if (_connectionState.value is ConnectionState.Disconnected) {
                logRepository.logInfo("Attempting auto-reconnect after: $lastReason")
                performConnectAndInitialize(config, isAutoReconnect = true)
            }
        }
    }

    private fun createTransport(config: AdapterConfig): ObdTransport {
        val onProgress: (String) -> Unit = { step ->
            logRepository.logInfo(step)
            val current = _connectionState.value
            if (current is ConnectionState.Connecting) {
                _connectionState.value = current.copy(statusMessage = step)
            }
        }

        return when (config) {
            is AdapterConfig.BluetoothClassic -> BluetoothClassicTransport(
                context = context,
                bluetoothAdapter = bluetoothScanner.bluetoothAdapter,
                config = config,
                onProgress = onProgress
            )
            is AdapterConfig.BluetoothLe -> BleTransport(
                context = context,
                bluetoothAdapter = bluetoothScanner.bluetoothAdapter,
                config = config,
                onProgress = onProgress
            )
            is AdapterConfig.Wifi -> WifiTransport(
                context = context,
                config = config,
                onProgress = onProgress,
                onEndpointConnected = { ip, port ->
                    val updated = AdapterConfig.Wifi(ip, port)
                    _activeAdapterConfig.value = updated
                    preferencesRepository.saveWifiEndpoint(ip, port)
                    preferencesRepository.saveLastAdapter(updated)
                }
            )
            AdapterConfig.Simulator -> SimulatedObdTransport()
        }
    }

    companion object {
        private const val ATZ_TIMEOUT_MS = 2_200L
        private const val AT_COMMAND_TIMEOUT_MS = 2_000L
        private const val ECU_SEARCH_TIMEOUT_MS = 10_000L
        private const val BITMASK_QUERY_TIMEOUT_MS = 3_500L
        private const val MANUAL_COMMAND_TIMEOUT_MS = 5_000L
        private const val POLL_COMMAND_TIMEOUT_MS = 2_500L
        private const val HEARTBEAT_INTERVAL_MS = 3_000L
        private const val HEARTBEAT_TIMEOUT_MS = 3_000L
        private const val MAX_HEARTBEAT_FAILURES = 3
        private const val AUTO_RECONNECT_DELAY_MS = 2_500L
    }
}
