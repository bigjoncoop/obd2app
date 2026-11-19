package com.obd2logger.data.protocol

import com.obd2logger.data.logging.RawSessionLogRepository
import com.obd2logger.domain.model.ManufacturerPidDefinition
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface HeaderCommandTransport {
    suspend fun sendCommand(command: String, timeoutMs: Long): Result<String>
}

/**
 * Probes and reads read-only manufacturer-specific PIDs (Mode 22 / 21 / 1A) using ELM327
 * `ATSH` (plus optional `ATCRA` / `ATFCSH` / `ATFCSD` / `ATFCSM`), always restoring the default
 * functional OBD header afterward in a `finally` block so standard Mode 01 polling is never broken.
 */
@Singleton
class ManufacturerPidProber @Inject constructor(
    private val sessionManager: ElmSessionManager,
    private val logRepository: RawSessionLogRepository
) {

    suspend fun probeCandidates(
        candidates: List<ManufacturerPidDefinition>,
        protocol: String
    ): Map<String, String> {
        val transport = object : HeaderCommandTransport {
            override suspend fun sendCommand(command: String, timeoutMs: Long): Result<String> {
                return sessionManager.sendPolledCommand(command, timeoutMs)
            }
        }
        return probeWithTransport(
            transport = transport,
            candidates = candidates,
            protocol = protocol,
            logError = { msg -> logRepository.logError(msg) }
        )
    }

    suspend fun readEnabledPids(
        enabledPids: List<ManufacturerPidDefinition>,
        protocol: String
    ): Map<String, String> {
        if (enabledPids.isEmpty()) return emptyMap()
        val transport = object : HeaderCommandTransport {
            override suspend fun sendCommand(command: String, timeoutMs: Long): Result<String> {
                return sessionManager.sendPolledCommand(command, timeoutMs)
            }
        }
        return probeWithTransport(
            transport = transport,
            candidates = enabledPids,
            protocol = protocol,
            logError = { msg -> logRepository.logError(msg) }
        )
    }

    companion object {
        private const val AT_TIMEOUT_MS = 2_000L
        private const val PID_TIMEOUT_MS = 2_500L

        fun defaultFunctionalHeader(protocol: String): String {
            return if (protocol.contains("29/", ignoreCase = true)) {
                "18DB33F1"
            } else {
                "7DF"
            }
        }

        /**
         * Queries each candidate PID sequentially and returns a map of `pid.id -> formattedValue`
         * for every PID that returned a valid positive response matching its command & formula.
         * Guaranteed to restore the default CAN header (and reset ATCRA/ATFCSM if modified) in `finally`.
         */
        suspend fun probeWithTransport(
            transport: HeaderCommandTransport,
            candidates: List<ManufacturerPidDefinition>,
            protocol: String,
            logError: (String) -> Unit = {}
        ): Map<String, String> {
            if (candidates.isEmpty()) return emptyMap()

            val defaultHeader = defaultFunctionalHeader(protocol)
            val results = linkedMapOf<String, String>()
            var currentHeader: String? = null
            var modifiedCra = false
            var modifiedFc = false

            // Group by header/addressing settings to minimize AT header switches
            val ordered = candidates.sortedBy { "${it.requestHeader}|${it.receiveAddress.orEmpty()}" }

            try {
                for (pid in ordered) {
                    if (currentHeader != pid.requestHeader) {
                        val shRes = transport.sendCommand("ATSH ${pid.requestHeader}", AT_TIMEOUT_MS)
                        if (shRes.isFailure) {
                            logError("ATSH ${pid.requestHeader} failed for ${pid.id}; skipping.")
                            continue
                        }
                        currentHeader = pid.requestHeader
                    }

                    if (pid.receiveAddress != null) {
                        transport.sendCommand("ATCRA ${pid.receiveAddress}", AT_TIMEOUT_MS)
                        modifiedCra = true
                    } else if (modifiedCra) {
                        transport.sendCommand("ATCRA", AT_TIMEOUT_MS)
                        modifiedCra = false
                    }

                    if (pid.flowControlHeader != null) {
                        transport.sendCommand("ATFCSH ${pid.flowControlHeader}", AT_TIMEOUT_MS)
                        modifiedFc = true
                    }
                    if (pid.flowControlData != null) {
                        transport.sendCommand("ATFCSD ${pid.flowControlData}", AT_TIMEOUT_MS)
                        modifiedFc = true
                    }
                    if (pid.flowControlMode != null) {
                        transport.sendCommand("ATFCSM ${pid.flowControlMode}", AT_TIMEOUT_MS)
                        modifiedFc = true
                    }

                    val rawReply = transport.sendCommand(pid.command, PID_TIMEOUT_MS).getOrNull() ?: continue
                    val valueStr = decodeAndFormatResponse(pid, rawReply)
                    if (valueStr != null) {
                        results[pid.id] = valueStr
                    }
                }
            } finally {
                // ALWAYS restore default functional header and CAN filters so Mode 01 polling stays intact
                if (modifiedCra) {
                    runCatching { transport.sendCommand("ATCRA", AT_TIMEOUT_MS) }
                }
                if (modifiedFc) {
                    runCatching { transport.sendCommand("ATFCSM 0", AT_TIMEOUT_MS) }
                }
                if (currentHeader != null) {
                    runCatching { transport.sendCommand("ATSH $defaultHeader", AT_TIMEOUT_MS) }
                }
            }

            return results
        }

        fun decodeAndFormatResponse(
            pid: ManufacturerPidDefinition,
            rawReply: String
        ): String? {
            val parsed = ElmResponseParser.parseObdResponse(
                rawResponse = rawReply,
                expectedService = pid.serviceByte,
                expectedPid = null,
                sentCommand = pid.command
            )
            if (parsed !is ElmParseResult.Success) return null

            val expectedPrefix = pid.pidBytes
            val full = parsed.dataBytes
            if (full.size < expectedPrefix.size + pid.expectedBytes) return null
            for (i in expectedPrefix.indices) {
                if (full[i] != expectedPrefix[i]) return null
            }

            val payload = full.copyOfRange(expectedPrefix.size, full.size)
            val evaluated = SafeFormulaEvaluator.evaluate(pid.formula, payload) ?: return null

            if (pid.min != null && evaluated < pid.min - 1e-6) return null
            if (pid.max != null && evaluated > pid.max + 1e-6) return null

            return if (pid.decimals <= 0) {
                "%.0f".format(Locale.US, evaluated)
            } else {
                "%.${pid.decimals}f".format(Locale.US, evaluated)
            }
        }
    }
}
