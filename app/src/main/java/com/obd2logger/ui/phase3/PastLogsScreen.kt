package com.obd2logger.ui.phase3

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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obd2logger.data.logging.CsvLogStorage
import com.obd2logger.domain.model.SavedCsvLogSummary
import com.obd2logger.ui.theme.AccentYellow
import com.obd2logger.ui.theme.CardBorderDark
import com.obd2logger.ui.theme.CardDark
import com.obd2logger.ui.theme.MutedWhite
import com.obd2logger.ui.theme.PureWhite
import com.obd2logger.ui.theme.StatusRed
import com.obd2logger.ui.theme.TrueBlack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PastLogsScreen(
    logs: List<SavedCsvLogSummary>,
    onRefresh: () -> Unit,
    onOpenLog: (SavedCsvLogSummary) -> Unit,
    onShareLog: (SavedCsvLogSummary) -> Unit,
    onDeleteLog: (SavedCsvLogSummary) -> Unit,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingDelete by remember { mutableStateOf<SavedCsvLogSummary?>(null) }

    LaunchedEffect(Unit) {
        onRefresh()
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = CardDark,
            titleContentColor = PureWhite,
            textContentColor = MutedWhite,
            title = { Text("Delete CSV Log?", fontWeight = FontWeight.Bold) },
            text = { Text("Permanently delete ${target.filename} from ${CsvLogStorage.DISPLAY_FOLDER_PATH}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteLog(target)
                        pendingDelete = null
                    }
                ) {
                    Text("DELETE", color = StatusRed, fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("CANCEL", color = MutedWhite)
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "PAST LOGS (${logs.size})",
                    color = PureWhite,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp
                )
                Text(
                    text = CsvLogStorage.DISPLAY_FOLDER_PATH,
                    color = AccentYellow,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onOpenFolder,
                    border = BorderStroke(1.dp, AccentYellow.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = AccentYellow,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Folder", color = AccentYellow, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh Logs",
                        tint = AccentYellow
                    )
                }
            }
        }

        if (logs.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxSize(),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, CardBorderDark)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            tint = MutedWhite,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = "No CSV logs recorded yet",
                            color = PureWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Connect to your OBD2 adapter and tap START LOGGING on Home to create your first trackLog CSV.",
                            color = MutedWhite,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items = logs, key = { it.uri }) { log ->
                    PastLogCard(
                        log = log,
                        onOpen = { onOpenLog(log) },
                        onShare = { onShareLog(log) },
                        onDelete = { pendingDelete = log }
                    )
                }
            }
        }
    }
}

@Composable
private fun PastLogCard(
    log: SavedCsvLogSummary,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val dateLabel = remember(log.lastModifiedMs) {
        SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.US).format(Date(log.lastModifiedMs))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(14.dp),
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = AccentYellow,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = log.filename,
                        color = PureWhite,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$dateLabel • ${formatFileSize(log.sizeBytes)}",
                        color = MutedWhite,
                        fontSize = 12.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpen,
                    border = BorderStroke(1.dp, CardBorderDark),
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = PureWhite,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open", color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onShare,
                    border = BorderStroke(1.dp, AccentYellow.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = null,
                        tint = AccentYellow,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share", color = AccentYellow, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onDelete,
                    border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .height(42.dp)
                        .background(TrueBlack, RoundedCornerShape(10.dp)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = StatusRed,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
