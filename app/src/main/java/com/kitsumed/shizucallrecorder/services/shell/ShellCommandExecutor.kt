/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.shell

import com.kitsumed.shizucallrecorder.utils.AppLogger

class ShellCommandExecutor {
    companion object {
        private const val TAG = "SCR:ShellCommandExecutor"
    }

    /**
     * Grants an AppOp permission at the package level for a specific user profile.
     *
     * See: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/AppOps.md?autodive=0%2F%2F%2F#app_ops-as-access-restrictions
     *
     * @param packageName The package name of the app to grant the permission to.
     * @param opName The AppOp operation name (e.g., "READ_PHONE_STATE").
     * @param userProfileId The user ID for which to grant the permission.
     * @return True if the command was successful, false otherwise.
     */
    fun grantAppOpByPackage(packageName: String, opName: String, userProfileId: Int): Boolean {
        return execute("appops", "set", "--user", userProfileId.toString(), packageName, opName, "allow")
    }

    /**
     * Grants an AppOp permission at the app UID level (prioritized over package value) for a specific user profile.
     *
     * See: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/AppOps.md?autodive=0%2F%2F%2F#app_ops-as-access-restrictions
     *
     * @param uid The UID of the app to grant the permission to.
     * @param opName The AppOp operation name (e.g., "READ_PHONE_STATE").
     * @param userProfileId The user ID for which to grant the permission.
     * @return True if the command was successful, false otherwise.
     */
    fun grantAppOpByUid(uid: Int, opName: String, userProfileId: Int): Boolean {
        return execute("appops", "set", "--user", userProfileId.toString(), "--uid", uid.toString(), opName, "allow")
    }

    /**
     * Grants a role to a package in a specific user profile.
     *
     * See: https://cs.android.com/android/platform/superproject/+/android-latest-release:packages/modules/Permission/PermissionController/src/com/android/permissioncontroller/role/Role.md?q=roles.xml
     * List of roles can be found here: https://cs.android.com/android/platform/superproject/+/android-latest-release:packages/modules/Permission/PermissionController/res/xml/roles.xml?q=roles.xml
     *
     * @param packageName The package name of the app to grant the role to.
     * @param roleName The name of the role to grant (e.g., "DIALER").
     * @param userProfileId The user ID for which to grant the role.
     * @return True if the command was successful, false otherwise.
     */
    fun grantRole(packageName: String, roleName: String, userProfileId: Int): Boolean {
        return execute("cmd", "role", "add-role-holder", "--user", userProfileId.toString(), roleName, packageName)
    }

    /**
     * Executes a shell command and returns true if the command was successful (exit code 0) and there was no error output.
     * Logs the command, exit code, output, and error output for debugging purposes.
     *
     * @param command The shell command to execute as a vararg of strings.
     * @return True if the command was successful, false otherwise.
     */
    private fun execute(vararg command: String): Boolean {
        return try {
            AppLogger.i(TAG, "Executing command: ${command.joinToString(" ")}")
            val process = ProcessBuilder(*command).start()
            val exitCode = process.waitFor()
            val inputOutput = process.inputStream.bufferedReader().readText().trim()
            val errorOutput = process.errorStream.bufferedReader().readText().trim()

            if (exitCode == 0 && errorOutput.isEmpty()) {
                AppLogger.i(TAG, "Command executed successfully. Output: ${inputOutput.ifBlank { "Empty Output" }}")
                true
            } else {
                AppLogger.e(TAG, "Command failed. Exit code $exitCode. Output: ${inputOutput.ifBlank { "Empty Output" }}, Error: ${errorOutput.ifBlank { "Empty Error" }}")
                false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception executing command: ${command.joinToString(" ")}", e)
            false
        }
    }
}