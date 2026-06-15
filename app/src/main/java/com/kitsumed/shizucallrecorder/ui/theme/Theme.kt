/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Green80,
    onPrimary = DeepDarkGreen, // Dark text on light-green buttons
    primaryContainer = GreenContainerDark,
    onPrimaryContainer = GreenContainerLight,

    secondary = GreenGrey80,
    onSecondary = DarkGreyGreen,

    tertiary = AccentGreen80,
    onTertiary = AccentGreenDark,

    surface = DarkSurface,
    onSurface = OffWhiteText,
    outline = GreyGreenOutline
)

private val LightColorScheme = lightColorScheme(
    primary = Green40,
    onPrimary = White, // White text on dark-green buttons
    primaryContainer = GreenContainerLight,
    onPrimaryContainer = VeryDarkForest,

    secondary = GreenGrey40,
    onSecondary = White,

    surface = LightSurface,
    onSurface = NearBlackText,
    outline = GreyGreenOutline
)

@Composable
fun ShizucallrecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}