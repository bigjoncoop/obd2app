package com.obd2logger.data.protocol

import com.obd2logger.data.transport.WifiTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmResponseParserTest {

    @Test
    fun `extractElmVersion parses standard and clone ATZ responses and rejects non-ELM replies`() {
        assertEquals(
            "ELM327 v1.5",
            ElmResponseParser.extractElmVersion("ATZ\r\rELM327 v1.5\r\r>")
        )
        assertEquals(
            "ELM327 v2.1",
            ElmResponseParser.extractElmVersion("\u0000\r\nELM327  v2.1\r\n>")
        )
        assertNull(ElmResponseParser.extractElmVersion("OK\r\n>"))
        assertNull(ElmResponseParser.extractElmVersion("?"))
    }

    @Test
    fun `parseObdResponse strips echo, spaces, SEARCHING, CR, LF, prompt, and null bytes`() {
        val raw = "0100\r\u0000SEARCHING...\r\n41 00 BE 3F B8 13 \r\n>"
        val result = ElmResponseParser.parseObdResponse(
            rawResponse = raw,
            expectedService = 0x01,
            expectedPid = 0x00,
            sentCommand = "0100"
        )
        assertTrue(result is ElmParseResult.Success)
        val success = result as ElmParseResult.Success
        assertEquals(0x01, success.service)
        assertEquals(0x00, success.pid)
        assertArrayEquals(
            byteArrayOf(0xBE.toByte(), 0x3F.toByte(), 0xB8.toByte(), 0x13.toByte()),
            success.dataBytes
        )
    }

    @Test
    fun `parseObdResponse handles multi-ECU 41 00 responses by bitwise-ORing bitmasks`() {
        val raw = """
            0100
            SEARCHING...
            41 00 BE 1F A8 13
            41 00 01 20 10 01
            >
        """.trimIndent()

        val result = ElmResponseParser.parseObdResponse(
            rawResponse = raw,
            expectedService = 0x01,
            expectedPid = 0x00,
            sentCommand = "0100"
        )
        assertTrue(result is ElmParseResult.Success)
        val success = result as ElmParseResult.Success
        assertEquals(2, success.ecuPayloads.size)
        assertArrayEquals(
            byteArrayOf(0xBF.toByte(), 0x3F.toByte(), 0xB8.toByte(), 0x13.toByte()),
            success.dataBytes
        )
    }

    @Test
    fun `parseObdResponse reassembles ISO-TP multi-frame responses with length header`() {
        val raw = """
            0902
            014
            0: 49 02 01 31 48 47
            1: 43 4D 38 32 36 33
            2: 33 41 30 30 34 33
            3: 35 32 00 00 00 00
            >
        """.trimIndent()

        val result = ElmResponseParser.parseObdResponse(
            rawResponse = raw,
            expectedService = 0x09,
            expectedPid = 0x02,
            sentCommand = "0902"
        )
        assertTrue(result is ElmParseResult.Success)
        val success = result as ElmParseResult.Success
        // Total length 0x14 = 20 bytes; minus 2 header bytes (49 02) = 18 payload bytes (01 + 17 ASCII VIN chars)
        assertEquals(18, success.dataBytes.size)
        assertEquals(0x01, success.dataBytes[0].toInt() and 0xFF)
        val vin = String(success.dataBytes.copyOfRange(1, 18), Charsets.US_ASCII)
        assertEquals("1HGCM82633A004352", vin)
    }

    @Test
    fun `parseObdResponse reassembles ISO-TP Mode 03 multi-frame without PID byte`() {
        val raw = """
            008
            0: 43 03 01 33 01 71
            1: 04 20 00 00 00 00
            >
        """.trimIndent()

        val result = ElmResponseParser.parseObdResponse(
            rawResponse = raw,
            expectedService = 0x03,
            expectedPid = null,
            sentCommand = "03"
        )
        assertTrue(result is ElmParseResult.Success)
        val success = result as ElmParseResult.Success
        // 8 bytes total - 1 byte service header (43) = 7 payload bytes
        assertArrayEquals(
            byteArrayOf(0x03, 0x01, 0x33, 0x01, 0x71, 0x04, 0x20),
            success.dataBytes
        )
    }

    @Test
    fun `parseObdResponse detects all ELM327 error strings`() {
        val cases = listOf(
            "NO DATA\r\n>" to ElmErrorType.NO_DATA,
            "?\r\n>" to ElmErrorType.UNKNOWN_COMMAND,
            "SEARCHING...\r\nUNABLE TO CONNECT\r\n>" to ElmErrorType.UNABLE_TO_CONNECT,
            "BUS BUSY\r\n>" to ElmErrorType.BUS_BUSY,
            "CAN ERROR\r\n>" to ElmErrorType.CAN_ERROR,
            "STOPPED\r\n>" to ElmErrorType.STOPPED,
            "BUFFER FULL\r\n>" to ElmErrorType.BUFFER_FULL,
            "BUS INIT: ...ERROR\r\n>" to ElmErrorType.BUS_INIT_ERROR,
            "BUS INIT: ERROR\r\n>" to ElmErrorType.BUS_INIT_ERROR
        )

        for ((raw, expectedError) in cases) {
            val result = ElmResponseParser.parseObdResponse(
                rawResponse = raw,
                expectedService = 0x01,
                expectedPid = 0x00,
                sentCommand = "0100"
            )
            assertTrue("Expected Error for '$raw' but got $result", result is ElmParseResult.Error)
            assertEquals(expectedError, (result as ElmParseResult.Error).errorType)
        }
    }

    @Test
    fun `parseObdResponse rejects responses with wrong service or wrong PID`() {
        // Expecting 41 0C (RPM), but adapter returned 41 0D (Speed)
        val wrongPid = ElmResponseParser.parseObdResponse(
            rawResponse = "41 0D 3C\r\n>",
            expectedService = 0x01,
            expectedPid = 0x0C,
            sentCommand = "010C"
        )
        assertTrue(wrongPid is ElmParseResult.InvalidResponse)

        // Expecting Service 01 (41 00), but adapter returned 7F 01 12 (negative response)
        val negativeResp = ElmResponseParser.parseObdResponse(
            rawResponse = "7F 01 12\r\n>",
            expectedService = 0x01,
            expectedPid = 0x00,
            sentCommand = "0100"
        )
        assertTrue(negativeResp is ElmParseResult.InvalidResponse)
    }

    @Test
    fun `decodeSupportedPidsBitmask decodes 0100 and 0120 bitmasks accurately`() {
        // Bitmask: 0x80 0x00 0x00 0x01 -> MSB (PID 01) and LSB (PID 20 = 0x20)
        val mask00 = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x01)
        val pids00 = ElmResponseParser.decodeSupportedPidsBitmask(0x00, mask00)
        assertEquals(setOf(0x01, 0x20), pids00)

        // Base 0x20 with MSB and LSB -> PID 0x21 and PID 0x40
        val pids20 = ElmResponseParser.decodeSupportedPidsBitmask(0x20, mask00)
        assertEquals(setOf(0x21, 0x40), pids20)
    }

    @Test
    fun `WifiTransport buildEndpointCandidateList places saved IP and port first without duplicates`() {
        val candidates = WifiTransport.buildEndpointCandidateList("192.168.4.1", 35000)
        assertEquals("192.168.4.1" to 35000, candidates.first())
        assertEquals(5, candidates.size)

        val customCandidates = WifiTransport.buildEndpointCandidateList("10.0.0.50", 23)
        assertEquals("10.0.0.50" to 23, customCandidates.first())
        assertEquals(6, customCandidates.size)
    }
}
