package com.obd2logger.data.protocol

import com.obd2logger.data.logging.RawSessionLogRepository
import com.obd2logger.data.preferences.AdapterPreferencesRepository
import com.obd2logger.data.preferences.PollingSpeed
import com.obd2logger.domain.model.ConnectionState
import com.obd2logger.domain.model.LiveSensorValue
import com.obd2logger.domain.model.PidDefinition
import com.obd2logger.domain.model.PidPriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prioritized Mode 01 live-sensor polling engine.
 *
 * - Polls only PIDs reported as supported in the vehicle's 0100/0120/0140... bitmasks.
 * - Uses [PidPriority] tiers (HIGH every cycle, MEDIUM every 3rd cycle, LOW every 8th cycle).
 * - Optionally batches multiple PIDs into a single Mode 01 request (e.g. `010C0D11`) and
 *   automatically falls back to single-PID requests if batching fails.
 * - Validates every `41 <PID>` response header before accepting any data; missing or invalid
 *   readings remain/revert to `null` ("--").
 */
@Singleton
class SensorPollingEngine @Inject constructor(
    private val sessionManager: ElmSessionManager,
    private val logRepository: RawSessionLogRepository,
    private val preferencesRepository: AdapterPreferencesRepository
) {
    private val pollingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _liveValues = MutableStateFlow<List<LiveSensorValue>>(emptyList())
    val liveValues: StateFlow<List<LiveSensorValue>> = _liveValues.asStateFlow()

    private val _isPollingEnabled = MutableStateFlow(true)
    val isPollingEnabled: StateFlow<Boolean> = _isPollingEnabled.asStateFlow()

    private val _isBatchingEnabled = MutableStateFlow(true)
    val isBatchingEnabled: StateFlow<Boolean> = _isBatchingEnabled.asStateFlow()

    private val _isBatchFallbackActive = MutableStateFlow(false)
    val isBatchFallbackActive: StateFlow<Boolean> = _isBatchFallbackActive.asStateFlow()

    @Volatile
    private var currentPollingSpeed: PollingSpeed = PollingSpeed.FAST

    private var pollingJob: Job? = null
    private var activeSupportedPids: List<PidDefinition> = emptyList()

    init {
        observePollingSpeed()
        observeConnectionAndPollingState()
    }

    private fun observePollingSpeed() {
        pollingScope.launch {
            preferencesRepository.pollingSpeed.collect { speed ->
                currentPollingSpeed = speed
            }
        }
    }

    private fun observeConnectionAndPollingState() {
        pollingScope.launch {
            combine(
                sessionManager.connectionState,
                _isPollingEnabled
            ) { state, pollingEnabled ->
                state to pollingEnabled
            }.collect { (state, pollingEnabled) ->
                if (state is ConnectionState.Connected) {
                    val supportedDefs = PidTable.withSupportFlags(state.supportedPids)
                        .filter { it.supported }

                    // Reset table & fallback state if the supported PID set changed (new connection)
                    if (supportedDefs.map { it.id } != activeSupportedPids.map { it.id }) {
                        activeSupportedPids = supportedDefs
                        _isBatchFallbackActive.value = false
                        _liveValues.value = supportedDefs.map { LiveSensorValue(pid = it) }
                    }

                    if (pollingEnabled && supportedDefs.isNotEmpty()) {
                        startPollingLoopIfNeeded(supportedDefs)
                    }
                    // When pollingEnabled is false while still Connected, the polling loop
                    // exits cleanly after its current in-flight command so commands never overlap.
                } else {
                    stopPollingLoop()
                    activeSupportedPids = emptyList()
                    _isBatchFallbackActive.value = false
                    _liveValues.value = emptyList()
                }
            }
        }
    }

    fun setPollingEnabled(enabled: Boolean) {
        _isPollingEnabled.value = enabled
    }

    /**
     * Pauses polling and suspends until any in-flight polling command finishes its '>' prompt,
     * ensuring session-start or diagnostic commands never overlap with polling traffic.
     */
    suspend fun pausePollingGracefully() {
        _isPollingEnabled.value = false
        pollingJob?.join()
    }

    fun setBatchingEnabled(enabled: Boolean) {
        _isBatchingEnabled.value = enabled
        if (enabled) {
            _isBatchFallbackActive.value = false
        }
    }

    private fun startPollingLoopIfNeeded(supportedDefs: List<PidDefinition>) {
        if (pollingJob?.isActive == true) return
        pollingJob = pollingScope.launch {
            var cycleIndex = 0L
            var consecutiveBatchFailures = 0

            while (isActive) {
                val state = sessionManager.connectionState.value
                if (state !is ConnectionState.Connected || !_isPollingEnabled.value) {
                    break
                }

                val duePids = selectDuePids(supportedDefs, cycleIndex)
                if (duePids.isEmpty()) {
                    cycleIndex++
                    delay(IDLE_CYCLE_DELAY_MS)
                    continue
                }

                val useBatching = _isBatchingEnabled.value && !_isBatchFallbackActive.value
                if (useBatching) {
                    val batches = buildPidBatches(duePids)
                    for (batch in batches) {
                        if (!isActive || sessionManager.connectionState.value !is ConnectionState.Connected || !_isPollingEnabled.value) {
                            break
                        }
                        if (batch.size > 1 && _isBatchingEnabled.value && !_isBatchFallbackActive.value) {
                            val batchSucceeded = pollBatch(batch)
                            if (batchSucceeded) {
                                consecutiveBatchFailures = 0
                            } else {
                                consecutiveBatchFailures++
                                if (consecutiveBatchFailures >= MAX_CONSECUTIVE_BATCH_FAILURES && !_isBatchFallbackActive.value) {
                                    _isBatchFallbackActive.value = true
                                    logRepository.logInfo(
                                        "Multi-PID batching failed $MAX_CONSECUTIVE_BATCH_FAILURES times; falling back to single-PID polling."
                                    )
                                }
                                // Fall back to single-PID polling for the PIDs in this failed batch
                                for (pid in batch) {
                                    if (!isActive || sessionManager.connectionState.value !is ConnectionState.Connected || !_isPollingEnabled.value) {
                                        break
                                    }
                                    pollSinglePid(pid)
                                    if (_isPollingEnabled.value) delay(currentPollingSpeed.interCommandDelayMs)
                                }
                            }
                        } else {
                            for (pid in batch) {
                                if (!isActive || sessionManager.connectionState.value !is ConnectionState.Connected || !_isPollingEnabled.value) {
                                    break
                                }
                                pollSinglePid(pid)
                                if (_isPollingEnabled.value) delay(currentPollingSpeed.interCommandDelayMs)
                            }
                        }
                        if (_isPollingEnabled.value) delay(currentPollingSpeed.interCommandDelayMs)
                    }
                } else {
                    for (pid in duePids) {
                        if (!isActive || sessionManager.connectionState.value !is ConnectionState.Connected || !_isPollingEnabled.value) {
                            break
                        }
                        pollSinglePid(pid)
                        if (_isPollingEnabled.value) delay(currentPollingSpeed.interCommandDelayMs)
                    }
                }

                cycleIndex++
                if (_isPollingEnabled.value) delay(currentPollingSpeed.cycleDelayMs)
            }
        }
    }

    private fun stopPollingLoop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun pollBatch(batch: List<PidDefinition>): Boolean {
        val cmd = "01" + batch.joinToString("") { it.hexId }
        val replyResult = sessionManager.sendPolledCommand(cmd, POLL_TIMEOUT_MS)
        val rawReply = replyResult.getOrNull() ?: return false

        val parsed = ElmResponseParser.parseMultiPidMode01Response(
            rawResponse = rawReply,
            requestedPids = batch,
            sentCommand = cmd
        )

        return when (parsed) {
            is MultiPidParseResult.Success -> {
                val now = System.currentTimeMillis()
                val updates = mutableMapOf<Int, Pair<Double?, ByteArray?>>()
                for (pid in batch) {
                    val bytes = parsed.pidPayloads[pid.id]
                    val evaluated = bytes?.let { pid.evaluate(it) }
                    if (evaluated == null) {
                        return false
                    }
                    updates[pid.id] = evaluated to bytes
                }
                applySensorUpdates(updates, now)
                true
            }
            is MultiPidParseResult.Error,
            is MultiPidParseResult.InvalidResponse -> false
        }
    }

    private suspend fun pollSinglePid(pid: PidDefinition) {
        val cmd = pid.command
        val replyResult = sessionManager.sendPolledCommand(cmd, POLL_TIMEOUT_MS)
        val rawReply = replyResult.getOrNull()

        if (rawReply == null) {
            clearSensorValue(pid.id)
            return
        }

        val parsed = ElmResponseParser.parseObdResponse(
            rawResponse = rawReply,
            expectedService = 0x01,
            expectedPid = pid.id,
            sentCommand = cmd
        )

        when (parsed) {
            is ElmParseResult.Success -> {
                val evaluated = pid.evaluate(parsed.dataBytes)
                if (evaluated != null) {
                    applySensorUpdates(
                        mapOf(pid.id to (evaluated to parsed.dataBytes)),
                        System.currentTimeMillis()
                    )
                } else {
                    clearSensorValue(pid.id)
                }
            }
            is ElmParseResult.Error,
            is ElmParseResult.InvalidResponse -> {
                clearSensorValue(pid.id)
            }
        }
    }

    private fun applySensorUpdates(updates: Map<Int, Pair<Double?, ByteArray?>>, timestampMs: Long) {
        _liveValues.update { current ->
            current.map { item ->
                val pidId = item.pid.id
                if (pidId in updates) {
                    val (newVal, newBytes) = updates[pidId]!!
                    item.copy(
                        numericValue = newVal,
                        lastUpdatedMs = if (newVal != null) timestampMs else null,
                        rawBytes = if (newVal != null) newBytes else null
                    )
                } else {
                    item
                }
            }
        }
    }

    private fun clearSensorValue(pidId: Int) {
        _liveValues.update { current ->
            current.map { item ->
                if (item.pid.id == pidId && item.numericValue != null) {
                    item.copy(numericValue = null, lastUpdatedMs = null, rawBytes = null)
                } else {
                    item
                }
            }
        }
    }

    companion object {
        private const val POLL_TIMEOUT_MS = 2_500L
        private const val INTER_COMMAND_DELAY_MS = 25L
        private const val CYCLE_DELAY_MS = 40L
        private const val IDLE_CYCLE_DELAY_MS = 100L
        private const val MAX_CONSECUTIVE_BATCH_FAILURES = 2
        private const val MAX_BATCH_PIDS = 3
        private const val MAX_BATCH_PAYLOAD_BYTES = 6

        /**
         * Selects PIDs due on [cycleIndex] according to their [PidPriority.cycleInterval].
         */
        fun selectDuePids(pids: List<PidDefinition>, cycleIndex: Long): List<PidDefinition> {
            return pids.filter { pid ->
                pid.supported && (cycleIndex % pid.priority.cycleInterval == 0L)
            }
        }

        /**
         * Groups [pids] into batches of up to [maxPidsPerBatch] PIDs whose combined
         * `(1 + expectedBytes)` does not exceed [maxPayloadBytes] (fitting in a single CAN frame).
         */
        fun buildPidBatches(
            pids: List<PidDefinition>,
            maxPidsPerBatch: Int = MAX_BATCH_PIDS,
            maxPayloadBytes: Int = MAX_BATCH_PAYLOAD_BYTES
        ): List<List<PidDefinition>> {
            if (pids.isEmpty()) return emptyList()
            val batches = mutableListOf<List<PidDefinition>>()
            var currentBatch = mutableListOf<PidDefinition>()
            var currentBytes = 0

            for (pid in pids) {
                val pidCost = 1 + pid.expectedBytes
                if (currentBatch.isNotEmpty() &&
                    (currentBatch.size >= maxPidsPerBatch || currentBytes + pidCost > maxPayloadBytes)
                ) {
                    batches.add(currentBatch)
                    currentBatch = mutableListOf()
                    currentBytes = 0
                }
                currentBatch.add(pid)
                currentBytes += pidCost
            }
            if (currentBatch.isNotEmpty()) {
                batches.add(currentBatch)
            }
            return batches
        }
    }
}
