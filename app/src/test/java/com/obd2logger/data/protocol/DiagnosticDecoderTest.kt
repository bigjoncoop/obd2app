package com.obd2logger.data.protocol

import com.obd2logger.domain.model.IgnitionType
import com.obd2logger.domain.model.MonitorKey
import com.obd2logger.domain.model.MonitorStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticDecoderTest {

    @Test
    fun `decodeReadiness0101 decodes MIL status, DTC count, ignition type, and monitors`() {
        // 82 07 65 04 -> MIL ON, 2 DTCs, Spark, Misfire/Fuel/Components Complete,
        // Cat/O2/O2Heater Complete, Evap Incomplete, others N/A
        val bytes = byteArrayOf(0x82.toByte(), 0x07, 0x65, 0x04)
        val decoded = DiagnosticDecoder.decodeReadiness0101(bytes)
        assertNotNull(decoded)
        val (milPair, readiness) = decoded!!
        assertTrue(milPair.first)
        assertEquals(2, milPair.second)
        assertEquals(IgnitionType.SPARK, readiness.ignitionType)
        assertEquals(MonitorStatus.COMPLETE.csvValue, readiness.statusOf(MonitorKey.MISFIRE))
        assertEquals(MonitorStatus.COMPLETE.csvValue, readiness.statusOf(MonitorKey.CATALYST))
        assertEquals(MonitorStatus.INCOMPLETE.csvValue, readiness.statusOf(MonitorKey.EVAP))
        assertEquals(MonitorStatus.NA.csvValue, readiness.statusOf(MonitorKey.SECONDARY_AIR))
    }

    @Test
    fun `decodeMode09Ascii and decodeMode09Cvn decode VIN, Cal ID, CVN, and ECU Name`() {
        val vinParsed = ElmResponseParser.parseObdResponse(
            rawResponse = "014\r0: 49 02 01 31 48 47\r1: 43 4D 38 32 36 33\r2: 33 41 30 30 34 33\r3: 35 32 00 00 00 00\r>",
            expectedService = 0x09,
            expectedPid = 0x02,
            sentCommand = "0902"
        ) as ElmParseResult.Success
        assertEquals("1HGCM82633A004352", DiagnosticDecoder.decodeMode09Ascii(vinParsed.ecuPayloads, isVin = true))

        val cvnPayload = listOf(byteArrayOf(0x01, 0x9A.toByte(), 0x3C, 0x7F, 0x21))
        assertEquals("9A3C7F21", DiagnosticDecoder.decodeMode09Cvn(cvnPayload))

        val ecuPayload = listOf(byteArrayOf(0x01) + "ECM-EngineControl\u0000\u0000".toByteArray(Charsets.US_ASCII))
        assertEquals("ECM-EngineControl", DiagnosticDecoder.decodeMode09Ascii(ecuPayload, isVin = false))
    }

    @Test
    fun `decodeFreezeFramePidPayload strips frame byte 0x00 and decodeAdapterVoltage cleans ATRV`() {
        val ffRpm = DiagnosticDecoder.decodeFreezeFramePidPayload(byteArrayOf(0x00, 0x23, 0xF0.toByte()), expectedBytes = 2)
        assertArrayEquals(byteArrayOf(0x23, 0xF0.toByte()), ffRpm)

        assertEquals("14.2", DiagnosticDecoder.decodeAdapterVoltage("ATRV\r14.2V\r>"))
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", DiagnosticDecoder.decodeProtocol("ATDP\rAUTO, ISO 15765-4 (CAN 11/500)\r>"))
        assertEquals("B1S1 B1S2", DiagnosticDecoder.decodeO2Locations2Bank(0x03))
    }
}
