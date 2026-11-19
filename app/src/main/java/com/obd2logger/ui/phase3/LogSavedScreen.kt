package com.obd2logger.ui.phase3

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obd2logger.data.logging.CsvLogStorage
import com.obd2logger.data.logging.SessionArtifactEntry
import com.obd2logger.domain.model.SavedCsvLogSummary
import com.obd2logger.domain.model.SessionDiagnosticSnapshot
import com.obd2logger.service.ObdLoggingService
import com.obd2logger.ui.theme.AccentYellow
import com.obd2logger.ui.theme.CardBorderDark
import com.obd2logger.ui.theme.CardDark
import com.obd2logger.ui.theme.InfoBlue
import com.obd2logger.ui.theme.MutedWhite
import com.obd2logger.ui.theme.PureWhite
import com.obd2logger.ui.theme.StatusGreen
import com.obd2logger.ui.theme.TrueBlack
import java.util.Locale

@Composable
fun LogSavedScreen(
    summary: SavedCsvLogSummary,
    diagnostics: SessionDiagnosticSnapshot,
    artifacts: List<SessionArtifactEntry>,
    zipUri: String?,
    zipFilename: String?,
    isZipBuilding: Boolean,
    onShareMainCsv: () -> Unit,
    onShareSessionZip: () -> Unit,
    onOpenFolder: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrueBlack)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .size(76.dp)
                .background(StatusGreen.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Log Saved",
                tint = StatusGreen,
                modifier = Modifier.size(44.dp)
            )
        }

        Text(
            text = "Log Saved",
            color = PureWhite,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold
        )

        Text(
            text = "Saved to ${CsvLogStorage.DISPLAY_FOLDER_PATH}",
            color = AccentYellow,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

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
                SummaryStatRow("Duration", ObdLoggingService.formatElapsed(summary.durationMs), monospace = true)
                SummaryStatRow("Main Rows", "${summary.rowCount}", monospace = true)
                SummaryStatRow("Main Size", formatFileSize(summary.sizeBytes), monospace = true)
                SummaryStatRow("MIL / DTCs", "${diagnostics.milStatusCsv} (${diagnostics.storedDtcs.size} stored)")
                SummaryStatRow("VIN", diagnostics.vin, monospace = true)

                if (artifacts.isNotEmpty() || zipFilename != null || isZipBuilding) {
                    HorizontalDivider(color = CardBorderDark)
                    Text(
                        text = "SESSION FILES PRODUCED",
                        color = AccentYellow,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp
                    )
                    artifacts.forEach { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TrueBlack, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.type.displayLabel,
                                    color = InfoBlue,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = file.filename,
                                    color = PureWhite,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${file.rowCount} rows",
                                color = MutedWhite,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TrueBlack, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Full Session ZIP + manifest.json",
                                color = StatusGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = zipFilename ?: if (isZipBuilding) "Streaming ZIP archive..." else "ZIP unavailable",
                                color = PureWhite,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isZipBuilding) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = AccentYellow,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = onShareMainCsv,
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentYellow,
                contentColor = TrueBlack
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(16.dp),
                    ambientColor = AccentYellow,
                    spotColor = AccentYellow
                ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text("SHARE MAIN CSV", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        }

        Button(
            onClick = onShareSessionZip,
            enabled = zipUri != null && !isZipBuilding,
            colors = ButtonDefaults.buttonColors(
                containerColor = CardDark,
                contentColor = AccentYellow,
                disabledContainerColor = CardBorderDark,
                disabledContentColor = MutedWhite
            ),
            border = BorderStroke(1.dp, if (zipUri != null) AccentYellow else CardBorderDark),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (isZipBuilding) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = AccentYellow,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("BUILDING SESSION ZIP...", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            } else {
                Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("SHARE FULL SESSION (ZIP)", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            }
        }

        OutlinedButton(
            onClick = onOpenFolder,
            border = BorderStroke(1.dp, CardBorderDark),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                tint = PureWhite,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("OPEN FOLDER", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        TextButton(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) {
            Text("DONE", color = MutedWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun SummaryStatRow(
    label: String,
    value: String,
    monospace: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = MutedWhite, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            color = PureWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
        else -> "%.2f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    }
}
