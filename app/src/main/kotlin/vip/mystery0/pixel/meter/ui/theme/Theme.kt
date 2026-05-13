package com.kakao.taxi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── LIGHT: Warm sunrise — cream parchment + burnt orange ────────────────────
private val WarmLightScheme = lightColorScheme(
    primary              = Color(0xFFE8804A),   // burnt orange
    onPrimary            = Color(0xFFFFFFFF),
    primaryContainer     = Color(0xFFF5EFE8),   // warm ivory
    onPrimaryContainer   = Color(0xFF2D2016),
    secondary            = Color(0xFFAA9980),
    onSecondary          = Color(0xFFFFFFFF),
    secondaryContainer   = Color(0xFFEEE8F5),
    onSecondaryContainer = Color(0xFF2D2016),
    tertiary             = Color(0xFFF0A070),   // soft peach
    onTertiary           = Color(0xFFFFFFFF),
    background           = Color(0xFFFAF6F1),   // parchment
    onBackground         = Color(0xFF2D2016),
    surface              = Color(0xFFFAF6F1),
    onSurface            = Color(0xFF2D2016),
    surfaceVariant       = Color(0xFFF5EFE8),
    onSurfaceVariant     = Color(0xFF7A6650),
    outline              = Color(0xFFE8E0D5)
)

// ── DARK: Deep ocean midnight — pure cold blue, zero orange warmth ───────────
// Think: deep-sea navy → slate-blue cards → electric sky-blue accents
private val OceanDarkScheme = darkColorScheme(
    primary              = Color(0xFF5BB8FF),   // electric sky blue (tabs, active, brand accent)
    onPrimary            = Color(0xFF003258),
    primaryContainer     = Color(0xFF00243D),   // deep navy card (active state)
    onPrimaryContainer   = Color(0xFFBBDEFF),   // pale ice text on navy
    secondary            = Color(0xFF7EB5D6),   // muted steel blue
    onSecondary          = Color(0xFF00293E),
    secondaryContainer   = Color(0xFF0A1E2E),   // dark teal-navy card (alt cards)
    onSecondaryContainer = Color(0xFFB0D8F0),
    tertiary             = Color(0xFF90CAF9),   // periwinkle highlight
    onTertiary           = Color(0xFF002648),
    background           = Color(0xFF040D18),   // near-black deep navy
    onBackground         = Color(0xFFCEE5FF),   // ice blue-white text
    surface              = Color(0xFF081525),   // dark slate blue
    onSurface            = Color(0xFFCEE5FF),
    surfaceVariant       = Color(0xFF0D2035),   // card background — noticeably lighter than surface
    onSurfaceVariant     = Color(0xFF7EB5D6),   // steel blue label text
    outline              = Color(0xFF1A3A56)    // subtle blue border
)

// ── OLED: Pure black + icy neon blue ────────────────────────────────────────
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
    background           = Color(0xFF000000),   // true black
    onBackground         = Color(0xFFCEE5FF),
    surface              = Color(0xFF020810),
    onSurface            = Color(0xFFCEE5FF),
    surfaceVariant       = Color(0xFF061422),
    onSurfaceVariant     = Color(0xFF7EB5D6),
    outline              = Color(0xFF0C2236)
)

@Composable
fun PixelPulseTheme(
    darkTheme: Boolean = false,
    isOledTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        isOledTheme && darkTheme -> OledDarkScheme
        darkTheme                -> OceanDarkScheme
        else                     -> WarmLightScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
