package com.obd2logger.ui.phase3

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2logger.data.logging.CsvLogStorage
import com.obd2logger.domain.model.ConnectionState
import com.obd2logger.domain.model.LoggingState
import com.obd2logger.service.ObdLoggingService
import com.obd2logger.ui.phase1.AdapterConnectionSheet
import com.obd2logger.ui.phase1.ConnectionStatusCard
import com.obd2logger.ui.phase1.DebugTerminalCard
import com.obd2logger.ui.phase1.SimulatorActiveBanner
import com.obd2logger.ui.phase1.copyLogToClipboard
import com.obd2logger.ui.phase1.entriesCountLabel
import com.obd2logger.ui.phase1.shareLog
import com.obd2logger.ui.phase2.LiveSensorsCard
import com.obd2logger.ui.phase4.ClearCodesResultDialog
import com.obd2logger.ui.phase4.ClearCodesWarningDialog
import com.obd2logger.ui.phase4.ManufacturerPidsScreen
import com.obd2logger.ui.phase4.MonitorTestsCard
import com.obd2logger.ui.theme.AccentYellow
import com.obd2logger.ui.theme.CardBorderDark
import com.obd2logger.ui.theme.CardDark
import com.obd2logger.ui.theme.MutedWhite
import com.obd2logger.ui.theme.PureWhite
import com.obd2logger.ui.theme.StatusGreen
import com.obd2logger.ui.theme.StatusRed
import com.obd2logger.ui.theme.StatusYellow
import com.obd2logger.ui.theme.TrueBlack
import kotlinx.coroutines.launch

private data class NavTab(
    val label: String,
    val icon: ImageVector
)

private val navTabs = listOf(
    NavTab("Home", Icons.Default.Home),
    NavTab("Mfr PIDs", Icons.Default.Build),
    NavTab("Terminal", Icons.Default.Terminal),
    NavTab("Logs", Icons.Default.Folder),
    NavTab("Settings", Icons.Default.Settings)
)

@Composable
fun MainScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val onboardingCompleted by viewModel.onboardingCompleted.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val supportedPids by viewModel.supportedPids.collectAsStateWithLifecycle()
    val lastAdapterConfig by viewModel.lastAdapterConfig.collectAsStateWithLifecycle()
    val savedWifiEndpoint by viewModel.savedWifiEndpoint.collectAsStateWithLifecycle()
    val preferredBtPin by viewModel.preferredBtPin.collectAsStateWithLifecycle()
    val classicDevices by viewModel.classicDevices.collectAsStateWithLifecycle()
    val bleDevices by viewModel.bleDevices.collectAsStateWithLifecycle()
    val isClassicScanning by viewModel.isClassicScanning.collectAsStateWithLifecycle()
    val isBleScanning by viewModel.isBleScanning.collectAsStateWithLifecycle()
    val terminalEntries by viewModel.terminalEntries.collectAsStateWithLifecycle()
    val showHeartbeats by viewModel.showHeartbeats.collectAsStateWithLifecycle()
    val isSendingCommand by viewModel.isSendingManualCommand.collectAsStateWithLifecycle()
    val liveSensorValues by viewModel.liveSensorValues.collectAsStateWithLifecycle()
    val isPollingEnabled by viewModel.isPollingEnabled.collectAsStateWithLifecycle()
    val isBatchingEnabled by viewModel.isBatchingEnabled.collectAsStateWithLifecycle()
    val isBatchFallbackActive by viewModel.isBatchFallbackActive.collectAsStateWithLifecycle()
    val loggingState by viewModel.loggingState.collectAsStateWithLifecycle()
    val latestDiagnostics by viewModel.latestDiagnostics.collectAsStateWithLifecycle()
    val latestMode06Tests by viewModel.latestMode06Tests.collectAsStateWithLifecycle()
    val sessionGroups by viewModel.sessionGroups.collectAsStateWithLifecycle()
    val isClearingCodes by viewModel.isClearingCodes.collectAsStateWithLifecycle()
    val lastClearResult by viewModel.lastClearResult.collectAsStateWithLifecycle()
    val manufacturerPidStates by viewModel.manufacturerPidStates.collectAsStateWithLifecycle()
    val detectedMakes by viewModel.detectedMakes.collectAsStateWithLifecycle()
    val isProbingMfrPids by viewModel.isProbingMfrPids.collectAsStateWithLifecycle()
    val mfrImportMessage by viewModel.mfrImportMessage.collectAsStateWithLifecycle()
    val pollingSpeed by viewModel.pollingSpeed.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()

    var hasLocationPermission by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    var showConnectionSheet by rememberSaveable { mutableStateOf(false) }
    var showClearWarningDialog by rememberSaveable { mutableStateOf(false) }
    var manualCommandText by rememberSaveable { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.startLogging()
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasLocationPermission = viewModel.hasLocationPermission()
    }

    val jsonImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importManufacturerPidJson(uri)
        }
    }

    fun triggerStartLogging() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        viewModel.startLogging()
    }

    fun openFolderSafe() {
        try {
            context.startActivity(viewModel.buildOpenFolderIntent())
        } catch (_: ActivityNotFoundException) {
            scope.launch {
                snackbarHostState.showSnackbar("CSV logs are saved in ${CsvLogStorage.DISPLAY_FOLDER_PATH}")
            }
        }
    }

    fun openCsvSafe(uriString: String) {
        try {
            context.startActivity(viewModel.buildOpenCsvIntent(uriString))
        } catch (_: ActivityNotFoundException) {
            scope.launch {
                snackbarHostState.showSnackbar("No CSV viewer app installed — use Share to export.")
            }
        }
    }

    LaunchedEffect(loggingState) {
        val err = loggingState as? LoggingState.Error
        if (err != null) {
            snackbarHostState.showSnackbar(err.message)
            viewModel.dismissPostLogScreen()
        }
    }

    when (onboardingCompleted) {
        null -> {
            Box(modifier = Modifier.fillMaxSize().background(TrueBlack))
            return
        }
        false -> {
            OnboardingScreen(
                onFinish = { viewModel.setOnboardingCompleted(true) }
            )
            return
        }
        true -> Unit
    }

    val stoppedState = loggingState as? LoggingState.Stopped
    if (stoppedState != null) {
        LogSavedScreen(
            summary = stoppedState.summary,
            diagnostics = stoppedState.diagnostics,
            artifacts = stoppedState.artifacts,
            zipUri = stoppedState.zipUri,
            zipFilename = stoppedState.zipFilename,
            isZipBuilding = stoppedState.isZipBuilding,
            onShareMainCsv = {
                runCatching {
                    context.startActivity(
                        viewModel.buildShareCsvIntent(
                            stoppedState.summary.uri,
                            stoppedState.summary.filename
                        )
                    )
                }
            },
            onShareSessionZip = {
                val uri = stoppedState.zipUri ?: return@LogSavedScreen
                val name = stoppedState.zipFilename ?: return@LogSavedScreen
                runCatching {
                    context.startActivity(viewModel.buildShareCsvIntent(uri, name))
                }
            },
            onOpenFolder = { openFolderSafe() },
            onDone = { viewModel.dismissPostLogScreen() }
        )
        return
    }

    if (showClearWarningDialog) {
        ClearCodesWarningDialog(
            onConfirmClear = {
                showClearWarningDialog = false
                viewModel.clearCodes()
            },
            onDismiss = { showClearWarningDialog = false }
        )
    }

    lastClearResult?.let { clearRes ->
        ClearCodesResultDialog(
            result = clearRes,
            onDismiss = viewModel::dismissClearResult
        )
    }

    if (showConnectionSheet) {
        AdapterConnectionSheet(
            classicDevices = classicDevices,
            bleDevices = bleDevices,
            isClassicScanning = isClassicScanning,
            isBleScanning = isBleScanning,
            preferredPin = preferredBtPin,
            savedWifiEndpoint = savedWifiEndpoint,
            onStartClassicScan = viewModel::startClassicScan,
            onStopClassicScan = viewModel::stopClassicScan,
            onStartBleScan = viewModel::startBleScan,
            onStopBleScan = viewModel::stopBleScan,
            onSavePreferredPin = viewModel::savePreferredPin,
            onSaveWifiEndpoint = viewModel::saveWifiEndpoint,
            onSelectAdapter = { config ->
                showConnectionSheet = false
                viewModel.connect(config)
            },
            onDismiss = {
                showConnectionSheet = false
                viewModel.stopAllScans()
            }
        )
    }

    val isConnected = connectionState is ConnectionState.Connected
    val isActivelyLogging = loggingState is LoggingState.Preparing || loggingState is LoggingState.Logging

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(TrueBlack)
            .systemBarsPadding()
            .imePadding(),
        containerColor = TrueBlack,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = CardDark,
                tonalElevation = 0.dp
            ) {
                navTabs.forEachIndexed { index, tab ->
                    val isSelected = selectedTab == index
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTab = index },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TrueBlack,
                            selectedTextColor = AccentYellow,
                            indicatorColor = AccentYellow,
                            unselectedIconColor = MutedWhite,
                            unselectedTextColor = MutedWhite
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (connectionState.isSimulator) {
                            SimulatorActiveBanner()
                        }

                        ConnectionStatusCard(
                            state = connectionState,
                            supportedPids = supportedPids,
                            lastAdapterConfig = lastAdapterConfig,
                            onOpenConnectionSheet = { showConnectionSheet = true },
                            onOneTapReconnect = viewModel::reconnectLastAdapter,
                            onDisconnect = viewModel::disconnect,
                            onRetryCarConnection = viewModel::retryVehicleConnection
                        )

                        LoggingControlCard(
                            connectionState = connectionState,
                            loggingState = loggingState,
                            onStartLogging = { triggerStartLogging() },
                            onStopLogging = viewModel::stopLogging
                        )

                        AnimatedVisibility(
                            visible = latestDiagnostics != null,
                            enter = fadeIn(tween(250)),
                            exit = fadeOut(tween(200))
                        ) {
                            latestDiagnostics?.let { diag ->
                                DiagnosticResultsCard(
                                    diagnostics = diag,
                                    canClearCodes = isConnected && !isActivelyLogging,
                                    isClearingCodes = isClearingCodes,
                                    onRequestClearCodes = { showClearWarningDialog = true }
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = latestMode06Tests.isNotEmpty(),
                            enter = fadeIn(tween(250)),
                            exit = fadeOut(tween(200))
                        ) {
                            MonitorTestsCard(tests = latestMode06Tests)
                        }

                        LiveSensorsCard(
                            modifier = Modifier.height(420.dp),
                            isConnected = isConnected,
                            liveValues = liveSensorValues,
                            isPollingEnabled = isPollingEnabled,
                            isBatchingEnabled = isBatchingEnabled,
                            isBatchFallbackActive = isBatchFallbackActive,
                            onTogglePolling = viewModel::togglePolling,
                            onToggleBatching = viewModel::toggleBatching
                        )
                    }
                }

                1 -> {
                    ManufacturerPidsScreen(
                        pidStates = manufacturerPidStates,
                        detectedMakes = detectedMakes,
                        isConnected = isConnected,
                        isLogging = isActivelyLogging,
                        isProbing = isProbingMfrPids,
                        lastImportMessage = mfrImportMessage,
                        onProbeNow = viewModel::probeManufacturerPids,
                        onImportJson = {
                            jsonImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        onTogglePid = viewModel::setManufacturerPidEnabled
                    )
                }

                2 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (connectionState.isSimulator) {
                            SimulatorActiveBanner()
                        }
                        DebugTerminalCard(
                            modifier = Modifier.weight(1f),
                            entries = terminalEntries,
                            showHeartbeats = showHeartbeats,
                            isSendingCommand = isSendingCommand,
                            isTransportWritable = connectionState is ConnectionState.AdapterReady ||
                                isConnected,
                            manualCommandText = manualCommandText,
                            onManualCommandChange = { manualCommandText = it },
                            onSendCommand = { cmd ->
                                viewModel.sendManualCommand(cmd)
                                if (cmd == manualCommandText) {
                                    manualCommandText = ""
                                }
                            },
                            onToggleHeartbeats = viewModel::toggleShowHeartbeats,
                            onCopyLog = {
                                val text = viewModel.getFullLogText()
                                copyLogToClipboard(context, text)
                                scope.launch {
                                    snackbarHostState.showSnackbar("Raw log copied (${entriesCountLabel(text)})")
                                }
                            },
                            onShareLog = {
                                val text = viewModel.getFullLogText()
                                val file = viewModel.getLogFile()
                                shareLog(context, text, file)
                            },
                            onClearLog = {
                                viewModel.clearLog()
                                scope.launch {
                                    snackbarHostState.showSnackbar("Terminal log cleared")
                                }
                            }
                        )
                    }
                }

                3 -> {
                    PastLogsScreen(
                        sessions = sessionGroups,
                        onRefresh = viewModel::refreshSavedLogs,
                        onOpenMainCsv = { log -> openCsvSafe(log.uri) },
                        onShareMainCsv = { log ->
                            runCatching {
                                context.startActivity(viewModel.buildShareCsvIntent(log.uri, log.filename))
                            }
                        },
                        onShareSessionZip = { group ->
                            viewModel.shareSessionZip(
                                group = group,
                                onReady = { intent -> runCatching { context.startActivity(intent) } },
                                onError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                            )
                        },
                        onDeleteSession = { group ->
                            viewModel.deleteSessionGroup(group)
                            scope.launch {
                                snackbarHostState.showSnackbar("Deleted session ${group.stem}")
                            }
                        },
                        onOpenFolder = { openFolderSafe() }
                    )
                }

                4 -> {
                    SettingsScreen(
                        savedWifiEndpoint = savedWifiEndpoint,
                        pollingSpeed = pollingSpeed,
                        keepScreenOn = keepScreenOn,
                        hasLocationPermission = hasLocationPermission,
                        onSaveWifiEndpoint = { ip, port ->
                            viewModel.saveWifiEndpoint(ip, port)
                            scope.launch {
                                snackbarHostState.showSnackbar("Saved Wi-Fi endpoint $ip:$port")
                            }
                        },
                        onSelectPollingSpeed = viewModel::setPollingSpeed,
                        onToggleKeepScreenOn = viewModel::setKeepScreenOn,
                        onRequestLocationPermission = {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        },
                        onReplayOnboarding = { viewModel.setOnboardingCompleted(false) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoggingControlCard(
    connectionState: ConnectionState,
    loggingState: LoggingState,
    onStartLogging: () -> Unit,
    onStopLogging: () -> Unit
) {
    val isConnected = connectionState is ConnectionState.Connected
    val isPreparing = loggingState is LoggingState.Preparing
    val activeLog = loggingState as? LoggingState.Logging

    val buttonContainerColor by animateColorAsState(
        targetValue = when {
            activeLog != null -> StatusRed
            isPreparing -> CardBorderDark
            isConnected -> AccentYellow
            else -> CardBorderDark
        },
        label = "logBtnColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            when {
                activeLog != null -> StatusRed.copy(alpha = 0.7f)
                isConnected -> AccentYellow.copy(alpha = 0.5f)
                else -> CardBorderDark
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (activeLog != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (activeLog.isAdapterConnected) "LOGGING ACTIVE" else "RECONNECTING (WRITING '-')",
                            color = if (activeLog.isAdapterConnected) StatusGreen else StatusYellow,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = activeLog.filename,
                            color = MutedWhite,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("TIME", color = MutedWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = ObdLoggingService.formatElapsed(activeLog.elapsedMs),
                                color = PureWhite,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 19.sp
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("ROWS", color = MutedWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = "${activeLog.rowCount}",
                                color = AccentYellow,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 19.sp
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    when {
                        activeLog != null -> onStopLogging()
                        isPreparing -> onStopLogging()
                        else -> onStartLogging()
                    }
                },
                enabled = isConnected || activeLog != null || isPreparing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonContainerColor,
                    contentColor = if (activeLog != null) PureWhite else TrueBlack,
                    disabledContainerColor = CardBorderDark,
                    disabledContentColor = MutedWhite.copy(alpha = 0.6f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .then(
                        if (isConnected && activeLog == null && !isPreparing) {
                            Modifier.shadow(
                                elevation = 16.dp,
                                shape = RoundedCornerShape(16.dp),
                                ambientColor = AccentYellow,
                                spotColor = AccentYellow
                            )
                        } else {
                            Modifier
                        }
                    ),
                shape = RoundedCornerShape(16.dp)
            ) {
                when {
                    isPreparing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = AccentYellow,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = (loggingState as LoggingState.Preparing).stepMessage,
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    activeLog != null -> {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "STOP LOGGING",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 19.sp
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "START LOGGING",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 19.sp
                        )
                    }
                }
            }

            if (!isConnected && activeLog == null && !isPreparing) {
                Text(
                    text = "Connect to your vehicle ECU (green Connected status) to enable CSV logging.",
                    color = MutedWhite,
                    fontSize = 12.sp
                )
            }
        }
    }
}
