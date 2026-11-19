package com.obd2logger.ui.phase4

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obd2logger.data.protocol.ClearCodesResult
import com.obd2logger.ui.theme.AccentYellow
import com.obd2logger.ui.theme.CardBorderDark
import com.obd2logger.ui.theme.CardDark
import com.obd2logger.ui.theme.MutedWhite
import com.obd2logger.ui.theme.PureWhite
import com.obd2logger.ui.theme.StatusGreen
import com.obd2logger.ui.theme.StatusRed
import com.obd2logger.ui.theme.TrueBlack

@Composable
fun ClearCodesWarningDialog(
    onConfirmClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmText by remember { mutableStateOf("") }
    val isConfirmed = confirmText.trim().equals("CLEAR", ignoreCase = false)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        titleContentColor = AccentYellow,
        textContentColor = PureWhite,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = AccentYellow,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Clear Trouble Codes (Mode 04)?",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = TrueBlack),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, AccentYellow.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "• Erases stored DTCs and freeze frame data.",
                            color = PureWhite,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "• Resets readiness monitors — your vehicle may fail an emissions inspection until drive-cycle monitors complete again.",
                            color = AccentYellow,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "• The Check Engine Light (MIL) may return if the underlying fault still exists.",
                            color = PureWhite,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "• Permanent DTCs (Mode 0A) cannot be cleared by Mode 04.",
                            color = MutedWhite,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "• A pre-clear JSON backup snapshot will be saved first; clearing aborts if backup fails.",
                            color = StatusGreen,
                            fontSize = 12.sp
                        )
                    }
                }

                Text(
                    text = "Type CLEAR in uppercase to confirm:",
                    color = MutedWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = confirmText,
                    onValueChange = { confirmText = it.uppercase() },
                    placeholder = { Text("CLEAR", color = MutedWhite.copy(alpha = 0.5f)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        color = AccentYellow,
                        fontSize = 16.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentYellow,
                        unfocusedBorderColor = CardBorderDark,
                        cursorColor = AccentYellow
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmClear,
                enabled = isConfirmed,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusRed,
                    contentColor = PureWhite,
                    disabledContainerColor = CardBorderDark,
                    disabledContentColor = MutedWhite
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("CLEAR CODES", fontWeight = FontWeight.ExtraBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = MutedWhite, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun ClearCodesResultDialog(
    result: ClearCodesResult,
    onDismiss: () -> Unit
) {
    val (title, body, accent) = when (result) {
        is ClearCodesResult.Success -> Triple(
            "Codes Cleared",
            "Backup saved: ${result.snapshotFilename}\n" +
                "MIL: ${if (result.postMilOn == true) "On" else "Off"} • " +
                "Stored: ${result.remainingStored.size} • Pending: ${result.remainingPending.size} • " +
                "Permanent: ${result.remainingPermanent.size}",
            StatusGreen
        )
        is ClearCodesResult.CodesRemain -> Triple(
            "Clear Sent — Codes Remain",
            "Backup saved: ${result.snapshotFilename}\n" +
                "Stored remaining: ${result.remainingStored.size}, Pending: ${result.remainingPending.size}, " +
                "Permanent: ${result.remainingPermanent.size}. Fault may still be active.",
            AccentYellow
        )
        is ClearCodesResult.Refused -> Triple(
            "Clear Codes Refused",
            (result.snapshotFilename?.let { "Backup saved: $it\n" } ?: "") + result.reason,
            StatusRed
        )
        is ClearCodesResult.SnapshotFailed -> Triple(
            "Clear Codes Blocked",
            result.reason,
            StatusRed
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        titleContentColor = accent,
        textContentColor = PureWhite,
        title = { Text(title, fontWeight = FontWeight.ExtraBold) },
        text = {
            Text(
                text = body,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentYellow,
                    contentColor = TrueBlack
                )
            ) {
                Text("OK", fontWeight = FontWeight.ExtraBold)
            }
        }
    )
}
