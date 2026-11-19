package com.obd2logger.data.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.obd2logger.domain.model.AdapterConfig
import com.obd2logger.domain.model.TransportType
import com.obd2logger.domain.transport.ObdTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Classic (RFCOMM / SPP) transport implementation.
 *
 * - Triggers pairing if not already bonded, handling PIN prompts (1234 / 0000 / 6789).
 * - Cancels discovery before connecting.
 * - Performs all socket operations on [Dispatchers.IO].
 * - Connects via `createRfcommSocketToServiceRecord(SPP_UUID)`, retries with reflection
 *   fallback `createRfcommSocket(1)`, and retries once more after a short delay.
 * - Closes sockets cleanly on failure or disconnect.
 */
@SuppressLint("MissingPermission")
class BluetoothClassicTransport(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val config: AdapterConfig.BluetoothClassic,
    private val onProgress: (String) -> Unit = {}
) : ObdTransport {

    override val displayName: String = config.displayName
    override val transportType: TransportType = TransportType.BLUETOOTH_CLASSIC

    private val _isOpenFlow = MutableStateFlow(false)
    override val isOpenFlow: StateFlow<Boolean> = _isOpenFlow.asStateFlow()
    override val isOpen: Boolean get() = _isOpenFlow.value

    private val _incomingBytes = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    override val incomingBytes: Flow<ByteArray> = _incomingBytes.asSharedFlow()

    private val _transportErrors = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val transportErrors: SharedFlow<String> = _transportErrors.asSharedFlow()

    private val _connectionDetails = MutableStateFlow<String?>(null)
    override val connectionDetails: StateFlow<String?> = _connectionDetails.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var activeSocket: BluetoothSocket? = null
    private var readerJob: Job? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        close()
        val adapter = bluetoothAdapter
            ?: throw IOException("Bluetooth is not supported on this device.")
        if (!adapter.isEnabled) {
            throw IOException("Bluetooth is turned off. Please turn on Bluetooth and try again.")
        }

        // Always cancel discovery before pairing/connecting
        runCatching {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
        }
        delay(100)

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(config.address.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid Bluetooth MAC address: ${config.address}", e)
        }

        // Ensure device is paired (bonded)
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            pairWithDevice(device, config.preferredPin)
        }

        // Cancel discovery again before opening RFCOMM socket
        runCatching {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
        }
        delay(100)

        val connectedSocket = connectWithRetries(device)
        activeSocket = connectedSocket
        _isOpenFlow.value = true
        startReaderLoop(connectedSocket)
    }

    private suspend fun pairWithDevice(device: BluetoothDevice, preferredPin: String) {
        onProgress(
            "Pairing with ${config.displayName}... Common PINs: $preferredPin, 1234, 0000, 6789"
        )
        val bondDeferred = CompletableDeferred<Boolean>()
        var sawBondingState = device.bondState == BluetoothDevice.BOND_BONDING

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val eventDevice: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                if (eventDevice?.address?.equals(device.address, ignoreCase = true) != true) return

                when (intent?.action) {
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        val pinToTry = preferredPin.ifBlank { "1234" }
                        onProgress("PIN prompt received. Trying PIN $pinToTry (or enter 1234 / 0000 / 6789)...")
                        runCatching {
                            device.setPin(pinToTry.toByteArray(Charsets.UTF_8))
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val newState = intent.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE,
                            BluetoothDevice.BOND_NONE
                        )
                        when (newState) {
                            BluetoothDevice.BOND_BONDING -> {
                                sawBondingState = true
                                onProgress("Pairing in progress (common PINs: 1234, 0000, 6789)...")
                            }
                            BluetoothDevice.BOND_BONDED -> {
                                bondDeferred.complete(true)
                            }
                            BluetoothDevice.BOND_NONE -> {
                                if (sawBondingState) {
                                    bondDeferred.complete(false)
                                }
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        try {
            if (device.bondState == BluetoothDevice.BOND_NONE) {
                val initiated = runCatching { device.createBond() }.getOrDefault(false)
                if (!initiated && device.bondState != BluetoothDevice.BOND_BONDED) {
                    onProgress("Waiting for system Bluetooth pairing...")
                }
            }
            val bonded = withTimeoutOrNull(PAIRING_TIMEOUT_MS) {
                bondDeferred.await()
            } ?: (device.bondState == BluetoothDevice.BOND_BONDED)

            if (!bonded && device.bondState != BluetoothDevice.BOND_BONDED) {
                throw IOException(
                    "Bluetooth pairing did not complete for ${config.displayName}. " +
                        "Please check PIN (try 1234, 0000, or 6789)."
                )
            }
            onProgress("Paired with ${config.displayName}.")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private suspend fun connectWithRetries(device: BluetoothDevice): BluetoothSocket {
        var lastError: Throwable? = null

        // Attempt 1: Standard SPP UUID via createRfcommSocketToServiceRecord
        onProgress("Connecting RFCOMM socket (SPP UUID)...")
        try {
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                socket.connect()
                _connectionDetails.value = "Bluetooth Classic SPP (Standard UUID)"
                return socket
            } catch (e: Exception) {
                closeQuietly(socket)
                throw e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastError = e
            onProgress("SPP UUID connect failed (${e.message ?: "error"}). Retrying with channel 1 reflection fallback...")
        }

        // Attempt 2: Reflection fallback createRfcommSocket(1)
        try {
            val fallbackSocket = createReflectionRfcommSocket(device, 1)
            try {
                fallbackSocket.connect()
                _connectionDetails.value = "Bluetooth Classic SPP (Channel 1 Reflection Fallback)"
                return fallbackSocket
            } catch (e: Exception) {
                closeQuietly(fallbackSocket)
                throw e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastError = e
            onProgress("Channel 1 fallback failed (${e.message ?: "error"}). Waiting 800ms for final retry...")
        }

        // Attempt 3: Retry once more after short delay
        delay(800)
        runCatching {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery()
            }
        }

        try {
            onProgress("Final retry: connecting RFCOMM socket...")
            val retrySocket = try {
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                try {
                    s.connect()
                    _connectionDetails.value = "Bluetooth Classic SPP (Retry Standard UUID)"
                    s
                } catch (e: Exception) {
                    closeQuietly(s)
                    throw e
                }
            } catch (_: Throwable) {
                val s = createReflectionRfcommSocket(device, 1)
                try {
                    s.connect()
                    _connectionDetails.value = "Bluetooth Classic SPP (Retry Channel 1 Fallback)"
                    s
                } catch (e: Exception) {
                    closeQuietly(s)
                    throw e
                }
            }
            return retrySocket
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastError = e
        }

        throw IOException(
            "Unable to open Bluetooth Classic socket to ${config.displayName}: ${lastError?.message ?: "Connection refused"}",
            lastError
        )
    }

    private fun createReflectionRfcommSocket(device: BluetoothDevice, channel: Int): BluetoothSocket {
        val method = device.javaClass.getMethod(
            "createRfcommSocket",
            Int::class.javaPrimitiveType
        )
        return method.invoke(device, channel) as BluetoothSocket
    }

    private fun startReaderLoop(socket: BluetoothSocket) {
        readerJob?.cancel()
        readerJob = ioScope.launch {
            val buffer = ByteArray(1024)
            try {
                val input = socket.inputStream
                while (isActive && _isOpenFlow.value) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) {
                        handleUnexpectedDisconnect("Bluetooth socket closed by adapter (EOF).")
                        break
                    }
                    if (bytesRead > 0) {
                        _incomingBytes.emit(buffer.copyOf(bytesRead))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (_isOpenFlow.value) {
                    handleUnexpectedDisconnect("Bluetooth socket dropped: ${e.message ?: "I/O error"}")
                }
            }
        }
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val socket = activeSocket
        if (!_isOpenFlow.value || socket == null || !socket.isConnected) {
            throw IOException("Bluetooth Classic socket is not open.")
        }
        try {
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
        } catch (e: IOException) {
            handleUnexpectedDisconnect("Write failed on Bluetooth socket: ${e.message}")
            throw e
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        _isOpenFlow.value = false
        readerJob?.cancel()
        readerJob = null
        val socket = activeSocket
        activeSocket = null
        closeQuietly(socket)
    }

    private fun handleUnexpectedDisconnect(reason: String) {
        val wasOpen = _isOpenFlow.value
        _isOpenFlow.value = false
        val socket = activeSocket
        activeSocket = null
        closeQuietly(socket)
        if (wasOpen) {
            _transportErrors.tryEmit(reason)
        }
    }

    private fun closeQuietly(socket: BluetoothSocket?) {
        if (socket != null) {
            runCatching { socket.inputStream?.close() }
            runCatching { socket.outputStream?.close() }
            runCatching { socket.close() }
        }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PAIRING_TIMEOUT_MS = 30_000L
    }
}
