package com.stockspeaker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── 暗色主题（默认） — 专业盯盘风格 ──
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0D1628),
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFFBBF24),
    onSecondary = Color(0xFF1A1500),
    secondaryContainer = Color(0xFF4A3F00),
    onSecondaryContainer = Color(0xFFFFDEA1),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF00382C),
    background = Color(0xFF0B1120),
    onBackground = Color(0xFFE8EDF5),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFE8EDF5),
    surfaceVariant = Color(0xFF1F2937),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF4B5563),
    outlineVariant = Color(0xFF374151),
    error = Color(0xFFEF4444),
    onError = Color.White
)

// ── 亮色主题 ──
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFFB45309),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEF3C7),
    onSecondaryContainer = Color(0xFF271900),
    tertiary = Color(0xFF059669),
    onTertiary = Color.White,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color.White
)

// ── 语义颜色 ──
object StockColors {
    val priceUp = Color(0xFFDC2626)       // 中国红：涨
    val priceDown = Color(0xFF16A34A)     // 中国绿：跌
    val priceUpBg = Color(0x1ADC2626)
    val priceDownBg = Color(0x1A16A34A)
    val priceFlat = Color(0xFF9CA3AF)

    val cardDark = Color(0xFF1A2332)
    val cardBorder = Color(0xFF2D3A4A)
    val accentGold = Color(0xFFFBBF24)
    val accentGoldBg = Color(0x1AFBBF24)
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
