/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.shizuku
/* Some ranting, Is it just me or the Shizuku API docs is really not up to date and easy to pick up on, even the Demo was confusing. I have been improving and
* this debugging this file for over 6 hours in total. Well I guess starting with a call recording app as my first Android app also wasn't the best idea x')
* At least I now have somewhat okay understanding on how the Shizuku API and its Server Service behave.
* TODO: Mayne remove the permissionListener logic, I don't think there's real scenario where any user would ever need this since the app onboarding require permission granting to proceed. Well it's still a safety check I guess?
*/

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.kitsumed.shizucallrecorder.BuildConfig
import com.kitsumed.shizucallrecorder.IShellService
import com.kitsumed.shizucallrecorder.services.ShellService
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * This class is responsible for managing the general interactions with the Shizuku manager app and its server process.
 *
 * It moves away all the complexities of dealing with Shizuku permission model, user service binding, and connection lifecycle management.
 *
 * NOTE: In this class, there will be mention of the "ShellService" / "Service" and the "Server". The Service is our code that will
 * run with elevated privileges, the [ShellService]. The Server is the Shizuku ADB server process (UID 2000/0) that hosts our ShellService.
 * @param context The application context (not Activity, to avoid leaks).
 * @param onBinderDied Optional callback invoked when the Shizuku-Server process (and our ShellService binder) connection are unexpectedly lost (they died).
 */
class ShizukuConnectionManager(
    private val context: Context,
    private val onBinderDied: () -> Unit = {}
) {

    companion object {
        private const val TAG = "SCR:ShizukuConnectionManager"

        /**
         * This permission code is used to identify the permission request when the user responds to the diaAppLogger. It can be any unique integer.
         */
        private const val PERMISSION_REQUEST_CODE = 204846 // random numbers, only even numbers because I decided so.

        /**
         * Checks if Shizuku is  installed and the server is running.
         *
         * @return True if Shizuku is installed and responding.
         */
        fun isAvailable(): Boolean {
            return try {
                Shizuku.pingBinder()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Shizuku unavailable: ${e.message}", e)
                false
            }
        }

        /**
         * Checks if the user has already granted Shizuku permission to this app. This check generally require Shizuku to be running, **a fallback check is available, see params below**.
         *
         * @param context Optional context for fallback permission check. Fallback happens when Shizuku server is not running. It checks the standard Android permission system. **THE FALLBACK CHECK MAY BE OUT-OF-SYNC WITH SHIZUKU SERVER STATE WHEN IT'S RUNNING, requiring a restart of it to sync.**
         * @return True if permission is granted, False otherwise or if check fails.
         */
        fun hasPermission(context: Context? = null): Boolean {
            return try {
                if (isAvailable()) {
                    // Check the permission from Shizuku side itself, this is the best source of truth but require Shizuku server to be running.
                    // Basically, the server holds its own list, and only load the Android permission list once, at its startup.
                    return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                } else {
                    if (context == null) {
                        AppLogger.v(TAG, "Cannot check permission via Shizuku API. Context is null, will not perform potentially out-of-sync Android permission check fallback. High probability of being the intended behavior, but might not.")
                        return false
                    }
                    return context.checkSelfPermission(ShizukuProvider.PERMISSION) == PackageManager.PERMISSION_GRANTED
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error while checking Shizuku permission", e)
                false
            }
        }

        /**
         * Check if the Shizuku server process has access to specific permissions. This basically checks the Android shell application permissions,
         * since the remote Shizuku server process runs under the shell user.
         * @param permissionName The name of the permission to check, e.g. [android.Manifest.permission.CAPTURE_AUDIO_OUTPUT] or `android.permission.CAPTURE_AUDIO_OUTPUT`.
         * @return True if the Shizuku server has the specified permission, false if it does not or if check fails. If Shizuku server is not available, it will return false since we cannot check it.
         */
        fun checkServerPermission(permissionName: String): Boolean {
            return try {
                if (isAvailable()) {
                    val result = Shizuku.checkRemotePermission(permissionName)
                    when (result) {
                        PackageManager.PERMISSION_GRANTED -> {
                            AppLogger.d(TAG, "Remote Shizuku server has permission: $permissionName")
                            return true
                        }
                        PackageManager.PERMISSION_DENIED -> {
                            AppLogger.w(TAG, "Remote Shizuku server does NOT have permission: $permissionName")
                            return false
                        }
                        else -> AppLogger.v(TAG, "Unexpected result from checkRemotePermission: $result for permission: $permissionName")
                    }
                    return false
                } else {
                    AppLogger.v(TAG, "Cannot check remote Shizuku server available permission. Server is not available.")
                    return false
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error while checking remote Shizuku server permission", e)
                false
            }
        }

        /**
         * Requests Shizuku permission from the user. This will trigger a system dialog asking the user to allow or deny permission.
         */
        fun requestPermission() {
            if (!hasPermission()) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        }

        /**
         * Resolves the Shizuku manager app package name using its declared permission.
         * This ensures we can find the app even if the user has enabled the "hide app" feature.
         * @return The package name of the Shizuku manager app, or null if it cannot be found (e.g. not installed).
         */
        fun getPackageName(context: Context): String? {
            return runCatching {
                context.packageManager.getPermissionInfo(ShizukuProvider.PERMISSION, 0)
            }.getOrNull()?.packageName
        }

        /**
         * Starts the Shizuku ADB server via broadcast intent. Can be called even if already running.
         *
         * @param context The application context.
         * @param authKey The authentication key for the Shizuku server.
         * @throws IllegalStateException if the Shizuku manager package cannot be found.
         */
        fun startServer(context: Context, authKey: String) {
            try {
                if (isAvailable()) {
                    AppLogger.i(TAG, "Shizuku server is already running, no need to send start broadcast")
                    return
                }
                val packageName = getPackageName(context) ?: throw IllegalStateException("Shizuku manager package not found, cannot start server")

                val action = "moe.shizuku.privileged.api.START"
                val intent = Intent(action)

                intent.apply {
                    setPackage(packageName)
                    putExtra("auth", authKey)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                context.sendBroadcast(intent)
                AppLogger.i(TAG, "Sent broadcast to start Shizuku server to $packageName")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to send broadcast to start Shizuku server", e)
            }
        }

        /**
         * Stops the Shizuku server via broadcast intent.
         *
         * @param context The application context.
         * @param authKey The authentication key for the Shizuku server.
         * @throws IllegalStateException if the Shizuku manager package cannot be found.
         */
        fun stopServer(context: Context, authKey: String) {
            try {
                if (!isAvailable()) {
                    AppLogger.i(TAG, "Shizuku server is already stopped, no need to send stop broadcast")
                    return
                }
                val packageName = getPackageName(context) ?: throw IllegalStateException("Shizuku manager package not found, cannot stop server")

                val action = "moe.shizuku.privileged.api.STOP"
                val intent = Intent(action)

                intent.apply {
                    setPackage(packageName)
                    putExtra("auth", authKey)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                context.sendBroadcast(intent)
                AppLogger.i(TAG, "Sent broadcast to stop Shizuku server to $packageName")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to send broadcast to stop Shizuku server", e)
            }
        }

        /**
         * Grants an app op permission to this app package via the Shizuku server.
         *
         * @param context The application context.
         * @param permissionName The name of the permission to grant, e.g. "MANAGE_ONGOING_CALLS" or [android.Manifest.permission.MANAGE_ONGOING_CALLS].
         * @return True if the command was ran and return exit code 0, false if it failed, or if server is not available.
         */
        suspend fun grantAppOp(context: Context, permissionName: String): Boolean {
            // Handle manifest "permission.MANAGE_ONGOING_CALLS" style
            val parsedPermissionName = permissionName.substringAfterLast('.')
            val shizukuConnectionManager = ShizukuConnectionManager(context)
            try {
                if (!isAvailable()) {
                    AppLogger.w(TAG, "Cannot grant AppOps $parsedPermissionName, Shizuku server is not available")
                    return false
                }
                val shellService = shizukuConnectionManager.getShellService()
                val result = shellService.grantAppOps(context.packageName, parsedPermissionName, getCurrentUserProfileId())
                AppLogger.i(TAG, "Tried to grant AppOps $parsedPermissionName to ${context.packageName} via ShellService. Result: $result")
                return result
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to grant AppOps $parsedPermissionName via ShellService", e)
                return false
            } finally {
                shizukuConnectionManager.unbind()
            }
        }

        /**
         * Helper function to get the current user profile ID, since some users may be user multiple profiles on their device.
         */
        private fun getCurrentUserProfileId(): Int {
            // Process.myUserHandle() returns the UserHandle of the current profile space.
            // Calling hashCode() on it returns the actual numerical integer ID (e.g., 0, 10, 95).
            return android.os.Process.myUserHandle().hashCode()
        }

        /**
         * Suspends and waits for the Shizuku server to become available, up to a specified timeout.
         * Useful after starting the server via broadcast.
         *
         * @param timeoutMillis Maximum time to wait in milliseconds. Defaults to 30000ms (30 seconds).
         * @param pollIntervalMillis Interval between checks in milliseconds. Defaults to 250ms.
         * @return True if the server became available within the timeout, false otherwise.
         */
        suspend fun waitForServer(timeoutMillis: Long = 30000, pollIntervalMillis: Long = 250): Boolean {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMillis) {
                if (isAvailable()) {
                    return true
                }
                delay(pollIntervalMillis)
            }
            AppLogger.w(TAG, "Timed out waiting for Shizuku server after ${timeoutMillis}ms")
            return false
        }
    }

    // Variables

    /**
     * Configuration for binding the Shizuku user service. Initialized lazily and reused for bind/unbind.
     */
    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        val isDebug = BuildConfig.DEBUG
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()

        Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
            // daemon=false means the ShellService process exits when the app process dies.
            // We want this because even if the ShellService kept running, we would no longer
            // have the pipe read-end on the app side to write the output file.
            .daemon(false)
            .processNameSuffix("ElevatedShellService")
            .debuggable(isDebug)
            .version(version)
    }

    /**
     * The active ServiceConnection. needed to unbind.
     */
    private var serviceConnection: ServiceConnection? = null

    /**
     * Suspend function that returns the [IShellService] proxy. It performs service binding (waiting for connection) if not already connected.
     * It also handles permission requests if missing.
     *
     * @return The AIDL proxy to the running [ShellService].
     * @throws IllegalStateException if Shizuku is not running or binding/connection fails.
     * @throws SecurityException if permission is denied.
     */
    suspend fun getShellService(): IShellService = suspendCancellableCoroutine { continuation ->
        if (!isAvailable()) {
            continuation.resumeWithException(IllegalStateException("Shizuku is not running"))
            return@suspendCancellableCoroutine
        }

        // Define the ServiceConnection to handle bind callbacks
        val connection = object : ServiceConnection {
            /**
             * Called when [ShellService] is successfully bound and the AIDL interface proxy is ready.
             *
             * **What to do:**
             * - You can now use the provided [IShellService] proxy to run code as the shell process.
             * - You can store the provided [IShellService] proxy for later use, but remember to null it out when [onDisconnected] is called.
             *
             * @param binder The AIDL proxy to the running [ShellService]. valid until [onDisconnected].
             */
            override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                if (binder != null) {
                    val proxy = IShellService.Stub.asInterface(binder)
                    AppLogger.i(TAG, "ShellService connected successfully")
                    if (continuation.isActive) {
                        continuation.resume(proxy)
                    }
                } else {
                    val e = IllegalStateException("Shizuku returned a binder that was null")
                    AppLogger.e(TAG, "Service connected with null binder", e)
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            }

            /**
             * Called when the connection with the binder/service has been **unexpectedly lost**. ONLY WHEN NOT PLANNED.
             * This typically happens when the process hosting the service (Shizuku ADB server) has crashed or been closed/killed.
             *
             * Since we run our ShellService via Shizuku, this happens when:
             * 1. The Shizuku ADB server  (UID 2000/0) hosting our service crashes (e.g. unhandled exception).
             * 2. The system kills the Shizuku ADB server.
             * 3. User stop Shizuku inside the app, causing the Shizuku ADB server to close.
             */
            override fun onServiceDisconnected(name: ComponentName?) {
                AppLogger.d(TAG, "ShellService disconnected prematurely")
                // Clean up our connection variables since the service is no longer available,
                // this also unbinds the service if it is still bound, but since the service is already gone, it will just clean up our variables.
                unbind()
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Shizuku service disconnected prematurely"))
                } else {
                    // Notify listener if this happens after successful connection
                    onBinderDied()
                }
            }
        }

        // Store connection so we can unbind later
        this.serviceConnection = connection

        /**
         * Start the core binding logic, elevating our ShellService to ADB/Shizuku context.
         */
        fun bindServiceInternal() {
            try {
                AppLogger.i(TAG, "Binding ShellService...")

                // Shizuku Server (libshizuku.so) will look for a previous binder with the same serviceArgs and reuse it if found,
                // otherwise it will create a new one. This allows for faster subsequent bindings.
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to bind service", e)
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }

        // Define Permission Listener, only used if we need to request permission for binding, else it will be ignored and not registered at all.
        val permissionListener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    // Unregister the listener immediately after receiving the result to avoid memory leaks and unnecessary callbacks.
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        AppLogger.d(TAG, "Permission granted, proceeding to bind")
                        bindServiceInternal()
                    } else {
                        AppLogger.w(TAG, "Shizuku permission denied, cannot continue with binding")
                        if (continuation.isActive) {
                            continuation.resumeWithException(SecurityException("Shizuku permission denied by user"))
                        }
                    }
                }
            }
        }

        // Execution logic
        if (hasPermission()) {
            bindServiceInternal()
        } else {
            AppLogger.w(TAG, "Cannot bind yet, missing permission, requesting Shizuku permission...")
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }

        // Cleanup if the coroutine is cancelled before completion
        continuation.invokeOnCancellation {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        }
    }

    /**
     * Unbind the ShellService from Shizuku, reset all internal variables to their initial state.
     */
    fun unbind() {
        val serviceConn = serviceConnection

        if (serviceConn != null) {
            try {
                // Ensure it is available since trying to unbind an already gone service will throw an exception since the binder is already "null" on Shizuku side.
                if (isAvailable()) {
                    // Unbound service. This, by itself, does not trigger [IShellService.destroy()] since we have configured the daemon mode to false when binding.
                    // However, Shizuku Server will trigger [IShellService.destroy()] if the user manually stop Shizuku in the app.
                    Shizuku.unbindUserService(userServiceArgs, serviceConn, false)
                    AppLogger.i(TAG, "ShellService was unbound")
                } else {
                    AppLogger.d(TAG, "ShellService binder is not available for a unbind. It has already been killed by the Shizuku Server. No issues here.")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Unexpected error occurred while trying to unbind ShellService", e)
            }
        }

        serviceConnection = null
    }
}