/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.onboarding.OnboardingStatus
import com.kitsumed.shizucallrecorder.ui.screens.DisclaimerScreen
import com.kitsumed.shizucallrecorder.ui.screens.PermissionsScreen
import com.kitsumed.shizucallrecorder.ui.screens.SettingsScreen
import com.kitsumed.shizucallrecorder.ui.screens.SponsorScreen
import com.kitsumed.shizucallrecorder.ui.theme.ShizucallrecorderTheme
import com.kitsumed.shizucallrecorder.ui.viewmodels.AppNavigationViewModel
import com.kitsumed.shizucallrecorder.ui.viewmodels.SettingsViewModel

/**
 * Top-level router composable called from [MainActivity].
 *
 * Decides which of three destinations to display - the one-time disclaimer, the permissions
 * checklist, or the main settings, wraps every destination in the app theme compose.
 *
 * ## State flow
 * - [AppNavigationViewModel] is the "Brain" for routing: it owns [AppNavigationViewModel.onboardingStatus]
 *   and decides which screen is active.
 * - [SettingsViewModel] is the "Brain" for settings: it owns [SettingsViewModel.updateTrigger]
 *   and all user-preference persistence.
 * - Both expose `StateFlow`s observed via [collectAsState] - the "bridge" that watches a data
 *   stream and triggers a refresh (recompose) whenever a value changes.
 * - [LocalLifecycleOwner] is the object that tells this composable whether the current screen
 *   is visible, in the background, or being destroyed. We attach a [LifecycleEventObserver]
 *   via [DisposableEffect] to refresh state whenever the user returns to the app (e.g. after
 *   granting a permission in the system Settings app).
 */
@Composable
fun AppNavigationScreen() {
    val activityContext = LocalContext.current

    // AppNavigationViewModel - the "Brain" for routing: owns onboarding state.
    val appNavViewModel: AppNavigationViewModel = viewModel()

    // SettingsViewModel - the "Brain" for settings: owns theme + preference state.
    val settingsViewModel: SettingsViewModel = viewModel()

    /**
     * [collectAsState] bridges the [AppNavigationViewModel.onboardingStatus] `StateFlow` to Compose.
     * Every time the flow emits a new [OnboardingStatus.Status] value, Compose triggers a
     * refresh (recompose) so [resolveScreen] picks the correct destination.
     */
    val onboardingStatus by appNavViewModel.onboardingStatus.collectAsState()

    /**
     * [collectAsState] bridges the [SettingsViewModel.updateTrigger] `StateFlow` to Compose.
     * Reading allow us to trigger a refresh (recompose) whenever the user changes a setting that requires a
     * major UI update (e.g. theme change) that can only be updated here in the AppNavigationScreen.
     */
    val settingsViewModelUpdateTrigger by settingsViewModel.updateTrigger.collectAsState() // reading .value here is required so it trigger a recomposition as soon as it changes.

    // AppPreferences is used to read preferences directly.
    val preferences = settingsViewModel.preferences

    // Listen for refresh in the SettingsViewModel, as certain settings changes may change some checks in the onboarding status.
    LaunchedEffect(settingsViewModelUpdateTrigger) {
        val newStatus = OnboardingStatus.getStatus(activityContext, preferences)
        if (newStatus != onboardingStatus) {
            appNavViewModel.refresh()
        }
    }

    // resolveScreen reads the flow-backed onboardingStatus - no direct preference reads here,
    // which is what caused the stale-state bug that existed before this architecture.
    val screenState = resolveScreen(onboardingStatus)

    // Trigger when the app resume activity, we want to refresh the navigation screens since
    // the user may have changed something in the system settings (e.g. granted a permission) that affects the onboarding status.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        appNavViewModel.refresh()
    }

    // Derive the active theme from AppPreferences so a theme change triggers a refresh (recompose)
    // and is applied immediately.
    val darkTheme = when ( preferences.getThemeMode()) {
        AppPreferences.ThemeMode.LIGHT -> false
        AppPreferences.ThemeMode.DARK   -> true
        AppPreferences.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val dynamicColor = preferences.isDynamicColorEnabled()

    // -------- Show the right screen
    ShizucallrecorderTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
        when (screenState) {
            AppScreen.Disclaimer -> DisclaimerScreen(
                onContinue = {
                    preferences.setDisclaimerAccepted(true)
                    appNavViewModel.refresh()
                }
            )

            AppScreen.Permissions -> PermissionsScreen(
                status              = onboardingStatus,
                onPermissionGranted = { appNavViewModel.refresh() }
            )

            AppScreen.Settings -> {
                val lastReminderTime = preferences.getLastForcedReminderSupportProjectTimeInApp()
                val currentTime = System.currentTimeMillis()

                // Create a local state initialized by your time check
                var showSponsorScreen by remember {
                    // Check if it's been more than a year since the last reminder. 31536000000 milliseconds = 1 year.
                    mutableStateOf(currentTime - lastReminderTime > 31536000000L)
                }

                if (showSponsorScreen) {
                    SponsorScreen( onDismiss = {
                        preferences.setLastForcedReminderSupportProjectTimeInApp(currentTime)
                        showSponsorScreen = false // Trigger recompose
                    })
                } else {
                    SettingsScreen(
                        viewModel = settingsViewModel
                    )
                }
            }
        }
    }
}

// -------- Private helpers

/** The three top-level screens. [AppNavigationScreen] shows one of these at a time. */
private enum class AppScreen {
    /** The user has not yet accepted the legal disclaimer. */
    Disclaimer,

    /** One or more required permissions are still missing. */
    Permissions,

    /** Everything is set up. Show the settings. */
    Settings
}

/**
 * Maps an [OnboardingStatus.Status] snapshot to the [AppScreen] that should be visible.
 *
 * The logic is intentionally linear:
 *  1. Disclaimer first — the user must accept before anything else is shown.
 *  2. Permissions next — every required permission must be granted.
 *  3. Settings last — shown only when the setup is fully complete.
 *
 * Relying on [OnboardingStatus.Status] (part of the `StateFlow`) instead of reading preferences
 * directly ensures that each acceptance/grant emits a new value through the flow, which
 * triggers a refresh (recompose) in [AppNavigationScreen] and advances the user automatically.
 *
 * @param status The latest snapshot emitted by [AppNavigationViewModel.onboardingStatus].
 * @return The [AppScreen] that matches the user's current setup progress.
 */
private fun resolveScreen(status: OnboardingStatus.Status): AppScreen {
    return when {
        !status.disclaimerAccepted -> AppScreen.Disclaimer
        !status.isComplete()       -> AppScreen.Permissions
        else                       -> AppScreen.Settings
    }
}
