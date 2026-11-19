package com.obd2logger.ui.phase1

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2logger.domain.model.AdapterConfig
import com.obd2logger.domain.model.ConnectionState
import com.obd2logger.domain.model.LogEntryType
import com.obd2logger.domain.model.TerminalLogEntry
import com.obd2logger.ui.phase2.LiveSensorsCard
import com.obd2logger.ui.theme.AccentYellow
import com.obd2logger.ui.theme.CardBorderDark
import com.obd2logger.ui.theme.CardDark
import com.obd2logger.ui.theme.InfoBlue
import com.obd2logger.ui.theme.MutedWhite
import com.obd2logger.ui.theme.PureWhite
import com.obd2logger.ui.theme.StatusGreen
import com.obd2logger.ui.theme.StatusRed
import com.obd2logger.ui.theme.StatusYellow
import com.obd2logger.ui.theme.TrueBlack
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun Phase1DebugTerminalScreen(
    viewModel: Phase1ViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

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

    var showConnectionSheet by rememberSaveable { mutableStateOf(false) }
    var manualCommandText by rememberSaveable { mutableStateOf("") }
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }

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

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(TrueBlack)
            .systemBarsPadding()
            .imePadding(),
        containerColor = TrueBlack,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Prominent SIMULATOR Banner whenever simulator is active
            if (connectionState.isSimulator) {
                SimulatorActiveBanner()
            }

            // Connection Status Card (Disconnected / Connecting / Adapter Ready / Connected)
            ConnectionStatusCard(
                state = connectionState,
                supportedPids = supportedPids,
                lastAdapterConfig = lastAdapterConfig,
                onOpenConnectionSheet = { showConnectionSheet = true },
                onOneTapReconnect = viewModel::reconnectLastAdapter,
                onDisconnect = viewModel::disconnect,
                onRetryCarConnection = viewModel::retryVehicleConnection
            )

            // Phase 2 Tab Switcher: Live Sensors <-> Debug Terminal (raw TX/RX logging always on)
            val tabs = listOf("Live Sensors", "Debug Terminal")
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = CardDark,
                contentColor = AccentYellow,
                indicator = { tabPositions ->
                    if (selectedTabIndex < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                            color = AccentYellow
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CardBorderDark, RoundedCornerShape(12.dp))
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTabIndex == index) AccentYellow else MutedWhite,
                                fontSize = 14.sp
                            )
                        }
                    )
                }
            }

            if (selectedTabIndex == 0) {
                LiveSensorsCard(
                    modifier = Modifier.weight(1f),
                    isConnected = connectionState is ConnectionState.Connected,
                    liveValues = liveSensorValues,
                    isPollingEnabled = isPollingEnabled,
                    isBatchingEnabled = isBatchingEnabled,
                    isBatchFallbackActive = isBatchFallbackActive,
                    onTogglePolling = viewModel::togglePolling,
                    onToggleBatching = viewModel::toggleBatching
                )
            } else {
                // Debug Terminal Card (TX yellow, RX white, Error red + manual command input + Copy/Share)
                DebugTerminalCard(
                    modifier = Modifier.weight(1f),
                    entries = terminalEntries,
                    showHeartbeats = showHeartbeats,
                    isSendingCommand = isSendingCommand,
                    isTransportWritable = connectionState is ConnectionState.AdapterReady ||
                        connectionState is ConnectionState.Connected,
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
                            snackbarHostState.showSnackbar("Raw log copied to clipboard (${entriesCountLabel(text)})")
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
    }
}

@Composable
fun SimulatorActiveBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AccentYellow, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.WarningAmber,
            contentDescription = "Simulator Active",
            tint = TrueBlack,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "SIMULATOR ACTIVE — VIRTUAL ELM327 ADAPTER",
            color = TrueBlack,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 13.sp
        )
    }
}

@Composable
fun ConnectionStatusCard(
    state: ConnectionState,
    supportedPids: Set<Int>,
    lastAdapterConfig: AdapterConfig?,
    onOpenConnectionSheet: () -> Unit,
    onOneTapReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRetryCarConnection: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "connectingPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val (indicatorColor, isPulsing) = when (state) {
        is ConnectionState.Disconnected -> StatusRed to false
        is ConnectionState.Connecting -> StatusYellow to true
        is ConnectionState.AdapterReady -> StatusYellow to false
        is ConnectionState.Connected -> StatusGreen to false
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, indicatorColor.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .alpha(if (isPulsing) pulseAlpha else 1f)
                            .background(indicatorColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = state.statusTitle,
                                color = indicatorColor,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 19.sp
                            )
                            if (state.isSimulator) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SIMULATOR",
                                    color = TrueBlack,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .background(AccentYellow, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        val adapterSubtitle = when (state) {
                            is ConnectionState.Connecting -> state.adapterName
                            is ConnectionState.AdapterReady -> state.adapterName
                            is ConnectionState.Connected -> state.adapterName
                            is ConnectionState.Disconnected -> null
                        }
                        if (!adapterSubtitle.isNullOrBlank()) {
                            Text(
                                text = adapterSubtitle,
                                color = PureWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                when (state) {
                    is ConnectionState.Disconnected -> {
                        Button(
                            onClick = onOpenConnectionSheet,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentYellow,
                                contentColor = TrueBlack
                            ),
                            modifier = Modifier
                                .height(46.dp)
                                .shadow(
                                    elevation = 10.dp,
                                    shape = RoundedCornerShape(12.dp),
                                    ambientColor = AccentYellow,
                                    spotColor = AccentYellow
                                ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                Icons.Default.BluetoothConnected,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Connect", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        }
                    }

                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = onOpenConnectionSheet,
                                border = BorderStroke(1.dp, CardBorderDark),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Switch", color = MutedWhite, fontSize = 13.sp)
                            }
                            OutlinedButton(
                                onClick = onDisconnect,
                                border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.7f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    Icons.Default.LinkOff,
                                    contentDescription = "Disconnect",
                                    tint = StatusRed,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Disconnect", color = StatusRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Status message box
            Text(
                text = state.statusMessage,
                color = if (state is ConnectionState.AdapterReady && state.isCarUnresponsive) {
                    AccentYellow
                } else {
                    MutedWhite
                },
                fontSize = 14.sp,
                lineHeight = 19.sp
            )

            // Transport details & Supported PIDs summary when AdapterReady or Connected
            val transportDetails = when (state) {
                is ConnectionState.AdapterReady -> state.transportDetails
                is ConnectionState.Connected -> state.transportDetails
                else -> null
            }
            if (!transportDetails.isNullOrBlank()) {
                Text(
                    text = transportDetails,
                    color = InfoBlue,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (state is ConnectionState.Connected && supportedPids.isNotEmpty()) {
                val pidListHex = supportedPids.sorted().joinToString(" ") { "%02X".format(it) }
                Text(
                    text = "Supported PIDs (0100..01A0): $pidListHex",
                    color = StatusGreen,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Retry Car button when in AdapterReady (e.g. ignition was off during 0100)
            if (state is ConnectionState.AdapterReady && state.isCarUnresponsive) {
                Button(
                    onClick = onRetryCarConnection,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentYellow,
                        contentColor = TrueBlack
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Retry Car Connection (Send 0100)", fontWeight = FontWeight.Bold)
                }
            }

            // One-tap reconnect button when Disconnected and a previous adapter is remembered
            if (state is ConnectionState.Disconnected && lastAdapterConfig != null) {
                OutlinedButton(
                    onClick = onOneTapReconnect,
                    border = BorderStroke(1.dp, AccentYellow),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = AccentYellow,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "One-Tap Reconnect: ${lastAdapterConfig.displayName}",
                        color = AccentYellow,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun DebugTerminalCard(
    modifier: Modifier = Modifier,
    entries: List<TerminalLogEntry>,
    showHeartbeats: Boolean,
    isSendingCommand: Boolean,
    isTransportWritable: Boolean,
    manualCommandText: String,
    onManualCommandChange: (String) -> Unit,
    onSendCommand: (String) -> Unit,
    onToggleHeartbeats: () -> Unit,
    onCopyLog: () -> Unit,
    onShareLog: () -> Unit,
    onClearLog: () -> Unit
) {
    val listState = rememberLazyListState()

    // Auto-scroll to the latest log entry
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CardBorderDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Terminal header & Copy / Share / Filter controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Debug Terminal (Raw TX / RX)",
                        color = PureWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "${entries.size} lines • Auto-saved to last_session_raw.log",
                        color = MutedWhite,
                        fontSize = 11.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = showHeartbeats,
                        onClick = onToggleHeartbeats,
                        label = {
                            Text(
                                text = if (showHeartbeats) "HB: ON" else "HB: OFF",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentYellow.copy(alpha = 0.2f),
                            selectedLabelColor = AccentYellow,
                            containerColor = TrueBlack,
                            labelColor = MutedWhite
                        )
                    )

                    IconButton(onClick = onCopyLog, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy Log",
                            tint = AccentYellow,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    IconButton(onClick = onShareLog, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share Log",
                            tint = AccentYellow,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    IconButton(onClick = onClearLog, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Clear Log",
                            tint = MutedWhite,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            // Copy & Share explicit action buttons row for accessibility and large touch targets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCopyLog,
                    border = BorderStroke(1.dp, AccentYellow.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = AccentYellow,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Log", color = AccentYellow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = onShareLog,
                    border = BorderStroke(1.dp, AccentYellow.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = null,
                        tint = AccentYellow,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share Log", color = AccentYellow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            // Live scrolling log console
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(TrueBlack, RoundedCornerShape(12.dp))
                    .border(1.dp, CardBorderDark, RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                if (entries.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No TX/RX traffic yet.\nConnect to an adapter to view live ELM327 communication.\nColor key: TX = Yellow • RX = White • Error = Red",
                            color = MutedWhite,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(entries, key = { it.id }) { entry ->
                                TerminalLogLine(entry = entry)
                            }
                        }
                    }
                }
            }

            // Quick-send AT / OBD command chips
            val quickCommands = remember {
                listOf("ATZ", "ATI", "ATRV", "ATDP", "0100", "010C", "010D", "0105", "03", "0902")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                quickCommands.forEach { cmd ->
                    Box(
                        modifier = Modifier
                            .background(TrueBlack, RoundedCornerShape(8.dp))
                            .border(1.dp, CardBorderDark, RoundedCornerShape(8.dp))
                            .clickable(enabled = isTransportWritable && !isSendingCommand) {
                                onSendCommand(cmd)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = cmd,
                            color = if (isTransportWritable) AccentYellow else MutedWhite.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Free-text manual command input + Send button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = manualCommandText,
                    onValueChange = onManualCommandChange,
                    placeholder = {
                        Text(
                            text = if (isTransportWritable) {
                                "Enter AT or OBD command (e.g. ATRV, 0100, 010C)..."
                            } else {
                                "Connect to an adapter to send commands..."
                            },
                            fontSize = 13.sp,
                            color = MutedWhite
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        color = PureWhite,
                        fontWeight = FontWeight.Bold
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (manualCommandText.isNotBlank() && !isSendingCommand) {
                                onSendCommand(manualCommandText)
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentYellow,
                        unfocusedBorderColor = CardBorderDark,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        cursorColor = AccentYellow
                    )
                )

                Button(
                    onClick = { onSendCommand(manualCommandText) },
                    enabled = manualCommandText.isNotBlank() && !isSendingCommand,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentYellow,
                        contentColor = TrueBlack,
                        disabledContainerColor = CardBorderDark,
                        disabledContentColor = MutedWhite
                    ),
                    modifier = Modifier.height(54.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Command")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SEND", fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun TerminalLogLine(entry: TerminalLogEntry) {
    // Color-coded per spec: TX yellow (#FFC107), RX white (#FFFFFF), errors red (#FF5252)
    val textColor: Color = when (entry.type) {
        LogEntryType.TX -> AccentYellow
        LogEntryType.RX -> PureWhite
        LogEntryType.ERROR -> StatusRed
        LogEntryType.INFO -> InfoBlue
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "[${entry.formattedTime}] ",
            color = MutedWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Text(
            text = entry.message,
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (entry.type == LogEntryType.TX || entry.type == LogEntryType.ERROR) {
                FontWeight.Bold
            } else {
                FontWeight.Normal
            },
            fontSize = 13.sp,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

fun copyLogToClipboard(context: Context, logText: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("OBD2 Raw Session Log", logText.ifEmpty { "(Empty log)" })
    clipboard.setPrimaryClip(clip)
}

fun shareLog(context: Context, logText: String, logFile: File) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "OBD2 Raw Session Log")
        putExtra(Intent.EXTRA_TEXT, logText.ifEmpty { "(Empty log)" })
        if (logFile.exists() && logFile.length() > 0L) {
            runCatching {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    logFile
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
    val chooser = Intent.createChooser(sendIntent, "Share OBD2 Raw Session Log").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

fun entriesCountLabel(text: String): String {
    if (text.isBlank()) return "0 lines"
    val count = text.lines().size
    return "$count lines"
}
