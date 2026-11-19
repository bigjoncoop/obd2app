package com.obd2logger.ui.phase1

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obd2logger.data.preferences.SavedWifiEndpoint
import com.obd2logger.data.transport.WifiTransport
import com.obd2logger.domain.model.AdapterConfig
import com.obd2logger.domain.model.ScannedBtDevice
import com.obd2logger.domain.model.TransportType
import com.obd2logger.ui.permissions.BluetoothPermissionHelper
import com.obd2logger.ui.theme.AccentYellow
import com.obd2logger.ui.theme.CardBorderDark
import com.obd2logger.ui.theme.CardDark
import com.obd2logger.ui.theme.MutedWhite
import com.obd2logger.ui.theme.PureWhite
import com.obd2logger.ui.theme.StatusGreen
import com.obd2logger.ui.theme.TrueBlack

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdapterConnectionSheet(
    classicDevices: List<ScannedBtDevice>,
    bleDevices: List<ScannedBtDevice>,
    isClassicScanning: Boolean,
    isBleScanning: Boolean,
    preferredPin: String,
    savedWifiEndpoint: SavedWifiEndpoint,
    onStartClassicScan: () -> Unit,
    onStopClassicScan: () -> Unit,
    onStartBleScan: () -> Unit,
    onStopBleScan: () -> Unit,
    onSavePreferredPin: (String) -> Unit,
    onSaveWifiEndpoint: (String, String) -> Unit,
    onSelectAdapter: (AdapterConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = TransportType.entries

    var hasBtPermissions by remember {
        mutableStateOf(BluetoothPermissionHelper.hasAllBluetoothPermissions(context))
    }
    var showPermissionExplanationDialog by remember { mutableStateOf(false) }
    var pendingScanTypeAfterPermission by remember { mutableStateOf<TransportType?>(null) }

    val permissionRequirement = remember {
        BluetoothPermissionHelper.getRequiredBluetoothPermissions()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        hasBtPermissions = BluetoothPermissionHelper.hasAllBluetoothPermissions(context)
        if (hasBtPermissions) {
            when (pendingScanTypeAfterPermission) {
                TransportType.BLUETOOTH_CLASSIC -> onStartClassicScan()
                TransportType.BLUETOOTH_LE -> onStartBleScan()
                else -> Unit
            }
        }
        pendingScanTypeAfterPermission = null
    }

    // Automatically start scanning when switching to BT Classic or BLE tab if permissions are granted
    LaunchedEffect(selectedTabIndex, hasBtPermissions) {
        when (tabs[selectedTabIndex]) {
            TransportType.BLUETOOTH_CLASSIC -> {
                onStopBleScan()
                if (hasBtPermissions) {
                    onStartClassicScan()
                }
            }
            TransportType.BLUETOOTH_LE -> {
                onStopClassicScan()
                if (hasBtPermissions) {
                    onStartBleScan()
                }
            }
            else -> {
                onStopClassicScan()
                onStopBleScan()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onStopClassicScan()
            onStopBleScan()
        }
    }

    if (showPermissionExplanationDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionExplanationDialog = false },
            containerColor = CardDark,
            titleContentColor = PureWhite,
            textContentColor = PureWhite,
            title = {
                Text(
                    text = permissionRequirement.title,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = permissionRequirement.plainEnglishExplanation,
                    color = MutedWhite,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionExplanationDialog = false
                        permissionLauncher.launch(permissionRequirement.permissions.toTypedArray())
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentYellow,
                        contentColor = TrueBlack
                    )
                ) {
                    Text("Continue & Grant Permission", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionExplanationDialog = false }) {
                    Text("Cancel", color = MutedWhite)
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TrueBlack,
        contentColor = PureWhite
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Connect to OBD2 Adapter",
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MutedWhite)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = CardDark,
                contentColor = PureWhite,
                edgePadding = 4.dp
            ) {
                tabs.forEachIndexed { index, transportType ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Text(
                                text = transportType.label,
                                color = if (selectedTabIndex == index) AccentYellow else MutedWhite,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        icon = {
                            val icon = when (transportType) {
                                TransportType.BLUETOOTH_CLASSIC -> Icons.Default.Bluetooth
                                TransportType.BLUETOOTH_LE -> Icons.AutoMirrored.Filled.BluetoothSearching
                                TransportType.WIFI -> Icons.Default.Wifi
                                TransportType.SIMULATOR -> Icons.Default.BugReport
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = transportType.label,
                                tint = if (selectedTabIndex == index) AccentYellow else MutedWhite,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            when (tabs[selectedTabIndex]) {
                TransportType.BLUETOOTH_CLASSIC -> {
                    BluetoothClassicTabContent(
                        hasPermissions = hasBtPermissions,
                        devices = classicDevices,
                        isScanning = isClassicScanning,
                        preferredPin = preferredPin,
                        onRequestPermissions = {
                            pendingScanTypeAfterPermission = TransportType.BLUETOOTH_CLASSIC
                            showPermissionExplanationDialog = true
                        },
                        onStartScan = onStartClassicScan,
                        onStopScan = onStopClassicScan,
                        onPinSelected = onSavePreferredPin,
                        onDeviceTapped = { device ->
                            onSelectAdapter(
                                AdapterConfig.BluetoothClassic(
                                    address = device.address,
                                    name = device.name ?: "",
                                    preferredPin = preferredPin
                                )
                            )
                        }
                    )
                }

                TransportType.BLUETOOTH_LE -> {
                    BluetoothLeTabContent(
                        hasPermissions = hasBtPermissions,
                        devices = bleDevices,
                        isScanning = isBleScanning,
                        onRequestPermissions = {
                            pendingScanTypeAfterPermission = TransportType.BLUETOOTH_LE
                            showPermissionExplanationDialog = true
                        },
                        onStartScan = onStartBleScan,
                        onStopScan = onStopBleScan,
                        onDeviceTapped = { device ->
                            onSelectAdapter(
                                AdapterConfig.BluetoothLe(
                                    address = device.address,
                                    name = device.name ?: ""
                                )
                            )
                        }
                    )
                }

                TransportType.WIFI -> {
                    WifiTabContent(
                        savedEndpoint = savedWifiEndpoint,
                        onSaveEndpoint = onSaveWifiEndpoint,
                        onConnectWifi = { ip, port ->
                            onSelectAdapter(AdapterConfig.Wifi(ip = ip, port = port))
                        },
                        onOpenAndroidWifiSettings = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        }
                    )
                }

                TransportType.SIMULATOR -> {
                    SimulatorTabContent(
                        onConnectSimulator = {
                            onSelectAdapter(AdapterConfig.Simulator)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BluetoothClassicTabContent(
    hasPermissions: Boolean,
    devices: List<ScannedBtDevice>,
    isScanning: Boolean,
    preferredPin: String,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onPinSelected: (String) -> Unit,
    onDeviceTapped: (ScannedBtDevice) -> Unit
) {
    if (!hasPermissions) {
        PermissionExplanationCard(
            title = "Bluetooth Permission Needed",
            explanation = BluetoothPermissionHelper.getRequiredBluetoothPermissions().plainEnglishExplanation,
            onGrantClicked = onRequestPermissions
        )
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Pairing PIN selector for unpaired Bluetooth Classic clones
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Default Pairing PIN:",
                color = MutedWhite,
                fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1234", "0000", "6789").forEach { pin ->
                    FilterChip(
                        selected = preferredPin == pin,
                        onClick = { onPinSelected(pin) },
                        label = { Text(pin, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentYellow,
                            selectedLabelColor = TrueBlack,
                            containerColor = CardDark,
                            labelColor = PureWhite
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Showing ALL Paired & Discovered Devices (${devices.size})",
                color = MutedWhite,
                fontSize = 13.sp
            )
            OutlinedButton(
                onClick = { if (isScanning) onStopScan() else onStartScan() },
                border = BorderStroke(1.dp, AccentYellow)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AccentYellow
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop Scan", color = AccentYellow)
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Scan",
                        tint = AccentYellow,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan Nearby", color = AccentYellow)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(CardDark, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isScanning) "Scanning for Bluetooth devices..." else "No Bluetooth devices found yet. Tap Scan Nearby.",
                    color = MutedWhite
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices, key = { it.address }) { device ->
                    DeviceItemRow(
                        device = device,
                        onClick = { onDeviceTapped(device) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BluetoothLeTabContent(
    hasPermissions: Boolean,
    devices: List<ScannedBtDevice>,
    isScanning: Boolean,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceTapped: (ScannedBtDevice) -> Unit
) {
    if (!hasPermissions) {
        PermissionExplanationCard(
            title = "Bluetooth LE Scan Permission Needed",
            explanation = BluetoothPermissionHelper.getRequiredBluetoothPermissions().plainEnglishExplanation,
            onGrantClicked = onRequestPermissions
        )
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Auto-probes FFF0 (FFF1/FFF2), FFE0 (FFE1), 18F0 (2AF0/2AF1), Nordic UART, or any notify+write service.",
            color = MutedWhite,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "BLE Devices Found (${devices.size})",
                color = MutedWhite,
                fontSize = 13.sp
            )
            OutlinedButton(
                onClick = { if (isScanning) onStopScan() else onStartScan() },
                border = BorderStroke(1.dp, AccentYellow)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AccentYellow
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop BLE Scan", color = AccentYellow)
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Scan BLE",
                        tint = AccentYellow,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan BLE", color = AccentYellow)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(CardDark, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isScanning) "Scanning for BLE devices..." else "No BLE devices discovered yet. Tap Scan BLE.",
                    color = MutedWhite
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices, key = { it.address }) { device ->
                    DeviceItemRow(
                        device = device,
                        onClick = { onDeviceTapped(device) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceItemRow(
    device: ScannedBtDevice,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, CardBorderDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayTitle,
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(device.address)
                        if (device.rssi != null) {
                            append(" • ${device.rssi} dBm")
                        }
                    },
                    color = MutedWhite,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Box(
                modifier = Modifier
                    .background(
                        color = if (device.isPaired) StatusGreen.copy(alpha = 0.2f) else AccentYellow.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (device.isPaired) "PAIRED" else if (device.isBle) "BLE" else "UNPAIRED",
                    color = if (device.isPaired) StatusGreen else AccentYellow,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WifiTabContent(
    savedEndpoint: SavedWifiEndpoint,
    onSaveEndpoint: (String, String) -> Unit,
    onConnectWifi: (String, Int) -> Unit,
    onOpenAndroidWifiSettings: () -> Unit
) {
    var ipText by rememberSaveable(savedEndpoint.ip) { mutableStateOf(savedEndpoint.ip) }
    var portText by rememberSaveable(savedEndpoint.port) { mutableStateOf(savedEndpoint.port.toString()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CardBorderDark)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Step 1: Join Adapter's Wi-Fi Network",
                    color = AccentYellow,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = "Connect your phone to the adapter's Wi-Fi network (e.g. WiFi_OBDII, V-LINK) in Android Settings. The app will automatically bind to Wi-Fi even with no internet.",
                    color = MutedWhite,
                    fontSize = 13.sp
                )
                OutlinedButton(
                    onClick = onOpenAndroidWifiSettings,
                    border = BorderStroke(1.dp, AccentYellow),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = AccentYellow)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Android Wi-Fi Settings", color = AccentYellow, fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CardBorderDark)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Step 2: TCP Endpoint (Saved IP:Port Tried First)",
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = ipText,
                        onValueChange = {
                            ipText = it
                            onSaveEndpoint(it, portText)
                        },
                        label = { Text("Adapter IP") },
                        singleLine = true,
                        modifier = Modifier.weight(0.65f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentYellow,
                            unfocusedBorderColor = CardBorderDark,
                            focusedLabelColor = AccentYellow,
                            unfocusedLabelColor = MutedWhite,
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite
                        )
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = {
                            portText = it
                            onSaveEndpoint(ipText, it)
                        },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.35f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentYellow,
                            unfocusedBorderColor = CardBorderDark,
                            focusedLabelColor = AccentYellow,
                            unfocusedLabelColor = MutedWhite,
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite
                        )
                    )
                }

                Text(
                    text = "Common defaults (tap to fill, or tried automatically on connect):",
                    color = MutedWhite,
                    fontSize = 12.sp
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WifiTransport.DEFAULT_ENDPOINTS.forEach { (defIp, defPort) ->
                        val label = "$defIp:$defPort"
                        FilterChip(
                            selected = ipText.trim() == defIp && portText.trim() == defPort.toString(),
                            onClick = {
                                ipText = defIp
                                portText = defPort.toString()
                                onSaveEndpoint(defIp, defPort.toString())
                            },
                            label = {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentYellow,
                                selectedLabelColor = TrueBlack,
                                containerColor = TrueBlack,
                                labelColor = PureWhite
                            )
                        )
                    }
                }

                Button(
                    onClick = {
                        val port = portText.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 35000
                        val ip = ipText.trim().ifEmpty { "192.168.0.10" }
                        onSaveEndpoint(ip, port.toString())
                        onConnectWifi(ip, port)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentYellow,
                        contentColor = TrueBlack
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect via Wi-Fi TCP", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun SimulatorTabContent(
    onConnectSimulator: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AccentYellow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "SIMULATOR (Debug Mode)",
                color = AccentYellow,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
            Text(
                text = "Connects to an in-app simulated ELM327 v1.5 adapter for testing the command queue, init sequence, and Debug Terminal without a physical car.",
                color = PureWhite,
                fontSize = 14.sp
            )
            Text(
                text = "Whenever active, a prominent \"SIMULATOR\" badge is displayed at the top of the screen.",
                color = MutedWhite,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onConnectSimulator,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentYellow,
                    contentColor = TrueBlack
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Launch SIMULATOR Session", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun PermissionExplanationCard(
    title: String,
    explanation: String,
    onGrantClicked: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AccentYellow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                color = AccentYellow,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                text = explanation,
                color = PureWhite,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Button(
                onClick = onGrantClicked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentYellow,
                    contentColor = TrueBlack
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Review & Grant Permission", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}
