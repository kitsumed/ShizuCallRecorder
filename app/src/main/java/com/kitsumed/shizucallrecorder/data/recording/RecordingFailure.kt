/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.data.recording

import android.os.Parcelable
import com.kitsumed.shizucallrecorder.R
import kotlinx.parcelize.Parcelize

/**
 * Enum class representing the possible failure reasons for a recording session.
 *
 * This enum is used to communicate specific failure reasons from the recording engine (shell-side) to the application.
 *
 * @param descriptionResId The resource ID of the string description for the user-friendly error message.
 */
@Parcelize
enum class RecordingFailure(
    val descriptionResId: Int
) : Parcelable {
    /** Fatal error during initialization of the recording engine. */
    CAPTURE_START_FAILED(R.string.recording_failure_capture_start_failed),
    /** Fatal error while reading, processing, and writing to disk audio data in the capture loop. */
    CAPTURE_READ_FAILED(R.string.recording_failure_capture_read_failed),
}