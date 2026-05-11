package com.stockspeaker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── 亮色主题 "樱花交易终端" — 白底淡粉，专业盯盘风格 ──
private val LightScheme = lightColorScheme(
    primary = Color(0xFFD43D5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE5EB),
    onPrimaryContainer = Color(0xFF5C1A2E),
    secondary = Color(0xFFC8840A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFF3E0),
    onSecondaryContainer = Color(0xFF4A3500),
    tertiary = Color(0xFF0F7B37),
    onTertiary = Color.White,
    background = Color(0xFFFFF5F5),
    onBackground = Color(0xFF2D1B20),
    surface = Color.White,
    onSurface = Color(0xFF2D1B20),
    surfaceVariant = Color(0xFFFFE5EB),
    onSurfaceVariant = Color(0xFF6B5560),
    outline = Color(0xFFE8D0D5),
    outlineVariant = Color(0xFFF0DEE2),
    error = Color(0xFFDC2626),
    onError = Color.White
)

// ── 语义颜色 ──
object StockColors {
    val priceUp = Color(0xFFDC2626)       // 中国红：涨
    val priceDown = Color(0xFF0F7B37)     // 中国绿：跌
    val priceUpBg = Color(0x1ADC2626)
    val priceDownBg = Color(0x1A0F7B37)
    val priceFlat = Color(0xFF888888)

    val cardBorder = Color(0xFFE8D0D5)
    val cardSurface = Color(0xFFFFFAFB)
    val accentGold = Color(0xFFC8840A)
    val accentGoldBg = Color(0x1AC8840A)
}

@Composable
fun StockSpeakerTheme(
    darkTheme: Boolean = false,  // 始终亮色
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightScheme,
        content = content
    )
}
