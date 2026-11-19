package com.obd2logger.ui.phase3

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obd2logger.data.logging.CsvLogStorage
import com.obd2logger.data.logging.LocationSnapshotProvider
import com.obd2logger.data.logging.LoggingSessionCoordinator
import com.obd2logger.data.logging.RawSessionLogRepository
import com.obd2logger.data.preferences.AdapterPreferencesRepository
import com.obd2logger.data.preferences.PollingSpeed
import com.obd2logger.data.preferences.SavedWifiEndpoint
import com.obd2logger.data.protocol.ClearCodesResult
import com.obd2logger.data.protocol.ElmSessionManager
import com.obd2logger.data.protocol.ManufacturerPidRepository
import com.obd2logger.data.protocol.SensorPollingEngine
import com.obd2logger.data.transport.BluetoothDeviceScanner
import com.obd2logger.domain.model.AdapterConfig
import com.obd2logger.domain.model.ConnectionState
import com.obd2logger.domain.model.LiveSensorValue
import com.obd2logger.domain.model.LoggingState
import com.obd2logger.domain.model.ManufacturerPidState
import com.obd2logger.domain.model.Mode06TestResult
import com.obd2logger.domain.model.SavedCsvLogSummary
import com.obd2logger.domain.model.ScannedBtDevice
import com.obd2logger.domain.model.SessionDiagnosticSnapshot
import com.obd2logger.domain.model.SessionLogGroup
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
class MainViewModel @Inject constructor(
    private val sessionManager: ElmSessionManager,
    private val sensorPollingEngine: SensorPollingEngine,
    private val scanner: BluetoothDeviceScanner,
    private val logRepository: RawSessionLogRepository,
    private val preferencesRepository: AdapterPreferencesRepository,
    private val loggingCoordinator: LoggingSessionCoordinator,
    private val manufacturerPidRepository: ManufacturerPidRepository,
    private val csvLogStorage: CsvLogStorage,
    private val locationProvider: LocationSnapshotProvider
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = sessionManager.connectionState
    val supportedPids: StateFlow<Set<Int>> = sessionManager.supportedPids
    val activeAdapterConfig: StateFlow<AdapterConfig?> = sessionManager.activeAdapterConfig

    val liveSensorValues: StateFlow<List<LiveSensorValue>> = sensorPollingEngine.liveValues
    val isPollingEnabled: StateFlow<Boolean> = sensorPollingEngine.isPollingEnabled
    val isBatchingEnabled: StateFlow<Boolean> = sensorPollingEngine.isBatchingEnabled
    val isBatchFallbackActive: StateFlow<Boolean> = sensorPollingEngine.isBatchFallbackActive

    val loggingState: StateFlow<LoggingState> = loggingCoordinator.loggingState
    val latestDiagnostics: StateFlow<SessionDiagnosticSnapshot?> = loggingCoordinator.latestDiagnostics
    val latestMode06Tests: StateFlow<List<Mode06TestResult>> = loggingCoordinator.latestMode06Tests
    val sessionGroups: StateFlow<List<SessionLogGroup>> = loggingCoordinator.sessionGroups
    val isClearingCodes: StateFlow<Boolean> = loggingCoordinator.isClearingCodes
    val lastClearResult: StateFlow<ClearCodesResult?> = loggingCoordinator.lastClearResult

    val manufacturerPidStates: StateFlow<List<ManufacturerPidState>> = manufacturerPidRepository.pidStates
    val detectedMakes: StateFlow<Set<String>> = manufacturerPidRepository.detectedMakes
    val isProbingMfrPids: StateFlow<Boolean> = loggingCoordinator.isProbingMfrPids
    val mfrImportMessage: StateFlow<String?> = manufacturerPidRepository.lastImportMessage

    val lastAdapterConfig: StateFlow<AdapterConfig?> = preferencesRepository.lastAdapterConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val savedWifiEndpoint: StateFlow<SavedWifiEndpoint> = preferencesRepository.savedWifiEndpoint
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SavedWifiEndpoint())

    val preferredBtPin: StateFlow<String> = preferencesRepository.preferredBtPin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "1234")

    val onboardingCompleted: StateFlow<Boolean?> = preferencesRepository.onboardingCompleted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pollingSpeed: StateFlow<PollingSpeed> = preferencesRepository.pollingSpeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PollingSpeed.FAST)

    val keepScreenOn: StateFlow<Boolean> = preferencesRepository.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    fun connect(config: AdapterConfig) = sessionManager.connect(config, isAutoReconnect = false)

    fun reconnectLastAdapter() {
        val last = lastAdapterConfig.value ?: return
        sessionManager.connect(last, isAutoReconnect = false)
    }

    fun disconnect() = sessionManager.disconnect("Disconnected by user.")

    fun retryVehicleConnection() = sessionManager.retryVehicleConnection()

    fun startClassicScan() = scanner.startClassicScan()
    fun stopClassicScan() = scanner.stopClassicScan()
    fun startBleScan() = scanner.startBleScan()
    fun stopBleScan() = scanner.stopBleScan()
    fun stopAllScans() = scanner.stopAllScans()

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

    fun setOnboardingCompleted(completed: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setOnboardingCompleted(completed)
        }
    }

    fun setPollingSpeed(speed: PollingSpeed) {
        viewModelScope.launch {
            preferencesRepository.setPollingSpeed(speed)
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setKeepScreenOn(enabled)
        }
    }

    fun startLogging() = loggingCoordinator.startLogging()
    fun stopLogging() = loggingCoordinator.stopLogging()
    fun dismissPostLogScreen() = loggingCoordinator.dismissPostLogScreen()

    fun clearCodes() = loggingCoordinator.clearCodes()
    fun dismissClearResult() = loggingCoordinator.dismissClearResult()

    fun probeManufacturerPids() = loggingCoordinator.probeManufacturerPidsNow()
    fun setManufacturerPidEnabled(pidId: String, enabled: Boolean) =
        manufacturerPidRepository.setPidEnabled(pidId, enabled)

    fun importManufacturerPidJson(uri: Uri) {
        manufacturerPidRepository.importJsonFromUri(uri)
    }

    fun refreshSavedLogs() = loggingCoordinator.refreshSavedLogs()
    fun deleteSessionGroup(group: SessionLogGroup) = loggingCoordinator.deleteSessionGroup(group)

    fun shareSessionZip(group: SessionLogGroup, onReady: (Intent) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            loggingCoordinator.ensureSessionZipForGroup(group)
                .onSuccess { zipSummary ->
                    onReady(csvLogStorage.buildShareIntent(zipSummary.uri, zipSummary.filename))
                }
                .onFailure { err ->
                    onError(err.message ?: "Unable to build session ZIP.")
                }
        }
    }

    fun buildShareCsvIntent(uriString: String, filename: String): Intent =
        csvLogStorage.buildShareIntent(uriString, filename)

    fun buildOpenCsvIntent(uriString: String): Intent =
        csvLogStorage.buildOpenFileIntent(uriString)

    fun buildOpenFolderIntent(): Intent =
        csvLogStorage.buildOpenFolderIntent()

    fun hasLocationPermission(): Boolean = locationProvider.hasLocationPermission()

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

    fun clearLog() = logRepository.clearLog()

    fun getFullLogText(): String = logRepository.getFullLogText(includeHeartbeats = _showHeartbeats.value)

    fun getLogFile(): File = logRepository.lastSessionLogFile

    override fun onCleared() {
        super.onCleared()
        scanner.stopAllScans()
    }
}
