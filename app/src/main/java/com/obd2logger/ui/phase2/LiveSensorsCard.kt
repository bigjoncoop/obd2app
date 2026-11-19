package com.obd2logger.ui.phase2

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obd2logger.domain.model.LiveSensorValue
import com.obd2logger.ui.theme.AccentYellow
import com.obd2logger.ui.theme.CardBorderDark
import com.obd2logger.ui.theme.CardDark
import com.obd2logger.ui.theme.MutedWhite
import com.obd2logger.ui.theme.PureWhite
import com.obd2logger.ui.theme.StatusGreen
import com.obd2logger.ui.theme.StatusYellow
import com.obd2logger.ui.theme.TrueBlack

@Composable
fun LiveSensorsCard(
    modifier: Modifier = Modifier,
    isConnected: Boolean,
    liveValues: List<LiveSensorValue>,
    isPollingEnabled: Boolean,
    isBatchingEnabled: Boolean,
    isBatchFallbackActive: Boolean,
    onTogglePolling: () -> Unit,
    onToggleBatching: () -> Unit
) {
    val visibleSensors = liveValues.filter { it.pid.showInLiveUi }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIVE SENSORS (${visibleSensors.size})",
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = isPollingEnabled,
                        onClick = onTogglePolling,
                        label = {
                            Text(
                                text = if (isPollingEnabled) "Polling ON" else "Paused",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StatusGreen.copy(alpha = 0.2f),
                            selectedLabelColor = StatusGreen,
                            containerColor = TrueBlack,
                            labelColor = MutedWhite
                        )
                    )
                    FilterChip(
                        selected = isBatchingEnabled && !isBatchFallbackActive,
                        onClick = onToggleBatching,
                        label = {
                            val label = when {
                                !isBatchingEnabled -> "Batch OFF"
                                isBatchFallbackActive -> "Fallback 1x"
                                else -> "Batch ON"
                            }
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentYellow.copy(alpha = 0.2f),
                            selectedLabelColor = AccentYellow,
                            containerColor = TrueBlack,
                            labelColor = if (isBatchFallbackActive) StatusYellow else MutedWhite
                        )
                    )
                }
            }

            if (!isConnected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TrueBlack, RoundedCornerShape(10.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Connect to a vehicle ECU (Connected / Green) to poll supported Mode 01 live sensor values.",
                        color = MutedWhite,
                        fontSize = 13.sp
                    )
                }
            } else if (visibleSensors.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TrueBlack, RoundedCornerShape(10.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No supported Mode 01 sensor PIDs reported by vehicle.",
                        color = MutedWhite,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TrueBlack, RoundedCornerShape(10.dp)),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(
                        items = visibleSensors,
                        key = { it.pid.id }
                    ) { sensor ->
                        SensorValueRow(sensor = sensor)
                        HorizontalDivider(
                            color = CardBorderDark.copy(alpha = 0.5f),
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SensorValueRow(sensor: LiveSensorValue) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sensor.pid.name,
                color = PureWhite,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "PID ${sensor.pid.hexId} • ${sensor.pid.priority.label}",
                color = MutedWhite.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = sensor.formattedValue,
                color = if (sensor.hasData) PureWhite else MutedWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            if (sensor.unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = sensor.unit,
                    color = MutedWhite,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}
