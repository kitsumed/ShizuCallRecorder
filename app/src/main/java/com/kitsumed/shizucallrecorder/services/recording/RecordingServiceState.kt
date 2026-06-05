/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.recording

import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData

/**
 * Represents the state of the recording service, including the current recording metadata and engine state.
 */
sealed class RecordingServiceState {
    abstract val metadata: EnrichedCallData?

    /**
     * Initial transient state when the service has received a start command and is setting things up.
     */
    data class Starting(override val metadata: EnrichedCallData) : RecordingServiceState()

    /**
     * Represents the state when the recording service is in standby mode, waiting for a call to start recording.
     * @param metadata The metadata associated with the current recording session, or null if the service is not initialized or has stopped (ended) recording.
     */
    data class Standby(override val metadata: EnrichedCallData? = null) : RecordingServiceState()

    /**
     * Represents the state when the recording service is actively recording a call. Contains the current recording engine and metadata.
     * @param engine The audio recording engine currently in use.
     * @param isPaused Indicates whether the recording is currently paused.
     * @param metadata The metadata associated with the current recording session
     */
    data class Active(
        val engine: AudioRecordingEngine,
        val isPaused: Boolean = false,
        override val metadata: EnrichedCallData
    ) : RecordingServiceState()
}
