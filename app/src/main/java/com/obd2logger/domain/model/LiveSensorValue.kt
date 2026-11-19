package com.obd2logger.domain.model

/**
 * Represents the latest validated reading for a supported Mode 01 PID.
 * Shows "--" whenever [numericValue] is null (no data received or validation failed).
 */
data class LiveSensorValue(
    val pid: PidDefinition,
    val numericValue: Double? = null,
    val lastUpdatedMs: Long? = null,
    val rawBytes: ByteArray? = null
) {
    val formattedValue: String
        get() = pid.formatValue(numericValue)

    val unit: String
        get() = pid.imperialUnit

    val hasData: Boolean
        get() = numericValue != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LiveSensorValue) return false
        if (pid != other.pid) return false
        if (numericValue != other.numericValue) return false
        if (lastUpdatedMs != other.lastUpdatedMs) return false
        if (rawBytes != null) {
            if (other.rawBytes == null) return false
            if (!rawBytes.contentEquals(other.rawBytes)) return false
        } else if (other.rawBytes != null) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = pid.hashCode()
        result = 31 * result + (numericValue?.hashCode() ?: 0)
        result = 31 * result + (lastUpdatedMs?.hashCode() ?: 0)
        result = 31 * result + (rawBytes?.contentHashCode() ?: 0)
        return result
    }
}
