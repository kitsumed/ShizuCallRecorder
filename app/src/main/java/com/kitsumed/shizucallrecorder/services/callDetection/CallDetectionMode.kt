/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.callDetection

import android.os.Build
import com.kitsumed.shizucallrecorder.R

/**
 * Defines the various modes of call detection available in the app, along with their associated metadata such as API level requirements and component class names.
 * @param key A unique string identifier for the mode, used for storage and retrieval.
 * @param minApi The minimum Android API level required for this mode to function properly.
 * @param maxApi The maximum Android API level at which this mode is valid. Defaults to [Int.MAX_VALUE], meaning it is valid for all future versions unless explicitly deprecated.
 * @param titleResId The string resource ID for the user-facing title of this mode, used in the UI.
 * @param descriptionResId The string resource ID for the user-facing description of this mode.
 * @param componentClassName The fully qualified class name of the component responsible for implementing this call detection mode.
 */
enum class CallDetectionMode(
    val key: String,
    val minApi: Int,
    val maxApi: Int = Int.MAX_VALUE,
    val titleResId: Int,
    val descriptionResId: Int,
    val componentClassName: String
) {
    PhoneState(
        key = "PhoneState",
        minApi = Build.VERSION_CODES.R, // Android 11 (API 30)
        maxApi = Int.MAX_VALUE,
        titleResId = R.string.call_detection_mode_phonestate_title,
        descriptionResId = R.string.call_detection_mode_phonestate_description,
        // Use a raw string literal to prevent crash with compose preview, it does not support ::class.java.name
        componentClassName = "com.kitsumed.shizucallrecorder.services.callDetection.phoneState.PhoneStateReceiver"
    ),
    InCallService(
        key = "InCallService",
        minApi = Build.VERSION_CODES.S, // Android 12 (API 31), added AppOps permission check, a single OR statement
        maxApi = Int.MAX_VALUE,
        titleResId = R.string.call_detection_mode_incallservice_title,
        descriptionResId = R.string.call_detection_mode_incallservice_description,
        componentClassName = "com.kitsumed.shizucallrecorder.services.callDetection.incall.InCallService"
    );

    /**
     * Checks if this specific mode is valid for the running Android device version.
     */
    fun isSupportedOnCurrentApi(): Boolean {
        val currentApi = Build.VERSION.SDK_INT
        return currentApi >= minApi && currentApi <= maxApi
    }

    companion object {
        /**
         * Retrieves the [CallDetectionMode] corresponding to the provided key.
         * @param key The unique string identifier for the mode.
         * @return The matching [CallDetectionMode] if found.
         * @throws IllegalArgumentException if the key does not correspond to any known mode.
         */
        fun fromKey(key: String?): CallDetectionMode {
            return entries.firstOrNull { it.key == key }
                ?: throw IllegalArgumentException("Unknown CallDetectionMode key: $key")
        }

        /**
         * Resolves the proper (best) default mode based on the Android version of the device.
         */
        fun getDefaultModeForDevice(): CallDetectionMode {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+, prefer InCallService, more reliable, more data available.
                CallDetectionMode.InCallService
            } else {
                CallDetectionMode.PhoneState
            }
        }
    }
}