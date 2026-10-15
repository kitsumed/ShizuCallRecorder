/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.data.recording;

import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.services.recording.RecordingPlanResolver

/**
 * All recording sources supported by the app, with metadata for the Settings UI.
 *
 * **Note**: Theses enums are mostly for UI/preference saving purposes. The real source/capture selection is done in [RecordingPlanResolver].
 * @param key The string key representing the recording source.
 * @param titleResId The string resource ID for the human-readable name shown in the dropdown.
 * @param descriptionResId The string resource ID for the one-line description shown below the dropdown.
 * @param minApi The minimum Android API level required for this source.
 * @param maxApi The maximum Android API level that supports this source, or null if not defined.
 * @param isDebugOnly When true, the entry should be hidden in the UI unless the debug mode is enabled
 * @param isChannelSplitAllowed When false, the audio channel split (stereo uplink/downlink) logic is not proposed to the user.
 */
enum class RecordingSource(
        val key: String,
        val titleResId: Int,
        val descriptionResId: Int,
        val minApi: Int,
        val maxApi: Int?,
        val isDebugOnly: Boolean,
        val isChannelSplitAllowed: Boolean
) {
    // Single Type Sources
    VOICE_CALL(

        key            = "voice-call",
        titleResId        = R.string.audio_source_voice_call,
        descriptionResId  = R.string.audio_source_voice_call_description,
        minApi            = 4,
        maxApi            = null,
        isDebugOnly       = false,
        isChannelSplitAllowed = false
    ),

    UPLINK(
        key            = "uplink",
        titleResId        = R.string.audio_source_voice_call_uplink,
        descriptionResId  = R.string.audio_source_voice_call_uplink_description,
        minApi            = 4,
        maxApi            = null,
        isDebugOnly       = false,
        isChannelSplitAllowed = false
    ),
    DOWNLINK(
        key            = "downlink",
        titleResId        = R.string.audio_source_voice_call_downlink,
        descriptionResId  = R.string.audio_source_voice_call_downlink_description,
        minApi            = 4,
        maxApi            = null,
        isDebugOnly       = false,
        isChannelSplitAllowed = false
    ),

    MIC_VOICE_COMMUNICATION(
        key            = "mic-voice-communication",
        titleResId        = R.string.audio_source_voice_comm,
        descriptionResId  = R.string.audio_source_voice_comm_description,
        minApi            = 11,
        maxApi            = null,
        isDebugOnly       = false,
        isChannelSplitAllowed = false
    ),

    // Dual Type Sources
    UPLINK_DOWNLINK(
        key            = "uplink-downlink",
        titleResId        = R.string.audio_source_uplink_downlink,
        descriptionResId  = R.string.audio_source_uplink_downlink_description,
        minApi            = 4,
        maxApi            = null,
        isDebugOnly       = false,
        isChannelSplitAllowed = true
    );

    companion object {
        /**
         * Parses a string key and returns the corresponding RecordingSource enum value.
         * @param key The string key representing the recording source.
         * @return The corresponding RecordingSource enum value, or null if no match is found.
         */
        fun fromKey(key: String): RecordingSource? {
            return entries.find { it.key == key }
        }
    }
}