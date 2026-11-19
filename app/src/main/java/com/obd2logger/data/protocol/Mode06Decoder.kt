package com.obd2logger.data.protocol

import com.obd2logger.domain.model.Mode06Phase
import com.obd2logger.domain.model.Mode06ResultStatus
import com.obd2logger.domain.model.Mode06TestResult
import java.util.Locale

/**
 * Decodes SAE J1979 Mode 06 (On-Board Monitoring Test Results) responses for both:
 * - CAN (ISO 15765-4): 9-byte records `[MID, TID, UASID, ValHi, ValLo, MinHi, MinLo, MaxHi, MaxLo]`
 * - Legacy non-CAN (ISO 9141 / KWP2000 / J1850): 6-byte records `[TID, CID, ValHi, ValLo, LimHi, LimLo]`
 */
object Mode06Decoder {

    private const val LIMIT_EPSILON = 1e-6

    fun decodeSupportedMids(baseMid: Int, ecuPayloads: List<ByteArray>): Set<Int> {
        val valid = ecuPayloads.filter { it.size >= 4 }
        if (valid.isEmpty()) return emptySet()
        val combined = ByteArray(4)
        for (ecu in valid) {
            for (i in 0 until 4) {
                combined[i] = (combined[i].toInt() or ecu[i].toInt()).toByte()
            }
        }
        return ElmResponseParser.decodeSupportedPidsBitmask(baseMid, combined)
    }

    fun decodeMidPayloads(
        requestedMid: Int,
        ecuPayloads: List<ByteArray>,
        isCanProtocol: Boolean?,
        phase: Mode06Phase,
        timestampMs: Long
    ): List<Mode06TestResult> {
        if (ecuPayloads.isEmpty()) return emptyList()
        val out = mutableListOf<Mode06TestResult>()

        for (payload in ecuPayloads) {
            if (payload.isEmpty()) continue
            val useCan = when {
                isCanProtocol == true -> true
                payload.size >= 9 && (payload[0].toInt() and 0xFF) == (requestedMid and 0xFF) &&
                    payload.size % 9 == 0 -> true
                isCanProtocol == false -> false
                payload.size % 6 == 0 -> false
                else -> payload.size >= 9
            }

            if (useCan) {
                var offset = 0
                while (offset + 9 <= payload.size) {
                    val chunk = payload.copyOfRange(offset, offset + 9)
                    out.add(decodeCanRecord(chunk, phase, timestampMs))
                    offset += 9
                }
            } else {
                var offset = 0
                while (offset + 6 <= payload.size) {
                    val chunk = payload.copyOfRange(offset, offset + 6)
                    out.add(decodeLegacyRecord(requestedMid, chunk, phase, timestampMs))
                    offset += 6
                }
            }
        }
        return out
    }

    private fun decodeCanRecord(
        record: ByteArray,
        phase: Mode06Phase,
        timestampMs: Long
    ): Mode06TestResult {
        val mid = record[0].toInt() and 0xFF
        val tid = record[1].toInt() and 0xFF
        val uasid = record[2].toInt() and 0xFF
        val rawHex = record.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

        val uasidDef = Mode06Tables.uasidDefinition(uasid)
        val rawVal = ((record[3].toInt() and 0xFF) shl 8) or (record[4].toInt() and 0xFF)
        val rawMin = ((record[5].toInt() and 0xFF) shl 8) or (record[6].toInt() and 0xFF)
        val rawMax = ((record[7].toInt() and 0xFF) shl 8) or (record[8].toInt() and 0xFF)

        val unit: String
        val valStr: String
        val minStr: String
        val maxStr: String
        val result: Mode06ResultStatus

        if (uasidDef == null) {
            unit = "Unknown"
            valStr = rawVal.toString()
            minStr = rawMin.toString()
            maxStr = rawMax.toString()
            result = Mode06ResultStatus.UNKNOWN
        } else {
            val scaledVal = uasidDef.scale(record[3].toInt(), record[4].toInt())
            val scaledMin = uasidDef.scale(record[5].toInt(), record[6].toInt())
            val scaledMax = uasidDef.scale(record[7].toInt(), record[8].toInt())

            unit = uasidDef.unit
            valStr = formatScaled(scaledVal, uasidDef.decimals)
            minStr = formatScaled(scaledMin, uasidDef.decimals)
            maxStr = formatScaled(scaledMax, uasidDef.decimals)

            result = when {
                !scaledVal.isFinite() || !scaledMin.isFinite() || !scaledMax.isFinite() ->
                    Mode06ResultStatus.UNKNOWN
                scaledMin > scaledMax + LIMIT_EPSILON ->
                    Mode06ResultStatus.UNKNOWN
                scaledVal >= scaledMin - LIMIT_EPSILON && scaledVal <= scaledMax + LIMIT_EPSILON ->
                    Mode06ResultStatus.PASS
                else ->
                    Mode06ResultStatus.FAIL
            }
        }

        return Mode06TestResult(
            timestampMs = timestampMs,
            phase = phase,
            midHex = "%02X".format(mid),
            midName = Mode06Tables.midName(mid),
            tidHex = "%02X".format(tid),
            tidName = Mode06Tables.tidName(tid),
            uasidHex = "%02X".format(uasid),
            unit = unit,
            testValue = valStr,
            minLimit = minStr,
            maxLimit = maxStr,
            result = result,
            rawHex = rawHex
        )
    }

    /**
     * Legacy non-CAN format (`[TID, CID, ValHi, ValLo, LimHi, LimLo]`):
     * Bit 7 of CID indicates Min Limit (`1`) vs Max Limit (`0`).
     */
    private fun decodeLegacyRecord(
        requestedMid: Int,
        record: ByteArray,
        phase: Mode06Phase,
        timestampMs: Long
    ): Mode06TestResult {
        val tid = record[0].toInt() and 0xFF
        val cid = record[1].toInt() and 0xFF
        val isMinLimit = (cid and 0x80) != 0
        val rawVal = ((record[2].toInt() and 0xFF) shl 8) or (record[3].toInt() and 0xFF)
        val rawLimit = ((record[4].toInt() and 0xFF) shl 8) or (record[5].toInt() and 0xFF)
        val rawHex = record.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

        val passed = if (isMinLimit) rawVal >= rawLimit else rawVal <= rawLimit

        return Mode06TestResult(
            timestampMs = timestampMs,
            phase = phase,
            midHex = "%02X".format(requestedMid and 0xFF),
            midName = Mode06Tables.midName(requestedMid),
            tidHex = "%02X".format(tid),
            tidName = Mode06Tables.tidName(tid),
            uasidHex = "-",
            unit = "Unknown",
            testValue = rawVal.toString(),
            minLimit = if (isMinLimit) rawLimit.toString() else "-",
            maxLimit = if (!isMinLimit) rawLimit.toString() else "-",
            result = if (passed) Mode06ResultStatus.PASS else Mode06ResultStatus.FAIL,
            rawHex = rawHex
        )
    }

    private fun formatScaled(value: Double, decimals: Int): String {
        return if (decimals <= 0) {
            "%.0f".format(Locale.US, value)
        } else {
            "%.${decimals}f".format(Locale.US, value)
        }
    }
}
