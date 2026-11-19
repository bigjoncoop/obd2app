package com.obd2logger.data.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.obd2logger.domain.model.ScannedBtDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles scanning for both Bluetooth Classic (paired + newly discovered via startDiscovery)
 * and Bluetooth LE devices (via BluetoothLeScanner).
 *
 * Intentionally does NOT filter devices by name because cheap ELM327 clones use arbitrary
 * names (OBDII, V-LINK, Vgate, IOS-Vlink, CBT, Android-Vlink, CHX, etc.) or no name at all.
 */
@Singleton
@SuppressLint("MissingPermission")
class BluetoothDeviceScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _classicDevices = MutableStateFlow<List<ScannedBtDevice>>(emptyList())
    val classicDevices: StateFlow<List<ScannedBtDevice>> = _classicDevices.asStateFlow()

    private val _bleDevices = MutableStateFlow<List<ScannedBtDevice>>(emptyList())
    val bleDevices: StateFlow<List<ScannedBtDevice>> = _bleDevices.asStateFlow()

    private val _isClassicScanning = MutableStateFlow(false)
    val isClassicScanning: StateFlow<Boolean> = _isClassicScanning.asStateFlow()

    private val _isBleScanning = MutableStateFlow(false)
    val isBleScanning: StateFlow<Boolean> = _isBleScanning.asStateFlow()

    private var classicReceiverRegistered = false

    private val classicDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssiShort = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    val rssi = if (rssiShort != Short.MIN_VALUE) rssiShort.toInt() else null
                    if (device != null && !device.address.isNullOrBlank()) {
                        upsertClassicDevice(device, rssi)
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null && !device.address.isNullOrBlank()) {
                        upsertClassicDevice(device, null)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _isClassicScanning.value = true
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isClassicScanning.value = false
                }
            }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val name = runCatching { device.name }.getOrNull()
                ?: result.scanRecord?.deviceName
            val isBonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
            val scanned = ScannedBtDevice(
                address = address,
                name = name,
                isPaired = isBonded,
                rssi = result.rssi,
                isBle = true
            )
            _bleDevices.update { current ->
                val mutable = current.toMutableList()
                val idx = mutable.indexOfFirst { it.address.equals(address, ignoreCase = true) }
                if (idx >= 0) {
                    val existing = mutable[idx]
                    mutable[idx] = scanned.copy(
                        name = if (!scanned.name.isNullOrBlank()) scanned.name else existing.name
                    )
                } else {
                    mutable.add(scanned)
                }
                mutable.sortedWith(
                    compareByDescending<ScannedBtDevice> { !it.name.isNullOrBlank() }
                        .thenByDescending { it.rssi ?: -120 }
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _isBleScanning.value = false
        }
    }

    /**
     * Populates already-paired devices immediately and starts Bluetooth Classic discovery
     * for unpaired nearby devices.
     */
    fun startClassicScan() {
        val adapter = bluetoothAdapter ?: return
        stopBleScan()
        refreshPairedDevices()
        registerClassicReceiverIfNeeded()
        runCatching {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            val started = adapter.startDiscovery()
            _isClassicScanning.value = started
        }
    }

    fun refreshPairedDevices() {
        val adapter = bluetoothAdapter ?: return
        val bonded = runCatching { adapter.bondedDevices ?: emptySet() }.getOrDefault(emptySet())
        val mapped = bonded.mapNotNull { dev ->
            val addr = dev.address ?: return@mapNotNull null
            ScannedBtDevice(
                address = addr,
                name = runCatching { dev.name }.getOrNull(),
                isPaired = true,
                rssi = null,
                isBle = false
            )
        }
        _classicDevices.update { current ->
            val unpairedDiscovered = current.filterNot { existing ->
                mapped.any { it.address.equals(existing.address, ignoreCase = true) }
            }
            (mapped + unpairedDiscovered).sortedWith(
                compareByDescending<ScannedBtDevice> { it.isPaired }
                    .thenByDescending { !it.name.isNullOrBlank() }
                    .thenBy { it.displayTitle.lowercase() }
            )
        }
    }

    fun stopClassicScan() {
        runCatching {
            val adapter = bluetoothAdapter
            if (adapter?.isDiscovering == true) {
                adapter.cancelDiscovery()
            }
        }
        _isClassicScanning.value = false
    }

    fun startBleScan() {
        val adapter = bluetoothAdapter ?: return
        stopClassicScan()
        val scanner = adapter.bluetoothLeScanner ?: return
        if (_isBleScanning.value) {
            stopBleScan()
        }
        // Seed with any already-bonded BLE/Dual devices so user sees them right away too
        val bondedLe = runCatching { adapter.bondedDevices ?: emptySet() }.getOrDefault(emptySet())
            .filter { dev ->
                val type = runCatching { dev.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
                type == BluetoothDevice.DEVICE_TYPE_LE || type == BluetoothDevice.DEVICE_TYPE_DUAL
            }
            .mapNotNull { dev ->
                val addr = dev.address ?: return@mapNotNull null
                ScannedBtDevice(
                    address = addr,
                    name = runCatching { dev.name }.getOrNull(),
                    isPaired = true,
                    rssi = null,
                    isBle = true
                )
            }
        _bleDevices.value = bondedLe

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching {
            scanner.startScan(null, settings, bleScanCallback)
            _isBleScanning.value = true
        }.onFailure {
            _isBleScanning.value = false
        }
    }

    fun stopBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (_isBleScanning.value && scanner != null) {
            runCatching { scanner.stopScan(bleScanCallback) }
        }
        _isBleScanning.value = false
    }

    fun stopAllScans() {
        stopClassicScan()
        stopBleScan()
    }

    private fun upsertClassicDevice(device: BluetoothDevice, rssi: Int?) {
        val addr = device.address ?: return
        val name = runCatching { device.name }.getOrNull()
        val isPaired = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        val entry = ScannedBtDevice(
            address = addr,
            name = name,
            isPaired = isPaired,
            rssi = rssi,
            isBle = false
        )
        _classicDevices.update { current ->
            val mutable = current.toMutableList()
            val idx = mutable.indexOfFirst { it.address.equals(addr, ignoreCase = true) }
            if (idx >= 0) {
                val old = mutable[idx]
                mutable[idx] = entry.copy(
                    name = if (!entry.name.isNullOrBlank()) entry.name else old.name,
                    rssi = entry.rssi ?: old.rssi
                )
            } else {
                mutable.add(entry)
            }
            mutable.sortedWith(
                compareByDescending<ScannedBtDevice> { it.isPaired }
                    .thenByDescending { !it.name.isNullOrBlank() }
                    .thenBy { it.displayTitle.lowercase() }
            )
        }
    }

    private fun registerClassicReceiverIfNeeded() {
        if (classicReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(classicDiscoveryReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(classicDiscoveryReceiver, filter)
        }
        classicReceiverRegistered = true
    }
}
