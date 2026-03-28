package com.mediadrop.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Dark color scheme (navy + gold) ─────────────────────────────────────────
val DarkColorScheme = darkColorScheme(
    primary             = Gold500,
    onPrimary           = Navy950,
    primaryContainer    = Navy700,
    onPrimaryContainer  = Gold400,
    secondary           = Teal400,
    onSecondary         = Navy950,
    secondaryContainer  = Navy800,
    onSecondaryContainer= Teal400,
    tertiary            = Purple400,
    onTertiary          = Navy950,
    background          = Navy950,
    onBackground        = White,
    surface             = Navy900,
    onSurface           = White,
    surfaceVariant      = Navy800,
    onSurfaceVariant    = Grey200,
    outline             = Grey600,
    outlineVariant      = Navy700,
    error               = Red400,
    onError             = White,
    errorContainer      = Color(0xFF3D0000),
    onErrorContainer    = Red400,
    inverseSurface      = Grey100,
    inverseOnSurface    = Navy900,
    inversePrimary      = Gold600,
    scrim               = Color(0xCC000000)
)

// ── Light color scheme (warm cream + gold) ───────────────────────────────────
val LightColorScheme = lightColorScheme(
    primary             = Gold600,
    onPrimary           = White,
    primaryContainer    = Color(0xFFFFF3C0),
    onPrimaryContainer  = Color(0xFF3A2800),
    secondary           = Teal500,
    onSecondary         = White,
    secondaryContainer  = Color(0xFFCCF9F4),
    onSecondaryContainer= Color(0xFF00302B),
    tertiary            = Color(0xFF7B5EBC),
    onTertiary          = White,
    background          = Color(0xFFFAF8F2),
    onBackground        = Color(0xFF1A1A1A),
    surface             = Color(0xFFFFFFFF),
    onSurface           = Color(0xFF1A1A1A),
    surfaceVariant      = Color(0xFFF2EDD8),
    onSurfaceVariant    = Color(0xFF4A4030),
    outline             = Color(0xFFB8A88A),
    outlineVariant      = Color(0xFFE0D5B8),
    error               = Color(0xFFB00020),
    onError             = White,
    inverseSurface      = Navy900,
    inverseOnSurface    = White,
    inversePrimary      = Gold400,
    scrim               = Color(0x66000000)
)

/**
 * @param darkTheme  true = dark navy/gold, false = light cream/gold.
 *                   Pass the value from SettingsViewModel.
 */
@Composable
fun MediaDropTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography  = DCTypography,
        content     = content
    )
}
