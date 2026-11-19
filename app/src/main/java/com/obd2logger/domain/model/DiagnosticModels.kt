package com.obd2logger.domain.model

/**
 * Decoded OBD-II Diagnostic Trouble Code with short plain-English description.
 */
data class DecodedDtc(
    val code: String,
    val description: String
) {
    fun toCsvToken(): String = "$code:$description"
}

enum class MonitorStatus(val csvValue: String) {
    COMPLETE("Complete"),
    INCOMPLETE("Incomplete"),
    NA("N/A")
}

enum class IgnitionType(val csvValue: String) {
    SPARK("Spark"),
    COMPRESSION("Compression")
}

enum class MonitorKey(val displayName: String) {
    MISFIRE("Misfire Monitor"),
    FUEL_SYSTEM("Fuel System Monitor"),
    COMPONENTS("Components Monitor"),
    CATALYST("Catalyst Monitor"),
    HEATED_CATALYST("Heated Catalyst Monitor"),
    EVAP("Evap Monitor"),
    SECONDARY_AIR("Secondary Air Monitor"),
    AC_REFRIGERANT("A/C Refrigerant Monitor"),
    O2_SENSOR("O2 Sensor Monitor"),
    O2_HEATER("O2 Sensor Heater Monitor"),
    EGR_VVT("EGR/VVT Monitor")
}

data class ReadinessSnapshot(
    val monitors: Map<MonitorKey, MonitorStatus>,
    val ignitionType: IgnitionType
) {
    fun statusOf(key: MonitorKey): String = monitors[key]?.csvValue ?: "-"
}

data class FreezeFrameSnapshot(
    val dtc: DecodedDtc? = null,
    val rpm: String = "-",
    val speedMph: String = "-",
    val engineLoadPct: String = "-",
    val coolantTempF: String = "-",
    val stftB1Pct: String = "-",
    val ltftB1Pct: String = "-",
    val intakeManifoldPressurePsi: String = "-",
    val intakeAirTempF: String = "-"
) {
    val dtcCsvString: String
        get() = dtc?.toCsvToken() ?: "-"
}

/**
 * Complete diagnostic & vehicle identity snapshot gathered once at session start
 * (while sensor polling is paused). Unavailable fields default to "-".
 */
data class SessionDiagnosticSnapshot(
    val sessionId: String,
    val milOn: Boolean? = null,
    val dtcCount: Int? = null,
    val storedDtcs: List<DecodedDtc> = emptyList(),
    val pendingDtcs: List<DecodedDtc> = emptyList(),
    val permanentDtcs: List<DecodedDtc> = emptyList(),
    val readinessSinceCleared: ReadinessSnapshot? = null,
    val readinessThisDriveCycle: ReadinessSnapshot? = null,
    val freezeFrame: FreezeFrameSnapshot = FreezeFrameSnapshot(),
    val vin: String = "-",
    val calibrationId: String = "-",
    val cvn: String = "-",
    val obdStandard: String = "-",
    val ecuName: String = "-",
    val protocol: String = "-",
    val adapterType: String = "-",
    val adapterFirmware: String = "-",
    val appVersion: String = "-",
    val supportedPidsHex: String = "-"
) {
    val milStatusCsv: String
        get() = when (milOn) {
            true -> "On"
            false -> "Off"
            null -> "-"
        }

    val dtcCountCsv: String
        get() = dtcCount?.toString() ?: "-"

    val storedDtcsCsv: String
        get() = if (storedDtcs.isEmpty()) "-" else storedDtcs.joinToString(";") { it.toCsvToken() }

    val pendingDtcsCsv: String
        get() = if (pendingDtcs.isEmpty()) "-" else pendingDtcs.joinToString(";") { it.toCsvToken() }

    val permanentDtcsCsv: String
        get() = if (permanentDtcs.isEmpty()) "-" else permanentDtcs.joinToString(";") { it.toCsvToken() }
}

data class GpsSnapshot(
    val gpsTime: String = "-",
    val longitude: String = "-",
    val latitude: String = "-",
    val speedMps: String = "-",
    val altitude: String = "-"
) {
    companion object {
        val EMPTY = GpsSnapshot()
    }
}

data class SavedCsvLogSummary(
    val uri: String,
    val filename: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val durationMs: Long = 0L,
    val rowCount: Long = 0L
)

sealed class LoggingState {
    data object Idle : LoggingState()

    data class Preparing(
        val stepMessage: String
    ) : LoggingState()

    data class Logging(
        val sessionId: String,
        val filename: String,
        val startTimeMs: Long,
        val elapsedMs: Long,
        val rowCount: Long,
        val isAdapterConnected: Boolean,
        val diagnostics: SessionDiagnosticSnapshot
    ) : LoggingState()

    data class Stopped(
        val summary: SavedCsvLogSummary,
        val diagnostics: SessionDiagnosticSnapshot
    ) : LoggingState()

    data class Error(
        val message: String
    ) : LoggingState()
}
