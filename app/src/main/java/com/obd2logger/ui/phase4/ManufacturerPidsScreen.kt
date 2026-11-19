package com.obd2logger.ui.phase4

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obd2logger.domain.model.ManufacturerPidState
import com.obd2logger.ui.theme.AccentYellow
import com.obd2logger.ui.theme.CardBorderDark
import com.obd2logger.ui.theme.CardDark
import com.obd2logger.ui.theme.InfoBlue
import com.obd2logger.ui.theme.MutedWhite
import com.obd2logger.ui.theme.PureWhite
import com.obd2logger.ui.theme.StatusGreen
import com.obd2logger.ui.theme.TrueBlack

@Composable
fun ManufacturerPidsScreen(
    pidStates: List<ManufacturerPidState>,
    detectedMakes: Set<String>,
    isConnected: Boolean,
    isLogging: Boolean,
    isProbing: Boolean,
    lastImportMessage: String?,
    onProbeNow: () -> Unit,
    onImportJson: () -> Unit,
    onTogglePid: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val groupedByCategory = pidStates.groupBy { it.definition.category }
    val enabledCount = pidStates.count { it.enabled }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MANUFACTURER PIDS ($enabledCount ON)",
                    color = PureWhite,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp
                )
                val makeLabel = if (detectedMakes.isEmpty()) {
                    "VIN make: Not probed yet (read-only Mode 22/21)"
                } else {
                    "Detected make: ${detectedMakes.joinToString(", ")}"
                }
                Text(
                    text = makeLabel,
                    color = AccentYellow,
                    fontSize = 12.sp
                )
            }
        }

        // Polling load warning banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardDark, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = AccentYellow,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Enabling manufacturer PIDs logs to _extended.csv and switches CAN headers (ATSH), which adds polling load and slows Mode 01 updates. Read-only queries only.",
                color = MutedWhite,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onProbeNow,
                enabled = isConnected && !isLogging && !isProbing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentYellow,
                    contentColor = TrueBlack,
                    disabledContainerColor = CardBorderDark,
                    disabledContentColor = MutedWhite
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isProbing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = AccentYellow,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("PROBING...", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                } else {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("PROBE ECU", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                }
            }

            OutlinedButton(
                onClick = onImportJson,
                border = BorderStroke(1.dp, AccentYellow.copy(alpha = 0.7f)),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(
                    Icons.Default.FileOpen,
                    contentDescription = null,
                    tint = AccentYellow,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("IMPORT JSON", color = AccentYellow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        if (!lastImportMessage.isNullOrBlank()) {
            Text(
                text = lastImportMessage,
                color = InfoBlue,
                fontSize = 12.sp
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            groupedByCategory.forEach { (category, list) ->
                item(key = "cat_$category") {
                    Text(
                        text = category.uppercase(),
                        color = AccentYellow,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(items = list, key = { it.definition.id }) { state ->
                    ManufacturerPidRow(
                        state = state,
                        onToggle = { enabled -> onTogglePid(state.definition.id, enabled) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ManufacturerPidRow(
    state: ManufacturerPidState,
    onToggle: (Boolean) -> Unit
) {
    val def = state.definition
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (state.detected) StatusGreen.copy(alpha = 0.6f) else CardBorderDark
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = def.name,
                        color = PureWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (state.detected) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(StatusGreen.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "DETECTED",
                                color = StatusGreen,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 10.sp
                            )
                        }
                    }
                    if (def.isUserImported) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(InfoBlue.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "CUSTOM",
                                color = InfoBlue,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                val makesText = if (def.makes.isEmpty()) "All Makes" else def.makes.joinToString("/")
                Text(
                    text = "${def.moduleName} (${def.requestHeader}) • ${def.command} • $makesText",
                    color = MutedWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )

                Text(
                    text = def.source,
                    color = MutedWhite.copy(alpha = 0.75f),
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.End) {
                if (state.latestValue != "-") {
                    Text(
                        text = "${state.latestValue} ${def.unit}",
                        color = AccentYellow,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TrueBlack,
                        checkedTrackColor = AccentYellow,
                        uncheckedThumbColor = MutedWhite,
                        uncheckedTrackColor = TrueBlack
                    )
                )
            }
        }
    }
}
