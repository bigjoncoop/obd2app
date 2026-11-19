package com.obd2logger.ui.phase3

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obd2logger.ui.theme.AccentYellow
import com.obd2logger.ui.theme.CardBorderDark
import com.obd2logger.ui.theme.CardDark
import com.obd2logger.ui.theme.MutedWhite
import com.obd2logger.ui.theme.PureWhite
import com.obd2logger.ui.theme.TrueBlack
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val highlight: String
)

private val onboardingPages = listOf(
    OnboardingPage(
        icon = Icons.Default.BluetoothConnected,
        title = "1. Connect Your OBD2 Adapter",
        body = "Plug your Bluetooth Classic, BLE, or Wi-Fi ELM327 adapter into your vehicle's OBD2 port and turn the ignition ON.",
        highlight = "Tap Connect on the Home screen to pair or auto-probe your adapter."
    ),
    OnboardingPage(
        icon = Icons.Default.Description,
        title = "2. Scan Codes & Log 1Hz CSV",
        body = "Tap START LOGGING to read stored, pending, and permanent DTCs, readiness monitors, freeze frame, and VIN — then stream 1-second sensor rows.",
        highlight = "Every file uses a fixed, Torque-compatible schema saved to Documents/OBDLogs."
    ),
    OnboardingPage(
        icon = Icons.Default.Share,
        title = "3. Reliable & Ready for AI",
        body = "Logging runs in a foreground service even with the screen off, flushes regularly, and automatically reconnects if the adapter drops mid-drive.",
        highlight = "Share any saved CSV directly from the Log Saved screen or Past Logs tab."
    )
)

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrueBlack)
            .systemBarsPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onFinish) {
                Text("Skip", color = MutedWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "OBD2 LOGGER",
            color = AccentYellow,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { pageIndex ->
            val page = onboardingPages[pageIndex]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, CardBorderDark)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(84.dp)
                                .background(AccentYellow.copy(alpha = 0.14f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = page.icon,
                                contentDescription = null,
                                tint = AccentYellow,
                                modifier = Modifier.size(42.dp)
                            )
                        }

                        Text(
                            text = page.title,
                            color = PureWhite,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = page.body,
                            color = PureWhite.copy(alpha = 0.9f),
                            fontSize = 16.sp,
                            lineHeight = 23.sp,
                            textAlign = TextAlign.Center
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TrueBlack, RoundedCornerShape(12.dp))
                                .padding(14.dp)
                        ) {
                            Text(
                                text = page.highlight,
                                color = AccentYellow,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // Page indicator dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 20.dp)
        ) {
            repeat(onboardingPages.size) { index ->
                val isSelected = pagerState.currentPage == index
                val color by animateColorAsState(
                    targetValue = if (isSelected) AccentYellow else CardBorderDark,
                    label = "dotColor"
                )
                Box(
                    modifier = Modifier
                        .height(10.dp)
                        .width(if (isSelected) 28.dp else 10.dp)
                        .background(color, CircleShape)
                )
            }
        }

        val isLastPage = pagerState.currentPage == onboardingPages.lastIndex
        Button(
            onClick = {
                if (isLastPage) {
                    onFinish()
                } else {
                    scope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                }
            },
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
            Text(
                text = if (isLastPage) "GET STARTED" else "NEXT",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 17.sp
            )
        }
    }
}
