package com.obd2logger.ui.phase3

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obd2logger.BuildConfig
import com.obd2logger.data.preferences.PollingSpeed
import com.obd2logger.data.preferences.SavedWifiEndpoint
import com.obd2logger.ui.theme.AccentYellow
import com.obd2logger.ui.theme.CardBorderDark
import com.obd2logger.ui.theme.CardDark
import com.obd2logger.ui.theme.MutedWhite
import com.obd2logger.ui.theme.PureWhite
import com.obd2logger.ui.theme.StatusGreen
import com.obd2logger.ui.theme.TrueBlack

@Composable
fun SettingsScreen(
    savedWifiEndpoint: SavedWifiEndpoint,
    pollingSpeed: PollingSpeed,
    keepScreenOn: Boolean,
    hasLocationPermission: Boolean,
    onSaveWifiEndpoint: (String, String) -> Unit,
    onSelectPollingSpeed: (PollingSpeed) -> Unit,
    onToggleKeepScreenOn: (Boolean) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onReplayOnboarding: () -> Unit,
    modifier: Modifier = Modifier
) {
    var ipText by remember { mutableStateOf(savedWifiEndpoint.ip) }
    var portText by remember { mutableStateOf(savedWifiEndpoint.port.toString()) }

    LaunchedEffect(savedWifiEndpoint) {
        ipText = savedWifiEndpoint.ip
        portText = savedWifiEndpoint.port.toString()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "SETTINGS",
            color = PureWhite,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp
        )

        // Wi-Fi Adapter Endpoint
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, CardBorderDark)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Wi-Fi OBD2 Adapter Endpoint",
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = "Tried first before auto-probing common adapter IPs (192.168.0.10:35000, 192.168.4.1:35000, etc.).",
                    color = MutedWhite,
                    fontSize = 12.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = ipText,
                        onValueChange = { ipText = it },
                        label = { Text("IP Address") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentYellow,
                            unfocusedBorderColor = CardBorderDark,
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite,
                            focusedLabelColor = AccentYellow,
                            unfocusedLabelColor = MutedWhite
                        )
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentYellow,
                            unfocusedBorderColor = CardBorderDark,
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite,
                            focusedLabelColor = AccentYellow,
                            unfocusedLabelColor = MutedWhite
                        )
                    )
                }
                Button(
                    onClick = { onSaveWifiEndpoint(ipText, portText) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentYellow,
                        contentColor = TrueBlack
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("SAVE WI-FI ENDPOINT", fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        // Polling Speed
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, CardBorderDark)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Sensor Polling Speed",
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                PollingSpeed.entries.forEach { speed ->
                    val selected = speed == pollingSpeed
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TrueBlack, RoundedCornerShape(10.dp))
                            .border(
                                1.dp,
                                if (selected) AccentYellow else CardBorderDark,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectPollingSpeed(speed) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { onSelectPollingSpeed(speed) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = AccentYellow,
                                unselectedColor = MutedWhite
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = speed.label,
                                color = if (selected) AccentYellow else PureWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = speed.description,
                                color = MutedWhite,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Display & Optional GPS
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, CardBorderDark)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Keep Screen On",
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Prevent display sleep while viewing live gauges (background service runs either way).",
                            color = MutedWhite,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Switch(
                        checked = keepScreenOn,
                        onCheckedChange = onToggleKeepScreenOn,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TrueBlack,
                            checkedTrackColor = AccentYellow,
                            uncheckedThumbColor = MutedWhite,
                            uncheckedTrackColor = TrueBlack
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Optional GPS Logging",
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = if (hasLocationPermission) {
                                "Permission granted — GPS Time, Lat/Lon, Speed, Altitude will be logged when available."
                            } else {
                                "Not granted — GPS columns safely write '-'."
                            },
                            color = if (hasLocationPermission) StatusGreen else MutedWhite,
                            fontSize = 12.sp
                        )
                    }
                    if (!hasLocationPermission) {
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onRequestLocationPermission,
                            border = BorderStroke(1.dp, AccentYellow)
                        ) {
                            Text("Enable", color = AccentYellow, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Onboarding & Version
        OutlinedButton(
            onClick = onReplayOnboarding,
            border = BorderStroke(1.dp, CardBorderDark),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("View 3-Card Onboarding Again", color = PureWhite, fontWeight = FontWeight.SemiBold)
        }

        Text(
            text = "OBD2 Logger v${BuildConfig.VERSION_NAME}",
            color = MutedWhite,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 12.dp)
        )
    }
}
