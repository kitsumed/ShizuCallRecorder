/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.viewmodels

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kitsumed.shizucallrecorder.BuildConfig
import com.kitsumed.shizucallrecorder.services.call.CallSessionManager
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.kitsumed.shizucallrecorder.utils.AppLogger

// -------- Screen state & action types owned by this ViewModel

/**
 * The four phone-call lifecycle events that can be simulated from the debug panel.
 *
 * Used by [SettingsViewModel.triggerDebugAction] to fire a broadcast that exercises the
 * recording pipeline without needing a real phone call.
 */
enum class DebugAction {
    /** Simulate an incoming call starting to ring. */
    RINGING,
    /** Simulate the call being answered (off-hook) or an outgoing call being placed. */
    OFFHOOK,
    /** Simulate the call being idle (no active call). */
    IDLE
}

/**
 * Interface defining all user actions that can be triggered from the Settings screen.
 * This abstraction allows Compose overloads without concrete ViewModels, allowing Previews of the Stateless UI.
 */
interface SettingsActions {
    fun setAutoRecordIncoming(enabled: Boolean)
    fun setAutoRecordOutgoing(enabled: Boolean)
    fun setVibrationEnabled(enabled: Boolean)
    fun setIgnoreAnonymousIncoming(enabled: Boolean)
    fun setIgnoreCrossCountryIncoming(enabled: Boolean)
    fun setIgnoreCrossCountryOutgoing(enabled: Boolean)
    fun setIgnoreContactsModeIncoming(modeEnum: AppPreferences.IgnoreContactsMode)
    fun setIgnoreContactsModeOutgoing(modeEnum: AppPreferences.IgnoreContactsMode)
    fun setAudioSource(source: String)
    fun setAudioCodec(codec: String)
    fun setAudioBitRate(bitRate: Int)
    fun setThemeMode(mode: AppPreferences.ThemeMode)
    fun setDynamicColorEnabled(enabled: Boolean)
    fun setShowToastsEnabled(enabled: Boolean)
    fun setAppLanguage(languageCode: String)
    fun setLoggingEnabled(enabled: Boolean)
    fun setDebugEnabled(enabled: Boolean)
    fun setDebugCallerNumber(number: String)
    fun triggerDebugAction(action: DebugAction)
    fun exportLogs(uri: android.net.Uri)
    fun getAppVersion(): String
    fun setShizukuAutoManageEnabled(enabled: Boolean)
    fun setShizukuStartOnRecordEnabled(enabled: Boolean)
    fun setShizukuKeepAliveEnabled(enabled: Boolean)
    fun setShizukuAuthKey(key: String)
    fun setFileNameTemplate(template: String)
}

/**
 * The "Brain" of the Settings screen.
 *
 * Navigation and onboarding routing are handled by [AppNavigationViewModel].
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application), SettingsActions {

    /**
     * Application context — safe to store in a ViewModel because it lives as long as the app
     * process, unlike an Activity context which is destroyed and recreated on every rotation.
     */
    private val appContext = application.applicationContext

    /**
     * Read and Manager AppPreference settings
     */
    val preferences = AppPreferences(appContext)

    // -------- Internal mutable state
    // Private so only this ViewModel can mutate it.

    /**
     * Backing store for [updateTrigger].
     */
    private val _updateTrigger = MutableStateFlow(0)

    // -------- Public state

    /**
     * A trigger flow for recomposition.
     */
    val updateTrigger: StateFlow<Int> = _updateTrigger.asStateFlow()

    // -------- Refresh

    /**
     * Retrieves the formatted application version string, including CI run numbers.
     *
     * @return Formatted string like "Version 1.0 (1) - CI Run #1234" or "Version 1.0 (1)"
     */
    override fun getAppVersion(): String {
        return try {
            val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val base = "Version ${packageInfo.versionName} (${packageInfo.longVersionCode})"
            val ciBuild = BuildConfig.CI_BUILD_NUMBER
            if (ciBuild.lowercase() == "local") {
                "$base - Local Build"
            } else {
                "$base - CI Run #$ciBuild"
            }
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            "Unknown Version"
        }
    }

    /**
     * Triggers a recompose across the settings screen.
     * 
     * **Important:** Jetpack Compose compiler is aggressive when optimizing and will skip
     * recomposition of components if it thinks inputs haven't changed (Dead Parameter Elimination).
     * Since [preferences] reads are not backed by Compose `State`, you must wrap your reads in 
     * `remember(updateTrigger)` in your composables so the compiler knows they must be re-evaluated.
     * 
     * Example:
     * ```kotlin
     * val updateTrigger by viewModel.updateTrigger.collectAsState()
     * val autoRecord = remember(updateTrigger) { preferences.isAutoRecordIncomingEnabled() }
     * ```
     */
    fun refresh() {
        _updateTrigger.update { it + 1 }
    }

    // -------- Recording settings

    /** Turn automatic recording of incoming calls on or off.
     *
     * @param enabled `true` to record incoming calls automatically.
     */
    override fun setAutoRecordIncoming(enabled: Boolean) {
        preferences.setAutoRecordIncomingEnabled(enabled)
        refresh()
    }

    /** Turn automatic recording of outgoing calls on or off.
     *
     * @param enabled `true` to record outgoing calls automatically.
     */
    override fun setAutoRecordOutgoing(enabled: Boolean) {
        preferences.setAutoRecordOutgoingEnabled(enabled)
        refresh()
    }

    /** Enables or disables vibration feedback.
     *
     * @param enabled `true` to vibrate on start/stop.
     */
    override fun setVibrationEnabled(enabled: Boolean) {
        preferences.setVibrationEnabled(enabled)
        refresh()
    }

    /** When enabled, anonymous calls (no caller ID) are not recorded automatically.
     *
     * @param enabled `true` to skip recording calls with no caller ID.
     */
    override fun setIgnoreAnonymousIncoming(enabled: Boolean) {
        preferences.setIgnoreAnonymousIncomingEnabled(enabled)
        // When we disable anonymous ignore, we automatically disable cross country ignore because both a related. Anonymous call may as well be cross-country.
        if (!enabled) preferences.setIgnoreCrossCountryIncomingEnabled(false)
        refresh()
    }

    /**
     * Sets whether to ignore incoming cross-country calls.
     */
    override fun setIgnoreCrossCountryIncoming(enabled: Boolean) {
        preferences.setIgnoreCrossCountryIncomingEnabled(enabled)
        refresh()
    }

    /**
     * Sets whether to ignore outgoing cross-country calls.
     */
    override fun setIgnoreCrossCountryOutgoing(enabled: Boolean) {
        preferences.setIgnoreCrossCountryOutgoingEnabled(enabled)
        refresh()
    }

    /**
     * Sets which incoming contacts to ignore.
     *
     * @param modeEnum The [AppPreferences.IgnoreContactsMode] enum value to set.
     */
    override fun setIgnoreContactsModeIncoming(modeEnum: AppPreferences.IgnoreContactsMode) {
        preferences.setIgnoreContactsModeIncoming(modeEnum)
        refresh()
    }

    /**
     * Sets which outgoing contacts to ignore.
     *
     * @param modeEnum The [AppPreferences.IgnoreContactsMode] enum value to set.
     */
    override fun setIgnoreContactsModeOutgoing(modeEnum: AppPreferences.IgnoreContactsMode) {
        preferences.setIgnoreContactsModeOutgoing(modeEnum)
        refresh()
    }

    /** Saves the audio source to use for recording (e.g. "mic-voice-communication").
     *
     * @param source The audio source key passed to scrcpy's `audio_source` parameter.
     */
    override fun setAudioSource(source: String) {
        preferences.setAudioSource(source)
        refresh()
    }

    /** Saves the audio codec to use ("opus" or "aac").
     *
     * @param codec The codec key string.
     */
    override fun setAudioCodec(codec: String) {
        preferences.setAudioCodec(codec)
        ScrcpyAudioCodec.fromKey(codec).let {
            // Automatically adjust the bitrate to recommended value when codec changes
            preferences.setAudioBitRate(it.defaultBitRate)
        }
        refresh()
    }

    /** Saves the audio bit rate in bits per second (e.g. 16000 = 16 kbps).
     *
     * @param bitRate The bit rate in bps.
     */
    override fun setAudioBitRate(bitRate: Int) {
        preferences.setAudioBitRate(bitRate)
        refresh()
    }

    // -------- File Naming --------

    /** Saves the file name template.
     *
     * @param template The template string.
     */
    override fun setFileNameTemplate(template: String) {
        preferences.setFileNameTemplate(template)
        refresh()
    }

    // -------- Visual settings

    /** Saves the app theme.
     *
     * @param mode The ThemeMode enum value.
     */
    override fun setThemeMode(mode: AppPreferences.ThemeMode) {
        preferences.setThemeMode(mode)
        refresh()
    }

    /** Enables or disables Material You colours extracted from the wallpaper.
     *
     * @param enabled `true` to use wallpaper-derived colours; `false` to use the static palette.
     */
    override fun setDynamicColorEnabled(enabled: Boolean) {
        preferences.setDynamicColorEnabled(enabled)
        refresh()
    }

    /** Enables or disables toast notifications.
     *
     * @param enabled `true` to show toast notifications; `false` to disable them.
     */
    override fun setShowToastsEnabled(enabled: Boolean) {
        preferences.setShowToastsEnabled(enabled)
        refresh()
    }

    /** Saves the app language using AppCompat.
     *
     * @param languageCode The BCP-47 language tag describing the locale, or empty to follow system setting.
     */
    override fun setAppLanguage(languageCode: String) {
        val localeList = if (languageCode.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageCode)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
        refresh()
    }

    // -------- Security settings

    /** Enables or disables the automatic management of Shizuku using broadcasts.
     *
     * @param enabled `true` to let the app start/stop Shizuku.
     */
    override fun setShizukuAutoManageEnabled(enabled: Boolean) {
        preferences.setShizukuAutoManageEnabled(enabled)
        refresh()
    }

    /** Enables or disables starting Shizuku only when recording starts. */
    override fun setShizukuStartOnRecordEnabled(enabled: Boolean) {
        preferences.setShizukuStartOnRecordEnabled(enabled)
        refresh()
    }

    /** Enables or disables keeping Shizuku alive (not sending stop intent). */
    override fun setShizukuKeepAliveEnabled(enabled: Boolean) {
        preferences.setShizukuKeepAliveEnabled(enabled)
        refresh()
    }

    /** Saves the Shizuku auth key used to send the start/stop broadcasts.
     *
     * @param key The auth key string.
     */
    override fun setShizukuAuthKey(key: String) {
        preferences.setShizukuAuthKey(key)
        refresh()
    }

    // -------- Debug settings

    /** Enables or disables application background logging.
     *
     * @param enabled `true` to log application flow.
     */
    override fun setLoggingEnabled(enabled: Boolean) {
        preferences.setLoggingEnabled(enabled)
        if (!enabled) {
            AppLogger.clearLogs()
        }
        refresh()
    }

    /** Enables or disables the debug panel and other internal debug checks like log redactions.
     *
     * @param enabled `true` to show the debug panel.
     */
    override fun setDebugEnabled(enabled: Boolean) {
        preferences.setDebugEnabled(enabled)
        refresh()
    }

    /**
     * Saves the phone number used when simulating a call in debug mode.
     * Does not call [refresh] because the text field already shows the correct value.
     *
     * @param number The phone number to simulate (digits, `+`, and `-` only).
     */
    override fun setDebugCallerNumber(number: String) {
        preferences.setDebugCallerNumber(number)
    }

    /**
     * Fires a simulated call broadcast so you can test the recording flow without making
     * a real phone call.
     *
     * @param action The type of simulated call event to fire (see [DebugAction]).
     */
    override fun triggerDebugAction(action: DebugAction) {
        viewModelScope.launch {
            val actionType = when (action) {
                DebugAction.IDLE     -> CallSessionManager.ACTION_DEBUG_IDLE
                DebugAction.RINGING  -> CallSessionManager.ACTION_DEBUG_RINGING
                DebugAction.OFFHOOK  -> CallSessionManager.ACTION_DEBUG_OFFHOOK
            }
            CallSessionManager.getInstance(appContext).handleDebugAction(actionType)
        }
    }

    /**
     * Exports the application logs to a user-selected SAF file destination.
     * Starts an async coroutine to avoid blocking the main UI thread during physical disk writes.
     */
    override fun exportLogs(uri: android.net.Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            AppLogger.exportReport(appContext, uri)
        }
    }
}
