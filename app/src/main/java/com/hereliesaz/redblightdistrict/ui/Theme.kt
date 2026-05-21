package com.hereliesaz.redblightdistrict.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFFFBF00)
val Blight = Color(0xFFFF0000)
val Cyan = Color(0xFF00E5FF)
val Pink = Color(0xFFFF3366)
val NearBlack = Color(0xFF0B0B0B)
val Charcoal = Color(0xFF222222)
val DimText = Color(0xFFEEEEEE)
val MutedText = Color(0xFF888888)

private val BlightDark = darkColorScheme(
    primary = Amber,
    onPrimary = Color.Black,
    secondary = Cyan,
    onSecondary = Color.Black,
    tertiary = Blight,
    onTertiary = Color.White,
    background = NearBlack,
    onBackground = DimText,
    surface = NearBlack,
    onSurface = DimText,
    surfaceVariant = Charcoal,
    onSurfaceVariant = DimText,
)

@Composable
fun RedBlightDistrictTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val systemDark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = BlightDark, content = content)
}
