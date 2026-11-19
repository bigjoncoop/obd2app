package com.obd2logger.data.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDecoderTest {

    @Test
    fun `decodeTwoBytes decodes P, C, B, U prefixes accurately and skips 00 00 padding`() {
        assertNull(DtcDecoder.decodeTwoBytes(0x00, 0x00))

        val p0171 = DtcDecoder.decodeTwoBytes(0x01, 0x71)
        assertNotNull(p0171)
        assertEquals("P0171", p0171!!.code)
        assertEquals("System Too Lean (Bank 1)", p0171.description)

        val c0123 = DtcDecoder.decodeTwoBytes(0x41, 0x23)
        assertNotNull(c0123)
        assertEquals("C0123", c0123!!.code)

        val b0100 = DtcDecoder.decodeTwoBytes(0x81, 0x00)
        assertNotNull(b0100)
        assertEquals("B0100", b0100!!.code)

        val u0100 = DtcDecoder.decodeTwoBytes(0xC1, 0x00)
        assertNotNull(u0100)
        assertEquals("U0100", u0100!!.code)
        assertEquals("Lost Communication With ECM/PCM A", u0100.description)
    }

    @Test
    fun `decodeDtcPayloads handles CAN count-byte format, legacy 6-byte format, and multi-ECU deduplication`() {
        // CAN format: 02 DTCs -> P0171, P0420
        val canPayload = byteArrayOf(0x02, 0x01, 0x71, 0x04, 0x20)
        val canDtcs = DtcDecoder.decodeDtcPayloads(listOf(canPayload), isCanProtocol = true)
        assertEquals(listOf("P0171", "P0420"), canDtcs.map { it.code })

        // CAN format with 0 DTCs and padding
        val canEmpty = byteArrayOf(0x00, 0x00, 0x00)
        assertTrue(DtcDecoder.decodeDtcPayloads(listOf(canEmpty), isCanProtocol = true).isEmpty())

        // Legacy non-CAN 6-byte format (no count byte)
        val legacyPayload = byteArrayOf(0x03, 0x00, 0x04, 0x20, 0x00, 0x00)
        val legacyDtcs = DtcDecoder.decodeDtcPayloads(listOf(legacyPayload), isCanProtocol = false)
        assertEquals(listOf("P0300", "P0420"), legacyDtcs.map { it.code })

        // Multi-ECU with duplicate P0171 across ECM and TCM
        val ecu1 = byteArrayOf(0x02, 0x01, 0x71, 0x07, 0x00)
        val ecu2 = byteArrayOf(0x01, 0x01, 0x71)
        val multiDtcs = DtcDecoder.decodeDtcPayloads(listOf(ecu1, ecu2), isCanProtocol = true)
        assertEquals(listOf("P0171", "P0700"), multiDtcs.map { it.code })
    }

    @Test
    fun `formatForCsv formats semicolon-separated CODE colon description or dash when empty`() {
        assertEquals("-", DtcDecoder.formatForCsv(emptyList()))

        val dtcs = DtcDecoder.decodeDtcPayloads(
            listOf(byteArrayOf(0x02, 0x01, 0x71, 0x04, 0x20)),
            isCanProtocol = true
        )
        assertEquals(
            "P0171:System Too Lean (Bank 1);P0420:Catalyst System Efficiency Below Threshold (Bank 1)",
            DtcDecoder.formatForCsv(dtcs)
        )
    }

    @Test
    fun `SaeDtcTable covers large generic set and provides fallback for manufacturer codes`() {
        assertTrue(SaeDtcTable.CODES.size >= 200)
        assertEquals("Random/Multiple Cylinder Misfire Detected", SaeDtcTable.lookup("p0300"))
        assertEquals("Manufacturer-Specific Powertrain Code", SaeDtcTable.lookup("P1456"))
        assertEquals("Manufacturer-Specific Chassis Code", SaeDtcTable.lookup("C1234"))
    }
}
