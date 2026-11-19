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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obd2logger.data.logging.CsvLogStorage
import com.obd2logger.domain.model.SavedCsvLogSummary
import com.obd2logger.domain.model.SessionDiagnosticSnapshot
import com.obd2logger.service.ObdLoggingService
import com.obd2logger.ui.theme.AccentYellow
import com.obd2logger.ui.theme.CardBorderDark
import com.obd2logger.ui.theme.CardDark
import com.obd2logger.ui.theme.MutedWhite
import com.obd2logger.ui.theme.PureWhite
import com.obd2logger.ui.theme.StatusGreen
import com.obd2logger.ui.theme.TrueBlack
import java.util.Locale

@Composable
fun LogSavedScreen(
    summary: SavedCsvLogSummary,
    diagnostics: SessionDiagnosticSnapshot,
    onShare: () -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .size(84.dp)
                .background(StatusGreen.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Log Saved",
                tint = StatusGreen,
                modifier = Modifier.size(48.dp)
            )
        }

        Text(
            text = "Log Saved",
            color = PureWhite,
            fontSize = 28.sp,
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SummaryStatRow("File", summary.filename, monospace = true)
                SummaryStatRow("Duration", ObdLoggingService.formatElapsed(summary.durationMs), monospace = true)
                SummaryStatRow("Rows Logged", "${summary.rowCount}", monospace = true)
                SummaryStatRow("File Size", formatFileSize(summary.sizeBytes), monospace = true)
                SummaryStatRow("MIL / DTCs", "${diagnostics.milStatusCsv} (${diagnostics.storedDtcs.size} stored)")
                SummaryStatRow("VIN", diagnostics.vin, monospace = true)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = onShare,
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentYellow,
                contentColor = TrueBlack
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(16.dp),
                    ambientColor = AccentYellow,
                    spotColor = AccentYellow
                ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text("SHARE CSV LOG", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
        }

        OutlinedButton(
            onClick = onOpenFolder,
            border = BorderStroke(1.dp, AccentYellow.copy(alpha = 0.7f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                tint = AccentYellow,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("OPEN FOLDER", color = AccentYellow, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        TextButton(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
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
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif
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
