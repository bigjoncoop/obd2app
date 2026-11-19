package com.obd2logger.data.logging

import com.obd2logger.data.protocol.DiagnosticDecoder
import com.obd2logger.domain.model.GpsSnapshot
import com.obd2logger.domain.model.LiveSensorValue
import com.obd2logger.domain.model.MonitorKey
import com.obd2logger.domain.model.SessionDiagnosticSnapshot
import java.util.Locale

data class CsvRowContext(
    val deviceTimeMs: Long,
    val gps: GpsSnapshot,
    val isAdapterConnected: Boolean,
    val liveByPid: Map<Int, LiveSensorValue>,
    val adapterVoltage: String?,
    val diagnostics: SessionDiagnosticSnapshot
)

/**
 * Single data-driven column definition for the fixed Torque-compatible CSV schema.
 * Adding a column requires only one entry in [CsvSchema.columns].
 */
data class CsvColumn(
    val header: String,
    val extractor: (CsvRowContext) -> String
)

/**
 * Fixed, machine-readable CSV schema (137 columns) in Torque Pro compatible naming.
 * Every CSV file writes the exact same header and column count regardless of vehicle PID support;
 * unsupported or unavailable values always emit "-".
 */
object CsvSchema {

    private fun pidValue(ctx: CsvRowContext, pidId: Int): String {
        if (!ctx.isAdapterConnected) return "-"
        val sensor = ctx.liveByPid[pidId] ?: return "-"
        return if (sensor.hasData) sensor.formattedValue else "-"
    }

    private fun pidRaw(ctx: CsvRowContext, pidId: Int): ByteArray? {
        if (!ctx.isAdapterConnected) return null
        return ctx.liveByPid[pidId]?.rawBytes
    }

    private fun pidBytesColumn(
        header: String,
        pidId: Int,
        decoder: (ByteArray) -> String?
    ): CsvColumn = CsvColumn(header) { ctx ->
        val raw = pidRaw(ctx, pidId) ?: return@CsvColumn "-"
        decoder(raw)?.takeIf { it.isNotBlank() } ?: "-"
    }

    private fun pidCol(header: String, pidId: Int): CsvColumn =
        CsvColumn(header) { ctx -> pidValue(ctx, pidId) }

    private fun o2VoltageColumn(header: String, primaryPid: Int, fallbackPid: Int? = null): CsvColumn =
        CsvColumn(header) { ctx ->
            val v = pidValue(ctx, primaryPid)
            if (v != "-" || fallbackPid == null) v else pidValue(ctx, fallbackPid)
        }

    private fun o2StftColumn(header: String, primaryPid: Int, fallbackPid: Int? = null): CsvColumn =
        CsvColumn(header) { ctx ->
            fun extractStft(pidId: Int): String? {
                val raw = pidRaw(ctx, pidId) ?: return null
                if (raw.size < 2) return null
                val b = raw[1].toInt() and 0xFF
                if (b == 0xFF) return null // SAE J1979: 0xFF = sensor not used in trim calc
                val pct = (b - 128) * 100.0 / 128.0
                return "%.1f".format(Locale.US, pct)
            }
            extractStft(primaryPid) ?: fallbackPid?.let { extractStft(it) } ?: "-"
        }

    private fun widebandLambdaColumn(sensorIndex: Int): CsvColumn {
        val header = "O2 Sensor $sensorIndex Wide Range Lambda"
        val voltagePid = 0x24 + (sensorIndex - 1)
        val currentPid = 0x34 + (sensorIndex - 1)
        return CsvColumn(header) { ctx ->
            val v = pidValue(ctx, voltagePid)
            if (v != "-") v else pidValue(ctx, currentPid)
        }
    }

    private fun readinessColumn(key: MonitorKey, thisDriveCycle: Boolean): CsvColumn {
        val header = if (thisDriveCycle) "${key.displayName} This Drive Cycle" else key.displayName
        return CsvColumn(header) { ctx ->
            val snap = if (thisDriveCycle) {
                ctx.diagnostics.readinessThisDriveCycle
            } else {
                ctx.diagnostics.readinessSinceCleared
            }
            snap?.statusOf(key) ?: "-"
        }
    }

    val columns: List<CsvColumn> = buildList {
        // A) Time / GPS
        add(CsvColumn("GPS Time") { it.gps.gpsTime })
        add(CsvColumn("Device Time") { CsvWriter.formatDeviceTime(it.deviceTimeMs) })
        add(CsvColumn("Longitude") { it.gps.longitude })
        add(CsvColumn("Latitude") { it.gps.latitude })
        add(CsvColumn("GPS Speed (Meters/second)") { it.gps.speedMps })
        add(CsvColumn("Altitude") { it.gps.altitude })

        // B) Core engine
        add(pidCol("Engine RPM(rpm)", 0x0C))
        add(pidCol("Speed (OBD)(mph)", 0x0D))
        add(pidCol("Engine Load(%)", 0x04))
        add(pidCol("Absolute Load Value(%)", 0x43))
        add(pidCol("Throttle Position(Manifold)(%)", 0x11))
        add(pidCol("Relative Throttle Position(%)", 0x45))
        add(pidCol("Absolute Throttle Position B(%)", 0x47))
        add(pidCol("Accelerator Pedal Position D(%)", 0x49))
        add(pidCol("Accelerator Pedal Position E(%)", 0x4A))
        add(pidCol("Accelerator Pedal Position F(%)", 0x4B))
        add(pidCol("Relative Accelerator Pedal Position(%)", 0x5A))
        add(pidCol("Commanded Throttle Actuator(%)", 0x4C))
        add(pidCol("Timing Advance(°)", 0x0E))
        add(pidCol("Run time since engine start(s)", 0x1F))

        // C) Air / fuel
        add(pidCol("Mass Air Flow Rate(g/s)", 0x10))
        add(pidCol("Intake Manifold Pressure(psi)", 0x0B))
        add(pidCol("Intake Air Temperature(°F)", 0x0F))
        add(pidCol("Barometric pressure (from vehicle)(psi)", 0x33))
        add(pidCol("Ambient air temp(°F)", 0x46))
        add(pidCol("Fuel Trim Bank 1 Short Term(%)", 0x06))
        add(pidCol("Fuel Trim Bank 1 Long Term(%)", 0x07))
        add(pidCol("Fuel Trim Bank 2 Short Term(%)", 0x08))
        add(pidCol("Fuel Trim Bank 2 Long Term(%)", 0x09))
        add(pidBytesColumn("Fuel Status 1", 0x03) { bytes ->
            bytes.getOrNull(0)?.let { DiagnosticDecoder.decodeFuelStatus(it.toInt()) }
        })
        add(pidBytesColumn("Fuel Status 2", 0x03) { bytes ->
            bytes.getOrNull(1)?.let { DiagnosticDecoder.decodeFuelStatus(it.toInt()) }
        })
        add(pidCol("Fuel pressure(psi)", 0x0A))
        add(pidCol("Fuel Rail Pressure(psi)", 0x22))
        add(pidCol("Fuel Rail Pressure (Absolute)(psi)", 0x23))
        add(pidCol("Fuel Level (From Engine ECU)(%)", 0x2F))
        add(pidCol("Engine Fuel Rate(gal/h)", 0x5E))
        add(pidCol("Fuel Injection Timing(°)", 0x5D))
        add(pidCol("Commanded Equivalence Ratio(lambda)", 0x44))
        add(pidCol("Ethanol Fuel %(%)", 0x52))
        add(pidBytesColumn("Fuel Type", 0x51) { bytes ->
            bytes.getOrNull(0)?.let { DiagnosticDecoder.decodeFuelType(it.toInt()) }
        })

        // D) Temperatures
        add(pidCol("Engine Coolant Temperature(°F)", 0x05))
        add(pidCol("Engine oil temperature(°F)", 0x5C))
        add(pidCol("Catalyst Temperature (Bank 1 Sensor 1)(°F)", 0x3C))
        add(pidCol("Catalyst Temperature (Bank 2 Sensor 1)(°F)", 0x3D))
        add(pidCol("Catalyst Temperature (Bank 1 Sensor 2)(°F)", 0x3E))
        add(pidCol("Catalyst Temperature (Bank 2 Sensor 2)(°F)", 0x3F))

        // E) Oxygen / wideband
        add(o2VoltageColumn("O2 Bank 1 Sensor 1 Voltage(V)", 0x14))
        add(o2VoltageColumn("O2 Bank 1 Sensor 2 Voltage(V)", 0x15))
        add(o2VoltageColumn("O2 Bank 2 Sensor 1 Voltage(V)", 0x18, fallbackPid = 0x16))
        add(o2VoltageColumn("O2 Bank 2 Sensor 2 Voltage(V)", 0x19, fallbackPid = 0x17))
        add(o2StftColumn("O2 Bank 1 Sensor 1 Short Term Trim(%)", 0x14))
        add(o2StftColumn("O2 Bank 1 Sensor 2 Short Term Trim(%)", 0x15))
        add(o2StftColumn("O2 Bank 2 Sensor 1 Short Term Trim(%)", 0x18, fallbackPid = 0x16))
        add(o2StftColumn("O2 Bank 2 Sensor 2 Short Term Trim(%)", 0x19, fallbackPid = 0x17))
        for (sensorIdx in 1..8) {
            add(widebandLambdaColumn(sensorIdx))
        }
        add(CsvColumn("O2 Sensor Locations") { ctx ->
            val raw4 = pidRaw(ctx, 0x1D)
            if (raw4 != null && raw4.isNotEmpty()) {
                DiagnosticDecoder.decodeO2Locations4Bank(raw4[0].toInt())
            } else {
                val raw2 = pidRaw(ctx, 0x13)
                if (raw2 != null && raw2.isNotEmpty()) {
                    DiagnosticDecoder.decodeO2Locations2Bank(raw2[0].toInt())
                } else {
                    "-"
                }
            }
        })

        // F) Emissions
        add(pidCol("Commanded EGR(%)", 0x2C))
        add(pidCol("EGR Error(%)", 0x2D))
        add(pidCol("Commanded Evaporative Purge(%)", 0x2E))
        add(CsvColumn("Evap System Vapor Pressure(Pa)") { ctx ->
            val p32 = pidValue(ctx, 0x32)
            if (p32 != "-") p32 else pidValue(ctx, 0x53)
        })
        add(pidBytesColumn("Secondary Air Status", 0x12) { bytes ->
            bytes.getOrNull(0)?.let { DiagnosticDecoder.decodeSecondaryAirStatus(it.toInt()) }
        })

        // G) Electrical
        add(pidCol("Voltage (Control Module)(V)", 0x42))
        add(CsvColumn("Voltage (OBD Adapter)(V)") { ctx ->
            if (!ctx.isAdapterConnected) "-" else (ctx.adapterVoltage ?: "-")
        })

        // H) Torque
        add(pidCol("Driver's Demand Engine Torque(%)", 0x61))
        add(pidCol("Actual Engine Torque(%)", 0x62))
        add(pidCol("Engine Reference Torque(lb-ft)", 0x63))

        // I) Turbo / diesel (if supported)
        add(pidCol("Turbo/Boost Pressure(psi)", 0x70))
        add(pidCol("Exhaust Pressure(psi)", 0x73))
        add(pidCol("Turbocharger RPM(rpm)", 0x74))
        add(pidCol("Exhaust Gas Temp Bank 1(°F)", 0x78))
        add(pidCol("Exhaust Gas Temp Bank 2(°F)", 0x79))
        add(pidCol("DPF Inlet Temp(°F)", 0x7C))
        add(pidBytesColumn("DPF Outlet Temp(°F)", 0x7C) { bytes ->
            if (bytes.size >= 5 && (bytes[0].toInt() and 0x02) != 0) {
                val rawTemp = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                val tempF = ((rawTemp * 0.1 - 40.0) * 9.0 / 5.0) + 32.0
                "%.0f".format(Locale.US, tempF)
            } else {
                null
            }
        })
        add(pidCol("DPF Differential Pressure", 0x7A))
        add(pidCol("NOx Sensor Readings", 0x83))
        add(pidCol("Engine Friction Torque(%)", 0x8E))

        // J) Counters
        add(pidCol("Distance travelled with MIL/CEL lit(mi)", 0x21))
        add(pidCol("Time run with MIL on(min)", 0x4D))
        add(pidCol("Distance travelled since codes cleared(mi)", 0x31))
        add(pidCol("Time since codes cleared(min)", 0x4E))
        add(pidCol("Warm-ups since codes cleared(count)", 0x30))

        // K) Readiness monitors (since cleared, then This Drive Cycle)
        for (key in MonitorKey.entries) {
            add(readinessColumn(key, thisDriveCycle = false))
        }
        add(CsvColumn("Ignition Type") { ctx ->
            ctx.diagnostics.readinessSinceCleared?.ignitionType?.csvValue ?: "-"
        })
        for (key in MonitorKey.entries) {
            add(readinessColumn(key, thisDriveCycle = true))
        }
        add(CsvColumn("Ignition Type This Drive Cycle") { ctx ->
            ctx.diagnostics.readinessThisDriveCycle?.ignitionType?.csvValue ?: "-"
        })

        // L) Trouble codes & freeze frame (read once at start, repeated on every row)
        add(CsvColumn("MIL Status (On/Off)") { it.diagnostics.milStatusCsv })
        add(CsvColumn("DTC Count") { it.diagnostics.dtcCountCsv })
        add(CsvColumn("Stored DTCs") { it.diagnostics.storedDtcsCsv })
        add(CsvColumn("Pending DTCs") { it.diagnostics.pendingDtcsCsv })
        add(CsvColumn("Permanent DTCs") { it.diagnostics.permanentDtcsCsv })
        add(CsvColumn("Freeze Frame DTC") { it.diagnostics.freezeFrame.dtcCsvString })
        add(CsvColumn("FF Engine RPM") { it.diagnostics.freezeFrame.rpm })
        add(CsvColumn("FF Speed") { it.diagnostics.freezeFrame.speedMph })
        add(CsvColumn("FF Engine Load") { it.diagnostics.freezeFrame.engineLoadPct })
        add(CsvColumn("FF Coolant Temp") { it.diagnostics.freezeFrame.coolantTempF })
        add(CsvColumn("FF Fuel Trim B1 Short") { it.diagnostics.freezeFrame.stftB1Pct })
        add(CsvColumn("FF Fuel Trim B1 Long") { it.diagnostics.freezeFrame.ltftB1Pct })
        add(CsvColumn("FF Intake Manifold Pressure") { it.diagnostics.freezeFrame.intakeManifoldPressurePsi })
        add(CsvColumn("FF Intake Air Temp") { it.diagnostics.freezeFrame.intakeAirTempF })

        // M) Vehicle / session info (repeated on every row)
        add(CsvColumn("VIN") { it.diagnostics.vin })
        add(CsvColumn("Calibration ID") { it.diagnostics.calibrationId })
        add(CsvColumn("CVN") { it.diagnostics.cvn })
        add(CsvColumn("OBD Standard") { it.diagnostics.obdStandard })
        add(CsvColumn("ECU Name") { it.diagnostics.ecuName })
        add(CsvColumn("Protocol") { it.diagnostics.protocol })
        add(CsvColumn("Adapter Type") { it.diagnostics.adapterType })
        add(CsvColumn("Adapter Firmware") { it.diagnostics.adapterFirmware })
        add(CsvColumn("App Version") { it.diagnostics.appVersion })
        add(CsvColumn("Supported PIDs") { it.diagnostics.supportedPidsHex })
        add(CsvColumn("Session ID") { it.diagnostics.sessionId })
    }

    val headers: List<String> = columns.map { it.header }

    fun headerRowCsv(): String = CsvWriter.formatRow(headers)

    fun buildRow(context: CsvRowContext): List<String> = columns.map { it.extractor(context) }

    fun buildRowCsv(context: CsvRowContext): String = CsvWriter.formatRow(buildRow(context))
}
