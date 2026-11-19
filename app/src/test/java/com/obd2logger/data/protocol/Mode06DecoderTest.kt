package com.obd2logger.data.protocol

import com.obd2logger.domain.model.Mode06Phase
import com.obd2logger.domain.model.Mode06ResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mode06DecoderTest {

    @Test
    fun `decodeMidPayloads decodes CAN ISO-TP multi-frame response with multiple tests and PASS FAIL limits`() {
        // 2 tests under MID A2 (Misfire Cylinder 1):
        // Test 1: TID 0B (EWMA), UASID 01 (count, *1), val=2, min=0, max=655 -> PASS
        // Test 2: TID 0C (Current), UASID 01 (count, *1), val=800 (03 20), min=0, max=500 (01 F4) -> FAIL
        val raw = """
            013
            0: 46 A2 0B 01 00 02
            1: 00 00 02 8F A2 0C
            2: 01 03 20 00 00 01
            3: F4 00 00 00 00 00
            >
        """.trimIndent()

        val parsed = ElmResponseParser.parseObdResponse(
            rawResponse = raw,
            expectedService = 0x06,
            expectedPid = null,
            sentCommand = "06A2"
        ) as ElmParseResult.Success

        val tests = Mode06Decoder.decodeMidPayloads(
            requestedMid = 0xA2,
            ecuPayloads = parsed.ecuPayloads,
            isCanProtocol = true,
            phase = Mode06Phase.START,
            timestampMs = 1_700_000_000_000L
        )

        assertEquals(2, tests.size)

        val t1 = tests[0]
        assertEquals("A2", t1.midHex)
        assertEquals("Misfire Cylinder 1 Data", t1.midName)
        assertEquals("0B", t1.tidHex)
        assertEquals("01", t1.uasidHex)
        assertEquals("count", t1.unit)
        assertEquals("2", t1.testValue)
        assertEquals("0", t1.minLimit)
        assertEquals("655", t1.maxLimit)
        assertEquals(Mode06ResultStatus.PASS, t1.result)

        val t2 = tests[1]
        assertEquals("0C", t2.tidHex)
        assertEquals("800", t2.testValue)
        assertEquals("500", t2.maxLimit)
        assertEquals(Mode06ResultStatus.FAIL, t2.result)
    }

    @Test
    fun `UASID scaling decodes voltage, signed temperature, and unknown UASIDs safely`() {
        // MID 01 (O2 B1S1), TID 01, UASID 0B (0.001 V/bit): val=450mV (01 C2), min=350 (01 5E), max=600 (02 58)
        val o2Payload = byteArrayOf(
            0x01, 0x01, 0x0B,
            0x01, 0xC2.toByte(),
            0x01, 0x5E,
            0x02, 0x58
        )
        // Signed temp UASID 0x96 (0.1*raw - 40 C): raw=500 -> 10.0 C, min=-200 -> -60.0 C, max=1000 -> 60.0 C
        val signedTempPayload = byteArrayOf(
            0x21, 0x80.toByte(), 0x96.toByte(),
            0x01, 0xF4.toByte(),
            0xFF.toByte(), 0x38,
            0x03, 0xE8.toByte()
        )
        // Unknown MID FF, unknown TID 99, unknown UASID 7E
        val unknownPayload = byteArrayOf(
            0xFF.toByte(), 0x99.toByte(), 0x7E,
            0x00, 0x0A,
            0x00, 0x00,
            0x00, 0x14
        )

        val o2 = Mode06Decoder.decodeMidPayloads(0x01, listOf(o2Payload), true, Mode06Phase.START, 1L).single()
        assertEquals("V", o2.unit)
        assertEquals("0.450", o2.testValue)
        assertEquals("0.350", o2.minLimit)
        assertEquals("0.600", o2.maxLimit)
        assertEquals(Mode06ResultStatus.PASS, o2.result)

        val temp = Mode06Decoder.decodeMidPayloads(0x21, listOf(signedTempPayload), true, Mode06Phase.END, 1L).single()
        assertEquals("°C", temp.unit)
        assertEquals("10.0", temp.testValue)
        assertEquals("-60.0", temp.minLimit)
        assertEquals("60.0", temp.maxLimit)
        assertEquals(Mode06ResultStatus.PASS, temp.result)

        val unk = Mode06Decoder.decodeMidPayloads(0xFF, listOf(unknownPayload), true, Mode06Phase.START, 1L).single()
        assertEquals("Unknown", unk.midName)
        assertEquals("Unknown", unk.tidName)
        assertEquals("Unknown", unk.unit)
        assertEquals("10", unk.testValue)
        assertEquals("0", unk.minLimit)
        assertEquals("20", unk.maxLimit)
        assertEquals(Mode06ResultStatus.UNKNOWN, unk.result)
    }

    @Test
    fun `decodeMidPayloads supports legacy non-CAN 6-byte records and bitmask decoding`() {
        val legacyPass = byteArrayOf(0x05, 0x00, 0x00, 0x10, 0x00, 0x20) // Max limit 32 >= 16 -> PASS
        val legacyFail = byteArrayOf(0x06, 0x80.toByte(), 0x00, 0x05, 0x00, 0x10) // Min limit 16 > 5 -> FAIL

        val decoded = Mode06Decoder.decodeMidPayloads(
            requestedMid = 0x01,
            ecuPayloads = listOf(legacyPass, legacyFail),
            isCanProtocol = false,
            phase = Mode06Phase.START,
            timestampMs = 1L
        )
        assertEquals(2, decoded.size)
        assertEquals(Mode06ResultStatus.PASS, decoded[0].result)
        assertEquals(Mode06ResultStatus.FAIL, decoded[1].result)

        val mids = Mode06Decoder.decodeSupportedMids(
            0x00,
            listOf(byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x01))
        )
        assertTrue(0x01 in mids && 0x20 in mids)
    }
}
