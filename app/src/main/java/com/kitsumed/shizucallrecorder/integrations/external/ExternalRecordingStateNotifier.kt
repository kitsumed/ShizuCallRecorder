/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.external

import android.content.Context
import android.content.Intent
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.services.recording.RecordingServiceState
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * Sends [ExternalRecordingApi.ACTION_RECORDING_STATE_CHANGED] broadcasts to external applications.
 *
 * The broadcast is sent explicitly (with [Intent.setPackage]) to each application holding
 * [ExternalRecordingApi.PERMISSION_CONTROL_RECORDING], and is also protected by that same permission.
 * Apps that were not granted the permission by the user never receive it.
 *
 * Explicit broadcasts are used so apps can receive them with a manifest-declared receiver, implicit broadcasts
 * are not delivered to manifest receivers since Android 8.
 *
 * @see <a href="https://developer.android.com/develop/background-work/background-tasks/broadcasts#restrict-broadcasts-permissions">Restricting broadcasts with permissions</a>
 * @see <a href="https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions">Implicit broadcast exceptions</a>
 */
class ExternalRecordingStateNotifier(private val context: Context) {

    private val appPreferences = AppPreferences(context)

    /**
     * Broadcasts the external state matching a recording service state change, if any.
     *
     * @param oldState The previous [RecordingServiceState].
     * @param newState The new [RecordingServiceState].
     */
    fun onServiceStateChanged(oldState: RecordingServiceState, newState: RecordingServiceState) {
        val state = ExternalRecordingApi.resolveStateTransition(
            toPhase(oldState),
            toPhase(newState),
            isPausedChanged = oldState.isRecordingPaused != newState.isRecordingPaused
        ) ?: return

        publishState(state, newState.isRecordingPaused)
    }

    /**
     * Broadcasts a non-failure state.
     *
     * @param state One of the `ExternalRecordingApi.STATE_*` values.
     * @param isPaused Whether the recording is paused, only meaningful with [ExternalRecordingApi.STATE_RECORDING].
     */
    fun publishState(state: String, isPaused: Boolean = false) {
        send(Intent(ExternalRecordingApi.ACTION_RECORDING_STATE_CHANGED).apply {
            putExtra(ExternalRecordingApi.EXTRA_STATE, state)
            putExtra(ExternalRecordingApi.EXTRA_IS_PAUSED, isPaused)
        }, errorCode = null)
    }

    /**
     * Broadcasts a [ExternalRecordingApi.STATE_FAILED] state.
     *
     * @param errorCode One of the `ExternalRecordingApi.ERROR_*` values.
     * @param message Human-readable description of the error.
     */
    fun publishFailure(errorCode: String, message: String) {
        send(Intent(ExternalRecordingApi.ACTION_RECORDING_STATE_CHANGED).apply {
            putExtra(ExternalRecordingApi.EXTRA_STATE, ExternalRecordingApi.STATE_FAILED)
            putExtra(ExternalRecordingApi.EXTRA_ERROR_CODE, errorCode)
            putExtra(ExternalRecordingApi.EXTRA_ERROR_MESSAGE, message)
        }, errorCode)
    }

    /**
     * Broadcasts the result of a command that was not executed (rejected or already in the requested state).
     *
     * @param decision The [ExternalRecordingApi.CommandDecision] returned by [ExternalRecordingApi.decideCommand].
     * @param isPaused Whether the current recording is paused.
     */
    fun publishDecision(decision: ExternalRecordingApi.CommandDecision, isPaused: Boolean = false) {
        when (decision) {
            is ExternalRecordingApi.CommandDecision.Reject -> publishFailure(decision.errorCode, decision.message)
            is ExternalRecordingApi.CommandDecision.AlreadyInState -> publishState(decision.state, isPaused)
            ExternalRecordingApi.CommandDecision.Execute -> {}
        }
    }

    private fun send(intent: Intent, errorCode: String?) {
        if (!ExternalRecordingApi.shouldPublish(appPreferences.isExternalRecordingControlEnabled(), errorCode)) return

        val authorizedPackages = try {
            context.packageManager.getPackagesHoldingPermissions(arrayOf(ExternalRecordingApi.PERMISSION_CONTROL_RECORDING), 0)
        } catch (e: Exception) {
            AppLogger.e("Failed to query packages holding the external recording control permission.", e)
            return
        }

        AppLogger.d("Broadcasting external recording state ${intent.getStringExtra(ExternalRecordingApi.EXTRA_STATE)} (error=$errorCode) to ${authorizedPackages.size} app(s).")
        for (packageInfo in authorizedPackages) {
            if (packageInfo.packageName == context.packageName) continue
            context.sendBroadcast(Intent(intent).setPackage(packageInfo.packageName), ExternalRecordingApi.PERMISSION_CONTROL_RECORDING)
        }
    }

    companion object {
        /**
         * Maps a [RecordingServiceState] to its [ExternalRecordingApi.ServicePhase].
         *
         * A [RecordingServiceState.Standby] without metadata means no call was detected.
         */
        fun toPhase(state: RecordingServiceState): ExternalRecordingApi.ServicePhase = when (state) {
            is RecordingServiceState.Standby -> if (state.metadata == null) ExternalRecordingApi.ServicePhase.NO_CALL else ExternalRecordingApi.ServicePhase.STANDBY
            is RecordingServiceState.Starting -> ExternalRecordingApi.ServicePhase.STARTING
            is RecordingServiceState.Active -> ExternalRecordingApi.ServicePhase.RECORDING
        }
    }
}
