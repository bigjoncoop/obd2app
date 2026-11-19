package com.obd2logger.data.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.obd2logger.domain.model.AdapterConfig
import com.obd2logger.domain.model.TransportType
import com.obd2logger.domain.transport.ObdTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Low Energy (GATT) transport for ELM327 BLE adapters.
 *
 * - Probes known OBD2 BLE service/characteristic pairs in priority order:
 *   1. FFF0 (notify FFF1, write FFF2)
 *   2. FFE0 (FFE1 for both notify & write, or FFE2 for write)
 *   3. 18F0 (notify 2AF0, write 2AF1)
 *   4. Nordic UART 6E400001-B5A3-F393-E0A9-E50E24DCCA9E (write 6E400002, notify 6E400003)
 *   5. Fallback: any service with one notifiable/indicatable and one writable characteristic.
 * - Serializes all GATT operations (one at a time) via [gattOpMutex].
 * - Requests max MTU (517), enables notifications, writes CCCD descriptor (0x2902) and awaits
 *   completion before sending commands.
 * - Uses the appropriate write type (with/without response) per characteristic.
 * - Chunks outgoing writes to (MTU - 3) bytes.
 * - Buffers fragmented incoming notifications until the '>' prompt arrives.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val config: AdapterConfig.BluetoothLe,
    private val onProgress: (String) -> Unit = {}
) : ObdTransport {

    override val displayName: String = config.displayName
    override val transportType: TransportType = TransportType.BLUETOOTH_LE

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

    private val gattOpMutex = Mutex()
    private val notificationBufferLock = Any()
    private val notificationBuffer = ByteArrayOutputStream()

    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null
    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var negotiatedMtu: Int = DEFAULT_MTU

    @Volatile
    private var connectDeferred: CompletableDeferred<Unit>? = null
    @Volatile
    private var servicesDeferred: CompletableDeferred<Int>? = null
    @Volatile
    private var mtuDeferred: CompletableDeferred<Int>? = null
    @Volatile
    private var descriptorWriteDeferred: CompletableDeferred<Int>? = null
    @Volatile
    private var charWriteDeferred: CompletableDeferred<Int>? = null

    private data class SelectedBlePair(
        val profileLabel: String,
        val serviceUuid: UUID,
        val notifyChar: BluetoothGattCharacteristic,
        val writeChar: BluetoothGattCharacteristic
    )

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectDeferred?.complete(Unit)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val err = IOException("BLE GATT disconnected (status=$status)")
                    connectDeferred?.completeExceptionally(err)
                    servicesDeferred?.completeExceptionally(err)
                    mtuDeferred?.completeExceptionally(err)
                    descriptorWriteDeferred?.completeExceptionally(err)
                    charWriteDeferred?.completeExceptionally(err)
                    handleUnexpectedDisconnect("BLE adapter disconnected (GATT status=$status).")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            servicesDeferred?.complete(status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= DEFAULT_MTU) {
                negotiatedMtu = mtu
            }
            mtuDeferred?.complete(status)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            descriptorWriteDeferred?.complete(status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            charWriteDeferred?.complete(status)
        }

        // Android 13 (API 33+) callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                onNotificationChunkReceived(value)
            }
        }

        // Android 8 - 12 (API 26 - 32) callback
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: return
                onNotificationChunkReceived(value.copyOf())
            }
        }
    }

    /**
     * Buffers fragmented BLE notifications until the '>' prompt arrives (or buffer exceeds max size),
     * then emits the complete response bytes into [_incomingBytes].
     */
    private fun onNotificationChunkReceived(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        var bytesToEmit: ByteArray? = null
        synchronized(notificationBufferLock) {
            notificationBuffer.write(chunk)
            val containsPrompt = chunk.any { it == '>'.code.toByte() }
            if (containsPrompt || notificationBuffer.size() >= MAX_FRAGMENT_BUFFER_BYTES) {
                bytesToEmit = notificationBuffer.toByteArray()
                notificationBuffer.reset()
            }
        }
        bytesToEmit?.let { payload ->
            _incomingBytes.tryEmit(payload)
        }
    }

    override suspend fun open() = withContext(Dispatchers.IO) {
        close()
        val adapter = bluetoothAdapter
            ?: throw IOException("Bluetooth is not supported on this device.")
        if (!adapter.isEnabled) {
            throw IOException("Bluetooth is turned off. Please turn on Bluetooth and try again.")
        }

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(config.address.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid BLE MAC address: ${config.address}", e)
        }

        try {
            // 1. Connect GATT
            onProgress("Connecting BLE GATT to ${config.displayName}...")
            val connDef = CompletableDeferred<Unit>()
            connectDeferred = connDef
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("Failed to initiate BLE GATT connection.")
            bluetoothGatt = gatt

            withTimeout(GATT_CONNECT_TIMEOUT_MS) {
                connDef.await()
            }

            // 2. Request max MTU (queued GATT op #1)
            onProgress("Requesting max BLE MTU (517)...")
            negotiatedMtu = DEFAULT_MTU
            gattOpMutex.withLock {
                val mtuDef = CompletableDeferred<Int>()
                mtuDeferred = mtuDef
                val started = gatt.requestMtu(MAX_REQUESTED_MTU)
                if (started) {
                    withTimeoutOrNull(GATT_OP_TIMEOUT_MS) {
                        mtuDef.await()
                    }
                }
                mtuDeferred = null
            }

            // 3. Discover GATT services (queued GATT op #2)
            onProgress("Discovering BLE GATT services...")
            gattOpMutex.withLock {
                val svcDef = CompletableDeferred<Int>()
                servicesDeferred = svcDef
                if (!gatt.discoverServices()) {
                    throw IOException("BLE discoverServices() failed to start.")
                }
                val svcStatus = withTimeout(GATT_SERVICE_DISCOVERY_TIMEOUT_MS) {
                    svcDef.await()
                }
                servicesDeferred = null
                if (svcStatus != BluetoothGatt.GATT_SUCCESS) {
                    throw IOException("BLE service discovery failed with status $svcStatus.")
                }
            }

            // 4. Auto-select notify + write characteristics by probing known pairs
            val selected = selectCharacteristics(gatt)
                ?: throw IOException(
                    "No compatible OBD2 BLE notify/write characteristics found on ${config.displayName}."
                )
            notifyCharacteristic = selected.notifyChar
            writeCharacteristic = selected.writeChar

            onProgress(
                "Selected BLE profile ${selected.profileLabel} (MTU=$negotiatedMtu). Enabling notifications..."
            )

            // 5. Enable notifications and write CCCD descriptor (queued GATT op #3)
            enableNotificationsAndAwaitCccd(gatt, selected.notifyChar)

            _connectionDetails.value =
                "BLE ${selected.profileLabel} • MTU $negotiatedMtu • Notify ${shortUuid(selected.notifyChar.uuid)} / Write ${shortUuid(selected.writeChar.uuid)}"
            _isOpenFlow.value = true
            onProgress("BLE ready (${_connectionDetails.value}).")
        } catch (e: Throwable) {
            close()
            throw if (e is IOException) e else IOException("BLE connection failed: ${e.message}", e)
        }
    }

    /**
     * Probes known OBD2 BLE service/characteristic pairs in exact priority order:
     * 1. FFF0 (notify FFF1, write FFF2)
     * 2. FFE0 (FFE1 for both, or FFE1 + FFE2)
     * 3. 18F0 (notify 2AF0, write 2AF1)
     * 4. Nordic UART 6E400001... (write 6E400002, notify 6E400003)
     * 5. Fallback: any service with one notifiable and one writable characteristic.
     */
    private fun selectCharacteristics(gatt: BluetoothGatt): SelectedBlePair? {
        val services = gatt.services ?: emptyList()

        // 1. Probe FFF0 (notify FFF1, write FFF2)
        gatt.getService(UUID_SERVICE_FFF0)?.let { svc ->
            val fff1 = svc.getCharacteristic(UUID_CHAR_FFF1)
            val fff2 = svc.getCharacteristic(UUID_CHAR_FFF2)
            if (fff1 != null && fff2 != null && isNotifiable(fff1) && isWritable(fff2)) {
                return SelectedBlePair("FFF0 (FFF1/FFF2)", svc.uuid, fff1, fff2)
            }
            // Also handle swapped FFF1/FFF2 on quirky clones
            if (fff1 != null && fff2 != null && isNotifiable(fff2) && isWritable(fff1)) {
                return SelectedBlePair("FFF0 (FFF2/FFF1)", svc.uuid, fff2, fff1)
            }
            findPairInService(svc, "FFF0")?.let { return it }
        }

        // 2. Probe FFE0 (FFE1 for both notify & write, or FFE1 + FFE2)
        gatt.getService(UUID_SERVICE_FFE0)?.let { svc ->
            val ffe1 = svc.getCharacteristic(UUID_CHAR_FFE1)
            val ffe2 = svc.getCharacteristic(UUID_CHAR_FFE2)
            if (ffe1 != null && isNotifiable(ffe1) && isWritable(ffe1)) {
                return SelectedBlePair("FFE0 (FFE1 shared)", svc.uuid, ffe1, ffe1)
            }
            if (ffe1 != null && ffe2 != null && isNotifiable(ffe1) && isWritable(ffe2)) {
                return SelectedBlePair("FFE0 (FFE1/FFE2)", svc.uuid, ffe1, ffe2)
            }
            findPairInService(svc, "FFE0")?.let { return it }
        }

        // 3. Probe 18F0 (notify 2AF0, write 2AF1)
        gatt.getService(UUID_SERVICE_18F0)?.let { svc ->
            val c2af0 = svc.getCharacteristic(UUID_CHAR_2AF0)
            val c2af1 = svc.getCharacteristic(UUID_CHAR_2AF1)
            if (c2af0 != null && c2af1 != null && isNotifiable(c2af0) && isWritable(c2af1)) {
                return SelectedBlePair("18F0 (2AF0/2AF1)", svc.uuid, c2af0, c2af1)
            }
            findPairInService(svc, "18F0")?.let { return it }
        }

        // 4. Probe Nordic UART (6E400001... RX=6E400002, TX=6E400003)
        gatt.getService(UUID_SERVICE_NUS)?.let { svc ->
            val rxWrite = svc.getCharacteristic(UUID_CHAR_NUS_RX)
            val txNotify = svc.getCharacteristic(UUID_CHAR_NUS_TX)
            if (txNotify != null && rxWrite != null && isNotifiable(txNotify) && isWritable(rxWrite)) {
                return SelectedBlePair("Nordic UART", svc.uuid, txNotify, rxWrite)
            }
            findPairInService(svc, "Nordic UART")?.let { return it }
        }

        // 5. Fallback: any service with one notifiable and one writable characteristic
        // Check non-standard services first (skipping Generic Access 1800 and Generic Attribute 1801)
        val nonGenericServices = services.filterNot {
            it.uuid == UUID_GENERIC_ACCESS || it.uuid == UUID_GENERIC_ATTRIBUTE
        }
        for (svc in nonGenericServices + services) {
            findPairInService(svc, "Fallback (${shortUuid(svc.uuid)})")?.let { return it }
        }

        return null
    }

    private fun findPairInService(
        service: android.bluetooth.BluetoothGattService,
        label: String
    ): SelectedBlePair? {
        val chars = service.characteristics ?: return null
        val notifiable = chars.filter { isNotifiable(it) }
        val writable = chars.filter { isWritable(it) }
        if (notifiable.isEmpty() || writable.isEmpty()) return null

        // Prefer separate notify and write characteristics if available, otherwise shared
        for (n in notifiable) {
            val distinctWrite = writable.firstOrNull { it.uuid != n.uuid }
            if (distinctWrite != null) {
                return SelectedBlePair(label, service.uuid, n, distinctWrite)
            }
        }
        return SelectedBlePair(label, service.uuid, notifiable.first(), writable.first())
    }

    private fun isNotifiable(characteristic: BluetoothGattCharacteristic): Boolean {
        val props = characteristic.properties
        return (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
            (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    }

    private fun isWritable(characteristic: BluetoothGattCharacteristic): Boolean {
        val props = characteristic.properties
        return (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
            (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    }

    private suspend fun enableNotificationsAndAwaitCccd(
        gatt: BluetoothGatt,
        notifyChar: BluetoothGattCharacteristic
    ) {
        gattOpMutex.withLock {
            val notifySet = gatt.setCharacteristicNotification(notifyChar, true)
            if (!notifySet) {
                throw IOException("BLE setCharacteristicNotification() returned false.")
            }

            val cccd = notifyChar.getDescriptor(UUID_CCCD)
            if (cccd == null) {
                // Some ultra-cheap BLE clones omit the CCCD descriptor; local notification enable is enough
                return@withLock
            }

            val useIndication =
                (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 &&
                    (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            val descriptorValue = if (useIndication) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }

            val descDef = CompletableDeferred<Int>()
            descriptorWriteDeferred = descDef

            val writeStarted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, descriptorValue) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = descriptorValue
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }

            if (!writeStarted) {
                descriptorWriteDeferred = null
                throw IOException("Failed to initiate BLE CCCD descriptor write.")
            }

            val status = withTimeout(GATT_OP_TIMEOUT_MS) {
                descDef.await()
            }
            descriptorWriteDeferred = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw IOException("BLE CCCD descriptor write failed with GATT status $status.")
            }
        }
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val gatt = bluetoothGatt
        val writeChar = writeCharacteristic
        if (!_isOpenFlow.value || gatt == null || writeChar == null) {
            throw IOException("BLE GATT transport is not open.")
        }

        val maxChunk = (negotiatedMtu - 3).coerceAtLeast(DEFAULT_PAYLOAD_SIZE)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + maxChunk, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            writeSingleChunk(gatt, writeChar, chunk)
            offset = end
        }
    }

    private suspend fun writeSingleChunk(
        gatt: BluetoothGatt,
        writeChar: BluetoothGattCharacteristic,
        chunk: ByteArray
    ) {
        gattOpMutex.withLock {
            if (!_isOpenFlow.value) {
                throw IOException("BLE transport closed during write.")
            }

            val writeType = resolveWriteType(writeChar)
            val writeDef = CompletableDeferred<Int>()
            charWriteDeferred = writeDef

            val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(writeChar, chunk, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                writeChar.writeType = writeType
                @Suppress("DEPRECATION")
                writeChar.value = chunk
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(writeChar)
            }

            if (!initiated) {
                charWriteDeferred = null
                throw IOException("BLE writeCharacteristic failed to start.")
            }

            val status = withTimeout(GATT_OP_TIMEOUT_MS) {
                writeDef.await()
            }
            charWriteDeferred = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw IOException("BLE writeCharacteristic failed with GATT status $status.")
            }
        }
    }

    private fun resolveWriteType(writeChar: BluetoothGattCharacteristic): Int {
        val props = writeChar.properties
        return when {
            (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 &&
                (props and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        _isOpenFlow.value = false
        synchronized(notificationBufferLock) {
            notificationBuffer.reset()
        }
        val gatt = bluetoothGatt
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        if (gatt != null) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    private fun handleUnexpectedDisconnect(reason: String) {
        val wasOpen = _isOpenFlow.value
        _isOpenFlow.value = false
        synchronized(notificationBufferLock) {
            // Flush any partial bytes so terminal shows what arrived before drop
            if (notificationBuffer.size() > 0) {
                _incomingBytes.tryEmit(notificationBuffer.toByteArray())
                notificationBuffer.reset()
            }
        }
        val gatt = bluetoothGatt
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        if (gatt != null) {
            runCatching { gatt.close() }
        }
        if (wasOpen) {
            _transportErrors.tryEmit(reason)
        }
    }

    private fun shortUuid(uuid: UUID): String {
        val full = uuid.toString().uppercase()
        return if (full.startsWith("0000") && full.endsWith("-0000-1000-8000-00805F9B34FB")) {
            full.substring(4, 8)
        } else {
            full.take(8)
        }
    }

    companion object {
        private const val DEFAULT_MTU = 23
        private const val DEFAULT_PAYLOAD_SIZE = 20
        private const val MAX_REQUESTED_MTU = 517
        private const val MAX_FRAGMENT_BUFFER_BYTES = 4096
        private const val GATT_CONNECT_TIMEOUT_MS = 10_000L
        private const val GATT_SERVICE_DISCOVERY_TIMEOUT_MS = 8_000L
        private const val GATT_OP_TIMEOUT_MS = 5_000L

        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val UUID_GENERIC_ACCESS: UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        val UUID_GENERIC_ATTRIBUTE: UUID = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")

        // Known OBD2 BLE UUID pairs
        val UUID_SERVICE_FFF0: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_FFF1: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_FFF2: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

        val UUID_SERVICE_FFE0: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_FFE1: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_FFE2: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")

        val UUID_SERVICE_18F0: UUID = UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_2AF0: UUID = UUID.fromString("00002af0-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_2AF1: UUID = UUID.fromString("00002af1-0000-1000-8000-00805f9b34fb")

        val UUID_SERVICE_NUS: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val UUID_CHAR_NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val UUID_CHAR_NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    }
}
