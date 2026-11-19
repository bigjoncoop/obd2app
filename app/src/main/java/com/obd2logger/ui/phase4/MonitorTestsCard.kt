package com.obd2logger.ui.phase4

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obd2logger.domain.model.Mode06ResultStatus
import com.obd2logger.domain.model.Mode06TestResult
import com.obd2logger.ui.theme.AccentYellow
import com.obd2logger.ui.theme.CardBorderDark
import com.obd2logger.ui.theme.CardDark
import com.obd2logger.ui.theme.MutedWhite
import com.obd2logger.ui.theme.PureWhite
import com.obd2logger.ui.theme.StatusGreen
import com.obd2logger.ui.theme.StatusRed
import com.obd2logger.ui.theme.TrueBlack

/**
 * Displays Mode 06 on-board monitor test results.
 * Failing tests are highlighted first in red/yellow; passing tests are shown in white.
 */
@Composable
fun MonitorTestsCard(
    tests: List<Mode06TestResult>,
    modifier: Modifier = Modifier
) {
    val failCount = tests.count { it.result == Mode06ResultStatus.FAIL }
    val passCount = tests.count { it.result == Mode06ResultStatus.PASS }

    // Sort failing tests first, then unknown, then passing
    val ordered = tests.sortedBy {
        when (it.result) {
            Mode06ResultStatus.FAIL -> 0
            Mode06ResultStatus.UNKNOWN -> 1
            Mode06ResultStatus.PASS -> 2
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (failCount > 0) StatusRed.copy(alpha = 0.7f) else CardBorderDark
        )
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
                    text = "MONITOR TESTS (MODE 06)",
                    color = PureWhite,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (failCount > 0) {
                        StatusChip(text = "$failCount FAIL", color = StatusRed)
                    }
                    StatusChip(text = "$passCount PASS", color = StatusGreen)
                }
            }

            HorizontalDivider(color = CardBorderDark)

            ordered.forEach { test ->
                MonitorTestRow(test = test)
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun MonitorTestRow(test: Mode06TestResult) {
    val isFail = test.result == Mode06ResultStatus.FAIL
    val isUnknown = test.result == Mode06ResultStatus.UNKNOWN

    val badgeColor = when (test.result) {
        Mode06ResultStatus.FAIL -> StatusRed
        Mode06ResultStatus.UNKNOWN -> AccentYellow
        Mode06ResultStatus.PASS -> StatusGreen
    }
    val titleColor = when {
        isFail -> StatusRed
        isUnknown -> AccentYellow
        else -> PureWhite
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isFail) StatusRed.copy(alpha = 0.12f) else TrueBlack,
                RoundedCornerShape(10.dp)
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = test.midName,
                color = titleColor,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            StatusChip(text = test.result.label, color = badgeColor)
        }

        val tidLabel = if (test.tidName != "Unknown") {
            "TID ${test.tidHex}: ${test.tidName}"
        } else {
            "MID ${test.midHex} • TID ${test.tidHex}"
        }
        Text(
            text = tidLabel,
            color = if (isFail) AccentYellow else MutedWhite,
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val unitSuffix = if (test.unit == "Unknown") "" else " ${test.unit}"
            Text(
                text = "Value: ${test.testValue}$unitSuffix",
                color = if (isFail) StatusRed else PureWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                text = "Limits: ${test.minLimit} .. ${test.maxLimit}",
                color = MutedWhite,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
    }
}
