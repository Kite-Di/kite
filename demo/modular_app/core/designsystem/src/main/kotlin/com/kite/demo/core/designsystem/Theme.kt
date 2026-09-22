package com.kite.demo.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Brand palette — deep teal primary with a warm amber accent. One place to
// change; screens never name raw colors.
private val Teal700 = Color(0xFF00675B)
private val Teal200 = Color(0xFF64D8CB)
private val Amber600 = Color(0xFFFFB300)
private val Ink = Color(0xFF1A1C1C)
private val Paper = Color(0xFFF7FAF9)
private val PaperDark = Color(0xFF111414)
private val Surface = Color(0xFFFFFFFF)
private val SurfaceDark = Color(0xFF1C2020)

private val LightColors = lightColorScheme(
    primary = Teal700,
    onPrimary = Color.White,
    secondary = Amber600,
    onSecondary = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
)

private val DarkColors = darkColorScheme(
    primary = Teal200,
    onPrimary = Ink,
    secondary = Amber600,
    onSecondary = Ink,
    background = PaperDark,
    onBackground = Paper,
    surface = SurfaceDark,
    onSurface = Paper,
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
)

/** Spacing scale — the only gaps screens should use. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

/** The app-wide theme: every screen (fragment ComposeView or full activity) wraps in this. */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
