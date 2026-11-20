/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.external

import com.kitsumed.shizucallrecorder.integrations.external.ExternalRecordingApi.ACTION_START_RECORDING
import com.kitsumed.shizucallrecorder.integrations.external.ExternalRecordingApi.ACTION_STOP_RECORDING
import com.kitsumed.shizucallrecorder.integrations.external.ExternalRecordingApi.CommandDecision
import com.kitsumed.shizucallrecorder.integrations.external.ExternalRecordingApi.ServicePhase
import com.kitsumed.shizucallrecorder.services.recording.RecordingServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalRecordingApiTest {

    private fun decide(command: String?, phase: ServicePhase, enabled: Boolean = true) =
        ExternalRecordingApi.decideCommand(command, enabled, phase)

    private fun assertRejected(decision: CommandDecision, errorCode: String) {
        assertTrue("Expected Reject but was $decision", decision is CommandDecision.Reject)
        decision as CommandDecision.Reject
        assertEquals(errorCode, decision.errorCode)
        assertTrue("Error message must not be blank", decision.message.isNotBlank())
    }

    // -------- Authorization

    @Test
    fun commandsAreRejectedWhenExternalControlIsDisabled() {
        for (command in listOf(ACTION_START_RECORDING, ACTION_STOP_RECORDING)) {
            for (phase in ServicePhase.entries) {
                assertRejected(decide(command, phase, enabled = false), ExternalRecordingApi.ERROR_EXTERNAL_CONTROL_DISABLED)
            }
        }
    }

    @Test
    fun unknownOrMissingCommandIsRejected() {
        assertRejected(decide("com.example.RECORD_EVERYTHING", ServicePhase.STANDBY), ExternalRecordingApi.ERROR_INVALID_COMMAND)
        assertRejected(decide(null, ServicePhase.STANDBY), ExternalRecordingApi.ERROR_INVALID_COMMAND)
        // Internal service actions are not part of the public API.
        assertRejected(decide("com.kitsumed.shizucallrecorder.START_RECORDING", ServicePhase.STANDBY), ExternalRecordingApi.ERROR_INVALID_COMMAND)
    }

    @Test
    fun onlyDisabledRejectionIsPublishedWhenExternalControlIsDisabled() {
        assertTrue(ExternalRecordingApi.shouldPublish(false, ExternalRecordingApi.ERROR_EXTERNAL_CONTROL_DISABLED))
        assertFalse(ExternalRecordingApi.shouldPublish(false, null))
        assertFalse(ExternalRecordingApi.shouldPublish(false, ExternalRecordingApi.ERROR_RECORDING_ENGINE_FAILED))
        assertTrue(ExternalRecordingApi.shouldPublish(true, null))
        assertTrue(ExternalRecordingApi.shouldPublish(true, ExternalRecordingApi.ERROR_RECORDING_ENGINE_FAILED))
    }

    // -------- Start

    @Test
    fun startDuringDetectedCallIsExecuted() {
        assertEquals(CommandDecision.Execute, decide(ACTION_START_RECORDING, ServicePhase.STANDBY))
    }

    @Test
    fun startWithoutDetectedCallIsRejected() {
        // Prevents external apps from recording outside of a call detected by ShizuCallRecorder.
        assertRejected(decide(ACTION_START_RECORDING, ServicePhase.NO_CALL), ExternalRecordingApi.ERROR_NO_ACTIVE_CALL)
    }

    // -------- Stop

    @Test
    fun stopDuringRecordingIsExecuted() {
        assertEquals(CommandDecision.Execute, decide(ACTION_STOP_RECORDING, ServicePhase.RECORDING))
    }

    @Test
    fun stopWhileStartingIsRejectedWithInvalidState() {
        assertRejected(decide(ACTION_STOP_RECORDING, ServicePhase.STARTING), ExternalRecordingApi.ERROR_INVALID_STATE)
    }

    // -------- Repeated commands

    @Test
    fun repeatedStartIsIdempotent() {
        assertEquals(CommandDecision.AlreadyInState(ExternalRecordingApi.STATE_STARTING), decide(ACTION_START_RECORDING, ServicePhase.STARTING))
        assertEquals(CommandDecision.AlreadyInState(ExternalRecordingApi.STATE_RECORDING), decide(ACTION_START_RECORDING, ServicePhase.RECORDING))
    }

    @Test
    fun repeatedStopIsIdempotent() {
        assertEquals(CommandDecision.AlreadyInState(ExternalRecordingApi.STATE_STOPPED), decide(ACTION_STOP_RECORDING, ServicePhase.STANDBY))
        assertEquals(CommandDecision.AlreadyInState(ExternalRecordingApi.STATE_STOPPED), decide(ACTION_STOP_RECORDING, ServicePhase.NO_CALL))
    }

    // -------- State transitions

    /** Replays a list of phases through [ExternalRecordingApi.resolveStateTransition] and returns the broadcasted states. */
    private fun replay(vararg phases: ServicePhase): List<String> {
        return phases.toList().zipWithNext().mapNotNull { (old, new) ->
            ExternalRecordingApi.resolveStateTransition(old, new, isPausedChanged = false)
        }
    }

    @Test
    fun startTransitionsBroadcastStartingThenRecording() {
        assertEquals(
            listOf(ExternalRecordingApi.STATE_STARTING, ExternalRecordingApi.STATE_RECORDING),
            replay(ServicePhase.STANDBY, ServicePhase.STARTING, ServicePhase.RECORDING)
        )
    }

    @Test
    fun stopThenRestartDuringSameCallBroadcastsNewStart() {
        // RECORDING -> STANDBY is broadcasted directly by the service (STOPPING / STOPPED), so it resolves to nothing here.
        assertEquals(
            listOf(ExternalRecordingApi.STATE_STARTING, ExternalRecordingApi.STATE_RECORDING, ExternalRecordingApi.STATE_STARTING, ExternalRecordingApi.STATE_RECORDING),
            replay(ServicePhase.STANDBY, ServicePhase.STARTING, ServicePhase.RECORDING, ServicePhase.STANDBY, ServicePhase.STARTING, ServicePhase.RECORDING)
        )
    }

    @Test
    fun failedStartBroadcastsStopped() {
        assertEquals(
            listOf(ExternalRecordingApi.STATE_STARTING, ExternalRecordingApi.STATE_STOPPED),
            replay(ServicePhase.STANDBY, ServicePhase.STARTING, ServicePhase.STANDBY)
        )
        assertEquals(ExternalRecordingApi.STATE_STOPPED, ExternalRecordingApi.resolveStateTransition(ServicePhase.STARTING, ServicePhase.NO_CALL, false))
    }

    @Test
    fun pauseAndResumeBroadcastRecording() {
        assertEquals(ExternalRecordingApi.STATE_RECORDING, ExternalRecordingApi.resolveStateTransition(ServicePhase.RECORDING, ServicePhase.RECORDING, isPausedChanged = true))
        assertNull(ExternalRecordingApi.resolveStateTransition(ServicePhase.RECORDING, ServicePhase.RECORDING, isPausedChanged = false))
    }

    @Test
    fun noBroadcastWithoutRecordingActivity() {
        assertNull(ExternalRecordingApi.resolveStateTransition(ServicePhase.NO_CALL, ServicePhase.STANDBY, false))
        assertNull(ExternalRecordingApi.resolveStateTransition(ServicePhase.STANDBY, ServicePhase.NO_CALL, false))
        assertNull(ExternalRecordingApi.resolveStateTransition(ServicePhase.RECORDING, ServicePhase.NO_CALL, false))
    }

    @Test
    fun standbyWithoutMetadataMeansNoCall() {
        assertEquals(ServicePhase.NO_CALL, ExternalRecordingStateNotifier.toPhase(RecordingServiceState.Standby(null)))
    }
}
