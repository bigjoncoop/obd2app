package com.obd2logger.data.protocol

import com.obd2logger.BuildConfig
import com.obd2logger.data.logging.RawSessionLogRepository
import com.obd2logger.domain.model.AdapterConfig
import com.obd2logger.domain.model.ConnectionState
import com.obd2logger.domain.model.DecodedDtc
import com.obd2logger.domain.model.FreezeFrameSnapshot
import com.obd2logger.domain.model.ReadinessSnapshot
import com.obd2logger.domain.model.SessionDiagnosticSnapshot
import javax.inject.Inject
import javax.inject.Singleton

data class SessionStartResult(
    val diagnostics: SessionDiagnosticSnapshot,
    val initialAdapterVoltage: String?
)

/**
 * Runs the sequential session-start diagnostic query sequence once when the user taps START LOGGING.
 * Polling is gracefully paused before any command is sent and resumed only after completion.
 * Commands are sent one at a time via [ElmSessionManager.sendPolledCommand] so they never overlap.
 */
@Singleton
class SessionDiagnosticsRunner @Inject constructor(
    private val sessionManager: ElmSessionManager,
    private val sensorPollingEngine: SensorPollingEngine,
    private val logRepository: RawSessionLogRepository
) {

    suspend fun runSessionStartSequence(
        sessionId: String,
        connectedState: ConnectionState.Connected,
        adapterConfig: AdapterConfig?,
        onProgress: (String) -> Unit
    ): SessionStartResult {
        onProgress("Pausing live polling...")
        sensorPollingEngine.pausePollingGracefully()
        logRepository.logInfo("Session start sequence begun (polling paused, sessionId=$sessionId).")

        val adapterTypeLabel = when (adapterConfig) {
            is AdapterConfig.BluetoothClassic -> "BT Classic"
            is AdapterConfig.BluetoothLe -> "BLE"
            is AdapterConfig.Wifi -> "WiFi"
            AdapterConfig.Simulator -> "Simulator"
            null -> if (connectedState.isSimulator) "Simulator" else "-"
        }

        // 1. Protocol (ATDP) & Adapter Voltage (ATRV)
        onProgress("Reading protocol & adapter voltage...")
        val protocol = sendSafe("ATDP")?.let { DiagnosticDecoder.decodeProtocol(it) } ?: "-"
        val isCanProtocol = if (protocol == "-") {
            null
        } else {
            protocol.contains("CAN", ignoreCase = true) || protocol.contains("15765")
        }
        val initialVoltage = sendSafe("ATRV")?.let { DiagnosticDecoder.decodeAdapterVoltage(it) }

        // 2. MIL status, DTC count, and readiness monitors (0101, 0141)
        onProgress("Reading MIL status & readiness monitors...")
        var milOn: Boolean? = null
        var dtcCount: Int? = null
        var readinessCleared: ReadinessSnapshot? = null
        queryObdParsed(0x01, 0x01, "0101")?.let { success ->
            DiagnosticDecoder.decodeReadiness0101(success.dataBytes)?.let { (milPair, readiness) ->
                milOn = milPair.first
                dtcCount = milPair.second
                readinessCleared = readiness
            }
        }

        val readinessDriveCycle: ReadinessSnapshot? = if (0x41 in connectedState.supportedPids) {
            queryObdParsed(0x01, 0x41, "0141")?.let {
                DiagnosticDecoder.decodeReadiness0141(it.dataBytes)
            }
        } else {
            null
        }

        // OBD Standard (011C)
        val obdStandard = if (0x1C in connectedState.supportedPids) {
            queryObdParsed(0x01, 0x1C, "011C")?.let { success ->
                success.dataBytes.firstOrNull()?.let { DiagnosticDecoder.decodeObdStandard(it.toInt()) }
            } ?: "-"
        } else {
            "-"
        }

        // 3. Stored (03), Pending (07), Permanent (0A) DTCs
        onProgress("Reading trouble codes (03 / 07 / 0A)...")
        val storedDtcs = queryObdParsed(0x03, null, "03")?.let {
            DtcDecoder.decodeDtcPayloads(it.ecuPayloads, isCanProtocol)
        } ?: emptyList()

        val pendingDtcs = queryObdParsed(0x07, null, "07")?.let {
            DtcDecoder.decodeDtcPayloads(it.ecuPayloads, isCanProtocol)
        } ?: emptyList()

        val permanentDtcs = queryObdParsed(0x0A, null, "0A")?.let {
            DtcDecoder.decodeDtcPayloads(it.ecuPayloads, isCanProtocol)
        } ?: emptyList()

        // 4. Freeze Frame (Mode 02)
        onProgress("Reading freeze frame data...")
        val freezeFrame = readFreezeFrame()

        // 5. Vehicle Info (Mode 09): 0900 bitmask (if supported), VIN (0902), Cal ID (0904), CVN (0906), ECU Name (090A)
        onProgress("Reading VIN & vehicle info (Mode 09)...")
        val mode09Supported: Set<Int>? = queryObdParsed(0x09, 0x00, "0900")?.let { success ->
            if (success.dataBytes.size >= 4) {
                ElmResponseParser.decodeSupportedPidsBitmask(0x00, success.dataBytes)
            } else {
                null
            }
        }

        fun shouldQueryMode09(pid: Int): Boolean = mode09Supported == null || pid in mode09Supported

        val vin = if (shouldQueryMode09(0x02)) {
            queryObdParsed(0x09, 0x02, "0902")?.let {
                DiagnosticDecoder.decodeMode09Ascii(it.ecuPayloads, isVin = true)
            } ?: "-"
        } else {
            "-"
        }

        val calibrationId = if (shouldQueryMode09(0x04)) {
            queryObdParsed(0x09, 0x04, "0904")?.let {
                DiagnosticDecoder.decodeMode09Ascii(it.ecuPayloads, isVin = false)
            } ?: "-"
        } else {
            "-"
        }

        val cvn = if (shouldQueryMode09(0x06)) {
            queryObdParsed(0x09, 0x06, "0906")?.let {
                DiagnosticDecoder.decodeMode09Cvn(it.ecuPayloads)
            } ?: "-"
        } else {
            "-"
        }

        val ecuName = if (shouldQueryMode09(0x0A)) {
            queryObdParsed(0x09, 0x0A, "090A")?.let {
                DiagnosticDecoder.decodeMode09Ascii(it.ecuPayloads, isVin = false)
            } ?: "-"
        } else {
            "-"
        }

        val supportedHex = if (connectedState.supportedPids.isEmpty()) {
            "-"
        } else {
            connectedState.supportedPids.sorted().joinToString(" ") { "%02X".format(it) }
        }

        val snapshot = SessionDiagnosticSnapshot(
            sessionId = sessionId,
            milOn = milOn,
            dtcCount = dtcCount,
            storedDtcs = storedDtcs,
            pendingDtcs = pendingDtcs,
            permanentDtcs = permanentDtcs,
            readinessSinceCleared = readinessCleared,
            readinessThisDriveCycle = readinessDriveCycle,
            freezeFrame = freezeFrame,
            vin = vin,
            calibrationId = calibrationId,
            cvn = cvn,
            obdStandard = obdStandard,
            ecuName = ecuName,
            protocol = protocol,
            adapterType = adapterTypeLabel,
            adapterFirmware = connectedState.elmVersion.ifBlank { "-" },
            appVersion = BuildConfig.VERSION_NAME,
            supportedPidsHex = supportedHex
        )

        logRepository.logInfo(
            "Session start sequence complete: MIL=${snapshot.milStatusCsv}, " +
                "Stored=${storedDtcs.size}, Pending=${pendingDtcs.size}, Permanent=${permanentDtcs.size}, VIN=$vin"
        )

        return SessionStartResult(
            diagnostics = snapshot,
            initialAdapterVoltage = initialVoltage
        )
    }

    private suspend fun readFreezeFrame(): FreezeFrameSnapshot {
        val ffDtcResult = queryObdParsed(0x02, 0x02, "020200")
            ?: return FreezeFrameSnapshot()

        val dtcBytes = DiagnosticDecoder.decodeFreezeFramePidPayload(ffDtcResult.dataBytes, expectedBytes = 2)
            ?: return FreezeFrameSnapshot()

        val ffDtc: DecodedDtc? = DtcDecoder.decodeTwoBytes(
            highByte = dtcBytes[0].toInt(),
            lowByte = dtcBytes[1].toInt()
        )
        if (ffDtc == null) {
            return FreezeFrameSnapshot()
        }

        suspend fun readFfPid(pidId: Int): String {
            val def = PidTable.findById(pidId) ?: return "-"
            val cmd = "02%02X00".format(pidId and 0xFF)
            val parsed = queryObdParsed(0x02, pidId, cmd) ?: return "-"
            val payload = DiagnosticDecoder.decodeFreezeFramePidPayload(parsed.dataBytes, def.expectedBytes)
                ?: return "-"
            val value = def.evaluate(payload) ?: return "-"
            return def.formatValue(value)
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

    private suspend fun sendSafe(command: String): String? {
        return sessionManager.sendPolledCommand(command, SESSION_CMD_TIMEOUT_MS).getOrNull()
    }

    private suspend fun queryObdParsed(
        expectedService: Int,
        expectedPid: Int?,
        command: String
    ): ElmParseResult.Success? {
        val raw = sendSafe(command) ?: return null
        val parsed = ElmResponseParser.parseObdResponse(
            rawResponse = raw,
            expectedService = expectedService,
            expectedPid = expectedPid,
            sentCommand = command
        )
        return parsed as? ElmParseResult.Success
    }

    companion object {
        private const val SESSION_CMD_TIMEOUT_MS = 4_500L
    }
}
