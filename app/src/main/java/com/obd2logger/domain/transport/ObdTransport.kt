package com.obd2logger.domain.transport

import com.obd2logger.domain.model.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Common transport contract for Bluetooth Classic (SPP), Bluetooth LE (GATT),
 * Wi-Fi (TCP), and the Debug Simulator.
 */
interface ObdTransport {
    val displayName: String
    val transportType: TransportType
    val isSimulator: Boolean
        get() = transportType == TransportType.SIMULATOR

    /**
     * Current open state of the transport.
     */
    val isOpen: Boolean

    /**
     * Reactive stream of transport open state so drops are detected immediately.
     */
    val isOpenFlow: StateFlow<Boolean>

    /**
     * Stream of incoming raw bytes from the adapter.
     */
    val incomingBytes: Flow<ByteArray>

    /**
     * Emits transport-level errors or unexpected disconnect messages immediately.
     */
    val transportErrors: SharedFlow<String>

    /**
     * Optional human-readable transport details (e.g. negotiated BLE characteristics/MTU or connected Wi-Fi IP:port).
     */
    val connectionDetails: StateFlow<String?>

    /**
     * Opens the transport connection. Suspends until ready to send/receive bytes.
     */
    suspend fun open()

    /**
     * Closes the transport cleanly and releases all underlying sockets/GATT resources.
     */
    suspend fun close()

    /**
     * Writes raw bytes to the adapter.
     */
    suspend fun write(bytes: ByteArray)
}
