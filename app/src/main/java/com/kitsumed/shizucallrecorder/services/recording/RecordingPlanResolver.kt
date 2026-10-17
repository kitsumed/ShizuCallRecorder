/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.recording

import android.content.Context
import android.media.MediaRecorder
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import com.kitsumed.shizucallrecorder.data.recording.CapturePlan
import com.kitsumed.shizucallrecorder.data.recording.RecordingCodec
import com.kitsumed.shizucallrecorder.data.recording.RecordingPlan
import com.kitsumed.shizucallrecorder.data.recording.RecordingSource
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * Resolves the audio configuration that should be used for one recording session.
 *
 * Based on the call data and the user preferences.
 */
class RecordingPlanResolver(
    context: Context
) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)

    /**
     * Resolves the recording plan based on the user preferences and the call data.
     * @param callData The enriched call data containing information about the call.
     * @return The resolved recording plan.
     */
    fun resolve(callData: EnrichedCallData): RecordingPlan {
        val source = preferences.getAudioSource()
        val codec = preferences.getAudioCodec()
        val bitrate = preferences.getAudioBitRate()
        val isPlaybackCaptureAllowed = preferences.isPlaybackCaptureAllowed()
        val isAudioChannelSplitEnabled = preferences.isAudioChannelSplitEnabled()

        // For VoIP-based calls, a different plan is needed.
        val originalPlan = if (callData.isCallAudioVoIpBased && isPlaybackCaptureAllowed) {
            AppLogger.v("Resolving VoIP-based recording plan.")
            resolveVoIpPlan(source, codec, bitrate, isAudioChannelSplitEnabled)
        } else {
            AppLogger.v("Resolving normal recording plan.")
            resolveNormalPlan(source, codec, bitrate, isAudioChannelSplitEnabled)
        }

        AppLogger.d("Resolved recording plan: $originalPlan")
        return originalPlan
    }

    /**
     * Resolves the generic recording plan, based on [CapturePlan.AudioRecord] only.
     */
    private fun resolveNormalPlan(
        source: RecordingSource,
        codec: RecordingCodec,
        bitrate: Int,
        isAudioChannelSplitEnabled: Boolean
    ): RecordingPlan {
        return when (source) {
            RecordingSource.VOICE_CALL -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_CALL
                ),
                secondary = null,
                // VOICE_CALL has no interest having isAudioChannelSplitEnabled true since it is a single source that mix both uplink & downlink.
                // However, this flag is handled on the UI level by our RecordingSource enum.
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            RecordingSource.UPLINK -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_UPLINK
                ),
                secondary = null,
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            RecordingSource.DOWNLINK -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_DOWNLINK
                ),
                secondary = null,
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            RecordingSource.MIC_VOICE_COMMUNICATION -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                ),
                secondary = null,
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            RecordingSource.MIC_VOICE_RECOGNITION -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                ),
                secondary = null,
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            RecordingSource.REMOTE_SUBMIX -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.REMOTE_SUBMIX
                ),
                secondary = null,
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            RecordingSource.UPLINK_DOWNLINK -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_UPLINK
                ),
                secondary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_DOWNLINK
                ),
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            RecordingSource.MIC_VOICE_COMMUNICATION_DOWNLINK -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                ),
                secondary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_DOWNLINK
                ),
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            RecordingSource.MIC_VOICE_RECOGNITION_DOWNLINK -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                ),
                secondary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_DOWNLINK
                ),
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )
        }
    }

    /**
     * Resolves the recording plan for VoIP-based calls, based on [CapturePlan.AudioRecord] and [CapturePlan.AudioPlayback].
     * This is needed for VoIP calls as they take different audio routes than standard telephony/VoLTE calls.
     */
    private fun resolveVoIpPlan(
        source: RecordingSource,
        codec: RecordingCodec,
        bitrate: Int,
        isAudioChannelSplitEnabled: Boolean
    ): RecordingPlan {
        return when (source) {
            RecordingSource.VOICE_CALL -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                ),
                secondary = CapturePlan.AudioPlayback,
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            RecordingSource.UPLINK -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                ),
                secondary = null,
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            RecordingSource.DOWNLINK -> RecordingPlan(
                primary = CapturePlan.AudioPlayback,
                secondary = null,
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            RecordingSource.MIC_VOICE_COMMUNICATION -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                ),
                secondary = null,
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            RecordingSource.MIC_VOICE_RECOGNITION -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                ),
                secondary = null,
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            // In this specific case, we don't capture downlink via playback because it is intended to capture the whole speaker audio, without any opt-out possibility from apps.
            RecordingSource.REMOTE_SUBMIX -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.REMOTE_SUBMIX
                ),
                secondary = null,
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            RecordingSource.UPLINK_DOWNLINK -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                ),
                secondary = CapturePlan.AudioPlayback,
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            RecordingSource.MIC_VOICE_COMMUNICATION_DOWNLINK -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                ),
                secondary = CapturePlan.AudioPlayback,
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )

            RecordingSource.MIC_VOICE_RECOGNITION_DOWNLINK -> RecordingPlan(
                primary = CapturePlan.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                ),
                secondary = CapturePlan.AudioPlayback,
                isAudioChannelSplitEnabled = isAudioChannelSplitEnabled,
                codec = codec,
                bitrate = bitrate
            )
        }

    }

}