package com.obd2logger.data.protocol

import com.obd2logger.domain.model.PidDefinition
import com.obd2logger.domain.model.PidPriority

/**
 * Single data-driven table of OBD-II Mode 01 sensor PIDs.
 * Adding a new PID requires only a single entry in [entries].
 * All formulas output values in imperial units.
 */
object PidTable {

    private const val KPA_TO_PSI = 0.1450377377
    private const val KMH_TO_MPH = 0.6213711922
    private const val LPH_TO_GAL_PER_HR = 0.2641720524
    private const val NM_TO_LB_FT = 0.7375621493

    private fun uByte(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF
    private fun uWord(bytes: ByteArray, offset: Int = 0): Int =
        (uByte(bytes, offset) shl 8) or uByte(bytes, offset + 1)

    private fun sWord(bytes: ByteArray, offset: Int = 0): Int =
        ((uByte(bytes, offset) shl 8) or uByte(bytes, offset + 1)).toShort().toInt()

    private fun cToF(celsius: Double): Double = (celsius * 9.0 / 5.0) + 32.0

    val entries: List<PidDefinition> = listOf(
        PidDefinition(
            id = 0x0C,
            name = "Engine RPM",
            expectedBytes = 2,
            formula = { bytes -> uWord(bytes) / 4.0 },
            imperialUnit = "rpm",
            priority = PidPriority.HIGH,
            decimals = 0
        ),
        PidDefinition(
            id = 0x0D,
            name = "Vehicle Speed",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * KMH_TO_MPH },
            imperialUnit = "mph",
            priority = PidPriority.HIGH,
            decimals = 0
        ),
        PidDefinition(
            id = 0x11,
            name = "Throttle Position",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * 100.0 / 255.0 },
            imperialUnit = "%",
            priority = PidPriority.HIGH,
            decimals = 1
        ),
        PidDefinition(
            id = 0x04,
            name = "Calculated Engine Load",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * 100.0 / 255.0 },
            imperialUnit = "%",
            priority = PidPriority.HIGH,
            decimals = 1
        ),
        PidDefinition(
            id = 0x0B,
            name = "Intake Manifold Pressure",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * KPA_TO_PSI },
            imperialUnit = "psi",
            priority = PidPriority.HIGH,
            decimals = 1
        ),
        PidDefinition(
            id = 0x10,
            name = "Mass Air Flow (MAF)",
            expectedBytes = 2,
            formula = { bytes -> uWord(bytes) / 100.0 },
            imperialUnit = "g/s",
            priority = PidPriority.HIGH,
            decimals = 2
        ),
        PidDefinition(
            id = 0x0E,
            name = "Timing Advance",
            expectedBytes = 1,
            formula = { bytes -> (uByte(bytes, 0) / 2.0) - 64.0 },
            imperialUnit = "°",
            priority = PidPriority.HIGH,
            decimals = 1
        ),
        PidDefinition(
            id = 0x45,
            name = "Relative Throttle Position",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * 100.0 / 255.0 },
            imperialUnit = "%",
            priority = PidPriority.HIGH,
            decimals = 1
        ),
        PidDefinition(
            id = 0x49,
            name = "Accelerator Pedal Position D",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * 100.0 / 255.0 },
            imperialUnit = "%",
            priority = PidPriority.HIGH,
            decimals = 1
        ),
        PidDefinition(
            id = 0x0F,
            name = "Intake Air Temp",
            expectedBytes = 1,
            formula = { bytes -> cToF((uByte(bytes, 0) - 40).toDouble()) },
            imperialUnit = "°F",
            priority = PidPriority.MEDIUM,
            decimals = 0
        ),
        PidDefinition(
            id = 0x06,
            name = "Short Term Fuel Trim (B1)",
            expectedBytes = 1,
            formula = { bytes -> (uByte(bytes, 0) - 128) * 100.0 / 128.0 },
            imperialUnit = "%",
            priority = PidPriority.MEDIUM,
            decimals = 1
        ),
        PidDefinition(
            id = 0x08,
            name = "Short Term Fuel Trim (B2)",
            expectedBytes = 1,
            formula = { bytes -> (uByte(bytes, 0) - 128) * 100.0 / 128.0 },
            imperialUnit = "%",
            priority = PidPriority.MEDIUM,
            decimals = 1
        ),
        PidDefinition(
            id = 0x0A,
            name = "Fuel Pressure",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * 3.0 * KPA_TO_PSI },
            imperialUnit = "psi",
            priority = PidPriority.MEDIUM,
            decimals = 1
        ),
        PidDefinition(
            id = 0x14,
            name = "O2 Sensor 1 Voltage (B1S1)",
            expectedBytes = 2,
            formula = { bytes -> uByte(bytes, 0) / 200.0 },
            imperialUnit = "V",
            priority = PidPriority.MEDIUM,
            decimals = 3
        ),
        PidDefinition(
            id = 0x15,
            name = "O2 Sensor 2 Voltage (B1S2)",
            expectedBytes = 2,
            formula = { bytes -> uByte(bytes, 0) / 200.0 },
            imperialUnit = "V",
            priority = PidPriority.MEDIUM,
            decimals = 3
        ),
        PidDefinition(
            id = 0x42,
            name = "Control Module Voltage",
            expectedBytes = 2,
            formula = { bytes -> uWord(bytes) / 1000.0 },
            imperialUnit = "V",
            priority = PidPriority.MEDIUM,
            decimals = 2
        ),
        PidDefinition(
            id = 0x43,
            name = "Absolute Load Value",
            expectedBytes = 2,
            formula = { bytes -> uWord(bytes) * 100.0 / 255.0 },
            imperialUnit = "%",
            priority = PidPriority.MEDIUM,
            decimals = 1
        ),
        PidDefinition(
            id = 0x44,
            name = "Commanded Equivalence Ratio",
            expectedBytes = 2,
            formula = { bytes -> uWord(bytes) * 2.0 / 65536.0 },
            imperialUnit = "λ",
            priority = PidPriority.MEDIUM,
            decimals = 2
        ),
        PidDefinition(
            id = 0x5E,
            name = "Engine Fuel Rate",
            expectedBytes = 2,
            formula = { bytes -> (uWord(bytes) / 20.0) * LPH_TO_GAL_PER_HR },
            imperialUnit = "gal/h",
            priority = PidPriority.MEDIUM,
            decimals = 2
        ),
        PidDefinition(
            id = 0x05,
            name = "Engine Coolant Temp",
            expectedBytes = 1,
            formula = { bytes -> cToF((uByte(bytes, 0) - 40).toDouble()) },
            imperialUnit = "°F",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x5C,
            name = "Engine Oil Temp",
            expectedBytes = 1,
            formula = { bytes -> cToF((uByte(bytes, 0) - 40).toDouble()) },
            imperialUnit = "°F",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x07,
            name = "Long Term Fuel Trim (B1)",
            expectedBytes = 1,
            formula = { bytes -> (uByte(bytes, 0) - 128) * 100.0 / 128.0 },
            imperialUnit = "%",
            priority = PidPriority.LOW,
            decimals = 1
        ),
        PidDefinition(
            id = 0x09,
            name = "Long Term Fuel Trim (B2)",
            expectedBytes = 1,
            formula = { bytes -> (uByte(bytes, 0) - 128) * 100.0 / 128.0 },
            imperialUnit = "%",
            priority = PidPriority.LOW,
            decimals = 1
        ),
        PidDefinition(
            id = 0x2F,
            name = "Fuel Tank Level",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * 100.0 / 255.0 },
            imperialUnit = "%",
            priority = PidPriority.LOW,
            decimals = 1
        ),
        PidDefinition(
            id = 0x33,
            name = "Barometric Pressure",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * KPA_TO_PSI },
            imperialUnit = "psi",
            priority = PidPriority.LOW,
            decimals = 1
        ),
        PidDefinition(
            id = 0x46,
            name = "Ambient Air Temp",
            expectedBytes = 1,
            formula = { bytes -> cToF((uByte(bytes, 0) - 40).toDouble()) },
            imperialUnit = "°F",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x2C,
            name = "Commanded EGR",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * 100.0 / 255.0 },
            imperialUnit = "%",
            priority = PidPriority.LOW,
            decimals = 1
        ),
        PidDefinition(
            id = 0x1F,
            name = "Run Time Since Start",
            expectedBytes = 2,
            formula = { bytes -> uWord(bytes).toDouble() },
            imperialUnit = "sec",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x21,
            name = "Distance With MIL On",
            expectedBytes = 2,
            formula = { bytes -> uWord(bytes) * KMH_TO_MPH },
            imperialUnit = "mi",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x31,
            name = "Distance Since Codes Cleared",
            expectedBytes = 2,
            formula = { bytes -> uWord(bytes) * KMH_TO_MPH },
            imperialUnit = "mi",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x4D,
            name = "Time Run With MIL On",
            expectedBytes = 2,
            formula = { bytes -> uWord(bytes).toDouble() },
            imperialUnit = "min",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x3C,
            name = "Catalyst Temp (B1S1)",
            expectedBytes = 2,
            formula = { bytes -> cToF((uWord(bytes) / 10.0) - 40.0) },
            imperialUnit = "°F",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x3D,
            name = "Catalyst Temp (B2S1)",
            expectedBytes = 2,
            formula = { bytes -> cToF((uWord(bytes) / 10.0) - 40.0) },
            imperialUnit = "°F",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        // --- Additional Mode 01 PIDs for Phase 3 Torque-compatible CSV logging ---
        PidDefinition(
            id = 0x47,
            name = "Absolute Throttle Position B",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * 100.0 / 255.0 },
            imperialUnit = "%",
            priority = PidPriority.HIGH,
            decimals = 1
        ),
        PidDefinition(
            id = 0x4A,
            name = "Accelerator Pedal Position E",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * 100.0 / 255.0 },
            imperialUnit = "%",
            priority = PidPriority.HIGH,
            decimals = 1
        ),
        PidDefinition(
            id = 0x4B,
            name = "Accelerator Pedal Position F",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * 100.0 / 255.0 },
            imperialUnit = "%",
            priority = PidPriority.HIGH,
            decimals = 1
        ),
        PidDefinition(
            id = 0x5A,
            name = "Relative Accelerator Pedal Position",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * 100.0 / 255.0 },
            imperialUnit = "%",
            priority = PidPriority.HIGH,
            decimals = 1
        ),
        PidDefinition(
            id = 0x4C,
            name = "Commanded Throttle Actuator",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * 100.0 / 255.0 },
            imperialUnit = "%",
            priority = PidPriority.MEDIUM,
            decimals = 1
        ),
        PidDefinition(
            id = 0x22,
            name = "Fuel Rail Pressure",
            expectedBytes = 2,
            formula = { bytes -> uWord(bytes) * 0.079 * KPA_TO_PSI },
            imperialUnit = "psi",
            priority = PidPriority.MEDIUM,
            decimals = 1
        ),
        PidDefinition(
            id = 0x23,
            name = "Fuel Rail Pressure (Absolute)",
            expectedBytes = 2,
            formula = { bytes -> uWord(bytes) * 10.0 * KPA_TO_PSI },
            imperialUnit = "psi",
            priority = PidPriority.MEDIUM,
            decimals = 1
        ),
        PidDefinition(
            id = 0x5D,
            name = "Fuel Injection Timing",
            expectedBytes = 2,
            formula = { bytes -> (uWord(bytes) / 128.0) - 210.0 },
            imperialUnit = "°",
            priority = PidPriority.MEDIUM,
            decimals = 1
        ),
        PidDefinition(
            id = 0x52,
            name = "Ethanol Fuel %",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * 100.0 / 255.0 },
            imperialUnit = "%",
            priority = PidPriority.LOW,
            decimals = 1
        ),
        PidDefinition(
            id = 0x51,
            name = "Fuel Type",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0).toDouble() },
            imperialUnit = "",
            priority = PidPriority.LOW,
            decimals = 0,
            showInLiveUi = false
        ),
        PidDefinition(
            id = 0x03,
            name = "Fuel System Status",
            expectedBytes = 2,
            formula = { bytes -> uByte(bytes, 0).toDouble() },
            imperialUnit = "",
            priority = PidPriority.LOW,
            decimals = 0,
            showInLiveUi = false
        ),
        PidDefinition(
            id = 0x3E,
            name = "Catalyst Temp (B1S2)",
            expectedBytes = 2,
            formula = { bytes -> cToF((uWord(bytes) / 10.0) - 40.0) },
            imperialUnit = "°F",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x3F,
            name = "Catalyst Temp (B2S2)",
            expectedBytes = 2,
            formula = { bytes -> cToF((uWord(bytes) / 10.0) - 40.0) },
            imperialUnit = "°F",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x13,
            name = "O2 Sensors Present (2 Banks)",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0).toDouble() },
            imperialUnit = "",
            priority = PidPriority.LOW,
            decimals = 0,
            showInLiveUi = false
        ),
        PidDefinition(
            id = 0x1D,
            name = "O2 Sensors Present (4 Banks)",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0).toDouble() },
            imperialUnit = "",
            priority = PidPriority.LOW,
            decimals = 0,
            showInLiveUi = false
        ),
        PidDefinition(
            id = 0x16,
            name = "O2 Sensor 3 Voltage",
            expectedBytes = 2,
            formula = { bytes -> uByte(bytes, 0) / 200.0 },
            imperialUnit = "V",
            priority = PidPriority.MEDIUM,
            decimals = 3
        ),
        PidDefinition(
            id = 0x17,
            name = "O2 Sensor 4 Voltage",
            expectedBytes = 2,
            formula = { bytes -> uByte(bytes, 0) / 200.0 },
            imperialUnit = "V",
            priority = PidPriority.MEDIUM,
            decimals = 3
        ),
        PidDefinition(
            id = 0x18,
            name = "O2 Sensor B2S1 Voltage",
            expectedBytes = 2,
            formula = { bytes -> uByte(bytes, 0) / 200.0 },
            imperialUnit = "V",
            priority = PidPriority.MEDIUM,
            decimals = 3
        ),
        PidDefinition(
            id = 0x19,
            name = "O2 Sensor B2S2 Voltage",
            expectedBytes = 2,
            formula = { bytes -> uByte(bytes, 0) / 200.0 },
            imperialUnit = "V",
            priority = PidPriority.MEDIUM,
            decimals = 3
        ),
        // Wideband O2 Lambda (Equivalence Ratio + Voltage: 0x24..0x2B; Equivalence Ratio + Current: 0x34..0x3B)
        PidDefinition(0x24, "O2 Sensor 1 Wide Range Lambda (V)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(0x25, "O2 Sensor 2 Wide Range Lambda (V)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(0x26, "O2 Sensor 3 Wide Range Lambda (V)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(0x27, "O2 Sensor 4 Wide Range Lambda (V)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(0x28, "O2 Sensor 5 Wide Range Lambda (V)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(0x29, "O2 Sensor 6 Wide Range Lambda (V)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(0x2A, "O2 Sensor 7 Wide Range Lambda (V)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(0x2B, "O2 Sensor 8 Wide Range Lambda (V)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(0x34, "O2 Sensor 1 Wide Range Lambda (mA)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(0x35, "O2 Sensor 2 Wide Range Lambda (mA)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(0x36, "O2 Sensor 3 Wide Range Lambda (mA)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(0x37, "O2 Sensor 4 Wide Range Lambda (mA)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(0x38, "O2 Sensor 5 Wide Range Lambda (mA)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(0x39, "O2 Sensor 6 Wide Range Lambda (mA)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(0x3A, "O2 Sensor 7 Wide Range Lambda (mA)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(0x3B, "O2 Sensor 8 Wide Range Lambda (mA)", 4, { bytes -> uWord(bytes) * 2.0 / 65536.0 }, "λ", PidPriority.MEDIUM, decimals = 3),
        PidDefinition(
            id = 0x2D,
            name = "EGR Error",
            expectedBytes = 1,
            formula = { bytes -> (uByte(bytes, 0) - 128) * 100.0 / 128.0 },
            imperialUnit = "%",
            priority = PidPriority.LOW,
            decimals = 1
        ),
        PidDefinition(
            id = 0x2E,
            name = "Commanded Evaporative Purge",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0) * 100.0 / 255.0 },
            imperialUnit = "%",
            priority = PidPriority.LOW,
            decimals = 1
        ),
        PidDefinition(
            id = 0x32,
            name = "Evap System Vapor Pressure",
            expectedBytes = 2,
            formula = { bytes -> sWord(bytes) / 4.0 },
            imperialUnit = "Pa",
            priority = PidPriority.LOW,
            decimals = 1
        ),
        PidDefinition(
            id = 0x53,
            name = "Absolute Evap Vapor Pressure",
            expectedBytes = 2,
            formula = { bytes -> (uWord(bytes) / 200.0) * 1000.0 },
            imperialUnit = "Pa",
            priority = PidPriority.LOW,
            decimals = 1
        ),
        PidDefinition(
            id = 0x12,
            name = "Secondary Air Status",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0).toDouble() },
            imperialUnit = "",
            priority = PidPriority.LOW,
            decimals = 0,
            showInLiveUi = false
        ),
        PidDefinition(
            id = 0x61,
            name = "Driver's Demand Engine Torque",
            expectedBytes = 1,
            formula = { bytes -> (uByte(bytes, 0) - 125).toDouble() },
            imperialUnit = "%",
            priority = PidPriority.HIGH,
            decimals = 0
        ),
        PidDefinition(
            id = 0x62,
            name = "Actual Engine Torque",
            expectedBytes = 1,
            formula = { bytes -> (uByte(bytes, 0) - 125).toDouble() },
            imperialUnit = "%",
            priority = PidPriority.HIGH,
            decimals = 0
        ),
        PidDefinition(
            id = 0x63,
            name = "Engine Reference Torque",
            expectedBytes = 2,
            formula = { bytes -> uWord(bytes) * NM_TO_LB_FT },
            imperialUnit = "lb-ft",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x70,
            name = "Turbo/Boost Pressure",
            expectedBytes = 5,
            formula = { bytes ->
                if ((uByte(bytes, 0) and 0x02) != 0) {
                    uWord(bytes, 3) * 0.03125 * KPA_TO_PSI
                } else {
                    null
                }
            },
            imperialUnit = "psi",
            priority = PidPriority.HIGH,
            decimals = 1
        ),
        PidDefinition(
            id = 0x73,
            name = "Exhaust Pressure",
            expectedBytes = 3,
            formula = { bytes ->
                if ((uByte(bytes, 0) and 0x01) != 0) {
                    uWord(bytes, 1) * 0.01 * KPA_TO_PSI
                } else {
                    null
                }
            },
            imperialUnit = "psi",
            priority = PidPriority.MEDIUM,
            decimals = 1
        ),
        PidDefinition(
            id = 0x74,
            name = "Turbocharger RPM",
            expectedBytes = 3,
            formula = { bytes ->
                if ((uByte(bytes, 0) and 0x01) != 0) {
                    uWord(bytes, 1) * 10.0
                } else {
                    null
                }
            },
            imperialUnit = "rpm",
            priority = PidPriority.HIGH,
            decimals = 0
        ),
        PidDefinition(
            id = 0x78,
            name = "Exhaust Gas Temp Bank 1",
            expectedBytes = 3,
            formula = { bytes ->
                if ((uByte(bytes, 0) and 0x01) != 0) {
                    cToF((uWord(bytes, 1) * 0.1) - 40.0)
                } else {
                    null
                }
            },
            imperialUnit = "°F",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x79,
            name = "Exhaust Gas Temp Bank 2",
            expectedBytes = 3,
            formula = { bytes ->
                if ((uByte(bytes, 0) and 0x01) != 0) {
                    cToF((uWord(bytes, 1) * 0.1) - 40.0)
                } else {
                    null
                }
            },
            imperialUnit = "°F",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x7A,
            name = "DPF Differential Pressure",
            expectedBytes = 3,
            formula = { bytes ->
                if ((uByte(bytes, 0) and 0x01) != 0) {
                    sWord(bytes, 1) * 0.01 * KPA_TO_PSI
                } else {
                    null
                }
            },
            imperialUnit = "psi",
            priority = PidPriority.LOW,
            decimals = 2
        ),
        PidDefinition(
            id = 0x7C,
            name = "DPF Inlet Temp",
            expectedBytes = 5,
            formula = { bytes ->
                if ((uByte(bytes, 0) and 0x01) != 0) {
                    cToF((uWord(bytes, 1) * 0.1) - 40.0)
                } else {
                    null
                }
            },
            imperialUnit = "°F",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x83,
            name = "NOx Sensor Reading",
            expectedBytes = 3,
            formula = { bytes ->
                if ((uByte(bytes, 0) and 0x01) != 0) {
                    uWord(bytes, 1).toDouble()
                } else {
                    null
                }
            },
            imperialUnit = "ppm",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x8E,
            name = "Engine Friction Torque",
            expectedBytes = 1,
            formula = { bytes -> (uByte(bytes, 0) - 125).toDouble() },
            imperialUnit = "%",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x30,
            name = "Warm-ups Since Codes Cleared",
            expectedBytes = 1,
            formula = { bytes -> uByte(bytes, 0).toDouble() },
            imperialUnit = "count",
            priority = PidPriority.LOW,
            decimals = 0
        ),
        PidDefinition(
            id = 0x4E,
            name = "Time Since Codes Cleared",
            expectedBytes = 2,
            formula = { bytes -> uWord(bytes).toDouble() },
            imperialUnit = "min",
            priority = PidPriority.LOW,
            decimals = 0
        )
    )

    private val byId: Map<Int, PidDefinition> = entries.associateBy { it.id }

    fun findById(pid: Int): PidDefinition? = byId[pid]

    /**
     * Returns the full PID table with each entry's [PidDefinition.supported] flag
     * populated from the vehicle's 0100/0120/0140... bitmask [supportedPids].
     */
    fun withSupportFlags(supportedPids: Set<Int>): List<PidDefinition> {
        return entries.map { entry ->
            entry.copy(supported = entry.id in supportedPids)
        }
    }
}
