package com.obd2logger.data.logging

import com.obd2logger.domain.model.ManufacturerPidDefinition
import com.obd2logger.domain.model.Mode06TestResult

/**
 * Formats rows for the Phase 4 companion CSV files:
 * - `trackLog-<ts>_mode06.csv`
 * - `trackLog-<ts>_extended.csv`
 */
object CompanionCsvWriters {

    val MODE06_HEADERS: List<String> = listOf(
        "Device Time",
        "Session ID",
        "VIN",
        "Phase",
        "MID",
        "MID Name",
        "TID",
        "UASID",
        "Unit",
        "Test Value",
        "Min Limit",
        "Max Limit",
        "Result",
        "Raw Hex"
    )

    fun mode06HeaderCsv(): String = CsvWriter.formatRow(MODE06_HEADERS)

    fun formatMode06RowCsv(
        sessionId: String,
        vin: String,
        test: Mode06TestResult
    ): String {
        val cells = listOf(
            CsvWriter.formatDeviceTime(test.timestampMs),
            sessionId,
            vin.ifBlank { "-" },
            test.phase.label,
            test.midHex,
            test.midName,
            test.tidHex,
            test.uasidHex,
            test.unit,
            test.testValue,
            test.minLimit,
            test.maxLimit,
            test.result.label,
            test.rawHex
        )
        return CsvWriter.formatRow(cells)
    }

    fun extendedHeaders(enabledPids: List<ManufacturerPidDefinition>): List<String> {
        return buildList {
            add("Device Time")
            add("Session ID")
            for (pid in enabledPids) {
                add(pid.csvColumnHeader)
            }
        }
    }

    fun extendedHeaderCsv(enabledPids: List<ManufacturerPidDefinition>): String {
        return CsvWriter.formatRow(extendedHeaders(enabledPids))
    }

    fun formatExtendedRowCsv(
        deviceTimeMs: Long,
        sessionId: String,
        enabledPids: List<ManufacturerPidDefinition>,
        valuesByPidId: Map<String, String>,
        isAdapterConnected: Boolean
    ): String {
        val cells = buildList {
            add(CsvWriter.formatDeviceTime(deviceTimeMs))
            add(sessionId)
            for (pid in enabledPids) {
                val value = if (!isAdapterConnected) {
                    "-"
                } else {
                    valuesByPidId[pid.id]?.takeIf { it.isNotBlank() } ?: "-"
                }
                add(value)
            }
        }
        return CsvWriter.formatRow(cells)
    }
}
