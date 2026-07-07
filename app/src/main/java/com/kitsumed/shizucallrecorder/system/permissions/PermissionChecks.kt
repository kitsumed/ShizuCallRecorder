/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.system.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

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
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
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
}
