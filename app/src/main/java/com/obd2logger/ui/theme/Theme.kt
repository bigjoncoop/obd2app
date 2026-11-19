package com.obd2logger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val TrueBlack = Color(0xFF000000)
val CardDark = Color(0xFF1E1E1E)
val CardBorderDark = Color(0xFF2E2E2E)
val AccentYellow = Color(0xFFFFC107)
val AccentYellowDim = Color(0x33FFC107)
val PureWhite = Color(0xFFFFFFFF)
val MutedWhite = Color(0xFFB0B0B0)
val StatusRed = Color(0xFFFF5252)
val StatusYellow = Color(0xFFFFC107)
val StatusGreen = Color(0xFF4CAF50)
val InfoBlue = Color(0xFF64B5F6)

private val ObdDarkColorScheme = darkColorScheme(
    primary = AccentYellow,
    onPrimary = TrueBlack,
    primaryContainer = CardDark,
    onPrimaryContainer = PureWhite,
    secondary = AccentYellow,
    onSecondary = TrueBlack,
    background = TrueBlack,
    onBackground = PureWhite,
    surface = CardDark,
    onSurface = PureWhite,
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = MutedWhite,
    error = StatusRed,
    onError = TrueBlack
)

private val ObdTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        color = PureWhite
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        color = PureWhite
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = PureWhite
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        color = PureWhite
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        color = PureWhite
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp
    )
)

@Composable
fun Obd2LoggerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ObdDarkColorScheme,
        typography = ObdTypography,
        content = content
    )
}
