/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.data

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import androidx.core.net.toUri
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioCodec
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioSource
import com.kitsumed.shizucallrecorder.services.callDetection.CallDetectionMode
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * AppPreferences wraps [android.content.SharedPreferences] to provide typed access to all
 * user-configurable settings stored on the device.
 */
class AppPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "shizucallrecorder_prefs"

        private const val TAG = "SCR:AppPreferences"
    }

    /**
     * Single source of truth for all default settings values.
     * These value are the default app settings.
     */
    object DefaultsValue {
        // --- Onboarding & Legal ---
        const val DISCLAIMER_ACCEPTED = false

        // Calculates (Install Time - 10 Months) to leave exactly 2 months remaining
        fun LAST_FORCED_REMINDER_SUPPORT_PROJECT_TIME(context: Context): Long = (runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }.getOrDefault(Long.MIN_VALUE)) - 25920000000L // 300 days in milliseconds
        
        // --- Storage & General ---
        val RECORDING_FOLDER_URI: String? = null
        const val VIBRATION_ENABLED = true
        val CALL_DETECTION_MODE = CallDetectionMode.getDefaultModeForDevice().key
        const val RECORD_THIRD_PARTY_CALLS = false
        
        // --- Automation ---
        const val AUTO_RECORD_INCOMING = false
        const val AUTO_RECORD_OUTGOING = false
        
        // --- Filters & Contacts ---
        const val IGNORE_ANONYMOUS_INCOMING = false
        const val IGNORE_CROSS_COUNTRY_INCOMING = false
        const val IGNORE_CROSS_COUNTRY_OUTGOING = false
        val IGNORE_CONTACTS_MODE_INCOMING = IgnoreContactsMode.NONE
        val IGNORE_CONTACTS_MODE_OUTGOING = IgnoreContactsMode.NONE
        val IGNORED_CONTACTS_INCOMING = emptySet<String>()
        val IGNORED_CONTACTS_OUTGOING = emptySet<String>()
        
        // --- Developer & Debug ---
        const val LOGGING_ENABLED = false
        const val DEBUG_ENABLED = false
        const val DEBUG_CALLER_NUMBER = ""
        
        // --- Audio/Scrcpy Quality ---
        val AUDIO_SOURCE = ScrcpyAudioSource.VOICE_CALL.cliKey
        val AUDIO_CODEC = ScrcpyAudioCodec.OPUS.cliKey

        val AUDIO_BITRATE = ScrcpyAudioCodec.OPUS.defaultBitRate

        // --- File Naming & Management ---
        const val FILE_NAME_TEMPLATE = "{date}_{direction}_{phone_number}"
        const val AUTO_DELETE_DAYS = 0 // 0 means Never

        // --- UI & Appearance ---
        val THEME_MODE = ThemeMode.SYSTEM
        const val DYNAMIC_COLOR = true
        const val SHOW_TOASTS = true
        // --- Security ---
        const val SHIZUKU_AUTO_MANAGE = false
        const val SHIZUKU_START_ON_RECORD = false
        const val SHIZUKU_KEEP_ALIVE = false
        const val SHIZUKU_AUTH_KEY = ""
    }

    /**
     * Enum containing all SharedPreferences keys to prevent string typos.
     * Add new keys here when adding new settings.
     */
    enum class Key(val id: String) {
        // --- Onboarding & Legal ---
        DISCLAIMER_ACCEPTED("disclaimer_accepted"),

        LAST_FORCED_REMINDER_SUPPORT_PROJECT_TIME_INAPP("last_forced_reminder_support_project_time_inapp"),
        LAST_FORCED_REMINDER_SUPPORT_PROJECT_TIME_NOTIFICATION("last_forced_reminder_support_project_time_notification"),
        // --- Others ---
        RECORDING_FOLDER_URI("recording_folder_uri"),
        VIBRATION_ENABLED("vibration_enabled"),
        AUTO_RECORD_INCOMING("auto_record_incoming"),
        AUTO_RECORD_OUTGOING("auto_record_outgoing"),
        IGNORE_ANONYMOUS_INCOMING("ignore_anonymous_incoming"),
        IGNORE_CROSS_COUNTRY_INCOMING("ignore_cross_country_incoming"),
        IGNORE_CROSS_COUNTRY_OUTGOING("ignore_cross_country_outgoing"),
        IGNORE_CONTACTS_MODE_INCOMING("ignore_contacts_mode_incoming"),
        IGNORE_CONTACTS_MODE_OUTGOING("ignore_contacts_mode_outgoing"),
        IGNORED_CONTACTS_INCOMING("ignored_contacts_incoming"),
        IGNORED_CONTACTS_OUTGOING("ignored_contacts_outgoing"),
        LOGGING_ENABLED("logging_enabled"),
        DEBUG_ENABLED("debug_enabled"),
        DEBUG_CALLER_NUMBER("debug_caller_number"),
        AUDIO_SOURCE("audio_source"),
        AUDIO_CODEC("audio_codec"),
        AUDIO_BITRATE("audio_bitrate"),
        FILE_NAME_TEMPLATE("file_name_template"),
        AUTO_DELETE_DAYS("auto_delete_days"),
        THEME_MODE("theme_mode"),
        DYNAMIC_COLOR("dynamic_color"),
        SHOW_TOASTS("show_toasts"),
        SHIZUKU_AUTO_MANAGE("shizuku_auto_manage"),
        SHIZUKU_START_ON_RECORD("shizuku_start_on_record"),
        SHIZUKU_KEEP_ALIVE("shizuku_keep_alive"),
        SHIZUKU_AUTH_KEY("shizuku_auth_key"),
        CALL_DETECTION_MODE("call_detection_mode"),
        RECORD_THIRD_PARTY_CALLS("record_third_party_calls");
    }

    // -------- Nested enums

    /**
     * Controls which contacts are excluded from automatic recording for a given call direction.
     *
     * @param key The lowercase string stored in SharedPreferences.
     */
    enum class IgnoreContactsMode(val key: String) {
        /** Record all contacts; ignore no one. */
        NONE("none"),
        /** Skip recording for all numbers that appear in the device's Contacts. */
        ALL("all"),
        /** Skip recording only for the numbers explicitly added to the ignore list. */
        SELECTED("selected");

        companion object {
            /**
             * Parses a key string back into an enum constant.
             *
             * @throws IllegalArgumentException if no matching entry is found.
             * @param key The string stored in SharedPreferences.
             * @return The matching [IgnoreContactsMode], or throws an error if unrecognized.
             */
            fun fromKey(key: String?): IgnoreContactsMode {
                return entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException("Unknown IgnoreContactsMode key: $key")
            }
        }
    }

    /**
     * Controls the app theme.
     *
     * @param key The lowercase string.
     */
    enum class ThemeMode(val key: String, val displayNameResId: Int) {
        SYSTEM("system", R.string.settings_theme_mode_system),
        LIGHT("light", R.string.settings_theme_mode_light),
        DARK("dark", R.string.settings_theme_mode_dark);
        companion object {
            /**
             * Parses a key string back into an enum constant.
             *
             * @throws IllegalArgumentException if no matching entry is found.
             * @param key The string stored in SharedPreferences.
             * @return The matching [ThemeMode], or throws an error if unrecognized.
             */
            fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException("Unknown ThemeMode key: $key")
        }
    }

    // -------- SharedPreferences instance

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -------- Helpers to simplify reading/writing

    private fun getBoolean(key: Key, default: Boolean = false) = prefs.getBoolean(key.id, default)
    private fun setBoolean(key: Key, value: Boolean) = prefs.edit { putBoolean(key.id, value) }

    private fun getString(key: Key, default: String? = null) = prefs.getString(key.id, default)
    private fun setString(key: Key, value: String?) = prefs.edit { putString(key.id, value) }

    private fun getInt(key: Key, default: Int = 0) = prefs.getInt(key.id, default)
    private fun setInt(key: Key, value: Int) = prefs.edit { putInt(key.id, value) }

    private fun getLong(key: Key, default: Long = 0L) = prefs.getLong(key.id, default)
    private fun setLong(key: Key, value: Long) = prefs.edit { putLong(key.id, value) }

    private fun getStringSet(key: Key, default: Set<String> = emptySet()) = prefs.getStringSet(key.id, default)?.toSet().orEmpty()
    private fun setStringSet(key: Key, value: Set<String>) = prefs.edit { putStringSet(key.id, value) }

    // ==========================================
    // -------- Accessors (By Category) ---------
    // ==========================================

    // -------- Onboarding & Disclaimer --------

    /** Checks if the user has accepted the disclaimer. */
    fun isDisclaimerAccepted() = getBoolean(Key.DISCLAIMER_ACCEPTED, DefaultsValue.DISCLAIMER_ACCEPTED)
    
    /** Sets whether the user has accepted the disclaimer. */
    fun setDisclaimerAccepted(accepted: Boolean) = setBoolean(Key.DISCLAIMER_ACCEPTED, accepted)

    /** Gets the timestamp of the last forced reminder about support project shown in-app. */
    fun getLastForcedReminderSupportProjectTimeInApp() = getLong(Key.LAST_FORCED_REMINDER_SUPPORT_PROJECT_TIME_INAPP, DefaultsValue.LAST_FORCED_REMINDER_SUPPORT_PROJECT_TIME(appContext))

    /** Sets the timestamp of the last forced reminder about support project shown in-app. */
    fun setLastForcedReminderSupportProjectTimeInApp(time: Long) = setLong(Key.LAST_FORCED_REMINDER_SUPPORT_PROJECT_TIME_INAPP, time)

    /** Gets the timestamp of the last forced reminder about support project shown in notifications. */
    fun getLastForcedReminderSupportProjectTimeNotification() = getLong(Key.LAST_FORCED_REMINDER_SUPPORT_PROJECT_TIME_NOTIFICATION, DefaultsValue.LAST_FORCED_REMINDER_SUPPORT_PROJECT_TIME(appContext))

    /** Sets the timestamp of the last forced reminder about support project shown in notifications. */
    fun setLastForcedReminderSupportProjectTimeNotification(time: Long) = setLong(Key.LAST_FORCED_REMINDER_SUPPORT_PROJECT_TIME_NOTIFICATION, time)

    // -------- Storage & General --------

    /** Gets the user-selected folder URI for storing recordings. */
    fun getRecordingFolderUri(): Uri? = getString(Key.RECORDING_FOLDER_URI, DefaultsValue.RECORDING_FOLDER_URI)?.toUri()
    
    /** Sets the user-selected folder URI for storing recordings. */
    fun setRecordingFolderUri(uri: Uri?) = setString(Key.RECORDING_FOLDER_URI, uri?.toString())

    /** Checks if vibration is enabled for notifications/actions. */
    fun isVibrationEnabled() = getBoolean(Key.VIBRATION_ENABLED, DefaultsValue.VIBRATION_ENABLED)
    
    /** Sets whether vibration is enabled. */
    fun setVibrationEnabled(enabled: Boolean) = setBoolean(Key.VIBRATION_ENABLED, enabled)

    /**
     * Gets the current preferred detection mode, automatically falling back
     * to a supported system mode if the saved preference is illegal for the current API.
     */
    fun getCallDetectionMode(): CallDetectionMode {
        val savedKey = getString(Key.CALL_DETECTION_MODE, DefaultsValue.CALL_DETECTION_MODE)
        val savedMode = try {
            CallDetectionMode.fromKey(savedKey)
        } catch (e: IllegalArgumentException) {
            AppLogger.e(TAG, "Invalid saved CallDetectionMode key: $savedKey, falling back to default. Error: ${e.message}")
            CallDetectionMode.getDefaultModeForDevice()
        }

        // Safety fallback for API compatibility mismatches, for example if user update or downgrade Android version
        return if (savedMode.isSupportedOnCurrentApi()) {
            savedMode
        } else {
            AppLogger.w(TAG, "Saved CallDetectionMode ${savedMode.key} is not supported on current API level, falling back to default.")
            CallDetectionMode.getDefaultModeForDevice()
        }
    }

    /**
     * Sets the call direction mode.
     * @throws IllegalArgumentException if the provided mode is not supported on the current API level, to prevent saving an invalid preference.
     */
    fun setCallDetectionMode(mode: CallDetectionMode) {
        if (!mode.isSupportedOnCurrentApi()) {
            throw IllegalArgumentException("Mode ${mode.name} is not supported on API ${Build.VERSION.SDK_INT}")
        }
        setString(Key.CALL_DETECTION_MODE, mode.key)
    }

    /** Checks if recording of calls from third-party apps (e.g. WhatsApp, Signal) is enabled. */
    fun isRecordThirdPartyCallsEnabled() = getBoolean(Key.RECORD_THIRD_PARTY_CALLS, DefaultsValue.RECORD_THIRD_PARTY_CALLS)
    /** Sets whether recording of calls from third-party apps is enabled. */
    fun setRecordThirdPartyCallsEnabled(enabled: Boolean) = setBoolean(Key.RECORD_THIRD_PARTY_CALLS, enabled)

    // -------- Automation --------

    /** Checks if auto-recording for incoming calls is enabled. */
    fun isAutoRecordIncomingEnabled() = getBoolean(Key.AUTO_RECORD_INCOMING, DefaultsValue.AUTO_RECORD_INCOMING)
    
    /** Sets whether auto-recording for incoming calls is enabled. */
    fun setAutoRecordIncomingEnabled(enabled: Boolean) = setBoolean(Key.AUTO_RECORD_INCOMING, enabled)

    /** Checks if auto-recording for outgoing calls is enabled. */
    fun isAutoRecordOutgoingEnabled() = getBoolean(Key.AUTO_RECORD_OUTGOING, DefaultsValue.AUTO_RECORD_OUTGOING)
    
    /** Sets whether auto-recording for outgoing calls is enabled. */
    fun setAutoRecordOutgoingEnabled(enabled: Boolean) = setBoolean(Key.AUTO_RECORD_OUTGOING, enabled)

    // -------- Filters & Contacts --------

    /** Checks if recording should be ignored for incoming anonymous calls. */
    fun isIgnoreAnonymousIncomingEnabled() = getBoolean(Key.IGNORE_ANONYMOUS_INCOMING, DefaultsValue.IGNORE_ANONYMOUS_INCOMING)
    
    /** Sets whether to ignore recording for incoming anonymous calls. */
    fun setIgnoreAnonymousIncomingEnabled(enabled: Boolean) = setBoolean(Key.IGNORE_ANONYMOUS_INCOMING, enabled)

    /** Checks if recording should be ignored for incoming cross-country calls. */
    fun isIgnoreCrossCountryIncomingEnabled() = getBoolean(Key.IGNORE_CROSS_COUNTRY_INCOMING, DefaultsValue.IGNORE_CROSS_COUNTRY_INCOMING)
    
    /** Sets whether to ignore recording for incoming cross-country calls. */
    fun setIgnoreCrossCountryIncomingEnabled(enabled: Boolean) = setBoolean(Key.IGNORE_CROSS_COUNTRY_INCOMING, enabled)

    /** Checks if recording should be ignored for outgoing cross-country calls. */
    fun isIgnoreCrossCountryOutgoingEnabled() = getBoolean(Key.IGNORE_CROSS_COUNTRY_OUTGOING, DefaultsValue.IGNORE_CROSS_COUNTRY_OUTGOING)
    
    /** Sets whether to ignore recording for outgoing cross-country calls. */
    fun setIgnoreCrossCountryOutgoingEnabled(enabled: Boolean) = setBoolean(Key.IGNORE_CROSS_COUNTRY_OUTGOING, enabled)

    /** Gets the contacts mode defining which incoming calls are ignored. */
    fun getIgnoreContactsModeIncoming() = IgnoreContactsMode.fromKey(getString(Key.IGNORE_CONTACTS_MODE_INCOMING, DefaultsValue.IGNORE_CONTACTS_MODE_INCOMING.key))
    
    /** Sets the contacts mode defining which incoming calls are ignored. */
    fun setIgnoreContactsModeIncoming(mode: IgnoreContactsMode) = setString(Key.IGNORE_CONTACTS_MODE_INCOMING, mode.key)

    /** Gets the contacts mode defining which outgoing calls are ignored. */
    fun getIgnoreContactsModeOutgoing() = IgnoreContactsMode.fromKey(getString(Key.IGNORE_CONTACTS_MODE_OUTGOING, DefaultsValue.IGNORE_CONTACTS_MODE_OUTGOING.key))
    
    /** Sets the contacts mode defining which outgoing calls are ignored. */
    fun setIgnoreContactsModeOutgoing(mode: IgnoreContactsMode) = setString(Key.IGNORE_CONTACTS_MODE_OUTGOING, mode.key)

    /** Gets the set of specific contact numbers to ignore for incoming calls. */
    fun getIgnoredContactsIncoming() = getStringSet(Key.IGNORED_CONTACTS_INCOMING, DefaultsValue.IGNORED_CONTACTS_INCOMING)
    
    /** Sets the set of specific contact numbers to ignore for incoming calls. */
    fun setIgnoredContactsIncoming(numbers: Set<String>) = setStringSet(Key.IGNORED_CONTACTS_INCOMING, numbers)

    /** Gets the set of specific contact numbers to ignore for outgoing calls. */
    fun getIgnoredContactsOutgoing() = getStringSet(Key.IGNORED_CONTACTS_OUTGOING, DefaultsValue.IGNORED_CONTACTS_OUTGOING)
    
    /** Sets the set of specific contact numbers to ignore for outgoing calls. */
    fun setIgnoredContactsOutgoing(numbers: Set<String>) = setStringSet(Key.IGNORED_CONTACTS_OUTGOING, numbers)

    // -------- Debug --------

    /** Checks if logging features are enabled. */
    fun isLoggingEnabled() = getBoolean(Key.LOGGING_ENABLED, DefaultsValue.LOGGING_ENABLED)

    /** Sets whether logging features are enabled. */
    fun setLoggingEnabled(enabled: Boolean) = setBoolean(Key.LOGGING_ENABLED, enabled)

    /** Checks if debug features are enabled. */
    fun isDebugEnabled() = getBoolean(Key.DEBUG_ENABLED, DefaultsValue.DEBUG_ENABLED)
    
    /** Sets whether debug features are enabled. */
    fun setDebugEnabled(enabled: Boolean) = setBoolean(Key.DEBUG_ENABLED, enabled)

    /** Gets the caller number override used for debugging. */
    fun getDebugCallerNumber() = getString(Key.DEBUG_CALLER_NUMBER, DefaultsValue.DEBUG_CALLER_NUMBER) ?: DefaultsValue.DEBUG_CALLER_NUMBER
    
    /** Sets the caller number override used for debugging. */
    fun setDebugCallerNumber(number: String) = setString(Key.DEBUG_CALLER_NUMBER, number)

    // -------- Audio/Scrcpy Quality --------

    /** Gets the configured audio source for scrcpy integration. */
    fun getAudioSource() = getString(Key.AUDIO_SOURCE, DefaultsValue.AUDIO_SOURCE) ?: DefaultsValue.AUDIO_SOURCE
    
    /** Sets the configured audio source. */
    fun setAudioSource(source: String) = setString(Key.AUDIO_SOURCE, source)

    /** Gets the configured audio codec for scrcpy integration. */
    fun getAudioCodec() = getString(Key.AUDIO_CODEC, DefaultsValue.AUDIO_CODEC) ?: DefaultsValue.AUDIO_CODEC
    
    /** Sets the configured audio codec. */
    fun setAudioCodec(codec: String) = setString(Key.AUDIO_CODEC, codec)

    /** Gets the configured audio bitrate. */
    fun getAudioBitRate() = getInt(Key.AUDIO_BITRATE, DefaultsValue.AUDIO_BITRATE)

    /** Sets the configured audio bitrate. */
    fun setAudioBitRate(bitRate: Int) = setInt(Key.AUDIO_BITRATE, bitRate)

    // -------- File Naming --------

    /** Gets the user configured file name template. */
    fun getFileNameTemplate() = getString(Key.FILE_NAME_TEMPLATE, DefaultsValue.FILE_NAME_TEMPLATE) ?: DefaultsValue.FILE_NAME_TEMPLATE

    /** Sets the user configured file name template. */
    fun setFileNameTemplate(template: String) = setString(Key.FILE_NAME_TEMPLATE, template)

    /** Gets the number of days after which recordings should be auto-deleted. 0 means never. */
    fun getAutoDeleteDays() = getInt(Key.AUTO_DELETE_DAYS, DefaultsValue.AUTO_DELETE_DAYS)

    /** Sets the number of days after which recordings should be auto-deleted. */
    fun setAutoDeleteDays(days: Int) = setInt(Key.AUTO_DELETE_DAYS, days)

    // -------- UI & Appearance --------

    /** Gets the current UI theme mode. */
    fun getThemeMode() = ThemeMode.fromKey(getString(Key.THEME_MODE, DefaultsValue.THEME_MODE.key))
    
    /** Sets the current UI theme mode. */
    fun setThemeMode(mode: ThemeMode) = setString(Key.THEME_MODE, mode.key)

    /** Checks if dynamic color (Material You) is enabled. */
    fun isDynamicColorEnabled() = getBoolean(Key.DYNAMIC_COLOR, DefaultsValue.DYNAMIC_COLOR)
    
    /** Sets whether dynamic color is enabled. */
    fun setDynamicColorEnabled(enabled: Boolean) = setBoolean(Key.DYNAMIC_COLOR, enabled)

    /** Checks if toast notifications are enabled. */
    fun isShowToastsEnabled() = getBoolean(Key.SHOW_TOASTS, DefaultsValue.SHOW_TOASTS)

    /** Sets whether toast notifications are enabled. */
    fun setShowToastsEnabled(enabled: Boolean) = setBoolean(Key.SHOW_TOASTS, enabled)

    // -------- Security --------

    /** Checks if the app should manage starting/stopping Shizuku. */
    fun isShizukuAutoManageEnabled() = getBoolean(Key.SHIZUKU_AUTO_MANAGE, DefaultsValue.SHIZUKU_AUTO_MANAGE)

    /** Sets whether the app should manage starting/stopping Shizuku. */
    fun setShizukuAutoManageEnabled(enabled: Boolean) = setBoolean(Key.SHIZUKU_AUTO_MANAGE, enabled)

    /** Checks if Shizuku should only start when recording starts. */
    fun isShizukuStartOnRecordEnabled() = getBoolean(Key.SHIZUKU_START_ON_RECORD, DefaultsValue.SHIZUKU_START_ON_RECORD)

    /** Sets whether Shizuku should only start when recording starts. */
    fun setShizukuStartOnRecordEnabled(enabled: Boolean) = setBoolean(Key.SHIZUKU_START_ON_RECORD, enabled)

    /** Checks if Shizuku should be kept alive when no longer needed. */
    fun isShizukuKeepAliveEnabled() = getBoolean(Key.SHIZUKU_KEEP_ALIVE, DefaultsValue.SHIZUKU_KEEP_ALIVE)

    /** Sets whether Shizuku should be kept alive when no longer needed. */
    fun setShizukuKeepAliveEnabled(enabled: Boolean) = setBoolean(Key.SHIZUKU_KEEP_ALIVE, enabled)

    /** Gets the Shizuku auth key. */
    fun getShizukuAuthKey() = getString(Key.SHIZUKU_AUTH_KEY, DefaultsValue.SHIZUKU_AUTH_KEY) ?: DefaultsValue.SHIZUKU_AUTH_KEY

    /** Sets the Shizuku auth key. */
    fun setShizukuAuthKey(key: String) = setString(Key.SHIZUKU_AUTH_KEY, key)
}
