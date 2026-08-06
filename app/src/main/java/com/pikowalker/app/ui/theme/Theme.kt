package com.pikowalker.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle

private val LightColors = lightColorScheme(
    primary = ForestGreen,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = MeadowLight,
    onPrimaryContainer = ForestGreenDark,
    secondary = ForestGreenLight,
    tertiary = PollenYellow,
    error = EarthRed,
    background = MeadowLight,
    surface = androidx.compose.ui.graphics.Color.White,
    onBackground = DeepForest,
    onSurface = DeepForest,
    outline = StoneGray
)

private val DarkColors = darkColorScheme(
    primary = ForestGreenLight,
    onPrimary = DeepForest,
    primaryContainer = ForestGreenDark,
    onPrimaryContainer = MeadowLight,
    secondary = ForestGreenLight,
    tertiary = PollenYellow,
    error = EarthRed,
    background = DeepForest,
    surface = ForestGreenDark,
    onBackground = MeadowLight,
    onSurface = MeadowLight,
    outline = StoneGray
)

// Android's default text layout reserves extra vertical space above/below the glyph itself
// (legacy "font padding"), which grows disproportionately with the system font-scale setting —
// that's what pushes text toward the bottom of small fixed-height buttons/badges at larger
// accessibility text sizes. Stripping it once here, at the root, fixes every Text() in the app
// (including inside Button/OutlinedButton, since Material3's ProvideTextStyle merges onto
// whatever LocalTextStyle already holds rather than replacing it).
private val NoFontPaddingStyle = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))

@Composable
fun PikoWalkerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography()
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.merge(NoFontPaddingStyle)
        ) {
            content()
        }
    }
}
