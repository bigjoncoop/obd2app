package com.obd2logger.data.protocol

import com.obd2logger.data.logging.RawSessionLogRepository
import com.obd2logger.domain.model.Mode06Phase
import com.obd2logger.domain.model.Mode06TestResult
import javax.inject.Inject
import javax.inject.Singleton

data class Mode06RunResult(
    val supportedMids: Set<Int>,
    val tests: List<Mode06TestResult>
)

/**
 * Queries Mode 06 (On-Board Monitor Test Results) sequentially through [ElmSessionManager.sendPolledCommand].
 * Callers must pause live polling before invoking so commands never overlap.
 */
@Singleton
class Mode06Runner @Inject constructor(
    private val sessionManager: ElmSessionManager,
    private val logRepository: RawSessionLogRepository
) {

    suspend fun discoverAndReadAll(
        isCanProtocol: Boolean?,
        phase: Mode06Phase,
        knownSupportedMids: Set<Int>? = null
    ): Mode06RunResult {
        val supportedMids = knownSupportedMids ?: discoverSupportedMids()
        if (supportedMids.isEmpty()) {
            logRepository.logInfo("Mode 06: No supported MIDs reported.")
            return Mode06RunResult(emptySet(), emptyList())
        }

        val results = mutableListOf<Mode06TestResult>()
        val now = System.currentTimeMillis()

        for (mid in supportedMids.sorted()) {
            val cmd = "06%02X".format(mid and 0xFF)
            val raw = sessionManager.sendPolledCommand(cmd, MODE06_TIMEOUT_MS).getOrNull()
            if (raw == null) {
                logRepository.logError("Mode 06 $cmd timed out or failed; continuing.")
                continue
            }

            val parsed = ElmResponseParser.parseObdResponse(
                rawResponse = raw,
                expectedService = 0x06,
                expectedPid = null,
                sentCommand = cmd
            )
            when (parsed) {
                is ElmParseResult.Success -> {
                    results += Mode06Decoder.decodeMidPayloads(
                        requestedMid = mid,
                        ecuPayloads = parsed.ecuPayloads,
                        isCanProtocol = isCanProtocol,
                        phase = phase,
                        timestampMs = now
                    )
                }
                is ElmParseResult.Error -> {
                    if (parsed.errorType != ElmErrorType.NO_DATA) {
                        logRepository.logError("Mode 06 $cmd returned ${parsed.errorType.displayMessage}")
                    }
                }
                is ElmParseResult.InvalidResponse -> {
                    logRepository.logError("Mode 06 $cmd invalid response: ${parsed.reason}")
                }
            }
        }

        return Mode06RunResult(supportedMids = supportedMids, tests = results)
    }

    /**
     * Reads only misfire-counter MIDs (0xA1..0xB1) that are in [supportedMids].
     */
    suspend fun readMisfireMids(
        supportedMids: Set<Int>,
        isCanProtocol: Boolean?
    ): List<Mode06TestResult> {
        val misfireMids = supportedMids.filter { Mode06Tables.isMisfireMid(it) }.toSet()
        if (misfireMids.isEmpty()) return emptyList()
        return discoverAndReadAll(
            isCanProtocol = isCanProtocol,
            phase = Mode06Phase.DURING,
            knownSupportedMids = misfireMids
        ).tests
    }

    private suspend fun discoverSupportedMids(): Set<Int> {
        val discovered = linkedSetOf<Int>()
        val maskBases = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)

        for (base in maskBases) {
            if (base != 0x00 && base !in discovered) break
            val cmd = "06%02X".format(base)
            val raw = sessionManager.sendPolledCommand(cmd, MODE06_TIMEOUT_MS).getOrNull() ?: break
            val parsed = ElmResponseParser.parseObdResponse(
                rawResponse = raw,
                expectedService = 0x06,
                expectedPid = base,
                sentCommand = cmd
            )
            if (parsed is ElmParseResult.Success) {
                discovered += Mode06Decoder.decodeSupportedMids(base, parsed.ecuPayloads)
            } else {
                break
            }
        }

        return discovered.filterNot { it in BITMASK_MIDS }.toSet()
    }

    companion object {
        private const val MODE06_TIMEOUT_MS = 4_500L
        private val BITMASK_MIDS = setOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)
    }
}
