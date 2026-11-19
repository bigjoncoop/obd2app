package com.obd2logger.domain.model

/**
 * Strictly models the connection states:
 * - Disconnected (Red)
 * - Connecting (Yellow, pulsing)
 * - AdapterReady (Yellow: ELM327 responded to ATZ, but car is unconfirmed or not responding)
 * - Connected (Green: valid 41 00 response received from the vehicle)
 */
sealed class ConnectionState {
    abstract val statusTitle: String
    abstract val statusMessage: String
    abstract val isSimulator: Boolean

    data class Disconnected(
        override val statusMessage: String = "Not connected. Tap Connect to choose an OBD2 adapter.",
        val isError: Boolean = false,
        override val isSimulator: Boolean = false
    ) : ConnectionState() {
        override val statusTitle: String = "Disconnected"
    }

    data class Connecting(
        val adapterName: String,
        override val statusMessage: String = "Opening connection to adapter...",
        override val isSimulator: Boolean = false
    ) : ConnectionState() {
        override val statusTitle: String = "Connecting..."
    }

    data class AdapterReady(
        val adapterName: String,
        val elmVersion: String,
        override val statusMessage: String,
        val isCarUnresponsive: Boolean = false,
        val transportDetails: String? = null,
        override val isSimulator: Boolean = false
    ) : ConnectionState() {
        override val statusTitle: String = "Adapter Ready"
    }

    data class Connected(
        val adapterName: String,
        val elmVersion: String,
        val supportedPids: Set<Int>,
        val transportDetails: String? = null,
        override val isSimulator: Boolean = false
    ) : ConnectionState() {
        override val statusTitle: String = "Connected"
        override val statusMessage: String =
            "$elmVersion • Vehicle ECU verified (41 00) • ${supportedPids.size} supported PIDs"
    }
}
