/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

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

/**
 * Synchronizes status and navigation bar icon contrast with the app theme for both Activity
 * windows and full-screen Dialog windows.
 *
 * @see <a href="https://developer.android.com/develop/ui/views/layout/edge-to-edge">Edge-to-edge layouts</a>
 * @see <a href="https://developer.android.com/develop/ui/compose/system/insets">Window insets in Compose</a>
 * @see <a href="https://developer.android.com/reference/androidx/core/view/WindowInsetsControllerCompat">WindowInsetsControllerCompat</a>
 */
@Composable
fun SystemBarIconAppearance(darkTheme: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current

    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window
            ?: context.findActivity()?.window
            ?: return@SideEffect

        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
