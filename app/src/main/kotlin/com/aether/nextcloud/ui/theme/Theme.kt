package com.aether.nextcloud.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

// Vivid brand palette: used below Android 12 and when Dynamic Color yields a near-grey scheme (neutral wallpaper),
// which would otherwise flatten the colour-block layout.

private val LightColors = lightColorScheme(
    primary = Color(0xFF005FB8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF00696B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB8EDEE),
    onSecondaryContainer = Color(0xFF002021),
    tertiary = Color(0xFF9A3F8F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD6F5),
    onTertiaryContainer = Color(0xFF3B0036),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6C8FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF004786),
    onPrimaryContainer = Color(0xFFD3E3FF),
    secondary = Color(0xFF4CDADC),
    onSecondary = Color(0xFF003738),
    secondaryContainer = Color(0xFF004F51),
    onSecondaryContainer = Color(0xFFB8EDEE),
    tertiary = Color(0xFFFFADF0),
    onTertiary = Color(0xFF5A0F53),
    tertiaryContainer = Color(0xFF79276F),
    onTertiaryContainer = Color(0xFFFFD6F5),
)

private fun ColorScheme.isNearGrey(): Boolean {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(primaryContainer.toArgb(), hsv)
    return hsv[1] < 0.12f
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AetherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val brand = if (darkTheme) DarkColors else LightColors
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).takeUnless { it.isNearGrey() } ?: brand
    } else brand
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = AetherTypography,
        content = content,
    )
}
