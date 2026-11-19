package com.obd2logger.data.logging

import com.obd2logger.data.protocol.PidTable
import com.obd2logger.domain.model.DecodedDtc
import com.obd2logger.domain.model.GpsSnapshot
import com.obd2logger.domain.model.IgnitionType
import com.obd2logger.domain.model.LiveSensorValue
import com.obd2logger.domain.model.MonitorKey
import com.obd2logger.domain.model.MonitorStatus
import com.obd2logger.domain.model.ReadinessSnapshot
import com.obd2logger.domain.model.SessionDiagnosticSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CsvWriterAndSchemaTest {

    @Test
    fun `escapeCell properly escapes commas, quotes, CR, and LF per RFC 4180`() {
        assertEquals("-", CsvWriter.escapeCell("-"))
        assertEquals("14.2", CsvWriter.escapeCell("14.2"))
        assertEquals("\"AUTO, ISO 15765-4 (CAN 11/500)\"", CsvWriter.escapeCell("AUTO, ISO 15765-4 (CAN 11/500)"))
        assertEquals("\"say \"\"hi\"\"\"", CsvWriter.escapeCell("say \"hi\""))
        assertEquals("\"line1\nline2\"", CsvWriter.escapeCell("line1\nline2"))
        assertEquals("\"line1\r\nline2\"", CsvWriter.escapeCell("line1\r\nline2"))
    }

    @Test
    fun `formatFilename and formatDeviceTime produce Torque Pro compatible strings`() {
        val zone = ZoneId.of("UTC")
        val zdt = ZonedDateTime.of(2026, 11, 18, 14, 32, 5, 123_000_000, zone)
        val epochMs = zdt.toInstant().toEpochMilli()

        assertEquals(
            "trackLog-2026-Nov-18_14-32-05.csv",
            CsvWriter.formatFilename(epochMs, zone)
        )
        assertEquals(
            "18-Nov-2026 14:32:05.123",
            CsvWriter.formatDeviceTime(epochMs, zone)
        )
    }

    @Test
    fun `every file has identical header and column count regardless of supported PIDs`() {
        val header = CsvSchema.headers
        val expectedCount = CsvSchema.columns.size
        assertEquals(137, expectedCount)
        assertEquals("GPS Time", header.first())
        assertEquals("Session ID", header.last())
        // Ensure no duplicate headers
        assertEquals(expectedCount, header.toSet().size)
        // Ensure MAF is g/s
        assertTrue("Mass Air Flow Rate(g/s)" in header)

        val emptyDiag = SessionDiagnosticSnapshot(sessionId = "sess-0")

        // Case 1: Vehicle supports zero PIDs
        val zeroPidRow = CsvSchema.buildRow(
            CsvRowContext(
                deviceTimeMs = 1_700_000_000_000L,
                gps = GpsSnapshot.EMPTY,
                isAdapterConnected = true,
                liveByPid = emptyMap(),
                adapterVoltage = null,
                diagnostics = emptyDiag
            )
        )
        assertEquals(expectedCount, zeroPidRow.size)
        val rpmIdx = header.indexOf("Engine RPM(rpm)")
        val mafIdx = header.indexOf("Mass Air Flow Rate(g/s)")
        val boostIdx = header.indexOf("Turbo/Boost Pressure(psi)")
        assertEquals("-", zeroPidRow[rpmIdx])
        assertEquals("-", zeroPidRow[mafIdx])
        assertEquals("-", zeroPidRow[boostIdx])

        // Case 2: Vehicle supports partial PIDs (RPM, MAF, Fuel Status 0x03)
        val rpmDef = PidTable.findById(0x0C)!!
        val mafDef = PidTable.findById(0x10)!!
        val fuelStatusDef = PidTable.findById(0x03)!!
        val partialLive = mapOf(
            0x0C to LiveSensorValue(rpmDef, numericValue = 1850.0, lastUpdatedMs = 1L, rawBytes = byteArrayOf(0x1C, 0xE8.toByte())),
            0x10 to LiveSensorValue(mafDef, numericValue = 15.0, lastUpdatedMs = 1L, rawBytes = byteArrayOf(0x05, 0xDC.toByte())),
            0x03 to LiveSensorValue(fuelStatusDef, numericValue = 2.0, lastUpdatedMs = 1L, rawBytes = byteArrayOf(0x02, 0x00))
        )
        val partialRow = CsvSchema.buildRow(
            CsvRowContext(
                deviceTimeMs = 1_700_000_000_000L,
                gps = GpsSnapshot.EMPTY,
                isAdapterConnected = true,
                liveByPid = partialLive,
                adapterVoltage = "14.2",
                diagnostics = emptyDiag.copy(
                    milOn = true,
                    dtcCount = 1,
                    storedDtcs = listOf(DecodedDtc("P0171", "System Too Lean (Bank 1)")),
                    vin = "1HGCM82633A004352",
                    readinessSinceCleared = ReadinessSnapshot(
                        monitors = mapOf(MonitorKey.MISFIRE to MonitorStatus.COMPLETE),
                        ignitionType = IgnitionType.SPARK
                    )
                )
            )
        )
        assertEquals(expectedCount, partialRow.size)
        assertEquals("1850", partialRow[rpmIdx])
        assertEquals("15.00", partialRow[mafIdx])
        assertEquals("-", partialRow[boostIdx]) // unsupported PID still "-"
        assertEquals("Closed loop (O2 sensor)", partialRow[header.indexOf("Fuel Status 1")])
        assertEquals("-", partialRow[header.indexOf("Fuel Status 2")])
        assertEquals("14.2", partialRow[header.indexOf("Voltage (OBD Adapter)(V)")])
        assertEquals("On", partialRow[header.indexOf("MIL Status (On/Off)")])
        assertEquals("P0171:System Too Lean (Bank 1)", partialRow[header.indexOf("Stored DTCs")])
        assertEquals("1HGCM82633A004352", partialRow[header.indexOf("VIN")])
        assertEquals("Complete", partialRow[header.indexOf("Misfire Monitor")])

        // Case 3: Mid-log adapter disconnect writes "-" for OBD sensors while preserving row count & diagnostics
        val disconnectedRow = CsvSchema.buildRow(
            CsvRowContext(
                deviceTimeMs = 1_700_000_001_000L,
                gps = GpsSnapshot.EMPTY,
                isAdapterConnected = false,
                liveByPid = partialLive, // even if stale values exist in memory
                adapterVoltage = "14.2",
                diagnostics = emptyDiag.copy(vin = "1HGCM82633A004352")
            )
        )
        assertEquals(expectedCount, disconnectedRow.size)
        assertEquals("-", disconnectedRow[rpmIdx])
        assertEquals("-", disconnectedRow[mafIdx])
        assertEquals("-", disconnectedRow[header.indexOf("Voltage (OBD Adapter)(V)")])
        assertEquals("1HGCM82633A004352", disconnectedRow[header.indexOf("VIN")])

        // Verify CSV round-trip line split respects escaping even with commas in Protocol
        val csvLine = CsvSchema.buildRowCsv(
            CsvRowContext(
                deviceTimeMs = 1_700_000_000_000L,
                gps = GpsSnapshot.EMPTY,
                isAdapterConnected = true,
                liveByPid = partialLive,
                adapterVoltage = "14.2",
                diagnostics = emptyDiag.copy(protocol = "AUTO, ISO 15765-4 (CAN 11/500)")
            )
        )
        val parsedCols = parseCsvLine(csvLine)
        assertEquals(expectedCount, parsedCols.size)
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", parsedCols[header.indexOf("Protocol")])
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (inQuotes) {
                if (ch == '"' && i + 1 < line.length && line[i + 1] == '"') {
                    cur.append('"')
                    i += 2
                    continue
                } else if (ch == '"') {
                    inQuotes = false
                } else {
                    cur.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> {
                        out.add(cur.toString())
                        cur.clear()
                    }
                    else -> cur.append(ch)
                }
            }
            i++
        }
        out.add(cur.toString())
        return out
    }
}
