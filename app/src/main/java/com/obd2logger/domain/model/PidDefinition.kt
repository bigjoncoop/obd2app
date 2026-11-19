package com.obd2logger.domain.model

import java.util.Locale

/**
 * Polling priority tiers for Mode 01 PIDs.
 * [cycleInterval] determines how often the PID is polled (every N polling cycles).
 */
enum class PidPriority(val cycleInterval: Int, val label: String) {
    HIGH(cycleInterval = 1, label = "HIGH"),
    MEDIUM(cycleInterval = 3, label = "MED"),
    LOW(cycleInterval = 8, label = "LOW")
}

/**
 * Data-driven definition of an OBD-II Mode 01 sensor PID.
 *
 * @property id Mode 01 PID byte (e.g. 0x0C for Engine RPM).
 * @property name Human-readable sensor name.
 * @property expectedBytes Minimum number of payload bytes after the `41 <PID>` header.
 * @property formula Converts validated payload bytes into an imperial numeric value, or null if invalid.
 * @property imperialUnit Imperial display unit (e.g. "rpm", "mph", "°F", "psi").
 * @property priority Polling priority tier.
 * @property supported Whether the connected vehicle reported support for this PID in 0100/0120/0140... bitmasks.
 * @property decimals Number of decimal places when formatting the live value.
 */
data class PidDefinition(
    val id: Int,
    val name: String,
    val expectedBytes: Int,
    val formula: (ByteArray) -> Double?,
    val imperialUnit: String,
    val priority: PidPriority,
    val supported: Boolean = false,
    val decimals: Int = 1,
    val showInLiveUi: Boolean = true
) {
    val hexId: String
        get() = "%02X".format(id and 0xFF)

    val command: String
        get() = "01%02X".format(id and 0xFF)

    fun evaluate(dataBytes: ByteArray): Double? {
        if (dataBytes.size < expectedBytes) return null
        val value = formula(dataBytes) ?: return null
        return if (value.isFinite()) value else null
    }

    fun formatValue(value: Double?): String {
        if (value == null || !value.isFinite()) return "--"
        return if (decimals <= 0) {
            "%.0f".format(Locale.US, value)
        } else {
            "%.${decimals}f".format(Locale.US, value)
        }
    }
}
