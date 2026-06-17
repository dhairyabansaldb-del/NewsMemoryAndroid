package com.dhairya.newsmemory.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontWeight

enum class ThemeMode { LIGHT, DARK, AUTO;
    companion object {
        fun from(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: AUTO
    }
}

private fun materialScheme(a: AlmanacColors) =
    if (a.isDark) darkColorScheme(
        primary = a.accent, onPrimary = a.accentInk,
        secondary = a.accent, onSecondary = a.accentInk,
        background = a.bg, onBackground = a.ink,
        surface = a.card, onSurface = a.ink,
        surfaceVariant = a.card2, onSurfaceVariant = a.inkMed,
        outline = a.line2, error = a.warn
    ) else lightColorScheme(
        primary = a.accent, onPrimary = a.accentInk,
        secondary = a.accent, onSecondary = a.accentInk,
        background = a.bg, onBackground = a.ink,
        surface = a.card, onSurface = a.ink,
        surfaceVariant = a.card2, onSurfaceVariant = a.inkMed,
        outline = a.line2, error = a.warn
    )

private fun typography(a: AlmanacColors) = Typography().run {
    val base = body(14.0)
    Typography(
        headlineMedium = display(27),
        titleLarge = display(18),
        titleMedium = body(14.0, FontWeight.SemiBold),
        titleSmall = Eyebrow,
        bodyLarge = body(14.0),
        bodyMedium = body(12.5),
        bodySmall = body(11.5),
        labelSmall = PillLabel,
        labelMedium = body(11.0, FontWeight.Medium)
    )
}

@Composable
fun NewsMemoryTheme(
    themeMode: ThemeMode = ThemeMode.AUTO,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }
    val almanac = if (dark) AlmanacDark else AlmanacLight
    CompositionLocalProvider(LocalAlmanac provides almanac) {
        MaterialTheme(
            colorScheme = materialScheme(almanac),
            typography = typography(almanac),
            content = content
        )
    }
}
