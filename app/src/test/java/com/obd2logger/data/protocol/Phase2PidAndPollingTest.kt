package com.obd2logger.data.protocol

import com.obd2logger.domain.model.LiveSensorValue
import com.obd2logger.domain.model.PidPriority
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2PidAndPollingTest {

    @Test
    fun `PidTable withSupportFlags marks only bitmask-supported PIDs as supported`() {
        val supported = setOf(0x0C, 0x0D, 0x05)
        val table = PidTable.withSupportFlags(supported)

        val rpm = table.first { it.id == 0x0C }
        val speed = table.first { it.id == 0x0D }
        val coolant = table.first { it.id == 0x05 }
        val throttle = table.first { it.id == 0x11 }

        assertTrue(rpm.supported)
        assertTrue(speed.supported)
        assertTrue(coolant.supported)
        assertFalse(throttle.supported)
    }

    @Test
    fun `imperial unit formulas compute accurate values and null returns double dash`() {
        val rpmDef = PidTable.findById(0x0C)!!
        // 0x1A 0xF8 = 6904 -> 6904 / 4 = 1726 rpm
        val rpmVal = rpmDef.evaluate(byteArrayOf(0x1A, 0xF8.toByte()))
        assertNotNull(rpmVal)
        assertEquals(1726.0, rpmVal!!, 0.01)
        assertEquals("1726", rpmDef.formatValue(rpmVal))
        assertEquals("rpm", rpmDef.imperialUnit)

        // Missing data -> "--"
        assertEquals("--", rpmDef.formatValue(null))
        assertEquals("--", LiveSensorValue(pid = rpmDef).formattedValue)
        // Insufficient bytes -> null
        assertNull(rpmDef.evaluate(byteArrayOf(0x1A)))

        val coolantDef = PidTable.findById(0x05)!!
        // 0x82 = 130 -> 130 - 40 = 90°C -> 194°F
        val coolantVal = coolantDef.evaluate(byteArrayOf(0x82.toByte()))
        assertNotNull(coolantVal)
        assertEquals(194.0, coolantVal!!, 0.01)
        assertEquals("°F", coolantDef.imperialUnit)

        val speedDef = PidTable.findById(0x0D)!!
        // 0x64 = 100 km/h -> 62.137 mph
        val speedVal = speedDef.evaluate(byteArrayOf(0x64))
        assertNotNull(speedVal)
        assertEquals(62.137, speedVal!!, 0.05)
        assertEquals("mph", speedDef.imperialUnit)

        val mafDef = PidTable.findById(0x10)!!
        // 0x05 0xDC = 1500 -> 1500 / 100 = 15.00 g/s
        val mafVal = mafDef.evaluate(byteArrayOf(0x05, 0xDC.toByte()))
        assertNotNull(mafVal)
        assertEquals(15.0, mafVal!!, 0.001)
        assertEquals("15.00", mafDef.formatValue(mafVal))
        assertEquals("g/s", mafDef.imperialUnit)
    }

    @Test
    fun `parseMultiPidMode01Response validates 41 header and extracts each PID payload`() {
        val rpm = PidTable.findById(0x0C)!!
        val speed = PidTable.findById(0x0D)!!
        val throttle = PidTable.findById(0x11)!!

        val result = ElmResponseParser.parseMultiPidMode01Response(
            rawResponse = "010C0D11\r41 0C 1A F8 0D 37 11 40\r>",
            requestedPids = listOf(rpm, speed, throttle),
            sentCommand = "010C0D11"
        )

        assertTrue(result is MultiPidParseResult.Success)
        val success = result as MultiPidParseResult.Success
        assertArrayEquals(byteArrayOf(0x1A, 0xF8.toByte()), success.pidPayloads[0x0C])
        assertArrayEquals(byteArrayOf(0x37), success.pidPayloads[0x0D])
        assertArrayEquals(byteArrayOf(0x40), success.pidPayloads[0x11])
    }

    @Test
    fun `parseMultiPidMode01Response rejects wrong header or incomplete batch`() {
        val rpm = PidTable.findById(0x0C)!!
        val speed = PidTable.findById(0x0D)!!

        val wrongService = ElmResponseParser.parseMultiPidMode01Response(
            rawResponse = "42 0C 1A F8 0D 37\r>",
            requestedPids = listOf(rpm, speed)
        )
        assertTrue(wrongService is MultiPidParseResult.InvalidResponse)

        val incomplete = ElmResponseParser.parseMultiPidMode01Response(
            rawResponse = "41 0C 1A F8\r>",
            requestedPids = listOf(rpm, speed)
        )
        assertTrue(incomplete is MultiPidParseResult.InvalidResponse)

        val errorReply = ElmResponseParser.parseMultiPidMode01Response(
            rawResponse = "?\r>",
            requestedPids = listOf(rpm, speed)
        )
        assertTrue(errorReply is MultiPidParseResult.Error)
        assertEquals(ElmErrorType.UNKNOWN_COMMAND, (errorReply as MultiPidParseResult.Error).errorType)
    }

    @Test
    fun `selectDuePids polls only supported PIDs according to priority intervals`() {
        val table = PidTable.withSupportFlags(setOf(0x0C, 0x0F, 0x05)) // HIGH, MEDIUM, LOW

        val cycle0 = SensorPollingEngine.selectDuePids(table, cycleIndex = 0)
        assertEquals(setOf(0x0C, 0x0F, 0x05), cycle0.map { it.id }.toSet())

        val cycle1 = SensorPollingEngine.selectDuePids(table, cycleIndex = 1)
        assertEquals(setOf(0x0C), cycle1.map { it.id }.toSet())

        val cycle3 = SensorPollingEngine.selectDuePids(table, cycleIndex = 3)
        assertEquals(setOf(0x0C, 0x0F), cycle3.map { it.id }.toSet())

        val cycle8 = SensorPollingEngine.selectDuePids(table, cycleIndex = 8)
        assertEquals(setOf(0x0C, 0x05), cycle8.map { it.id }.toSet())
    }

    @Test
    fun `buildPidBatches respects maxPidsPerBatch and maxPayloadBytes`() {
        val table = PidTable.withSupportFlags(setOf(0x0C, 0x0D, 0x11, 0x04, 0x0B))
            .filter { it.supported && it.priority == PidPriority.HIGH }

        val batches = SensorPollingEngine.buildPidBatches(
            pids = table,
            maxPidsPerBatch = 3,
            maxPayloadBytes = 6
        )

        assertTrue(batches.isNotEmpty())
        for (batch in batches) {
            assertTrue(batch.size <= 3)
            val totalCost = batch.sumOf { 1 + it.expectedBytes }
            assertTrue(totalCost <= 6)
        }
        assertEquals(table.map { it.id }.toSet(), batches.flatten().map { it.id }.toSet())
    }
}
