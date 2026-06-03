/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.onboarding

import android.content.Context
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.integrations.shizuku.ShizukuConnectionManager
import com.kitsumed.shizucallrecorder.system.permissions.PermissionChecks
import com.kitsumed.shizucallrecorder.system.storage.SafHelper
import com.kitsumed.shizucallrecorder.ui.viewmodels.AppNavigationViewModel

/**
 * OnboardingStatus aggregates all permission and setup states needed for the app to operate.
 *
 * Used by [AppNavigationViewModel] to decide which screen to show
 */
object OnboardingStatus {

    /**
     * An object that contains the state of every prerequisite (permissions) the app requires to work.
     *
     * @param disclaimerAccepted        True if the user has accepted the app disclaimer.
     * @param notificationsGranted      True if the app can post notifications.
     * @param contactsGranted           True if READ_CONTACTS is granted.
     * @param phoneStateGranted         True if READ_PHONE_STATE is granted.
     * @param callLogGranted            True if the app has permission to access the call log.
     * @param batteryExempted           True if the app is on the battery-optimisation whitelist.
     * @param manageOngoingCallsGranted True if the app has the AppOps permission to manage ongoing calls.
     * @param storageSelected           True if a valid SAF recording folder has been chosen.
     * @param shizukuRunning            True if the Shizuku service is currently active.
     * @param shizukuPermissionGranted  True if the user has granted Shizuku permission to this app.
     */
    data class Status(
        val disclaimerAccepted: Boolean,
        val notificationsGranted: Boolean,
        val contactsGranted: Boolean,
        val phoneStateGranted: Boolean,
        val callLogGranted: Boolean,
        val batteryExempted: Boolean,
        val manageOngoingCallsGranted: Boolean,
        val storageSelected: Boolean,
        val shizukuRunning: Boolean,
        val shizukuPermissionGranted: Boolean
    ) {
        /**
         * Returns true only when every prerequisite is satisfied, including the disclaimer.
         */
        fun isComplete(): Boolean {
            return disclaimerAccepted &&
                notificationsGranted &&
                contactsGranted &&
                phoneStateGranted &&
                callLogGranted &&
                batteryExempted &&
                manageOngoingCallsGranted &&
                storageSelected &&
                shizukuRunning &&
                shizukuPermissionGranted
        }
    }

    /**
     * Reads the current state of permissions and other requirements and returns a [Status] data class/object.
     *
     * @param context     App context used for permission checks, SAF folder validation and other context based checks.
     * @param preferences The app-wide [AppPreferences] to perform checks based on user app settings.
     * @return A fully populated [Status] reflecting the current device state.
     */
    fun getStatus(context: Context, preferences: AppPreferences): Status {
        val storageUri = preferences.getRecordingFolderUri()
        return Status(
            disclaimerAccepted       = preferences.isDisclaimerAccepted(),
            notificationsGranted     = PermissionChecks.hasNotificationPermission(context),
            contactsGranted          = PermissionChecks.hasContactsPermission(context),
            phoneStateGranted        = PermissionChecks.hasPhoneStatePermission(context),
            callLogGranted           = PermissionChecks.hasCallLogPermission(context),
            batteryExempted          = PermissionChecks.hasBatteryExemption(context),
            manageOngoingCallsGranted = PermissionChecks.hasManageOngoingCallsAppOps(context),
            storageSelected          = SafHelper.isFolderValid(context, storageUri),
            // Special check here, if the auto-manage option was enabled, users already passed this check, and we can assume app will be able to start/stop Shizuku as needed.
            shizukuRunning           = ShizukuConnectionManager.isAvailable() || preferences.isShizukuAutoManageEnabled(),
            // We provide the context to use the Android Permission system as a fallback. Since if isShizukuAutoManageEnabled is enabled, we can assume the
            // shizuku server may not be running at the moment.
            shizukuPermissionGranted = ShizukuConnectionManager.hasPermission(context)
        )
    }
}
