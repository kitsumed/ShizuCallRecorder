/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.viewmodels

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.integrations.shizuku.ShizukuConnectionManager
import com.kitsumed.shizucallrecorder.onboarding.OnboardingStatus
import com.kitsumed.shizucallrecorder.services.callDetection.CallDetectionMode
import com.kitsumed.shizucallrecorder.system.openAppSettings
import com.kitsumed.shizucallrecorder.system.openShizukuManager
import com.kitsumed.shizucallrecorder.system.permissions.AppPermission
import com.kitsumed.shizucallrecorder.ui.screens.PermissionsScreen
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Brain" of the permissions setup flow.
 *
 * This ViewModel decides which action to take based on the current [OnboardingStatus.Status].
 */
class PermissionsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SCR:PermissionsViewModel"
    }

    /**
     * Application context — safe to store in a ViewModel because it lives as long as the
     * app process, unlike an Activity context which is destroyed and recreated on every rotation.
     */
    private val appContext = application.applicationContext

    /** AppPreferences instance for accessing user preferences. */
    private val preferences = AppPreferences(appContext)


    /** MutableStateFlow to track whether a permission request is currently being processed. */
    private val _isProcessingGrantingRequest = MutableStateFlow(false)
    /** Processing state of permission request as a read-only StateFlow for UI observation. */
    val isProcessingGrantingRequest = _isProcessingGrantingRequest.asStateFlow()

    /** MutableStateFlow to hold any error messages that may occur during permission requests. */
    private val _errorMessage = MutableStateFlow<String?>(null)
    /** Read-only StateFlow for observing error messages in the UI. To be hidden when null */
    val errorMessage = _errorMessage.asStateFlow()


    /**
     * Works through each missing setup step in the correct order and invokes the matching
     * callback. Once all steps are complete, calls [onPermissionGranted] so the UI can refresh.
     *
     * For each runtime permission:
     *  - First press → the system permission dialog is shown via [requestRuntimePermission].
     *  - If the OS cannot show the popup (permanent denial), [PermissionsScreen] handles the
     *    fallback by calling [openAppSettings] in the launcher result callback.
     *
     * @param status                   Current state of every permission and setup step.
     * @param requestRuntimePermission Launches the system permission dialog for a given permission.
     * @param launchFolderPicker       Opens the folder picker to choose a recording folder.
     * @param onPermissionGranted      Called after any step completes so the UI can refresh.
     */
    @SuppressLint("BatteryLife")
    fun onGrantAccess(
        status: OnboardingStatus.Status,
        requestRuntimePermission: (String) -> Unit,
        launchFolderPicker: () -> Unit,
        onPermissionGranted: () -> Unit
    ) {
        // NOTE: Don't forget to return after each action, otherwise it will call dynamic permission requests prematurely and break the flow.
        when {
            !status.shizukuRunning           -> {appContext.openShizukuManager(); return}
            !status.shizukuPermissionGranted -> {ShizukuConnectionManager.requestPermission(); return}
            !status.notificationsGranted     -> {requestRuntimePermission(Manifest.permission.POST_NOTIFICATIONS); return}
            !status.contactsGranted          -> {requestRuntimePermission(Manifest.permission.READ_CONTACTS); return}
            !status.batteryExempted          -> {
                appContext.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:${appContext.packageName}".toUri()
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                return
            }
            !status.storageSelected          -> {launchFolderPicker() ; return}
            else                             -> { /* All steps completed, all global permission granted.*/ }
        }

        // Get a list of all required permissions for the current call detection mode that are not yet granted.
        val missingPermissions = status.callDetectionMode.requiredPermissions.filterNot { currentPermission ->
            status.callDetectionModeGrantedPermissions.contains(currentPermission)
        }

        when (val nextToRequest = missingPermissions.first()) {
            is AppPermission.Runtime -> {
                requestRuntimePermission(nextToRequest.manifestString)
            }
            is AppPermission.Elevated -> {
                _isProcessingGrantingRequest.value = true
                viewModelScope.launch(Dispatchers.IO) {
                    // Attempt to grant the elevated permission via Shizuku.
                    val success = nextToRequest.grant(appContext)
                    withContext(Dispatchers.Main) {
                        _isProcessingGrantingRequest.value = false
                        if (!success) {
                            AppLogger.w(TAG, "Failed to grant elevated permission with Shizuku.")
                            _errorMessage.value = appContext.getString(R.string.general_permission_escalation_exhausted, nextToRequest::class.simpleName, nextToRequest.permissionIdentifier, nextToRequest.escalationAttemptsChain.count())
                        }
                        // Refresh the UI to show potential changes
                        onPermissionGranted()
                    }
                }
            }
        }
        // Always trigger a refresh of the UI to detect and show new permission changes.
        onPermissionGranted()
    }

    /**
     * Updates the call detection mode in preferences when the user selects a new mode.
     * @param newMode The newly selected [CallDetectionMode].
     */
    fun onCallDetectionModeChanged(newMode: CallDetectionMode) {
        preferences.setCallDetectionMode(newMode)
    }

    /**
     * Dismisses the current error message.
     */
    fun dissmissError() {
        _errorMessage.value = null
    }
}