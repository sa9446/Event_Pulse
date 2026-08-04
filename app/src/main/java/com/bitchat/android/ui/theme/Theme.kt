package com.bitchat.android.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

// Standard UI semantics live in Material so stock components and custom Bitchat composables
// share one source of truth. LocalBitchatPalette below only supplies app-specific extra colors.
//
// Modern vibrant dark identity: deep charcoal-navy surfaces (never pure black), a violet
// primary that echoes the launcher accent, and a teal secondary for signal accents.
internal val DarkBitchatColorScheme = darkColorScheme(
    primary = Color(0xFF8B7CFF),
    onPrimary = Color(0xFF1A1340),
    primaryContainer = Color(0xFF342A70),
    onPrimaryContainer = Color(0xFFE5E1FF),
    secondary = Color(0xFF4DD6C3),
    onSecondary = Color(0xFF083A33),
    secondaryContainer = Color(0xFF0E4A42),
    onSecondaryContainer = Color(0xFFC9F5EC),
    tertiary = DarkBitchatPalette.accentOrange,
    onTertiary = Color(0xFF3A2400),
    background = Color(0xFF0B0E15),
    onBackground = Color(0xFFE8EAF2),
    surface = Color(0xFF141826),
    onSurface = Color(0xFFE8EAF2),
    surfaceVariant = Color(0xFF1D2337),
    onSurfaceVariant = Color(0xFFA7AEC4),
    outline = Color(0xFF3A4158),
    outlineVariant = Color(0xFF262C40),
    error = Color(0xFFFF5D6C),
    onError = Color(0xFF3A0A12)
)

internal val LightBitchatColorScheme = lightColorScheme(
    primary = Color(0xFF5B4BD9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E1FF),
    onPrimaryContainer = Color(0xFF1A1260),
    secondary = Color(0xFF00867C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F6F0),
    onSecondaryContainer = Color(0xFF003832),
    tertiary = LightBitchatPalette.accentOrange,
    onTertiary = Color(0xFF3A2400),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF191C26),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C26),
    surfaceVariant = Color(0xFFECEFF7),
    onSurfaceVariant = Color(0xFF545B70),
    outline = Color(0xFFC3C9D8),
    outlineVariant = Color(0xFFDADEEA),
    error = Color(0xFFD0021B),
    onError = Color.White
)

@Composable
fun BitchatTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    // App-level override from ThemePreferenceManager
    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.System)
    val shouldUseDark = when (darkTheme) {
        true -> true
        false -> false
        null -> when (themePref) {
            ThemePreference.Dark -> true
            ThemePreference.Light -> false
            ThemePreference.System -> isSystemInDarkTheme()
        }
    }

    val colorScheme = if (shouldUseDark) DarkBitchatColorScheme else LightBitchatColorScheme
    val palette = if (shouldUseDark) DarkBitchatPalette else LightBitchatPalette

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    if (!shouldUseDark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (!shouldUseDark) {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else 0
            }
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    CompositionLocalProvider(LocalBitchatPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
