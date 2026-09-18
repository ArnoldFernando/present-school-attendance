package com.attendancefr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF0F4C81)
private val Teal = Color(0xFF1B7A6E)
private val Gold = Color(0xFFE8B86D)
private val Ink = Color(0xFF12263A)
private val Mist = Color(0xFFF4F7FB)

private val LightColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4F5),
    onPrimaryContainer = Ink,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4EFEA),
    tertiary = Gold,
    background = Mist,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7EDF5),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC3F0),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF1A3E66),
    secondary = Color(0xFF8ED4C8),
    background = Color(0xFF0C1520),
    onBackground = Color(0xFFE6EEF7),
    surface = Color(0xFF132030),
    onSurface = Color(0xFFE6EEF7),
    surfaceVariant = Color(0xFF1E2E42),
    error = Color(0xFFF2B8B5),
)

@Composable
fun AttendanceFrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
