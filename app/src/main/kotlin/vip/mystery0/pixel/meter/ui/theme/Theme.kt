package com.kakao.taxi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── LIGHT: Warm sunrise — cream parchment + burnt orange ─────────────────────
private val WarmLightScheme = lightColorScheme(
    primary              = Color(0xFFE8804A),
    onPrimary            = Color(0xFFFFFFFF),
    primaryContainer     = Color(0xFFF5EFE8),
    onPrimaryContainer   = Color(0xFF2D2016),
    secondary            = Color(0xFFAA9980),
    onSecondary          = Color(0xFFFFFFFF),
    secondaryContainer   = Color(0xFFEEE8F5),
    onSecondaryContainer = Color(0xFF2D2016),
    tertiary             = Color(0xFFF0A070),
    onTertiary           = Color(0xFFFFFFFF),
    background           = Color(0xFFFAF6F1),
    onBackground         = Color(0xFF2D2016),
    surface              = Color(0xFFFAF6F1),
    onSurface            = Color(0xFF2D2016),
    surfaceVariant       = Color(0xFFF5EFE8),
    onSurfaceVariant     = Color(0xFF7A6650),
    outline              = Color(0xFFE8E0D5)
)

// ── DARK: Deep ocean midnight ─────────────────────────────────────────────────
private val OceanDarkScheme = darkColorScheme(
    primary              = Color(0xFF5BB8FF),
    onPrimary            = Color(0xFF003258),
    primaryContainer     = Color(0xFF00243D),
    onPrimaryContainer   = Color(0xFFBBDEFF),
    secondary            = Color(0xFF7EB5D6),
    onSecondary          = Color(0xFF00293E),
    secondaryContainer   = Color(0xFF0A1E2E),
    onSecondaryContainer = Color(0xFFB0D8F0),
    tertiary             = Color(0xFF90CAF9),
    onTertiary           = Color(0xFF002648),
    background           = Color(0xFF040D18),
    onBackground         = Color(0xFFCEE5FF),
    surface              = Color(0xFF081525),
    onSurface            = Color(0xFFCEE5FF),
    surfaceVariant       = Color(0xFF0D2035),
    onSurfaceVariant     = Color(0xFF7EB5D6),
    outline              = Color(0xFF1A3A56)
)

// ── OLED: Pure black + icy neon blue ─────────────────────────────────────────
private val OledDarkScheme = darkColorScheme(
    primary              = Color(0xFF5BB8FF),
    onPrimary            = Color(0xFF003258),
    primaryContainer     = Color(0xFF001828),
    onPrimaryContainer   = Color(0xFFBBDEFF),
    secondary            = Color(0xFF7EB5D6),
    onSecondary          = Color(0xFF000000),
    secondaryContainer   = Color(0xFF040C14),
    onSecondaryContainer = Color(0xFFB0D8F0),
    tertiary             = Color(0xFF90CAF9),
    onTertiary           = Color(0xFF000000),
    background           = Color(0xFF000000),
    onBackground         = Color(0xFFCEE5FF),
    surface              = Color(0xFF020810),
    onSurface            = Color(0xFFCEE5FF),
    surfaceVariant       = Color(0xFF061422),
    onSurfaceVariant     = Color(0xFF7EB5D6),
    outline              = Color(0xFF0C2236)
)

// ── NEOCLEAN: Cyberpunk cyan/pink/purple on near-black ────────────────────────
val NeoCyan   = Color(0xFF22D3EE)
val NeoPink   = Color(0xFFE879F9)
val NeoPurple = Color(0xFFA855F7)
val NeoBg     = Color(0xFF030712)
val NeoPanel  = Color(0xFF0F172A)
val NeoPanelAlt = Color(0xFF080F1E)
val NeoMuted  = Color(0xFF94A3B8)
val NeoText   = Color(0xFFE2E8F0)

private val NeoCleanScheme = darkColorScheme(
    primary              = NeoCyan,
    onPrimary            = Color(0xFF001A20),
    primaryContainer     = Color(0xFF0A2535),
    onPrimaryContainer   = Color(0xFFB0F0FF),
    secondary            = NeoPurple,
    onSecondary          = Color(0xFF1A0030),
    secondaryContainer   = Color(0xFF1E0840),
    onSecondaryContainer = Color(0xFFE5C8FF),
    tertiary             = NeoPink,
    onTertiary           = Color(0xFF280030),
    background           = NeoBg,
    onBackground         = NeoText,
    surface              = NeoPanel,
    onSurface            = NeoText,
    surfaceVariant       = NeoPanel,
    onSurfaceVariant     = NeoMuted,
    outline              = Color(0xFF1E3A4A)
)

@Composable
fun PixelPulseTheme(
    darkTheme: Boolean = false,
    isOledTheme: Boolean = false,
    isNeoTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        isNeoTheme               -> NeoCleanScheme
        isOledTheme && darkTheme -> OledDarkScheme
        darkTheme                -> OceanDarkScheme
        else                     -> WarmLightScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
