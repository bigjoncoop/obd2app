package com.obd2logger.ui.phase3

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obd2logger.domain.model.DecodedDtc
import com.obd2logger.domain.model.SessionDiagnosticSnapshot
import com.obd2logger.ui.theme.AccentYellow
import com.obd2logger.ui.theme.CardBorderDark
import com.obd2logger.ui.theme.CardDark
import com.obd2logger.ui.theme.InfoBlue
import com.obd2logger.ui.theme.MutedWhite
import com.obd2logger.ui.theme.PureWhite
import com.obd2logger.ui.theme.StatusGreen
import com.obd2logger.ui.theme.StatusRed
import com.obd2logger.ui.theme.TrueBlack

/**
 * Displays trouble codes (Stored, Pending, Permanent), MIL status, VIN, and protocol/freeze-frame summary.
 * Intentionally read-only (no clear-codes feature).
 */
@Composable
fun DiagnosticResultsCard(
    diagnostics: SessionDiagnosticSnapshot,
    modifier: Modifier = Modifier
) {
    val milColor: Color = when (diagnostics.milOn) {
        true -> StatusRed
        false -> StatusGreen
        null -> MutedWhite
    }
    val milBadgeText = when (diagnostics.milOn) {
        true -> "MIL ON (${diagnostics.dtcCount ?: 0} DTC)"
        false -> "MIL OFF (${diagnostics.dtcCount ?: 0} DTC)"
        null -> "MIL: -"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DIAGNOSTIC RESULTS",
                    color = PureWhite,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp
                )
                Box(
                    modifier = Modifier
                        .background(milColor.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = milBadgeText,
                        color = milColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp
                    )
                }
            }

            // VIN & Protocol / ECU Name
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TrueBlack, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("VIN", color = MutedWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = diagnostics.vin,
                        color = AccentYellow,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Protocol", color = MutedWhite, fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = diagnostics.protocol,
                        color = InfoBlue,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (diagnostics.ecuName != "-") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ECU", color = MutedWhite, fontSize = 12.sp)
                        Text(
                            text = diagnostics.ecuName,
                            color = PureWhite,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            HorizontalDivider(color = CardBorderDark)

            DtcCategorySection(
                label = "Stored Codes (Mode 03)",
                dtcs = diagnostics.storedDtcs,
                accentColor = StatusRed
            )
            DtcCategorySection(
                label = "Pending Codes (Mode 07)",
                dtcs = diagnostics.pendingDtcs,
                accentColor = AccentYellow
            )
            DtcCategorySection(
                label = "Permanent Codes (Mode 0A)",
                dtcs = diagnostics.permanentDtcs,
                accentColor = InfoBlue
            )

            diagnostics.freezeFrame.dtc?.let { ffDtc ->
                HorizontalDivider(color = CardBorderDark)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Freeze Frame Snapshot (${ffDtc.code})",
                        color = AccentYellow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "${ffDtc.description} • RPM ${diagnostics.freezeFrame.rpm}, " +
                            "Speed ${diagnostics.freezeFrame.speedMph} mph, Load ${diagnostics.freezeFrame.engineLoadPct}%",
                        color = MutedWhite,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DtcCategorySection(
    label: String,
    dtcs: List<DecodedDtc>,
    accentColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = MutedWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (dtcs.isEmpty()) {
            Text(
                text = "None",
                color = StatusGreen,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            dtcs.forEach { dtc ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TrueBlack, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dtc.code,
                        color = accentColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = dtc.description,
                        color = PureWhite,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
