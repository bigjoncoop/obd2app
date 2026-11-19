package com.obd2logger.domain.model

enum class TransportType(val label: String) {
    BLUETOOTH_CLASSIC("Bluetooth Classic"),
    BLUETOOTH_LE("Bluetooth LE"),
    WIFI("Wi-Fi"),
    SIMULATOR("Simulator")
}

sealed class AdapterConfig {
    abstract val transportType: TransportType
    abstract val displayName: String
    open val isSimulator: Boolean get() = false

    data class BluetoothClassic(
        val address: String,
        val name: String,
        val preferredPin: String = "1234"
    ) : AdapterConfig() {
        override val transportType: TransportType = TransportType.BLUETOOTH_CLASSIC
        override val displayName: String =
            if (name.isNotBlank()) "$name ($address)" else "BT Classic ($address)"
    }

    data class BluetoothLe(
        val address: String,
        val name: String
    ) : AdapterConfig() {
        override val transportType: TransportType = TransportType.BLUETOOTH_LE
        override val displayName: String =
            if (name.isNotBlank()) "$name ($address)" else "BLE ($address)"
    }

    data class Wifi(
        val ip: String,
        val port: Int
    ) : AdapterConfig() {
        override val transportType: TransportType = TransportType.WIFI
        override val displayName: String = "Wi-Fi ($ip:$port)"
    }

    data object Simulator : AdapterConfig() {
        override val transportType: TransportType = TransportType.SIMULATOR
        override val displayName: String = "SIMULATOR (Virtual ELM327 v1.5)"
        override val isSimulator: Boolean = true
    }
}
