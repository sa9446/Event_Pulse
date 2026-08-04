package com.bitchat.watch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

val BitchatWearColorScheme = ColorScheme(
    primary = Color(0xFF8B7CFF),
    onPrimary = Color(0xFF1A1340),
    primaryContainer = Color(0xFF342A70),
    onPrimaryContainer = Color(0xFFE5E1FF),
    secondary = Color(0xFF4DD6C3),
    onSecondary = Color(0xFF083A33),
    secondaryContainer = Color(0xFF0E4A42),
    onSecondaryContainer = Color(0xFFC9F5EC),
    tertiary = Color(0xFFFFB454),
    onTertiary = Color(0xFF3A2400),
    background = Color(0xFF0B0E15),
    onBackground = Color(0xFFE8EAF2),
    surfaceContainer = Color(0xFF141826),
    surfaceContainerLow = Color(0xFF10141F),
    surfaceContainerHigh = Color(0xFF1D2337),
    onSurface = Color(0xFFE8EAF2),
    onSurfaceVariant = Color(0xFFA7AEC4),
    outline = Color(0xFF3A4158),
    outlineVariant = Color(0xFF262C40),
    error = Color(0xFFFF5D6C),
    onError = Color(0xFF3A0A12),
)

@Composable
fun BitchatWearTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBitchatPalette provides DarkBitchatPalette) {
        MaterialTheme(
            colorScheme = BitchatWearColorScheme,
            typography = BitchatWearTypography,
            content = content
        )
    }
}
