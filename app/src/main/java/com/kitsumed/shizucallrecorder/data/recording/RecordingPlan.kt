/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.data.recording

import android.os.Parcelable
import kotlinx.parcelize.Parcelize


/**
 * Represents a plan for recording audio during a call recording session. (What will be the recording configuration? What will be our sources?)
 *
 * One object represents a single recording session, along with its configuration.
 */
@Parcelize
data class RecordingPlan(
    /**
     * First capture stream.
     *
     * When `isAudioChannelSplitEnabled` is true, this will be the audio source on the left side of the stereo recording..
     */
    val primary: CapturePlan,

    /**
     * Second capture stream.
     *
     * When `isAudioChannelSplitEnabled` is true, this will be the audio source on the right side of the stereo recording.
     *
     * **If null, only the primary capture stream/plan will be used.**
     */
    val secondary: CapturePlan?,

    /**
     * Whether to record and mix the two capture streams separately (stereo) or together (mono).
     */
    val isAudioChannelSplitEnabled: Boolean,

    /**
     * Selected output codec.
     */
    val codec: RecordingCodec,

    /**
     * Selected encoder bitrate in bits per second.
     */
    val bitrate: Int,
) : Parcelable
