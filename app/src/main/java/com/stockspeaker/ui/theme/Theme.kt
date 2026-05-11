package com.stockspeaker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── 暗色主题 "Brutalist Trading Terminal" ──
// 纯黑OLED背景 + 高对比白字 + 红绿仅用于涨跌方向
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF3B30),
    onPrimary = Color.White,
    primaryContainer = Color(0x33FF3B30),
    onPrimaryContainer = Color(0xFFFF6B60),
    secondary = Color(0xFFFFB800),
    onSecondary = Color.Black,
    secondaryContainer = Color(0x33FFB800),
    onSecondaryContainer = Color(0xFFFFB800),
    tertiary = Color(0xFF00C853),
    onTertiary = Color.Black,
    background = Color(0xFF000000),
    onBackground = Color(0xFFF0F0F0),
    surface = Color(0xFF080808),
    onSurface = Color(0xFFF0F0F0),
    surfaceVariant = Color(0xFF101010),
    onSurfaceVariant = Color(0xFF999999),
    outline = Color(0xFF2A2A2A),
    outlineVariant = Color(0xFF1A1A1A),
    error = Color(0xFFFF3B30),
    onError = Color.White
)

// ── 亮色主题 "High Contrast White" ──
private val LightScheme = lightColorScheme(
    primary = Color(0xFFDC2626),
    onPrimary = Color.White,
    primaryContainer = Color(0x1ADC2626),
    onPrimaryContainer = Color(0xFFDC2626),
    secondary = Color(0xFFB45309),
    onSecondary = Color.White,
    secondaryContainer = Color(0x1AFBBF24),
    onSecondaryContainer = Color(0xFFB45309),
    tertiary = Color(0xFF16A34A),
    onTertiary = Color.White,
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFF666666),
    outline = Color(0xFFD0D0D0),
    outlineVariant = Color(0xFFE5E5E5),
    error = Color(0xFFDC2626),
    onError = Color.White
)

// ── 语义颜色 ──
object StockColors {
    val priceUp = Color(0xFFFF3B30)
    val priceDown = Color(0xFF00C853)
    val priceUpBg = Color(0x1AFF3B30)
    val priceDownBg = Color(0x1A00C853)
    val priceFlat = Color(0xFF888888)

    val cardSurface = Color(0xFF0C0C0C)
    val cardBorder = Color(0xFF2A2A2A)
    val accentGold = Color(0xFFFFB800)
    val accentGoldBg = Color(0x1AFFB800)
    val runningGreen = Color(0xFF00E676)
    val runningGreenBg = Color(0x1A00E676)
}

@Composable
fun StockSpeakerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}
