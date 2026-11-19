package com.obd2logger.ui.phase1

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obd2logger.data.logging.RawSessionLogRepository
import com.obd2logger.data.preferences.AdapterPreferencesRepository
import com.obd2logger.data.preferences.SavedWifiEndpoint
import com.obd2logger.data.protocol.ElmSessionManager
import com.obd2logger.data.protocol.SensorPollingEngine
import com.obd2logger.data.transport.BluetoothDeviceScanner
import com.obd2logger.domain.model.AdapterConfig
import com.obd2logger.domain.model.ConnectionState
import com.obd2logger.domain.model.LiveSensorValue
import com.obd2logger.domain.model.ScannedBtDevice
import com.obd2logger.domain.model.TerminalLogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class Phase1ViewModel @Inject constructor(
    private val sessionManager: ElmSessionManager,
    private val sensorPollingEngine: SensorPollingEngine,
    private val scanner: BluetoothDeviceScanner,
    private val logRepository: RawSessionLogRepository,
    private val preferencesRepository: AdapterPreferencesRepository
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = sessionManager.connectionState
    val supportedPids: StateFlow<Set<Int>> = sessionManager.supportedPids
    val activeAdapterConfig: StateFlow<AdapterConfig?> = sessionManager.activeAdapterConfig

    val liveSensorValues: StateFlow<List<LiveSensorValue>> = sensorPollingEngine.liveValues
    val isPollingEnabled: StateFlow<Boolean> = sensorPollingEngine.isPollingEnabled
    val isBatchingEnabled: StateFlow<Boolean> = sensorPollingEngine.isBatchingEnabled
    val isBatchFallbackActive: StateFlow<Boolean> = sensorPollingEngine.isBatchFallbackActive

    val lastAdapterConfig: StateFlow<AdapterConfig?> = preferencesRepository.lastAdapterConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val savedWifiEndpoint: StateFlow<SavedWifiEndpoint> = preferencesRepository.savedWifiEndpoint
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SavedWifiEndpoint())

    val preferredBtPin: StateFlow<String> = preferencesRepository.preferredBtPin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "1234")

    val classicDevices: StateFlow<List<ScannedBtDevice>> = scanner.classicDevices
    val bleDevices: StateFlow<List<ScannedBtDevice>> = scanner.bleDevices
    val isClassicScanning: StateFlow<Boolean> = scanner.isClassicScanning
    val isBleScanning: StateFlow<Boolean> = scanner.isBleScanning

    private val _showHeartbeats = MutableStateFlow(true)
    val showHeartbeats: StateFlow<Boolean> = _showHeartbeats.asStateFlow()

    val terminalEntries: StateFlow<List<TerminalLogEntry>> = combine(
        logRepository.entries,
        _showHeartbeats
    ) { entries, showHb ->
        if (showHb) entries else entries.filterNot { it.isHeartbeat }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isSendingManualCommand = MutableStateFlow(false)
    val isSendingManualCommand: StateFlow<Boolean> = _isSendingManualCommand.asStateFlow()

    val persistedLogFilePath: String
        get() = logRepository.lastSessionLogFile.absolutePath

    fun connect(config: AdapterConfig) {
        sessionManager.connect(config, isAutoReconnect = false)
    }

    fun reconnectLastAdapter() {
        val last = lastAdapterConfig.value ?: return
        sessionManager.connect(last, isAutoReconnect = false)
    }

    fun disconnect() {
        sessionManager.disconnect("Disconnected by user.")
    }

    fun retryVehicleConnection() {
        sessionManager.retryVehicleConnection()
    }

    fun startClassicScan() {
        scanner.startClassicScan()
    }

    fun stopClassicScan() {
        scanner.stopClassicScan()
    }

    fun startBleScan() {
        scanner.startBleScan()
    }

    fun stopBleScan() {
        scanner.stopBleScan()
    }

    fun stopAllScans() {
        scanner.stopAllScans()
    }

    fun saveWifiEndpoint(ip: String, portStr: String) {
        val port = portStr.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 35000
        viewModelScope.launch {
            preferencesRepository.saveWifiEndpoint(ip.trim(), port)
        }
    }

    fun savePreferredPin(pin: String) {
        viewModelScope.launch {
            preferencesRepository.savePreferredPin(pin)
        }
    }

    fun sendManualCommand(rawCommand: String) {
        if (rawCommand.isBlank()) return
        viewModelScope.launch {
            _isSendingManualCommand.value = true
            try {
                sessionManager.sendManualCommand(rawCommand)
            } finally {
                _isSendingManualCommand.value = false
            }
        }
    }

    fun toggleShowHeartbeats() {
        _showHeartbeats.value = !_showHeartbeats.value
    }

    fun togglePolling() {
        sensorPollingEngine.setPollingEnabled(!isPollingEnabled.value)
    }

    fun toggleBatching() {
        sensorPollingEngine.setBatchingEnabled(!isBatchingEnabled.value)
    }

    fun clearLog() {
        logRepository.clearLog()
    }

    fun getFullLogText(): String {
        return logRepository.getFullLogText(includeHeartbeats = _showHeartbeats.value)
    }

    fun getLogFile(): File = logRepository.lastSessionLogFile

    override fun onCleared() {
        super.onCleared()
        scanner.stopAllScans()
    }
}
