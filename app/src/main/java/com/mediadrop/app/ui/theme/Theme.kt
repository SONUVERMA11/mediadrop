package com.mediadrop.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import com.google.accompanist.systemuicontroller.rememberSystemUiController

private val DarkColorScheme = darkColorScheme(
    primary          = Gold500,
    onPrimary        = Navy950,
    primaryContainer = Navy700,
    onPrimaryContainer = Gold400,

    secondary        = Teal400,
    onSecondary      = Navy950,
    secondaryContainer = Navy800,
    onSecondaryContainer = Teal400,

    tertiary         = Purple400,
    onTertiary       = Navy950,

    background       = Navy950,
    onBackground     = White,

    surface          = Navy900,
    onSurface        = White,
    surfaceVariant   = Navy800,
    onSurfaceVariant = Grey200,

    outline          = Grey600,
    outlineVariant   = Navy700,

    error            = Red400,
    onError          = White,
    errorContainer   = Color(0xFF3D0000),
    onErrorContainer = Red400,

    inverseSurface   = Grey100,
    inverseOnSurface = Navy900,
    inversePrimary   = Gold600,

    scrim            = Color(0xCC000000)
)

@Composable
fun MediaDropTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = DCTypography,
        content     = content
    )
}
