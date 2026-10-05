/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.data.recording

import android.media.MediaRecorder
import android.os.Parcelable
import com.kitsumed.shizucallrecorder.services.shell.recording.capture.AudioPlaybackCapture
import com.kitsumed.shizucallrecorder.services.shell.recording.capture.AudioRecordCapture
import com.kitsumed.shizucallrecorder.services.shell.recording.capture.IAudioCapture
import kotlinx.parcelize.Parcelize

/**
 * Represents a plan for capturing audio during a call recording session. (how will we capture the audio data? what will be our settings for the selected capture method?)
 *
 * Implementations for these classes should use the [IAudioCapture] interface to provide the audio capture logic.
 */
sealed class CapturePlan : Parcelable {

    /**
     * Creates an instance of [IAudioCapture] based on the specific capture plan implementation.
     */
    abstract fun createCapture(): IAudioCapture<*>

    /**
     * Checks whether the capture plan **may be available on the current android version**.
     *
     * **This does not guarantee that the capture plan will work** in all scenarios since manufacturers may have changed the source code of AOSP.
     *
     * @return true if the capture plan is available, false otherwise.
     */
    abstract fun isSupportedForAndroidVersion(): Boolean

    /**
     * Captures audio from physical/fake hardware inputs via [MediaRecorder.AudioSource] and [AudioRecord] APIs.
     *
     * @param audioSource The audio source to use for capturing audio. Defined in [MediaRecorder.AudioSource].
     */
    @Parcelize
    data class AudioRecord(
        val audioSource: Int,
    ) : CapturePlan() {

        override fun createCapture(): IAudioCapture<*> { return AudioRecordCapture(this) }

        override fun isSupportedForAndroidVersion(): Boolean {
            // Since Android 11, since the shell has system restricted "CAPTURE_AUDIO_OUTPUT" permissions.
            // Source: https://cs.android.com/android/platform/superproject/+/android11-release:frameworks/base/packages/Shell/AndroidManifest.xml;l=314-315
            return true
        }
    }


    /**
     * Captures system audio output with [AudioRecord] and the dynamic software routing at the mixer level `AudioFlinger`.
     *
     * This is the equivalent of using the "Playback Capture" public API added in Android 10, but we use the lower-level API
     * to bypass the restrictions of the public API and without requiring the user to grant the "Media projection" permission.
     */
    @Parcelize
    object AudioPlayback : CapturePlan() {

        override fun createCapture(): IAudioCapture<*> { return AudioPlaybackCapture(this) }

        override fun isSupportedForAndroidVersion(): Boolean {
            // The public API was added in Android 10, but the shell has the required permissions (MODIFY_AUDIO_ROUTING) since Android 13.
            // Source: https://cs.android.com/android/platform/superproject/+/android13-release:frameworks/base/packages/Shell/AndroidManifest.xml;l=623
            return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        }
    }
}