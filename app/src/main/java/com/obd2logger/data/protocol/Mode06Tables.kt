package com.obd2logger.data.protocol

/**
 * Bundled SAE J1979 / ISO 15031-5 Mode 06 tables:
 * - Monitor ID (MID) names
 * - Standard Test ID (TID) names
 * - Unit and Scaling ID (UASID) formulas & units
 */
object Mode06Tables {

    data class UasidDefinition(
        val unit: String,
        val signed: Boolean,
        val multiplier: Double,
        val offset: Double = 0.0,
        val decimals: Int = 3
    ) {
        fun scale(hiByte: Int, loByte: Int): Double {
            val combined = ((hiByte and 0xFF) shl 8) or (loByte and 0xFF)
            val raw = if (signed) combined.toShort().toDouble() else combined.toDouble()
            return (raw * multiplier) + offset
        }
    }

    fun midName(mid: Int): String {
        val id = mid and 0xFF
        return MID_NAMES[id] ?: when (id) {
            in 0x01..0x10 -> "Exhaust Gas Sensor Monitor (0x%02X)".format(id)
            in 0x21..0x24 -> "Catalyst Monitor Bank ${id - 0x20}"
            in 0x31..0x34 -> "EGR Monitor Bank ${id - 0x30}"
            in 0x35..0x38 -> "VVT Monitor Bank ${id - 0x34}"
            in 0x41..0x44 -> "Heated Catalyst Monitor Bank ${id - 0x40}"
            in 0x51..0x54 -> "Secondary Air Monitor ${id - 0x50}"
            in 0x61..0x64 -> "Fuel System Monitor Bank ${id - 0x60}"
            in 0x71..0x74 -> "Boost Pressure Control Monitor Bank ${id - 0x70}"
            in 0xA2..0xB1 -> "Misfire Cylinder ${id - 0xA1} Data"
            else -> "Unknown"
        }
    }

    fun tidName(tid: Int): String {
        val id = tid and 0xFF
        return TID_NAMES[id] ?: "Unknown"
    }

    fun uasidDefinition(uasid: Int): UasidDefinition? {
        val id = uasid and 0xFF
        SPECIAL_UASIDS[id]?.let { return it }
        val base = id and 0x7F
        val isSigned = (id and 0x80) != 0
        val baseSpec = UNSIGNED_BASE_UASIDS[base] ?: return null
        return baseSpec.copy(signed = isSigned)
    }

    /**
     * MIDs used for per-cylinder and general misfire counters (0xA1..0xB1).
     */
    fun isMisfireMid(mid: Int): Boolean = (mid and 0xFF) in 0xA1..0xB1

    private val MID_NAMES: Map<Int, String> = mapOf(
        0x01 to "O2 Sensor Monitor Bank 1 Sensor 1",
        0x02 to "O2 Sensor Monitor Bank 1 Sensor 2",
        0x03 to "O2 Sensor Monitor Bank 1 Sensor 3",
        0x04 to "O2 Sensor Monitor Bank 1 Sensor 4",
        0x05 to "O2 Sensor Monitor Bank 2 Sensor 1",
        0x06 to "O2 Sensor Monitor Bank 2 Sensor 2",
        0x07 to "O2 Sensor Monitor Bank 2 Sensor 3",
        0x08 to "O2 Sensor Monitor Bank 2 Sensor 4",
        0x09 to "O2 Sensor Monitor Bank 3 Sensor 1",
        0x0A to "O2 Sensor Monitor Bank 3 Sensor 2",
        0x0D to "O2 Sensor Monitor Bank 4 Sensor 1",
        0x0E to "O2 Sensor Monitor Bank 4 Sensor 2",
        0x21 to "Catalyst Monitor Bank 1",
        0x22 to "Catalyst Monitor Bank 2",
        0x23 to "Catalyst Monitor Bank 3",
        0x24 to "Catalyst Monitor Bank 4",
        0x31 to "EGR Monitor Bank 1",
        0x32 to "EGR Monitor Bank 2",
        0x33 to "EGR Monitor Bank 3",
        0x34 to "EGR Monitor Bank 4",
        0x35 to "VVT Monitor Bank 1",
        0x36 to "VVT Monitor Bank 2",
        0x37 to "VVT Monitor Bank 3",
        0x38 to "VVT Monitor Bank 4",
        0x39 to "EVAP Monitor (Cap Off)",
        0x3A to "EVAP Monitor (0.090\" Leak)",
        0x3B to "EVAP Monitor (0.040\" Leak)",
        0x3C to "EVAP Monitor (0.020\" Leak)",
        0x3D to "Purge Flow Monitor",
        0x41 to "Heated Catalyst Monitor Bank 1",
        0x42 to "Heated Catalyst Monitor Bank 2",
        0x43 to "Heated Catalyst Monitor Bank 3",
        0x44 to "Heated Catalyst Monitor Bank 4",
        0x51 to "Secondary Air Monitor 1",
        0x52 to "Secondary Air Monitor 2",
        0x53 to "Secondary Air Monitor 3",
        0x54 to "Secondary Air Monitor 4",
        0x61 to "Fuel System Monitor Bank 1",
        0x62 to "Fuel System Monitor Bank 2",
        0x63 to "Fuel System Monitor Bank 3",
        0x64 to "Fuel System Monitor Bank 4",
        0x71 to "Boost Pressure Control Monitor Bank 1",
        0x72 to "Boost Pressure Control Monitor Bank 2",
        0x81 to "NOx Adsorber Monitor Bank 1",
        0x82 to "NOx Adsorber Monitor Bank 2",
        0x85 to "NOx Catalyst Monitor Bank 1",
        0x86 to "NOx Catalyst Monitor Bank 2",
        0x90 to "NMHC Catalyst Monitor Bank 1",
        0x91 to "NMHC Catalyst Monitor Bank 2",
        0x98 to "Exhaust Gas Sensor Monitor Bank 1",
        0x99 to "Exhaust Gas Sensor Monitor Bank 2",
        0xA1 to "Misfire Monitor General Data",
        0xA2 to "Misfire Cylinder 1 Data",
        0xA3 to "Misfire Cylinder 2 Data",
        0xA4 to "Misfire Cylinder 3 Data",
        0xA5 to "Misfire Cylinder 4 Data",
        0xA6 to "Misfire Cylinder 5 Data",
        0xA7 to "Misfire Cylinder 6 Data",
        0xA8 to "Misfire Cylinder 7 Data",
        0xA9 to "Misfire Cylinder 8 Data",
        0xAA to "Misfire Cylinder 9 Data",
        0xAB to "Misfire Cylinder 10 Data",
        0xAC to "Misfire Cylinder 11 Data",
        0xAD to "Misfire Cylinder 12 Data",
        0xB2 to "PM Filter Monitor Bank 1",
        0xB3 to "PM Filter Monitor Bank 2"
    )

    private val TID_NAMES: Map<Int, String> = mapOf(
        0x01 to "Rich to lean sensor threshold voltage",
        0x02 to "Lean to rich sensor threshold voltage",
        0x03 to "Low sensor voltage for switch time calculation",
        0x04 to "High sensor voltage for switch time calculation",
        0x05 to "Rich to lean sensor switch time",
        0x06 to "Lean to rich sensor switch time",
        0x07 to "Minimum sensor voltage for test cycle",
        0x08 to "Maximum sensor voltage for test cycle",
        0x09 to "Time between sensor transitions",
        0x0A to "Sensor period",
        0x0B to "EWMA misfire counts for previous driving cycles",
        0x0C to "Misfire counts for last/current driving cycles"
    )

    private val UNSIGNED_BASE_UASIDS: Map<Int, UasidDefinition> = mapOf(
        0x01 to UasidDefinition("count", signed = false, multiplier = 1.0, decimals = 0),
        0x02 to UasidDefinition("count", signed = false, multiplier = 0.1, decimals = 1),
        0x03 to UasidDefinition("count", signed = false, multiplier = 0.01, decimals = 2),
        0x04 to UasidDefinition("count", signed = false, multiplier = 0.001, decimals = 3),
        0x05 to UasidDefinition("count", signed = false, multiplier = 0.0000305, decimals = 5),
        0x06 to UasidDefinition("count", signed = false, multiplier = 0.000305, decimals = 4),
        0x07 to UasidDefinition("rpm", signed = false, multiplier = 0.25, decimals = 2),
        0x08 to UasidDefinition("km/h", signed = false, multiplier = 0.01, decimals = 2),
        0x09 to UasidDefinition("km/h", signed = false, multiplier = 1.0, decimals = 0),
        0x0A to UasidDefinition("mV", signed = false, multiplier = 0.122, decimals = 2),
        0x0B to UasidDefinition("V", signed = false, multiplier = 0.001, decimals = 3),
        0x0C to UasidDefinition("V", signed = false, multiplier = 0.01, decimals = 2),
        0x0D to UasidDefinition("mA", signed = false, multiplier = 0.00390625, decimals = 3),
        0x0E to UasidDefinition("A", signed = false, multiplier = 0.001, decimals = 3),
        0x0F to UasidDefinition("A", signed = false, multiplier = 0.01, decimals = 2),
        0x10 to UasidDefinition("ms", signed = false, multiplier = 1.0, decimals = 0),
        0x11 to UasidDefinition("ms", signed = false, multiplier = 100.0, decimals = 0),
        0x12 to UasidDefinition("s", signed = false, multiplier = 1.0, decimals = 0),
        0x13 to UasidDefinition("mOhm", signed = false, multiplier = 1.0, decimals = 0),
        0x14 to UasidDefinition("Ohm", signed = false, multiplier = 1.0, decimals = 0),
        0x15 to UasidDefinition("kOhm", signed = false, multiplier = 1.0, decimals = 0),
        0x16 to UasidDefinition("°C", signed = false, multiplier = 0.1, decimals = 1),
        0x17 to UasidDefinition("kPa", signed = false, multiplier = 0.01, decimals = 2),
        0x18 to UasidDefinition("kPa", signed = false, multiplier = 0.0117, decimals = 3),
        0x19 to UasidDefinition("kPa", signed = false, multiplier = 0.079, decimals = 2),
        0x1A to UasidDefinition("kPa", signed = false, multiplier = 1.0, decimals = 0),
        0x1B to UasidDefinition("kPa", signed = false, multiplier = 10.0, decimals = 0),
        0x1C to UasidDefinition("°", signed = false, multiplier = 0.01, decimals = 2),
        0x1D to UasidDefinition("°", signed = false, multiplier = 0.5, decimals = 1),
        0x1E to UasidDefinition("ratio", signed = false, multiplier = 0.0000305, decimals = 5),
        0x1F to UasidDefinition("ratio", signed = false, multiplier = 0.05, decimals = 2),
        0x20 to UasidDefinition("ratio", signed = false, multiplier = 0.00390625, decimals = 4),
        0x21 to UasidDefinition("mHz", signed = false, multiplier = 1.0, decimals = 0),
        0x22 to UasidDefinition("Hz", signed = false, multiplier = 1.0, decimals = 0),
        0x23 to UasidDefinition("kHz", signed = false, multiplier = 1.0, decimals = 0),
        0x24 to UasidDefinition("counts", signed = false, multiplier = 1.0, decimals = 0),
        0x25 to UasidDefinition("km", signed = false, multiplier = 1.0, decimals = 0),
        0x26 to UasidDefinition("mV/ms", signed = false, multiplier = 0.1, decimals = 1),
        0x27 to UasidDefinition("g/s", signed = false, multiplier = 0.01, decimals = 2),
        0x28 to UasidDefinition("g/s", signed = false, multiplier = 1.0, decimals = 0),
        0x29 to UasidDefinition("Pa/s", signed = false, multiplier = 0.25, decimals = 2),
        0x2A to UasidDefinition("kg/h", signed = false, multiplier = 0.001, decimals = 3),
        0x2B to UasidDefinition("switches", signed = false, multiplier = 1.0, decimals = 0),
        0x2C to UasidDefinition("g/cyl", signed = false, multiplier = 0.01, decimals = 2),
        0x2D to UasidDefinition("mg/stroke", signed = false, multiplier = 0.01, decimals = 2),
        0x2E to UasidDefinition("bool", signed = false, multiplier = 1.0, decimals = 0),
        0x2F to UasidDefinition("%", signed = false, multiplier = 0.01, decimals = 2),
        0x30 to UasidDefinition("%", signed = false, multiplier = 0.001526, decimals = 3),
        0x31 to UasidDefinition("L", signed = false, multiplier = 0.001, decimals = 3),
        0x32 to UasidDefinition("inch", signed = false, multiplier = 0.0000305, decimals = 5),
        0x33 to UasidDefinition("ratio", signed = false, multiplier = 0.00024414, decimals = 4),
        0x34 to UasidDefinition("min", signed = false, multiplier = 1.0, decimals = 0),
        0x35 to UasidDefinition("ms", signed = false, multiplier = 10.0, decimals = 0),
        0x36 to UasidDefinition("g", signed = false, multiplier = 0.01, decimals = 2),
        0x37 to UasidDefinition("g", signed = false, multiplier = 0.1, decimals = 1),
        0x38 to UasidDefinition("g", signed = false, multiplier = 1.0, decimals = 0),
        0x39 to UasidDefinition("%", signed = false, multiplier = 0.01, decimals = 2)
    )

    private val SPECIAL_UASIDS: Map<Int, UasidDefinition> = mapOf(
        0x96 to UasidDefinition("°C", signed = true, multiplier = 0.1, offset = -40.0, decimals = 1),
        0xFC to UasidDefinition("kPa", signed = true, multiplier = 0.01, decimals = 2),
        0xFD to UasidDefinition("kPa", signed = true, multiplier = 0.001, decimals = 3),
        0xFE to UasidDefinition("Pa", signed = true, multiplier = 0.25, decimals = 2)
    )
}
