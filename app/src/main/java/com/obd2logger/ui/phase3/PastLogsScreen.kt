package com.obd2logger.ui.phase3

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Archive
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obd2logger.data.logging.CsvLogStorage
import com.obd2logger.domain.model.SavedCsvLogSummary
import com.obd2logger.domain.model.SessionLogGroup
import com.obd2logger.ui.theme.AccentYellow
import com.obd2logger.ui.theme.CardBorderDark
import com.obd2logger.ui.theme.CardDark
import com.obd2logger.ui.theme.InfoBlue
import com.obd2logger.ui.theme.MutedWhite
import com.obd2logger.ui.theme.PureWhite
import com.obd2logger.ui.theme.StatusGreen
import com.obd2logger.ui.theme.StatusRed
import com.obd2logger.ui.theme.TrueBlack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PastLogsScreen(
    sessions: List<SessionLogGroup>,
    onRefresh: () -> Unit,
    onOpenMainCsv: (SavedCsvLogSummary) -> Unit,
    onShareMainCsv: (SavedCsvLogSummary) -> Unit,
    onShareSessionZip: (SessionLogGroup) -> Unit,
    onDeleteSession: (SessionLogGroup) -> Unit,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingDelete by remember { mutableStateOf<SessionLogGroup?>(null) }

    LaunchedEffect(Unit) {
        onRefresh()
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = CardDark,
            titleContentColor = PureWhite,
            textContentColor = MutedWhite,
            title = { Text("Delete Session Files?", fontWeight = FontWeight.Bold) },
            text = {
                val names = target.allFiles.joinToString("\n• ", prefix = "• ") { it.filename }
                Text("Permanently delete all ${target.allFiles.size} file(s) for this session from ${CsvLogStorage.DISPLAY_FOLDER_PATH}?\n\n$names")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSession(target)
                        pendingDelete = null
                    }
                ) {
                    Text("DELETE ALL", color = StatusRed, fontWeight = FontWeight.ExtraBold)
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
                    text = "PAST LOGS (${sessions.size})",
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

        if (sessions.isEmpty()) {
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
                            text = "No session logs recorded yet",
                            color = PureWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Connect to your OBD2 adapter and tap START LOGGING on Home to create your first session.",
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
                items(items = sessions, key = { it.stem }) { group ->
                    SessionGroupCard(
                        group = group,
                        onOpenMain = { group.mainCsv?.let(onOpenMainCsv) },
                        onShareMain = { group.mainCsv?.let(onShareMainCsv) },
                        onShareZip = { onShareSessionZip(group) },
                        onDelete = { pendingDelete = group }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionGroupCard(
    group: SessionLogGroup,
    onOpenMain: () -> Unit,
    onShareMain: () -> Unit,
    onShareZip: () -> Unit,
    onDelete: () -> Unit
) {
    val dateLabel = remember(group.lastModifiedMs) {
        SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.US).format(Date(group.lastModifiedMs))
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
                        text = group.stem,
                        color = PureWhite,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$dateLabel • ${group.allFiles.size} file(s) • ${formatFileSize(group.totalSizeBytes)}",
                        color = MutedWhite,
                        fontSize = 12.sp
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (group.mainCsv != null) SessionFileTag("Main CSV", AccentYellow)
                if (group.mode06Csv != null) SessionFileTag("Mode 06", InfoBlue)
                if (group.extendedCsv != null) SessionFileTag("Extended", StatusGreen)
                if (group.preclearedJson != null) SessionFileTag("Pre-Clear", StatusRed)
                if (group.sessionZip != null) SessionFileTag("ZIP", PureWhite)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (group.mainCsv != null) {
                    OutlinedButton(
                        onClick = onOpenMain,
                        border = BorderStroke(1.dp, CardBorderDark),
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open Main CSV",
                            tint = PureWhite,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    OutlinedButton(
                        onClick = onShareMain,
                        border = BorderStroke(1.dp, AccentYellow.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            tint = AccentYellow,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Main CSV", color = AccentYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = onShareZip,
                    border = BorderStroke(1.dp, StatusGreen.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Archive,
                        contentDescription = null,
                        tint = StatusGreen,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Share ZIP", color = StatusGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onDelete,
                    border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .height(40.dp)
                        .background(TrueBlack, RoundedCornerShape(10.dp)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete Session",
                        tint = StatusRed,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionFileTag(label: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}
