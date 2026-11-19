package com.obd2logger.data.protocol

import com.obd2logger.domain.model.DecodedDtc

/**
 * Decodes SAE J2012 / ISO 15031-6 Diagnostic Trouble Codes from Mode 03, 07, 0A, and 02 responses.
 */
object DtcDecoder {

    private val TYPE_CHARS = charArrayOf('P', 'C', 'B', 'U')
    private const val HEX_DIGITS = "0123456789ABCDEF"

    /**
     * Decodes a 2-byte OBD-II DTC pair into a [DecodedDtc], or null if the bytes are `00 00` padding.
     */
    fun decodeTwoBytes(highByte: Int, lowByte: Int): DecodedDtc? {
        val a = highByte and 0xFF
        val b = lowByte and 0xFF
        if (a == 0 && b == 0) return null

        val prefix = TYPE_CHARS[(a ushr 6) and 0x03]
        val d1 = (a ushr 4) and 0x03
        val d2 = HEX_DIGITS[a and 0x0F]
        val d3 = HEX_DIGITS[(b ushr 4) and 0x0F]
        val d4 = HEX_DIGITS[b and 0x0F]
        val code = "$prefix$d1$d2$d3$d4"
        return DecodedDtc(code = code, description = SaeDtcTable.lookup(code))
    }

    /**
     * Decodes one or more ECU payloads returned by [ElmResponseParser.parseObdResponse] for
     * Mode 03 (stored), Mode 07 (pending), or Mode 0A (permanent).
     *
     * Handles both CAN ISO 15765-4 format (`<count> <dtc1_hi> <dtc1_lo> ...`) and legacy
     * ISO 9141 / KWP2000 / J1850 6-byte format (`<dtc1_hi> <dtc1_lo> ...`).
     */
    fun decodeDtcPayloads(
        ecuPayloads: List<ByteArray>,
        isCanProtocol: Boolean? = null
    ): List<DecodedDtc> {
        if (ecuPayloads.isEmpty()) return emptyList()

        val unique = linkedMapOf<String, DecodedDtc>()
        for (payload in ecuPayloads) {
            if (payload.isEmpty()) continue
            val hasCountByte = when {
                isCanProtocol == true -> true
                payload.size % 2 == 1 -> true
                else -> false
            }

            val startOffset: Int
            val maxPairs: Int
            if (hasCountByte) {
                val declaredCount = payload[0].toInt() and 0xFF
                startOffset = 1
                val availablePairs = (payload.size - 1) / 2
                maxPairs = minOf(declaredCount, availablePairs)
            } else {
                startOffset = 0
                maxPairs = payload.size / 2
            }

            for (i in 0 until maxPairs) {
                val idx = startOffset + (i * 2)
                val dtc = decodeTwoBytes(
                    highByte = payload[idx].toInt(),
                    lowByte = payload[idx + 1].toInt()
                )
                if (dtc != null && dtc.code !in unique) {
                    unique[dtc.code] = dtc
                }
            }
        }
        return unique.values.toList()
    }

    /**
     * Formats a list of DTCs as semicolon-separated `CODE:short description`, or `"-"` if empty.
     */
    fun formatForCsv(dtcs: List<DecodedDtc>): String {
        if (dtcs.isEmpty()) return "-"
        return dtcs.joinToString(";") { it.toCsvToken() }
    }
}
