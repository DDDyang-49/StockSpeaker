package com.stockspeaker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PremiumColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF7B5800),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDEA1),
    onSecondaryContainer = Color(0xFF271900),
    tertiary = Color(0xFF006B5E),
    onTertiary = Color.White,
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF1A1C20),
    surface = Color.White,
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFE8EDF2),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

object AppColors {
    val priceUp = Color(0xFFD32F2F)
    val priceDown = Color(0xFF2E7D32)
    val cardBackground = Color.White
    val dividerColor = Color(0xFFE8EDF2)
    val accentGold = Color(0xFFF9A825)
}

@Composable
fun StockSpeakerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PremiumColorScheme,
        content = content
    )
}
