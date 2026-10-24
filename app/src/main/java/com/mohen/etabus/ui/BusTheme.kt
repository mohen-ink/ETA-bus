package com.mohen.etabus.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mohen.etabus.model.*

internal val LocalDigitFont = staticCompositionLocalOf { DigitFont.MONO }
internal val LiveAmber: Color @Composable get() = if (MaterialTheme.colorScheme.background == Color(0xFF0E1220)) Color(0xFFFFB84D) else Color(0xFFF59E0B)
internal val ArrivingGreen: Color @Composable get() = if (MaterialTheme.colorScheme.background == Color(0xFF0E1220)) Color(0xFF34D399) else Color(0xFF10B981)
internal val AlertOrange: Color @Composable get() = MaterialTheme.colorScheme.error

@Composable
internal fun BusTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF8FA8FF), onPrimary = Color(0xFF0E1220), background = Color(0xFF0E1220),
        onBackground = Color(0xFFE8ECF8), surface = Color(0xFF171C2E), onSurface = Color(0xFFE8ECF8),
        surfaceVariant = Color(0xFF20273C), onSurfaceVariant = Color(0xFF8892AB), outline = Color(0xFF262D45),
        outlineVariant = Color(0xFF262D45), error = Color(0xFFFF8A6B)
    ) else lightColorScheme(
        primary = Color(0xFF1F3A8A), onPrimary = Color.White, background = Color(0xFFF4F6FB),
        onBackground = Color(0xFF0F172A), surface = Color.White, onSurface = Color(0xFF0F172A),
        surfaceVariant = Color(0xFFECF0F8), onSurfaceVariant = Color(0xFF64748B), outline = Color(0xFFE2E8F0),
        outlineVariant = Color(0xFFE2E8F0), error = Color(0xFFDC5A3C)
    )
    val typography = Typography(
        titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
        titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 15.sp), bodyMedium = TextStyle(fontSize = 15.sp),
        bodySmall = TextStyle(fontSize = 13.sp), labelSmall = TextStyle(fontSize = 13.sp)
    )
    CompositionLocalProvider(LocalDigitFont provides settings.digitFont) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}