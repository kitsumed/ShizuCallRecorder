/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.callDetection.incall

import android.os.Build
import android.telecom.Call
import android.telecom.Connection
import android.telecom.InCallService
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.data.call.RawCallData
import com.kitsumed.shizucallrecorder.services.RecordingDecisionEngine
import com.kitsumed.shizucallrecorder.utils.AppLogger
import com.kitsumed.shizucallrecorder.utils.PhoneNumberManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * InCallService implementation responsible for detecting call state changes and relaying them to the [RecordingDecisionEngine].
 * This is the primary call detection method for Android 12+ devices, leveraging the latest Telecom framework capabilities.
 *
 * Note: This service is designed to track only one call at a time. Parallel calls (e.g. call waiting, second incoming call) are not currently supported in this implementation.
 * We would first need to rework the whole recording logic.
 *
 * -- Support Guide for Maintainers --
 * This services works because Android 12+ added a single OR statement that checks for MANAGE_ONGOING_CALLS AppOps when InCallController
 * devices if it binds to our InCallService or not. We are binding as a companion/system-non-ui app, "IN_CALL_SERVICE_TYPE_NON_UI".
 * To ensure in future Android releases that they did not change this logic, we must check InCallController source code.
 * Android 11 (no AppOps, impossible to use): https://cs.android.com/android/platform/superproject/+/android11-release:packages/services/Telecomm/src/com/android/server/telecom/InCallController.java;l=1476-1486
 * Android 12 (works) : https://cs.android.com/android/platform/superproject/+/android12-release:packages/services/Telecomm/src/com/android/server/telecom/InCallController.java;l=1818-1829
 * Android 16 (works) : https://cs.android.com/android/platform/superproject/+/android-16.0.0_r4:packages/services/Telecomm/src/com/android/server/telecom/InCallController.java;l=2679-2691
 *
 * We can also quickly check that we are binding as NON_UI by running with adb "adb shell telecom is-non-ui-in-call-service-bound com.kitsumed.shizucallrecorder" while a call is ongoing.
 * For more detailed information, look at kitsumed comment: https://github.com/kitsumed/ShizuCallRecorder/issues/4#issuecomment-4524107187
 */
@RequiresApi(Build.VERSION_CODES.S) // Call detection method only available on Android 12+ for us. This hide warnings of previous API deprecations.
class InCallService : InCallService() {
    companion object {
        private const val TAG = "SCR:InCallService"
    }

    private lateinit var appPreferences: AppPreferences
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Reference to the currently tracked call. This implementation is designed to track only one call at a time. */
    private var activeTrackedCall: Call? = null
    /** Flag to ensure the [RecordingDecisionEngine] pipeline is only executed once per call session.*/
    private var isPipelineExecuted = false

    // Callback reference used to safely manage the listener lifespan
    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            handleCallStateChanged(call, state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appPreferences = AppPreferences(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { serviceScope.cancel() } // Prevent garbage collection issue, close and free the task if any
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        AppLogger.v(TAG, "Received onCallAdded callback for call: ${call.details}")

        // Prevent dual-call scenario. Could be supported with InCallService, but would require a rework of how the recording logic works.
        if (activeTrackedCall != null) {
            AppLogger.d(TAG, "Parallel call detected. Discarding new call, dual-call scenario is not currently supported with InCallService implementation.")
            return
        }

        val telecomManager = this.getSystemService(TELECOM_SERVICE) as? TelecomManager
        val packageName = call.details.accountHandle.componentName.packageName

        // Determine if the call is from the system dialer or default dialer (meaning it's a carrier call and not a third-party app call)
        val isCallFromSystemDialer = packageName == telecomManager?.systemDialerPackage ||
                packageName == telecomManager?.defaultDialerPackage || // Could be a third-party dialer, but it also means it handle carriers calls.
                packageName == "com.android.phone"

        // If the call is from a third-party app, only proceed if the user has explicitly enabled third-party app recording.
        if (!isCallFromSystemDialer && !appPreferences.isRecordThirdPartyCallsEnabled()) {
            AppLogger.i(TAG, "Received call from package ${packageName}, which is not the system or default dialer. Discarding call, user has not enabled third-party call recording.")
            return
        }

        // Assign and register tracking handles
        activeTrackedCall = call
        call.registerCallback(callCallback)
        AppLogger.i(TAG, "Primary call session detected and tracking initialized. Current state is: ${Connection.stateToString(call.details.state)} (${call.details.state})")

        // Edge Case: If the call is already active when we receive it
        if (call.details.state == Call.STATE_ACTIVE) {
            AppLogger.d(TAG, "Received call in already ACTIVE state. Triggering handleCallStateChanged directly.")
            handleCallStateChanged(call, Call.STATE_ACTIVE)
        }
    }

    // NOTE: Some OEMs (ex: Samsung) have already in the past broken this callback
    // (https://github.com/chenxiaolong/BCR/commit/b86fa503bcb7b72f3dc39457e89e5ad5aa197c80).
    // This BCR issue is regarding an Android version we do not support (9-10), but it's worth keeping in mind for future debugging.
    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        AppLogger.v(TAG, "Received onCallRemoved callback for call: ${call.details}")

        // Ensure we are tearing down the precise call that we were tracking
        if (call == activeTrackedCall) {
            AppLogger.i(TAG, "Primary call session disconnected. Releasing callbacks. Ending recording.")

            // Sever the listener relation to prevent framework memory leaks
            call.unregisterCallback(callCallback)

            // If an intent (Record or Standby) was successfully pushed to the FGS, we must shut it down
            if (isPipelineExecuted) {
                RecordingDecisionEngine.getInstance(this).endRecordingSession()
                isPipelineExecuted = false
            }

            // Flush the object reference to fully accept new calls down the road
            activeTrackedCall = null
        } else {
            AppLogger.d(TAG, "Received onCallRemoved for non-primary call. Ignoring.")
        }
    }

    /**
     * Handles the state change of the tracked call. If the call becomes active, it triggers the [RecordingDecisionEngine] pipeline to determine if recording should start.
     * @param call The [Call] object whose state has changed.
     * @param state A duplicate of `call.details.state`, provided for convenience. This is here to match the signature of the [Call.Callback.onStateChanged] method.
     */
    private fun handleCallStateChanged(call: Call, state: Int) {
        AppLogger.v(TAG, "Received onStateChanged callback for call: ${call.details}, current state: $state")
        // Restrict state change handling to the primary tracked call. This prevents issues with parallel calls (not supported in this implementation).
        if (call != activeTrackedCall) return
        AppLogger.d(TAG, "Primary call state changed to ${Connection.stateToString(call.details.state)} (${call.details.state})")



        if (state == Call.STATE_ACTIVE) {
            // Stop here if we've already executed the RecordingDecision pipeline (so it started the foreground service)
            // This prevents multiple triggers if the state changes during the call session.
            if (isPipelineExecuted) return
            isPipelineExecuted = true

            val details = call.details
            val rawNumber = details.handle?.schemeSpecificPart ?: ""

            val direction = when (details.callDirection) {
                Call.Details.DIRECTION_INCOMING -> CallDirection.INCOMING
                Call.Details.DIRECTION_OUTGOING -> CallDirection.OUTGOING
                else -> CallDirection.OUTGOING // Default to OUTGOING if direction is unknown
            }

            // First attempt to get the name from the user contacts list, then fallback to the telecom-provided caller name
            // (which may be defined by the caller or a third-party app)
            val oscallerName = details.contactDisplayName ?: details.callerDisplayName
            // Name of the app package responsible for this call (e.g. system dialer, default dialer, or a third-party app)
            val packageName = details.accountHandle.componentName.packageName

            val rawCallData = RawCallData(
                rawPhoneNumber = PhoneNumberManager.normalisePhoneNumber(rawNumber),
                direction = direction,
                osProvidedCallerName = oscallerName,
                packageName = packageName
            )

            AppLogger.i(TAG, "Primary call became ACTIVE. Triggering Decision Engine Pipeline.")
            val isSelfManaged = details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED)
            val isVoip = details.hasProperty(Call.Details.PROPERTY_VOIP_AUDIO_MODE)
            val isWifiCall = details.hasProperty(Call.Details.PROPERTY_WIFI)
            AppLogger.d(TAG, "Primary call details - isSelfManaged: $isSelfManaged, isVoip: $isVoip, isWifiCall: $isWifiCall")

            serviceScope.launch {
                val intentSentSuccessfully = RecordingDecisionEngine.getInstance(this@InCallService).executeDecisionPipeline(rawCallData)

                // If the Intent IPC pipeline fails structurally (e.g. FGS launching exception), reset flag to allow retries
                if (!intentSentSuccessfully) {
                    AppLogger.e(TAG, "Failed to start recording foreground service, intent dispatch failed. Resetting execution flag.")
                    isPipelineExecuted = false
                }
            }
        }
    }
}