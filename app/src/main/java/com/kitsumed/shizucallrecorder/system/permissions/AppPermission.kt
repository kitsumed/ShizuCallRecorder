/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.system.permissions

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.vector.ImageVector
import com.kitsumed.shizucallrecorder.integrations.shizuku.ShizukuConnectionManager
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * Represents a step in the escalation process **to grant** a permission. Every step try to grant a specific permission.
 *
 * Not to be confused with [AppPermission], which represents a permission that the app needs to function properly, and can **be checked if** it is granted or not.
 */
sealed class EscalationStep(val minApi: Int, val maxApi: Int) {
    /**
     * Grants an AppOpsManager permission for the app package.
     * @param opString The AppOpsManager operation string **to grant**. Can also be [AppOpsManager.OPSTR_READ_PHONE_STATE] constants.
     * @param minApi The minimum API level required for this escalation step.
     * @param maxApi The maximum API level allowed for this escalation step.
     */
    class PackageAppOp(val opString: String, minApi: Int, maxApi: Int) : EscalationStep(minApi, maxApi)
    /**
     * Grants an AppOpsManager permission for the app UID.
     * @param opString The AppOpsManager operation string **to grant**. Can also be [AppOpsManager.OPSTR_READ_PHONE_STATE] constants.
     * @param minApi The minimum API level required for this escalation step.
     * @param maxApi The maximum API level allowed for this escalation step.
     */
    class UidAppOp(val opString: String, minApi: Int, maxApi: Int) : EscalationStep(minApi, maxApi)
    /**
     * Grants a role to the app package.
     * @param roleName The role name **to grant**. Can also be [android.app.role.RoleManager.ROLE_DIALER] constants.
     * @param minApi The minimum API level required for this escalation step.
     * @param maxApi The maximum API level allowed for this escalation step.
     */
    class RoleGrant(val roleName: String, minApi: Int, maxApi: Int) : EscalationStep(minApi, maxApi)
}


/**
 * Represents a permission that the app needs to function properly.
 * This sealed class handle checking if the permission is granted, and can be subclassed to represent different types of permissions (runtime, AppOps, etc.).
 */
sealed class AppPermission(
    val titleResId: Int,
    val descriptionResId: Int,
    val icon: ImageVector
) {
    companion object {
        private const val TAG = "SCR:AppPermission"
    }

    /**
     * Checks if the permission is granted.
     * @param context The context to use for checking the permission.
     * @return True if the permission is granted, false otherwise.
     */
    abstract fun isGranted(context: Context): Boolean

    /**
     * Represents a runtime permission that can be requested to the user.
     * @param manifestString The manifest string of the permission to check. Can also be [android.Manifest.permission.READ_PHONE_STATE] constants.
     */
    class Runtime(
        val manifestString: String,
        titleResId: Int,
        descriptionResId: Int,
        icon: ImageVector
    ) : AppPermission(titleResId, descriptionResId, icon) {

        // Make the = operator compare only the permission itself (manifestString) instead of resource IDs and icon.
        override fun equals(other: Any?): Boolean = other is Runtime && manifestString == other.manifestString

        override fun hashCode(): Int = manifestString.hashCode()

        override fun isGranted(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                manifestString
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Represents a permission that requires elevated privileges to grant, (granted through Shizuku with commands).
     *
     * This sealed class allows to define a chain of escalation steps to attempt in order to gain the wanted permission.
     * It also inherits from [AppPermission] all the properties and methods to check if the permission was granted.
     */
    sealed class Elevated(
        /** The identifier of the permission to check. Used for logging and debugging. Recommend to use the targeted permission name itself.*/
        val permissionIdentifier: String,
        /** A list of [EscalationStep] (actions, commands, role granting, etc.) to attempt in order to gain the wanted permission */
        val escalationAttemptsChain: List<EscalationStep>,
        titleResId: Int,
        descriptionResId: Int,
        icon: ImageVector
    ) : AppPermission(titleResId, descriptionResId, icon) {

        /**
         * Grants a permission via the Shizuku server, following the escalation chain until the permission is granted or all steps are exhausted.
         *
         * @param context The application context to which the permission is being granted.
         * @return True if the permission was successfully granted in one of the escalation chain steps, false otherwise.
         */
        suspend fun grant(context: Context): Boolean {
            val packageName = context.packageName
            // Get the Linux application UID of our app installation.
            val uid = Process.myUid()
            // Process.myUserHandle() returns the UserHandle of the current profile space.
            // Calling hashCode() on it returns the actual numerical integer ID (e.g., 0, 10, 95).
            val userId = Process.myUserHandle().hashCode()

            val shizukuManager = ShizukuConnectionManager(context)

            try {
                if (!ShizukuConnectionManager.isAvailable()) {
                    AppLogger.e(TAG, "Shizuku is not available. Cannot escalate privileges for $permissionIdentifier")
                    return false
                }
                val shellService = shizukuManager.getShellService()

                // Only apply escalation steps that are valid for the current API level
                val validSteps = escalationAttemptsChain.filter { Build.VERSION.SDK_INT >= it.minApi && Build.VERSION.SDK_INT <= it.maxApi }

                for (step in validSteps) {
                    AppLogger.i(TAG, "Attempting step type: ${step::class.simpleName} for permission $permissionIdentifier")

                    when (step) {
                        is EscalationStep.PackageAppOp -> shellService.grantAppOpByPackage(packageName, step.opString, userId)
                        is EscalationStep.UidAppOp -> shellService.grantAppOpByUid(uid, step.opString, userId)
                        is EscalationStep.RoleGrant -> shellService.grantRole(packageName, step.roleName, userId)
                    }

                    // Verify if the permission is granted after each escalation step
                    if (isGranted(context)) {
                        AppLogger.i(TAG, "Successfully granted permission $permissionIdentifier! Privilege acquired via ${step::class.simpleName} step.")
                        return true
                    } else {
                        AppLogger.w(TAG, "Step ${step::class.simpleName} did not grant permission $permissionIdentifier.")
                    }
                }

                AppLogger.e(TAG, "All escalation steps exhausted for permission $permissionIdentifier. Cannot grant permission.")
                return false

            } catch (e: Exception) {
                AppLogger.e(TAG, "Exception during privilege escalation", e)
                return false
            } finally {
                shizukuManager.unbind()
            }
        }

        /**
         * Represents a permission that requires elevated privileges to grant, specifically an AppOpsManager operation.
         *
         * Note: Some OPSTR_* constants are only available in certain API levels or OEMs, other are hidden, and you may need to use the string directly.
         * @param opString The AppOpsManager **operation string to check if it is granted**. Can also be [AppOpsManager.OPSTR_READ_PHONE_STATE] constants.
         * @param escalationAttemptsChain An ordered list of [EscalationStep], where each step specifies the distinct actions, role granting, or command it must runs.
         * The escalation process executes these steps sequentially and halts immediately once [isGranted] returns true by checking if we now have [opString].
        */
        class AppOp(
            val opString: String,
            escalationAttemptsChain: List<EscalationStep>,
            titleResId: Int,
            descriptionResId: Int,
            icon: ImageVector,
        ) : Elevated(opString, escalationAttemptsChain, titleResId, descriptionResId, icon) {

            // Make the = operator compare only the permission itself (opString) instead of resource IDs and icon.
            override fun equals(other: Any?): Boolean = other is AppOp && opString == other.opString

            override fun hashCode(): Int = opString.hashCode()

            override fun isGranted(context: Context): Boolean {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

                return appOps.checkOpNoThrow(opString, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
            }
        }
    }
}