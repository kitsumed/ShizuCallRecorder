/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.external

/**
 * Public contract of the external recording control API, used by third-party applications (ex: a dialer) to
 * start or stop a recording during a call **that ShizuCallRecorder already detected**.
 *
 * The values in this object are part of the public API. **Do not rename or change them**, third-party apps hard-code them.
 *
 * This object also contains the decision logic of the API. It is kept free of Android types so it can be unit tested.
 *
 * @see ExternalRecordingControlReceiver
 * @see ExternalRecordingStateNotifier
 * @see <a href="https://developer.android.com/guide/topics/permissions/defining">Define a custom app permission</a>
 */
object ExternalRecordingApi {
    // -- Permission --

    /**
     * Runtime (dangerous) permission an external application must request **and the user must grant** before it can
     * send commands and receive recording state broadcasts.
     */
    const val PERMISSION_CONTROL_RECORDING = "com.kitsumed.shizucallrecorder.permission.CONTROL_RECORDING"

    // -- Commands (Intent actions sent by external apps to the ExternalRecordingControlReceiver) --

    /** Start recording the call currently detected by ShizuCallRecorder. */
    const val ACTION_START_RECORDING = "com.kitsumed.shizucallrecorder.api.START_RECORDING"

    /** Stop the current recording. The call stays in standby so a new recording can be started during the same call. */
    const val ACTION_STOP_RECORDING = "com.kitsumed.shizucallrecorder.api.STOP_RECORDING"

    // -- State broadcast (sent by ShizuCallRecorder to apps holding the permission) --

    /** Broadcast action sent to every app holding [PERMISSION_CONTROL_RECORDING] when the recording state changes. */
    const val ACTION_RECORDING_STATE_CHANGED = "com.kitsumed.shizucallrecorder.api.RECORDING_STATE_CHANGED"

    /** String extra, one of the `STATE_*` values. */
    const val EXTRA_STATE = "com.kitsumed.shizucallrecorder.api.extra.STATE"

    /** Boolean extra, only meaningful with [STATE_RECORDING]. True if the user paused the recording. */
    const val EXTRA_IS_PAUSED = "com.kitsumed.shizucallrecorder.api.extra.IS_PAUSED"

    /** String extra, one of the `ERROR_*` values. Only present with [STATE_FAILED]. */
    const val EXTRA_ERROR_CODE = "com.kitsumed.shizucallrecorder.api.extra.ERROR_CODE"

    /** String extra, human-readable description of the error. Only present with [STATE_FAILED]. */
    const val EXTRA_ERROR_MESSAGE = "com.kitsumed.shizucallrecorder.api.extra.ERROR_MESSAGE"

    // -- States --

    const val STATE_STARTING = "STARTING"
    const val STATE_RECORDING = "RECORDING"
    const val STATE_STOPPING = "STOPPING"
    const val STATE_STOPPED = "STOPPED"
    const val STATE_FAILED = "FAILED"

    // -- Error codes --

    /** The user did not enable "External recording control" in the ShizuCallRecorder settings. */
    const val ERROR_EXTERNAL_CONTROL_DISABLED = "EXTERNAL_CONTROL_DISABLED"

    /** No call is currently detected by ShizuCallRecorder, there is nothing to record. */
    const val ERROR_NO_ACTIVE_CALL = "NO_ACTIVE_CALL"

    /** The command is valid but cannot be executed in the current state (ex: STOP while the recording is still starting). */
    const val ERROR_INVALID_STATE = "INVALID_STATE"

    /** The intent action is not a known command. */
    const val ERROR_INVALID_COMMAND = "INVALID_COMMAND"

    /** Shizuku is not running or ShizuCallRecorder could not bind to it. */
    const val ERROR_SHIZUKU_UNAVAILABLE = "SHIZUKU_UNAVAILABLE"

    /** The user did not grant ShizuCallRecorder access to Shizuku. */
    const val ERROR_SHIZUKU_PERMISSION_DENIED = "SHIZUKU_PERMISSION_DENIED"

    /** "Auto-manage Shizuku" is enabled but the Shizuku authentication key is missing. */
    const val ERROR_SHIZUKU_AUTH_KEY_MISSING = "SHIZUKU_AUTH_KEY_MISSING"

    /** Shizuku disconnected unexpectedly while recording. */
    const val ERROR_SHIZUKU_DISCONNECTED = "SHIZUKU_DISCONNECTED"

    /** The recording could not be initialized (output folder missing, file creation failed, binder call failed, etc). */
    const val ERROR_RECORDING_INIT_FAILED = "RECORDING_INIT_FAILED"

    /** The recording engine reported a failure while starting or during the audio capture. */
    const val ERROR_RECORDING_ENGINE_FAILED = "RECORDING_ENGINE_FAILED"

    /** An unexpected internal error happened. */
    const val ERROR_INTERNAL = "INTERNAL_ERROR"

    // -- Decision logic --

    /**
     * Simplified view of the recording service state, used to decide what to do with a command.
     */
    enum class ServicePhase {
        /** The service is not running or has no call metadata: no call is currently detected. */
        NO_CALL,
        /** A call is detected and the service is waiting for a start request. */
        STANDBY,
        /** A recording is being started (waiting for Shizuku). */
        STARTING,
        /** A recording is in progress (paused or not). */
        RECORDING
    }

    /**
     * Result of [decideCommand].
     */
    sealed class CommandDecision {
        /** The command must be executed. */
        data object Execute : CommandDecision()

        /** The service is already in the requested state, the command is ignored and [state] is re-broadcasted. */
        data class AlreadyInState(val state: String) : CommandDecision()

        /** The command is rejected, a [STATE_FAILED] broadcast is sent with [errorCode] and [message]. */
        data class Reject(val errorCode: String, val message: String) : CommandDecision()
    }

    /**
     * Decides how a command received from an external application must be handled.
     *
     * Repeated commands are idempotent: starting while already starting/recording, or stopping while nothing is recording,
     * does not fail but re-broadcast the current state.
     *
     * @param command The intent action received from the external application.
     * @param isExternalControlEnabled Whether the user enabled external recording control in the app settings.
     * @param phase The current [ServicePhase] of the recording service.
     * @return The [CommandDecision] to apply.
     */
    fun decideCommand(command: String?, isExternalControlEnabled: Boolean, phase: ServicePhase): CommandDecision {
        if (!isExternalControlEnabled) {
            return CommandDecision.Reject(ERROR_EXTERNAL_CONTROL_DISABLED, "External recording control is disabled in the ShizuCallRecorder settings.")
        }

        return when (command) {
            ACTION_START_RECORDING -> when (phase) {
                ServicePhase.NO_CALL -> CommandDecision.Reject(ERROR_NO_ACTIVE_CALL, "No call is currently detected by ShizuCallRecorder.")
                ServicePhase.STANDBY -> CommandDecision.Execute
                ServicePhase.STARTING -> CommandDecision.AlreadyInState(STATE_STARTING)
                ServicePhase.RECORDING -> CommandDecision.AlreadyInState(STATE_RECORDING)
            }

            ACTION_STOP_RECORDING -> when (phase) {
                ServicePhase.NO_CALL, ServicePhase.STANDBY -> CommandDecision.AlreadyInState(STATE_STOPPED)
                ServicePhase.STARTING -> CommandDecision.Reject(ERROR_INVALID_STATE, "The recording is still starting. Send STOP_RECORDING again once the RECORDING state is received.")
                ServicePhase.RECORDING -> CommandDecision.Execute
            }

            else -> CommandDecision.Reject(ERROR_INVALID_COMMAND, "Unknown command: $command")
        }
    }

    /**
     * Resolves which state must be broadcasted when the recording service moves from [oldPhase] to [newPhase].
     *
     * [STATE_STOPPING], [STATE_STOPPED] (after a recording) and [STATE_FAILED] are not resolved here, they are
     * broadcasted directly by the service where the recording is released or the error happens.
     *
     * @param oldPhase The previous [ServicePhase].
     * @param newPhase The new [ServicePhase].
     * @param isPausedChanged True if the paused state of the recording changed between the two states.
     * @return The state to broadcast, or null if nothing must be broadcasted.
     */
    fun resolveStateTransition(oldPhase: ServicePhase, newPhase: ServicePhase, isPausedChanged: Boolean): String? {
        return when {
            newPhase == ServicePhase.STARTING && oldPhase != ServicePhase.STARTING -> STATE_STARTING
            newPhase == ServicePhase.RECORDING && (oldPhase != ServicePhase.RECORDING || isPausedChanged) -> STATE_RECORDING
            // The recording never started (failure or call ended while waiting for Shizuku).
            oldPhase == ServicePhase.STARTING && (newPhase == ServicePhase.STANDBY || newPhase == ServicePhase.NO_CALL) -> STATE_STOPPED
            else -> null
        }
    }

    /**
     * Whether a broadcast must be sent to external applications.
     *
     * When external control is disabled, nothing is broadcasted, except the rejection telling the caller
     * that the user must enable the feature.
     *
     * @param isExternalControlEnabled Whether the user enabled external recording control in the app settings.
     * @param errorCode The error code of the broadcast, or null if it is not a failure.
     */
    fun shouldPublish(isExternalControlEnabled: Boolean, errorCode: String?): Boolean {
        return isExternalControlEnabled || errorCode == ERROR_EXTERNAL_CONTROL_DISABLED
    }
}
