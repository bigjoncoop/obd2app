package com.obd2logger.data.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Wi-Fi TCP transport for ELM327 Wi-Fi adapters.
 *
 * - Binds the process to the active Wi-Fi network via [ConnectivityManager] even when the
 *   adapter's Wi-Fi network has no internet access.
 * - Tries the saved IP:port first, followed by common ELM327 Wi-Fi defaults:
 *   192.168.0.10:35000, 192.168.4.1:35000, 192.168.1.5:35000, 192.168.0.74:23, 192.168.1.1:35000.
 * - Uses a 5-second connect timeout per attempt.
 */
class WifiTransport(
    private val context: Context,
    private val config: AdapterConfig.Wifi,
    private val onProgress: (String) -> Unit = {},
    private val onEndpointConnected: suspend (String, Int) -> Unit = { _, _ -> }
) : ObdTransport {

    override val displayName: String
        get() = _connectionDetails.value ?: config.displayName
    override val transportType: TransportType = TransportType.WIFI

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

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeSocket: Socket? = null
    private var readerJob: Job? = null
    private var registeredNetworkCallback: ConnectivityManager.NetworkCallback? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        close()

        // 1. Bind process to Wi-Fi network even though OBD2 Wi-Fi AP has no internet
        val wifiNetwork = acquireAndBindWifiNetwork()
        if (wifiNetwork != null) {
            onProgress("Bound process to active Wi-Fi network (no-internet mode enabled).")
        } else {
            onProgress("No dedicated Wi-Fi network handle found; attempting direct TCP routing...")
        }

        // 2. Build ordered list of endpoints: saved IP:port first, then common defaults
        val endpoints = buildEndpointCandidateList(config.ip, config.port)
        var lastException: Throwable? = null

        for ((index, endpoint) in endpoints.withIndex()) {
            val (ip, port) = endpoint
            onProgress("Trying Wi-Fi TCP $ip:$port (${index + 1}/${endpoints.size}, 5s timeout)...")
            var candidateSocket: Socket? = null
            try {
                candidateSocket = wifiNetwork?.socketFactory?.createSocket() ?: Socket()
                candidateSocket.tcpNoDelay = true
                candidateSocket.keepAlive = true
                candidateSocket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)

                activeSocket = candidateSocket
                _connectionDetails.value = "Wi-Fi TCP ($ip:$port)"
                _isOpenFlow.value = true
                onProgress("TCP connected to $ip:$port.")
                runCatching { onEndpointConnected(ip, port) }
                startReaderLoop(candidateSocket)
                return@withContext
            } catch (e: CancellationException) {
                closeQuietly(candidateSocket)
                throw e
            } catch (e: Throwable) {
                lastException = e
                closeQuietly(candidateSocket)
                onProgress("No response on $ip:$port (${e.message ?: "timed out"}).")
            }
        }

        unbindNetworkQuietly()
        throw IOException(
            "Could not connect to any Wi-Fi OBD2 endpoint (${endpoints.joinToString { "${it.first}:${it.second}" }}). " +
                "Last error: ${lastException?.message ?: "Timed out"}",
            lastException
        )
    }

    private suspend fun acquireAndBindWifiNetwork(): Network? {
        val cm = connectivityManager ?: return null

        // Check existing networks for TRANSPORT_WIFI first
        val existingWifi = runCatching {
            cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        }.getOrNull()

        if (existingWifi != null) {
            runCatching { cm.bindProcessToNetwork(existingWifi) }
            return existingWifi
        }

        // Request Wi-Fi transport explicitly without NET_CAPABILITY_INTERNET
        val networkDeferred = CompletableDeferred<Network?>()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching { cm.bindProcessToNetwork(network) }
                networkDeferred.complete(network)
            }

            override fun onUnavailable() {
                networkDeferred.complete(null)
            }

            override fun onLost(network: Network) {
                if (_isOpenFlow.value) {
                    handleUnexpectedDisconnect("Wi-Fi network connection to adapter was lost.")
                }
            }
        }

        return try {
            cm.requestNetwork(request, callback)
            registeredNetworkCallback = callback
            withTimeoutOrNull(WIFI_BIND_TIMEOUT_MS) {
                networkDeferred.await()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun startReaderLoop(socket: Socket) {
        readerJob?.cancel()
        readerJob = ioScope.launch {
            val buffer = ByteArray(1024)
            try {
                val input = socket.getInputStream()
                while (isActive && _isOpenFlow.value) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) {
                        handleUnexpectedDisconnect("Wi-Fi TCP socket closed by adapter (EOF).")
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
                    handleUnexpectedDisconnect("Wi-Fi TCP socket dropped: ${e.message ?: "I/O error"}")
                }
            }
        }
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val socket = activeSocket
        if (!_isOpenFlow.value || socket == null || !socket.isConnected || socket.isClosed) {
            throw IOException("Wi-Fi TCP socket is not open.")
        }
        try {
            val out = socket.getOutputStream()
            out.write(bytes)
            out.flush()
        } catch (e: IOException) {
            handleUnexpectedDisconnect("Wi-Fi TCP write failed: ${e.message}")
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
        unbindNetworkQuietly()
    }

    private fun handleUnexpectedDisconnect(reason: String) {
        val wasOpen = _isOpenFlow.value
        _isOpenFlow.value = false
        val socket = activeSocket
        activeSocket = null
        closeQuietly(socket)
        unbindNetworkQuietly()
        if (wasOpen) {
            _transportErrors.tryEmit(reason)
        }
    }

    private fun unbindNetworkQuietly() {
        val cm = connectivityManager ?: return
        runCatching { cm.bindProcessToNetwork(null) }
        val cb = registeredNetworkCallback
        registeredNetworkCallback = null
        if (cb != null) {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }

    private fun closeQuietly(socket: Socket?) {
        if (socket != null) {
            runCatching { socket.getInputStream()?.close() }
            runCatching { socket.getOutputStream()?.close() }
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val WIFI_BIND_TIMEOUT_MS = 3_000L

        val DEFAULT_ENDPOINTS: List<Pair<String, Int>> = listOf(
            "192.168.0.10" to 35000,
            "192.168.4.1" to 35000,
            "192.168.1.5" to 35000,
            "192.168.0.74" to 23,
            "192.168.1.1" to 35000
        )

        fun buildEndpointCandidateList(
            savedIp: String,
            savedPort: Int
        ): List<Pair<String, Int>> {
            val result = mutableListOf<Pair<String, Int>>()
            val cleanIp = savedIp.trim()
            if (cleanIp.isNotEmpty() && savedPort in 1..65535) {
                result.add(cleanIp to savedPort)
            }
            for (defaultEndpoint in DEFAULT_ENDPOINTS) {
                if (!result.any { it.first.equals(defaultEndpoint.first, ignoreCase = true) && it.second == defaultEndpoint.second }) {
                    result.add(defaultEndpoint)
                }
            }
            return result
        }
    }
}
