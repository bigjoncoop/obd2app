package com.obd2logger.domain.model

data class ScannedBtDevice(
    val address: String,
    val name: String?,
    val isPaired: Boolean,
    val rssi: Int? = null,
    val isBle: Boolean = false
) {
    val displayTitle: String
        get() = if (!name.isNullOrBlank()) name.trim() else "Unnamed Adapter ($address)"
}
