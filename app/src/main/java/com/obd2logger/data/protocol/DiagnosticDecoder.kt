package com.obd2logger.data.protocol

import com.obd2logger.domain.model.IgnitionType
import com.obd2logger.domain.model.MonitorKey
import com.obd2logger.domain.model.MonitorStatus
import com.obd2logger.domain.model.ReadinessSnapshot

/**
 * Decoders for SAE J1979 Mode 01 readiness/status PIDs, Mode 02 freeze frames,
 * Mode 09 vehicle information, and ELM327 AT responses.
 */
object DiagnosticDecoder {

    /**
     * Decodes Mode 01 PID 01 (`41 01 A B C D`):
     * Returns `Pair(Pair(milOn, dtcCount), ReadinessSnapshot)` or null if `< 4` bytes.
     */
    fun decodeReadiness0101(dataBytes: ByteArray): Pair<Pair<Boolean, Int>, ReadinessSnapshot>? {
        if (dataBytes.size < 4) return null
        val a = dataBytes[0].toInt() and 0xFF
        val milOn = (a and 0x80) != 0
        val dtcCount = a and 0x7F
        val snapshot = decodeReadinessBytes(
            b = dataBytes[1].toInt() and 0xFF,
            c = dataBytes[2].toInt() and 0xFF,
            d = dataBytes[3].toInt() and 0xFF
        )
        return (milOn to dtcCount) to snapshot
    }

    /**
     * Decodes Mode 01 PID 41 (`41 41 A B C D`) for this drive cycle readiness monitors.
     */
    fun decodeReadiness0141(dataBytes: ByteArray): ReadinessSnapshot? {
        if (dataBytes.size < 4) return null
        return decodeReadinessBytes(
            b = dataBytes[1].toInt() and 0xFF,
            c = dataBytes[2].toInt() and 0xFF,
            d = dataBytes[3].toInt() and 0xFF
        )
    }

    private fun decodeReadinessBytes(b: Int, c: Int, d: Int): ReadinessSnapshot {
        val isCompression = (b and 0x08) != 0
        val ignitionType = if (isCompression) IgnitionType.COMPRESSION else IgnitionType.SPARK

        fun continuousStatus(availBit: Int, incompleteBit: Int): MonitorStatus {
            val available = (b and (1 shl availBit)) != 0
            if (!available) return MonitorStatus.NA
            val incomplete = (b and (1 shl incompleteBit)) != 0
            return if (incomplete) MonitorStatus.INCOMPLETE else MonitorStatus.COMPLETE
        }

        fun nonContinuousStatus(bit: Int): MonitorStatus {
            val available = (c and (1 shl bit)) != 0
            if (!available) return MonitorStatus.NA
            val incomplete = (d and (1 shl bit)) != 0
            return if (incomplete) MonitorStatus.INCOMPLETE else MonitorStatus.COMPLETE
        }

        val monitors = linkedMapOf(
            MonitorKey.MISFIRE to continuousStatus(availBit = 0, incompleteBit = 4),
            MonitorKey.FUEL_SYSTEM to continuousStatus(availBit = 1, incompleteBit = 5),
            MonitorKey.COMPONENTS to continuousStatus(availBit = 2, incompleteBit = 6),
            MonitorKey.CATALYST to nonContinuousStatus(0),
            MonitorKey.HEATED_CATALYST to nonContinuousStatus(1),
            MonitorKey.EVAP to nonContinuousStatus(2),
            MonitorKey.SECONDARY_AIR to nonContinuousStatus(3),
            MonitorKey.AC_REFRIGERANT to nonContinuousStatus(4),
            MonitorKey.O2_SENSOR to nonContinuousStatus(5),
            MonitorKey.O2_HEATER to nonContinuousStatus(6),
            MonitorKey.EGR_VVT to nonContinuousStatus(7)
        )
        return ReadinessSnapshot(monitors = monitors, ignitionType = ignitionType)
    }

    /**
     * Decodes Mode 09 ASCII responses such as VIN (`0902`), Calibration ID (`0904`), or ECU Name (`090A`).
     * Supports both ISO-TP CAN single payload (`[nodiByte, ...ascii...]`) and legacy multi-line payloads
     * (`[lineIdx, b1, b2, b3, b4]`).
     */
    fun decodeMode09Ascii(ecuPayloads: List<ByteArray>, isVin: Boolean = false): String? {
        if (ecuPayloads.isEmpty()) return null

        val rawAsciiBytes: ByteArray = if (ecuPayloads.size == 1) {
            val bytes = ecuPayloads.first()
            if (bytes.isEmpty()) return null
            // Skip NODI / message count byte (typically 0x01..0x0F) when present before ASCII data
            if (bytes.size > 1 && (bytes[0].toInt() and 0xFF) < 0x20) {
                bytes.copyOfRange(1, bytes.size)
            } else {
                bytes
            }
        } else {
            // Legacy multi-line: each payload starts with a 1-byte message sequence index (01, 02, 03...)
            val out = mutableListOf<Byte>()
            for (line in ecuPayloads) {
                val start = if (line.size > 1 && (line[0].toInt() and 0xFF) < 0x20) 1 else 0
                for (i in start until line.size) {
                    out.add(line[i])
                }
            }
            out.toByteArray()
        }

        if (isVin) {
            val vin = buildString {
                for (byte in rawAsciiBytes) {
                    val ch = (byte.toInt() and 0xFF).toChar()
                    if (ch in '0'..'9' || ch in 'A'..'Z' || ch in 'a'..'z') {
                        append(ch.uppercaseChar())
                    }
                }
            }
            return vin.takeIf { it.length >= 5 }
        }

        // For Cal ID / ECU Name, split null-separated entries (e.g. multiple Cal IDs) with ';'
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        for (byte in rawAsciiBytes) {
            val code = byte.toInt() and 0xFF
            if (code == 0x00) {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString().trim())
                    current.clear()
                }
            } else if (code in 0x20..0x7E) {
                current.append(code.toChar())
            }
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString().trim())
        }
        val cleaned = tokens.filter { it.isNotEmpty() }.distinct()
        return if (cleaned.isEmpty()) null else cleaned.joinToString(";")
    }

    /**
     * Decodes Mode 09 PID 06 (CVN - Calibration Verification Number), formatted as 8-char uppercase hex blocks.
     */
    fun decodeMode09Cvn(ecuPayloads: List<ByteArray>): String? {
        if (ecuPayloads.isEmpty()) return null

        val cvnBytes: ByteArray = if (ecuPayloads.size == 1) {
            val bytes = ecuPayloads.first()
            if (bytes.isEmpty()) return null
            if (bytes.size >= 5 && (bytes[0].toInt() and 0xFF) <= 0x10 && (bytes.size - 1) % 4 == 0) {
                bytes.copyOfRange(1, bytes.size)
            } else if (bytes.size > 4 && (bytes[0].toInt() and 0xFF) < 0x10) {
                bytes.copyOfRange(1, bytes.size)
            } else {
                bytes
            }
        } else {
            val out = mutableListOf<Byte>()
            for (line in ecuPayloads) {
                val start = if (line.size == 5) 1 else 0
                for (i in start until line.size) {
                    out.add(line[i])
                }
            }
            out.toByteArray()
        }

        if (cvnBytes.isEmpty()) return null
        val chunks = mutableListOf<String>()
        var idx = 0
        while (idx + 4 <= cvnBytes.size) {
            val hex = (0 until 4).joinToString("") { "%02X".format(cvnBytes[idx + it].toInt() and 0xFF) }
            if (hex != "00000000") {
                chunks.add(hex)
            }
            idx += 4
        }
        if (chunks.isEmpty() && cvnBytes.isNotEmpty()) {
            val fallback = cvnBytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            if (fallback.any { it != '0' }) chunks.add(fallback)
        }
        return if (chunks.isEmpty()) null else chunks.distinct().joinToString(";")
    }

    /**
     * Extracts freeze frame payload bytes from a Mode 02 response (`42 <PID> [<frame#>] <data...>`).
     * Strips the frame number byte `0x00` when present.
     */
    fun decodeFreezeFramePidPayload(dataBytes: ByteArray, expectedBytes: Int): ByteArray? {
        if (dataBytes.size >= expectedBytes + 1) {
            return dataBytes.copyOfRange(1, 1 + expectedBytes)
        }
        if (dataBytes.size == expectedBytes) {
            return dataBytes
        }
        return null
    }

    /**
     * Decodes Mode 01 PID 1C (OBD standards this vehicle conforms to).
     */
    fun decodeObdStandard(codeByte: Int): String {
        return when (codeByte and 0xFF) {
            0x01 -> "OBD-II (CARB)"
            0x02 -> "OBD (EPA)"
            0x03 -> "OBD and OBD-II"
            0x04 -> "OBD-I"
            0x05 -> "Not OBD compliant"
            0x06 -> "EOBD (Europe)"
            0x07 -> "EOBD and OBD-II"
            0x08 -> "EOBD and OBD"
            0x09 -> "EOBD, OBD and OBD II"
            0x0A -> "JOBD (Japan)"
            0x0B -> "JOBD and OBD II"
            0x0C -> "JOBD and EOBD"
            0x0D -> "JOBD, EOBD, and OBD II"
            0x11 -> "EMD"
            0x12 -> "EMD+"
            0x13 -> "HD OBD-C"
            0x14 -> "HD OBD"
            0x15 -> "WWH OBD"
            0x17 -> "HD EOBD-I"
            0x18 -> "HD EOBD-I N"
            0x19 -> "HD EOBD-II"
            0x1A -> "HD EOBD-II N"
            0x1C -> "OBDBr-1"
            0x1D -> "OBDBr-2"
            else -> "OBD Standard 0x%02X".format(codeByte and 0xFF)
        }
    }

    /**
     * Decodes Mode 01 PID 03 Fuel System Status (single byte A or B).
     */
    fun decodeFuelStatus(byteVal: Int): String {
        return when (byteVal and 0xFF) {
            0x00 -> "-"
            0x01 -> "Open loop (insufficient temp)"
            0x02 -> "Closed loop (O2 sensor)"
            0x04 -> "Open loop (engine load/decel)"
            0x08 -> "Open loop (system failure)"
            0x10 -> "Closed loop (fault)"
            else -> "Status 0x%02X".format(byteVal and 0xFF)
        }
    }

    /**
     * Decodes Mode 01 PID 51 Fuel Type.
     */
    fun decodeFuelType(byteVal: Int): String {
        return when (byteVal and 0xFF) {
            0x01 -> "Gasoline"
            0x02 -> "Methanol"
            0x03 -> "Ethanol"
            0x04 -> "Diesel"
            0x05 -> "LPG"
            0x06 -> "CNG"
            0x07 -> "Propane"
            0x08 -> "Electric"
            0x09 -> "Bifuel Gasoline"
            0x0A -> "Bifuel Methanol"
            0x0B -> "Bifuel Ethanol"
            0x0C -> "Bifuel LPG"
            0x0D -> "Bifuel CNG"
            0x0E -> "Bifuel Propane"
            0x0F -> "Bifuel Electricity"
            0x10 -> "Bifuel Electric/Combustion"
            0x11 -> "Hybrid Gasoline"
            0x12 -> "Hybrid Ethanol"
            0x13 -> "Hybrid Diesel"
            0x14 -> "Hybrid Electric"
            0x15 -> "Hybrid Electric/Combustion"
            0x16 -> "Hybrid Regenerative"
            0x17 -> "Bifuel Diesel"
            else -> "-"
        }
    }

    /**
     * Decodes Mode 01 PID 12 Commanded Secondary Air Status.
     */
    fun decodeSecondaryAirStatus(byteVal: Int): String {
        return when (byteVal and 0xFF) {
            0x01 -> "Upstream"
            0x02 -> "Downstream of catalytic converter"
            0x04 -> "From outside atmosphere or off"
            0x08 -> "Pump commanded on for diagnostics"
            else -> "-"
        }
    }

    /**
     * Decodes Mode 01 PID 13 (O2 sensors present in 2 banks).
     */
    fun decodeO2Locations2Bank(byteVal: Int): String {
        val v = byteVal and 0xFF
        if (v == 0) return "-"
        val labels = listOf("B1S1", "B1S2", "B1S3", "B1S4", "B2S1", "B2S2", "B2S3", "B2S4")
        val present = labels.indices.filter { (v and (1 shl it)) != 0 }.map { labels[it] }
        return if (present.isEmpty()) "-" else present.joinToString(" ")
    }

    /**
     * Decodes Mode 01 PID 1D (O2 sensors present in 4 banks).
     */
    fun decodeO2Locations4Bank(byteVal: Int): String {
        val v = byteVal and 0xFF
        if (v == 0) return "-"
        val labels = listOf("B1S1", "B1S2", "B2S1", "B2S2", "B3S1", "B3S2", "B4S1", "B4S2")
        val present = labels.indices.filter { (v and (1 shl it)) != 0 }.map { labels[it] }
        return if (present.isEmpty()) "-" else present.joinToString(" ")
    }

    /**
     * Extracts numeric voltage string from an ELM327 `ATRV` reply (e.g. `"14.2V"` -> `"14.2"`).
     */
    fun decodeAdapterVoltage(rawReply: String): String? {
        val lines = ElmResponseParser.cleanLines(rawReply, "ATRV")
        if (lines.isEmpty() || ElmResponseParser.detectError(lines) != null) return null
        val match = Regex("(\\d+(?:\\.\\d+)?)").find(lines.first()) ?: return null
        return match.groupValues[1]
    }

    /**
     * Extracts cleaned protocol string from an `ATDP` reply.
     */
    fun decodeProtocol(rawReply: String): String? {
        val lines = ElmResponseParser.cleanLines(rawReply, "ATDP")
        if (lines.isEmpty() || ElmResponseParser.detectError(lines) != null) return null
        val joined = lines.joinToString(" ").trim()
        return joined.takeIf { it.isNotEmpty() && !it.equals("OK", ignoreCase = true) }
    }
}
