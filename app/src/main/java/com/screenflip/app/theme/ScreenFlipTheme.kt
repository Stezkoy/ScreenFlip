package com.screenflip.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Applies the ScreenFlip Material 3 theme.
 *
 * The accent color comes from the system (Material You dynamic color), so the
 * app follows the wallpaper / accent selected in the device settings. The
 * explicit Material 3 palettes are only a fallback for devices that do not
 * provide dynamic colors.
 */
@Composable
fun ScreenFlipTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = runCatching {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }.getOrElse {
        if (darkTheme) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
