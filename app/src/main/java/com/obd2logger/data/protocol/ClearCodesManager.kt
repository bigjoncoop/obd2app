package com.obd2logger.data.protocol

import com.obd2logger.data.logging.CsvLogStorage
import com.obd2logger.data.logging.CsvWriter
import com.obd2logger.data.logging.RawSessionLogRepository
import com.obd2logger.domain.model.ConnectionState
import com.obd2logger.domain.model.DecodedDtc
import com.obd2logger.domain.model.FreezeFrameSnapshot
import com.obd2logger.domain.model.MonitorKey
import com.obd2logger.domain.model.ReadinessSnapshot
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class PreClearSnapshot(
    val timestampIso: String,
    val deviceTime: String,
    val vin: String,
    val milOn: Boolean?,
    val dtcCount: Int?,
    val storedDtcs: List<DecodedDtc>,
    val pendingDtcs: List<DecodedDtc>,
    val permanentDtcs: List<DecodedDtc>,
    val freezeFrame: FreezeFrameSnapshot,
    val readinessSinceCleared: ReadinessSnapshot?,
    val readinessThisDriveCycle: ReadinessSnapshot?
)

data class SavedPreClearFile(
    val uri: String,
    val filename: String,
    val snapshot: PreClearSnapshot
)

sealed class ClearCodesResult {
    data class Success(
        val snapshotFilename: String,
        val snapshotUri: String,
        val postMilOn: Boolean?,
        val postDtcCount: Int?,
        val remainingStored: List<DecodedDtc>,
        val remainingPending: List<DecodedDtc>,
        val remainingPermanent: List<DecodedDtc>
    ) : ClearCodesResult()

    data class CodesRemain(
        val snapshotFilename: String,
        val snapshotUri: String,
        val postMilOn: Boolean?,
        val remainingStored: List<DecodedDtc>,
        val remainingPending: List<DecodedDtc>,
        val remainingPermanent: List<DecodedDtc>
    ) : ClearCodesResult()

    data class Refused(
        val snapshotFilename: String?,
        val reason: String
    ) : ClearCodesResult()

    data class SnapshotFailed(
        val reason: String
    ) : ClearCodesResult()
}

interface PreClearSnapshotWriter {
    fun saveSnapshot(filename: String, jsonContent: String): Result<String>
}

interface ObdClearTransport {
    suspend fun sendCommand(command: String, timeoutMs: Long): Result<String>
}

/**
 * Pure JSON serializer for pre-clear diagnostic snapshots (no Android JSONObject dependency
 * so JVM unit tests execute cleanly).
 */
object PreClearSnapshotSerializer {

    fun toJson(snapshot: PreClearSnapshot): String {
        fun esc(s: String): String = buildString {
            append('"')
            for (ch in s) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }

        fun dtcArray(dtcs: List<DecodedDtc>): String {
            if (dtcs.isEmpty()) return "[]"
            return dtcs.joinToString(", ", prefix = "[", postfix = "]") { dtc ->
                """{"code":${esc(dtc.code)},"description":${esc(dtc.description)}}"""
            }
        }

        fun readinessObj(snap: ReadinessSnapshot?): String {
            if (snap == null) return "null"
            val entries = MonitorKey.entries.joinToString(", ") { key ->
                "${esc(key.displayName)}: ${esc(snap.statusOf(key))}"
            }
            return """{"ignitionType":${esc(snap.ignitionType.csvValue)}, "monitors":{$entries}}"""
        }

        val ff = snapshot.freezeFrame
        val ffJson = """{
            "dtc": ${ff.dtc?.let { """{"code":${esc(it.code)},"description":${esc(it.description)}}""" } ?: "null"},
            "rpm": ${esc(ff.rpm)},
            "speedMph": ${esc(ff.speedMph)},
            "engineLoadPct": ${esc(ff.engineLoadPct)},
            "coolantTempF": ${esc(ff.coolantTempF)},
            "stftB1Pct": ${esc(ff.stftB1Pct)},
            "ltftB1Pct": ${esc(ff.ltftB1Pct)},
            "intakeManifoldPressurePsi": ${esc(ff.intakeManifoldPressurePsi)},
            "intakeAirTempF": ${esc(ff.intakeAirTempF)}
        }""".trimIndent()

        return """
        {
          "timestampIso": ${esc(snapshot.timestampIso)},
          "deviceTime": ${esc(snapshot.deviceTime)},
          "vin": ${esc(snapshot.vin)},
          "milOn": ${snapshot.milOn?.toString() ?: "null"},
          "dtcCount": ${snapshot.dtcCount?.toString() ?: "null"},
          "storedDtcs": ${dtcArray(snapshot.storedDtcs)},
          "pendingDtcs": ${dtcArray(snapshot.pendingDtcs)},
          "permanentDtcs": ${dtcArray(snapshot.permanentDtcs)},
          "freezeFrame": $ffJson,
          "readinessSinceCleared": ${readinessObj(snapshot.readinessSinceCleared)},
          "readinessThisDriveCycle": ${readinessObj(snapshot.readinessThisDriveCycle)}
        }
        """.trimIndent()
    }
}

/**
 * Handles Mode 04 (Clear Trouble Codes) with strict safety invariants:
 * 1. Never runs automatically or during logging.
 * 2. Always re-reads DTCs, freeze frame, and readiness monitors first.
 * 3. Always persists `trackLog-<timestamp>_precleared.json` BEFORE sending Mode 04;
 *    aborts and refuses to clear if snapshot creation fails.
 * 4. Sends Mode 04, then verifies post-clear DTC/MIL state and logs every step.
 */
@Singleton
class ClearCodesManager @Inject constructor(
    private val sessionManager: ElmSessionManager,
    private val sensorPollingEngine: SensorPollingEngine,
    private val csvLogStorage: CsvLogStorage,
    private val logRepository: RawSessionLogRepository
) {

    suspend fun clearCodesWithSnapshotGuard(
        knownVin: String,
        knownProtocol: String,
        onProgress: (String) -> Unit = {}
    ): ClearCodesResult {
        if (sessionManager.connectionState.value !is ConnectionState.Connected) {
            return ClearCodesResult.Refused(null, "Adapter is not connected to vehicle ECU.")
        }

        val transport = object : ObdClearTransport {
            override suspend fun sendCommand(command: String, timeoutMs: Long): Result<String> {
                return sessionManager.sendPolledCommand(command, timeoutMs)
            }
        }
        val snapshotWriter = object : PreClearSnapshotWriter {
            override fun saveSnapshot(filename: String, jsonContent: String): Result<String> {
                return runCatching {
                    val (uri, stream) = csvLogStorage.openCompanionOutput(filename, "application/json")
                    stream.use { out ->
                        out.write(jsonContent.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    uri
                }
            }
        }

        val isCan = if (knownProtocol == "-") {
            null
        } else {
            knownProtocol.contains("CAN", ignoreCase = true) || knownProtocol.contains("15765")
        }

        onProgress("Pausing live polling...")
        sensorPollingEngine.pausePollingGracefully()
        return try {
            executeClearSequence(
                transport = transport,
                snapshotWriter = snapshotWriter,
                knownVin = knownVin,
                isCanProtocol = isCan,
                logInfo = { msg -> logRepository.logInfo(msg) },
                logError = { msg -> logRepository.logError(msg) },
                onProgress = onProgress
            )
        } finally {
            sensorPollingEngine.setPollingEnabled(true)
        }
    }

    companion object {
        private const val CMD_TIMEOUT_MS = 4_500L

        /**
         * Testable core Mode 04 clear-codes flow enforcing the snapshot-before-clear guard.
         */
        suspend fun executeClearSequence(
            transport: ObdClearTransport,
            snapshotWriter: PreClearSnapshotWriter,
            knownVin: String,
            isCanProtocol: Boolean?,
            nowMs: Long = System.currentTimeMillis(),
            logInfo: (String) -> Unit = {},
            logError: (String) -> Unit = {},
            onProgress: (String) -> Unit = {}
        ): ClearCodesResult {
            logInfo("Clear Codes (Mode 04): Step 1 — Re-reading DTCs, freeze frame, and readiness before clearing.")
            onProgress("Reading pre-clear diagnostic snapshot...")

            suspend fun queryObd(service: Int, pid: Int?, cmd: String): ElmParseResult.Success? {
                val raw = transport.sendCommand(cmd, CMD_TIMEOUT_MS).getOrNull() ?: return null
                return ElmResponseParser.parseObdResponse(raw, service, pid, cmd) as? ElmParseResult.Success
            }

            val r0101 = queryObd(0x01, 0x01, "0101")?.let { DiagnosticDecoder.decodeReadiness0101(it.dataBytes) }
            val r0141 = queryObd(0x01, 0x41, "0141")?.let { DiagnosticDecoder.decodeReadiness0141(it.dataBytes) }
            val stored = queryObd(0x03, null, "03")?.let { DtcDecoder.decodeDtcPayloads(it.ecuPayloads, isCanProtocol) } ?: emptyList()
            val pending = queryObd(0x07, null, "07")?.let { DtcDecoder.decodeDtcPayloads(it.ecuPayloads, isCanProtocol) } ?: emptyList()
            val permanent = queryObd(0x0A, null, "0A")?.let { DtcDecoder.decodeDtcPayloads(it.ecuPayloads, isCanProtocol) } ?: emptyList()
            val ff = readFreezeFrame(transport)
            val vin = if (knownVin.isNotBlank() && knownVin != "-") {
                knownVin
            } else {
                queryObd(0x09, 0x02, "0902")?.let {
                    DiagnosticDecoder.decodeMode09Ascii(it.ecuPayloads, isVin = true)
                } ?: "-"
            }

            val snapshot = PreClearSnapshot(
                timestampIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(nowMs).atOffset(ZoneOffset.UTC)),
                deviceTime = CsvWriter.formatDeviceTime(nowMs),
                vin = vin,
                milOn = r0101?.first?.first,
                dtcCount = r0101?.first?.second,
                storedDtcs = stored,
                pendingDtcs = pending,
                permanentDtcs = permanent,
                freezeFrame = ff,
                readinessSinceCleared = r0101?.second,
                readinessThisDriveCycle = r0141
            )

            val stem = CsvWriter.formatFilename(nowMs).removeSuffix(".csv")
            val snapshotFilename = "${stem}_precleared.json"
            val json = PreClearSnapshotSerializer.toJson(snapshot)

            onProgress("Saving pre-clear backup ($snapshotFilename)...")
            logInfo("Clear Codes (Mode 04): Step 2 — Saving snapshot $snapshotFilename before sending 04.")
            val saveResult = snapshotWriter.saveSnapshot(snapshotFilename, json)
            val snapshotUri = saveResult.getOrElse { err ->
                val msg = "Clear Codes aborted: Failed to save pre-clear snapshot (${err.message ?: "unknown error"})."
                logError(msg)
                return ClearCodesResult.SnapshotFailed(msg)
            }
            logInfo("Pre-clear snapshot saved: $snapshotFilename")

            // Step 3: Send Mode 04 ONLY after snapshot save succeeded
            onProgress("Sending Mode 04 clear command...")
            logInfo("Clear Codes (Mode 04): Step 3 — Sending 04 command.")
            val clearReply = transport.sendCommand("04", CMD_TIMEOUT_MS).getOrElse { err ->
                val msg = "Mode 04 command failed: ${err.message ?: "timeout"}"
                logError(msg)
                return ClearCodesResult.Refused(snapshotFilename, msg)
            }

            val clearParsed = ElmResponseParser.parseObdResponse(
                rawResponse = clearReply,
                expectedService = 0x04,
                expectedPid = null,
                sentCommand = "04"
            )
            // Note: valid 44 response may have 0 data bytes ("44\r>"), which ElmResponseParser marks InvalidResponse
            // if bytes.size < 2. Check cleaned lines explicitly for "44" or negative response "7F 04 XX".
            val cleanedLines = ElmResponseParser.cleanLines(clearReply, "04")
            val isPositive44 = clearParsed is ElmParseResult.Success || cleanedLines.any { line ->
                val compact = line.replace(" ", "").uppercase()
                compact == "44" || compact.startsWith("44") ||
                    (compact.startsWith("7E") && compact.length >= 7 && compact.substring(5).startsWith("44"))
            }
            val negativeCode = cleanedLines.firstNotNullOfOrNull { line ->
                val compact = line.replace(" ", "").uppercase()
                if (compact.startsWith("7F04") && compact.length >= 6) compact.substring(4, 6) else null
            }

            if (!isPositive44) {
                val reason = when (negativeCode) {
                    "22" -> "ECU refused Mode 04 (Conditions not correct — turn engine OFF, ignition ON)."
                    "33" -> "ECU refused Mode 04 (Security access denied)."
                    "12", "11" -> "ECU does not support Mode 04 clear."
                    null -> {
                        val err = ElmResponseParser.detectError(cleanedLines)?.displayMessage
                        err ?: "ECU did not confirm Mode 04 clear (${cleanedLines.joinToString(" ")})."
                    }
                    else -> "ECU refused Mode 04 (negative response 7F 04 $negativeCode)."
                }
                logError("Clear Codes (Mode 04) refused: $reason")
                return ClearCodesResult.Refused(snapshotFilename, reason)
            }

            // Step 4: Verify by re-reading MIL and DTCs
            onProgress("Verifying post-clear status...")
            logInfo("Clear Codes (Mode 04): Step 4 — Re-reading MIL & DTCs to verify.")
            val post0101 = queryObd(0x01, 0x01, "0101")?.let { DiagnosticDecoder.decodeReadiness0101(it.dataBytes) }
            val postStored = queryObd(0x03, null, "03")?.let { DtcDecoder.decodeDtcPayloads(it.ecuPayloads, isCanProtocol) } ?: emptyList()
            val postPending = queryObd(0x07, null, "07")?.let { DtcDecoder.decodeDtcPayloads(it.ecuPayloads, isCanProtocol) } ?: emptyList()
            val postPermanent = queryObd(0x0A, null, "0A")?.let { DtcDecoder.decodeDtcPayloads(it.ecuPayloads, isCanProtocol) } ?: emptyList()

            val postMil = post0101?.first?.first
            val postCount = post0101?.first?.second

            return if (postStored.isEmpty() && postPending.isEmpty() && postMil != true) {
                logInfo("Clear Codes (Mode 04) succeeded. Stored=0, Pending=0, Permanent=${postPermanent.size}.")
                ClearCodesResult.Success(
                    snapshotFilename = snapshotFilename,
                    snapshotUri = snapshotUri,
                    postMilOn = postMil,
                    postDtcCount = postCount,
                    remainingStored = postStored,
                    remainingPending = postPending,
                    remainingPermanent = postPermanent
                )
            } else {
                logInfo("Clear Codes (Mode 04) sent, but codes/MIL remain (Stored=${postStored.size}, Pending=${postPending.size}, MIL=$postMil).")
                ClearCodesResult.CodesRemain(
                    snapshotFilename = snapshotFilename,
                    snapshotUri = snapshotUri,
                    postMilOn = postMil,
                    remainingStored = postStored,
                    remainingPending = postPending,
                    remainingPermanent = postPermanent
                )
            }
        }

        private suspend fun readFreezeFrame(transport: ObdClearTransport): FreezeFrameSnapshot {
            val ffRaw = transport.sendCommand("020200", CMD_TIMEOUT_MS).getOrNull() ?: return FreezeFrameSnapshot()
            val ffParsed = ElmResponseParser.parseObdResponse(ffRaw, 0x02, 0x02, "020200") as? ElmParseResult.Success
                ?: return FreezeFrameSnapshot()
            val dtcBytes = DiagnosticDecoder.decodeFreezeFramePidPayload(ffParsed.dataBytes, 2)
                ?: return FreezeFrameSnapshot()
            val ffDtc = DtcDecoder.decodeTwoBytes(dtcBytes[0].toInt(), dtcBytes[1].toInt())
                ?: return FreezeFrameSnapshot()

            suspend fun readFfPid(pidId: Int): String {
                val def = PidTable.findById(pidId) ?: return "-"
                val cmd = "02%02X00".format(pidId)
                val raw = transport.sendCommand(cmd, CMD_TIMEOUT_MS).getOrNull() ?: return "-"
                val parsed = ElmResponseParser.parseObdResponse(raw, 0x02, pidId, cmd) as? ElmParseResult.Success
                    ?: return "-"
                val payload = DiagnosticDecoder.decodeFreezeFramePidPayload(parsed.dataBytes, def.expectedBytes)
                    ?: return "-"
                return def.formatValue(def.evaluate(payload))
            }

            return FreezeFrameSnapshot(
                dtc = ffDtc,
                rpm = readFfPid(0x0C),
                speedMph = readFfPid(0x0D),
                engineLoadPct = readFfPid(0x04),
                coolantTempF = readFfPid(0x05),
                stftB1Pct = readFfPid(0x06),
                ltftB1Pct = readFfPid(0x07),
                intakeManifoldPressurePsi = readFfPid(0x0B),
                intakeAirTempF = readFfPid(0x0F)
            )
        }
    }
}
