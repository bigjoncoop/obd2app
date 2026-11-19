package com.obd2logger.data.logging

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure Kotlin RFC 4180 CSV escaping, row formatting, and Torque Pro timestamp/filename formatters.
 */
object CsvWriter {

    private val FILENAME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MMM-dd_HH-mm-ss", Locale.US)

    private val DEVICE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss.SSS", Locale.US)

    /**
     * Escapes a single CSV cell according to RFC 4180:
     * Wraps in double quotes and doubles internal quotes if the cell contains
     * a comma, double quote, CR, or LF.
     */
    fun escapeCell(raw: String): String {
        val needsQuotes = raw.indexOf(',') >= 0 ||
            raw.indexOf('"') >= 0 ||
            raw.indexOf('\n') >= 0 ||
            raw.indexOf('\r') >= 0
        if (!needsQuotes) return raw
        val escaped = raw.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    /**
     * Formats a full row of cells into a comma-separated line (without trailing newline).
     */
    fun formatRow(cells: List<String>): String {
        return cells.joinToString(",") { escapeCell(it) }
    }

    /**
     * Formats the Torque Pro filename: `trackLog-YYYY-Mon-DD_HH-MM-SS.csv` in local time.
     */
    fun formatFilename(
        epochMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val stamp = Instant.ofEpochMilli(epochMs).atZone(zoneId).format(FILENAME_FORMATTER)
        return "trackLog-$stamp.csv"
    }

    /**
     * Formats `Device Time` (and `GPS Time`): `DD-Mon-YYYY HH:MM:SS.mmm` in local phone time.
     */
    fun formatDeviceTime(
        epochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        return Instant.ofEpochMilli(epochMs).atZone(zoneId).format(DEVICE_TIME_FORMATTER)
    }
}
