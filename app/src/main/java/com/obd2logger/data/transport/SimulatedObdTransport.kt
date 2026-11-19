package com.obd2logger.data.transport

import com.obd2logger.domain.model.TransportType
import com.obd2logger.domain.transport.ObdTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.sin

/**
 * Simulated ELM327 v1.5 transport selectable in Debug settings for testing without a car.
 * Always sets [isSimulator] = true so the UI displays a prominent "SIMULATOR" badge whenever active.
 */
class SimulatedObdTransport(
    private val simulateCarIgnitionOff: Boolean = false
) : ObdTransport {

    override val displayName: String = "SIMULATOR (Virtual ELM327 v1.5)"
    override val transportType: TransportType = TransportType.SIMULATOR
    override val isSimulator: Boolean = true

    private val _isOpenFlow = MutableStateFlow(false)
    override val isOpenFlow: StateFlow<Boolean> = _isOpenFlow.asStateFlow()
    override val isOpen: Boolean get() = _isOpenFlow.value

    private val _incomingBytes = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    override val incomingBytes: Flow<ByteArray> = _incomingBytes.asSharedFlow()

    private val _transportErrors = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val transportErrors: SharedFlow<String> = _transportErrors.asSharedFlow()

    private val _connectionDetails = MutableStateFlow<String?>("SIMULATOR • Virtual ISO 15765-4 CAN 11/500")
    override val connectionDetails: StateFlow<String?> = _connectionDetails.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandBuffer = StringBuilder()

    private var echoEnabled = true
    private var spacesEnabled = true
    private var linefeedsEnabled = true
    private var headersEnabled = false

    override suspend fun open() {
        echoEnabled = true
        spacesEnabled = true
        linefeedsEnabled = true
        headersEnabled = false
        commandBuffer.clear()
        delay(80)
        _isOpenFlow.value = true
    }

    override suspend fun close() {
        _isOpenFlow.value = false
        commandBuffer.clear()
    }

    override suspend fun write(bytes: ByteArray) {
        if (!_isOpenFlow.value) {
            throw IOException("Simulator transport is closed.")
        }
        val text = bytes.toString(Charsets.US_ASCII)
        for (ch in text) {
            if (ch == '\r' || ch == '\n') {
                val cmd = commandBuffer.toString().trim()
                commandBuffer.clear()
                if (cmd.isNotEmpty() || ch == '\r') {
                    scope.launch {
                        processCommand(cmd)
                    }
                }
            } else {
                commandBuffer.append(ch)
            }
        }
    }

    private suspend fun processCommand(rawCmd: String) {
        delay(45)
        if (!_isOpenFlow.value) return
        val normalized = rawCmd.replace(" ", "").uppercase()

        val body = when {
            normalized.isEmpty() -> "OK"
            normalized == "ATZ" -> {
                echoEnabled = true
                spacesEnabled = true
                linefeedsEnabled = true
                headersEnabled = false
                "ELM327 v1.5"
            }
            normalized == "ATI" -> "ELM327 v1.5"
            normalized == "ATE0" -> {
                echoEnabled = false
                "OK"
            }
            normalized == "ATE1" -> {
                echoEnabled = true
                "OK"
            }
            normalized == "ATL0" -> {
                linefeedsEnabled = false
                "OK"
            }
            normalized == "ATL1" -> {
                linefeedsEnabled = true
                "OK"
            }
            normalized == "ATS0" -> {
                spacesEnabled = false
                "OK"
            }
            normalized == "ATS1" -> {
                spacesEnabled = true
                "OK"
            }
            normalized == "ATH0" -> {
                headersEnabled = false
                "OK"
            }
            normalized == "ATH1" -> {
                headersEnabled = true
                "OK"
            }
            normalized.startsWith("ATAT") || normalized.startsWith("ATSP") || normalized.startsWith("ATST") -> "OK"
            normalized == "ATRV" -> "14.2V"
            normalized == "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
            normalized == "ATDPN" -> "A6"
            normalized.startsWith("AT") -> "OK"
            else -> buildObdResponse(normalized)
        }

        val lineBreak = if (linefeedsEnabled) "\r\n" else "\r"
        val fullReply = buildString {
            if (echoEnabled && rawCmd.isNotEmpty()) {
                append(rawCmd)
                append(lineBreak)
            }
            append(body)
            append(lineBreak)
            append(">")
        }

        _incomingBytes.emit(fullReply.toByteArray(Charsets.US_ASCII))
    }

    private fun buildObdResponse(cmd: String): String {
        if (simulateCarIgnitionOff) {
            return "UNABLE TO CONNECT"
        }

        val rawHex = when {
            // Multi-PID Mode 01 request (e.g. 010C0D11)
            cmd.startsWith("01") && cmd.length > 4 && cmd.length % 2 == 0 -> {
                val pids = cmd.substring(2).chunked(2)
                val parts = pids.map { pidHex ->
                    val data = singleMode01PidPayload(pidHex) ?: return "NO DATA"
                    "$pidHex $data"
                }
                "41 ${parts.joinToString(" ")}"
            }
            cmd.startsWith("01") && cmd.length == 4 -> {
                val pidHex = cmd.substring(2)
                val data = singleMode01PidPayload(pidHex) ?: return "NO DATA"
                "41 $pidHex $data"
            }

            // Stored, Pending, Permanent DTCs
            cmd == "03" -> "43 02 01 71 04 20"
            cmd == "07" -> "47 01 03 00 00 00"
            cmd == "0A" -> "4A 01 01 71 00 00"

            // Mode 02 Freeze Frame (Frame 00)
            cmd == "020200" -> "42 02 00 01 71"
            cmd.startsWith("02") && cmd.length == 6 && cmd.endsWith("00") -> {
                val pidHex = cmd.substring(2, 4)
                val ffData = freezeFramePidPayload(pidHex) ?: return "NO DATA"
                "42 $pidHex 00 $ffData"
            }

            // Mode 09 Vehicle Info (ISO-TP multi-frame examples)
            cmd == "0900" -> "49 00 55 40 00 00" // Supports 02, 04, 06, 0A
            cmd == "0902" -> return "014\r0: 49 02 01 31 48 47\r1: 43 4D 38 32 36 33\r2: 33 41 30 30 34 33\r3: 35 32 00 00 00 00"
            cmd == "0904" -> return "013\r0: 49 04 01 33 37 38\r1: 30 35 2D 52 42 42\r2: 2D 41 35 32 30 00\r3: 00 00 00 00 00 00"
            cmd == "0906" -> "49 06 01 9A 3C 7F 21"
            cmd == "090A" -> return "017\r0: 49 0A 01 45 43 4D\r1: 2D 45 6E 67 69 6E\r2: 65 43 6F 6E 74 72\r3: 6F 6C 00 00 00 00"
            else -> return "NO DATA"
        }

        val withHeader = if (headersEnabled) "7E8 06 $rawHex" else rawHex
        return if (spacesEnabled) withHeader else withHeader.replace(" ", "")
    }

    private fun singleMode01PidPayload(pidHex: String): String? {
        val t = System.currentTimeMillis() / 1000.0
        val rpmRaw = ((1800 + 600 * sin(t * 0.5)) * 4).toInt().coerceIn(2800, 16000)
        val speedKmh = (55 + 20 * sin(t * 0.25)).toInt().coerceIn(0, 160)

        return when (pidHex) {
            // Supported PIDs bitmasks
            "00" -> "BE 3F B8 13"
            "20" -> "A0 05 A0 11"
            "40" -> "7A D0 00 00"
            "60" -> "00 00 00 00"
            "80" -> "00 00 00 00"
            "A0" -> "00 00 00 00"

            // Common Mode 01 PIDs
            "01" -> "82 07 65 04" // MIL ON, 2 DTCs
            "03" -> "02 00"       // Closed loop
            "04" -> "5A"          // Engine load ~35%
            "05" -> "82"          // Coolant temp 90°C (194°F)
            "06" -> "82"          // STFT Bank 1 (+1.6%)
            "07" -> "7E"          // LTFT Bank 1 (-1.6%)
            "08" -> "81"          // STFT Bank 2 (+0.8%)
            "09" -> "80"          // LTFT Bank 2 (0.0%)
            "0A" -> "64"          // Fuel pressure 300 kPa
            "0B" -> "62"          // Intake manifold pressure 98 kPa
            "0C" -> "%02X %02X".format((rpmRaw shr 8) and 0xFF, rpmRaw and 0xFF)
            "0D" -> "%02X".format(speedKmh and 0xFF)
            "0E" -> "98"          // Timing advance +12°
            "0F" -> "44"          // Intake air temp 28°C (82.4°F)
            "10" -> "05 DC"       // MAF 15.00 g/s
            "11" -> "40"          // Throttle position 25%
            "13" -> "03"          // O2 sensors B1S1, B1S2
            "14" -> "90 80"       // O2 B1S1 0.720V, 0.0% STFT
            "15" -> "88 FF"       // O2 B1S2 0.680V, STFT unused
            "1C" -> "01"          // OBD-II (CARB)
            "1F" -> "02 58"       // Run time 600s
            "21" -> "00 2A"       // Distance with MIL on 42 km
            "23" -> "01 F4"       // Fuel rail pressure (abs) 5000 kPa (~725 psi)
            "2E" -> "33"          // Commanded evap purge 20%
            "2F" -> "B8"          // Fuel level ~72%
            "30" -> "0C"          // 12 warm-ups since codes cleared
            "31" -> "04 B0"       // 1200 km since codes cleared
            "33" -> "65"          // Barometric pressure 101 kPa (~14.6 psi)
            "3C" -> "18 9C"       // Catalyst temp B1S1 590°C
            "3D" -> "18 38"       // Catalyst temp B2S1 580°C
            "41" -> "00 07 65 00" // Drive cycle readiness: all supported complete
            "42" -> "37 78"       // Control module voltage 14.200V
            "43" -> "00 60"       // Absolute load ~37.6%
            "44" -> "80 00"       // Commanded equivalence ratio 1.00
            "45" -> "28"          // Relative throttle ~15.7%
            "46" -> "41"          // Ambient air temp 25°C (77°F)
            "47" -> "42"          // Absolute throttle B ~25.9%
            "49" -> "38"          // Accelerator pedal D ~22.0%
            "4A" -> "38"          // Accelerator pedal E ~22.0%
            "4C" -> "30"          // Commanded throttle actuator ~18.8%
            "5C" -> "87"          // Engine oil temp 95°C (203°F)
            else -> null
        }
    }

    private fun freezeFramePidPayload(pidHex: String): String? {
        return when (pidHex) {
            "04" -> "70"    // Engine load ~43.9%
            "05" -> "80"    // Coolant temp 88°C (190°F)
            "06" -> "98"    // STFT B1 +18.8%
            "07" -> "94"    // LTFT B1 +15.6%
            "0B" -> "55"    // MAP 85 kPa (~12.3 psi)
            "0C" -> "23 F0" // RPM 2300
            "0D" -> "48"    // Speed 72 km/h (~45 mph)
            "0F" -> "46"    // IAT 30°C (86°F)
            else -> null
        }
    }
}
