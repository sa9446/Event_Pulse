package com.bitchat.watch.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class BitchatPalette(
    val inputOutline: Color,
    val inputOutlineFocused: Color,
    val inputSurface: Color,
    val inputSurfaceFocused: Color,
    val inputButton: Color,
    val textTertiary: Color,
    val accentOrange: Color,
    val accentPurple: Color,
    val peerColors: PeerColorStyle,
)

val DarkBitchatPalette = BitchatPalette(
    inputOutline = Color(0xFF3A4158),
    inputOutlineFocused = Color(0xFF8B7CFF),
    inputSurface = Color(0xFF10141F),
    inputSurfaceFocused = Color(0xFF191E2E),
    inputButton = Color(0xFF232940),
    textTertiary = Color(0xFF8A91A8),
    accentOrange = Color(0xFFFFB454),
    accentPurple = Color(0xFFC48FFF),
    peerColors = PeerColorStyle.Dark,
)

val LocalBitchatPalette = staticCompositionLocalOf { DarkBitchatPalette }

object BitchatMotion {
    const val QUICK_MS = 120
    const val STANDARD_MS = 180
    const val EMPHASIZED_MS = 240
}
