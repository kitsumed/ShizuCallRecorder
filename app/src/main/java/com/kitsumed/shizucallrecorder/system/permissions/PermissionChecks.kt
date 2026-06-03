/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.system.permissions

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.os.Process

/**
 * PermissionChecks centralises all runtime permission queries used throughout the app.
 */
object PermissionChecks {

    /**
     * Returns true if the app is allowed to post notifications.
     *
     * @param context The app context.
     * @return true if the app can post notifications.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Returns true if [Manifest.permission.READ_PHONE_STATE] is granted.
     *
     * This permission is required to:
     *  - Receive [android.telephony.TelephonyManager.EXTRA_INCOMING_NUMBER] in broadcasts.
     *  - Call [android.telephony.TelephonyManager.getCallState] programmatically.
     *
     * @param context The app context.
     * @return true if the phone-state permission is currently granted.
     */
    fun hasPhoneStatePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns true if [Manifest.permission.READ_CALL_LOG] is granted.
     *
     * Required to read call logs and receive deprecated phone number in phone state broadcast intents.
     *
     * @param context The app context.
     * @return true if the read-call-log permission is currently granted.
     */
    fun hasCallLogPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns true if [Manifest.permission.READ_CONTACTS] is granted.
     *
     * Required for:
     *  - [ContactLookup.isKnownContact] queries against the Contacts provider.
     *  - Loading the contact list in the picker dialog.
     *
     * @param context The app context.
     * @return true if the contacts permission is currently granted.
     */
    fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns true if the app is exempt from Android's battery-optimisation restrictions.
     *
     * @param context The app context.
     * @return true if the app is on the battery-optimisation whitelist (or if the API is unavailable).
     */
    fun hasBatteryExemption(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Returns true if MANAGE_ONGOING_CALLS appops is granted.
     * This permission is required only starting from Android 12 (API 31).
     *
     * @param context The app context.
     * @return true if the permission is granted or not required.
     */
    fun hasManageOngoingCallsAppOps(context: Context): Boolean {
        // Android 12+ check
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

            val mode = appOps.checkOpNoThrow(
                "android:manage_ongoing_calls", // AppOpsManager.OPSTR_MANAGE_ONGOING_CALLS - https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/core/java/android/app/AppOpsManager.java;l=2202
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } else {
            true
        }
    }
}
