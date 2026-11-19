package com.obd2logger.domain.model

data class ManufacturerPidDefinition(
    val id: String,
    val name: String,
    val makes: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val years: String? = null,
    val moduleName: String,
    val requestHeader: String,
    val receiveAddress: String? = null,
    val flowControlHeader: String? = null,
    val flowControlData: String? = null,
    val flowControlMode: String? = null,
    val command: String,
    val expectedBytes: Int,
    val formula: String,
    val unit: String,
    val min: Double? = null,
    val max: Double? = null,
    val category: String,
    val source: String,
    val decimals: Int = 1,
    val isUserImported: Boolean = false
) {
    val csvColumnHeader: String
        get() = "$name($unit)"

    val serviceByte: Int
        get() = command.substring(0, 2).toInt(16)

    val pidBytes: ByteArray
        get() {
            val rest = command.substring(2)
            return ByteArray(rest.length / 2) { i ->
                rest.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
}

data class ManufacturerPidState(
    val definition: ManufacturerPidDefinition,
    val detected: Boolean = false,
    val enabled: Boolean = false,
    val latestValue: String = "-",
    val lastUpdatedMs: Long? = null
)
