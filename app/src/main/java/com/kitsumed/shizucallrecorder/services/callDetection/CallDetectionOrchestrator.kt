/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.callDetection

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * Orchestrates the enabling and disabling of call detection components based on user preferences and device capabilities.
 * @param context The application context used to access package manager and preferences.
 */
class CallDetectionOrchestrator(private val context: Context) {

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val prefs = AppPreferences(appContext)

    companion object {
        private const val TAG = "SCR:DetectionOrchestrator"
    }

    /**
     * Synchronizes manifest components with the preferred user settings and hardware API restrictions.
     */
    fun syncComponents() {
        val activeMode = prefs.getCallDetectionMode()
        AppLogger.i(TAG, "Synchronizing components. Active mode chosen: ${activeMode.name}")

        CallDetectionMode.entries.forEach { mode ->
            val shouldEnable = (mode == activeMode)
            setComponentState(mode.componentClassName, shouldEnable)
        }
    }

    /**
     * Enables or disables a specific component based on the provided class name and desired state.
     * @param className The fully qualified class name of the component to be toggled.
     * @param enable True to enable the component, false to disable it.
     */
    private fun setComponentState(className: String, enable: Boolean) {
        val componentName = ComponentName(appContext.packageName, className)
        val targetState = if (enable) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        try {
            // DONT_KILL_APP ensures changing modes doesn't abruptly crash the user session.
            packageManager.setComponentEnabledSetting(
                componentName,
                targetState,
                PackageManager.DONT_KILL_APP
            )
            AppLogger.d(TAG, "Component state updated: ${className.substringAfterLast('.')} -> Enabled=$enable")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to toggle component setting for $className", e)
        }
    }
}