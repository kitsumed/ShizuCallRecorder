/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.system.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.delay
import java.lang.reflect.Method
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Resolves the current audio mode of the phone and returns results based on it.
 *
 * @property context The application context used to access system services.
 */
class AudioModeResolver(val context: Context) {
    private val appContext = context.applicationContext
    private val audioManager: AudioManager by lazy {
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /**
     *  Checks if the phone current audio mode to attempt to detect if the ongoing call is using VoIP audio routing.
     *  **This must be called when the call is ongoing to reflect the current audio mode of the phone.**
     *
     *  This method will retry checking until the maxTimeoutMs is reached. This is because sometimes the device is slow to switch the audio mode
     *  if we call it too quickly after the call has started. See: https://github.com/kitsumed/ShizuCallRecorder/issues/136
     *
     *  @param maxTimeoutMs The maximum time to wait before giving up. Defaults to 700ms.
     *  @param pollIntervalMs The interval between checks. Defaults to 40ms.
     *  @return true if the current audio mode is a VoIP audio mode, false otherwise.
     */
    suspend fun isVoipAudioModeWithRetry(
        maxTimeoutMs: Duration = 700.milliseconds,
        pollIntervalMs: Duration = 40.milliseconds
    ): Boolean {
        val startTime = TimeSource.Monotonic.markNow()

        while (startTime.elapsedNow() < maxTimeoutMs) {
            if (isVoipAudioMode()) return true
            delay(pollIntervalMs)
        }

        return isVoipAudioMode()
    }


    /**
     * Checks if the phone current audio mode to attempt to detect if the ongoing call is using VoIP audio routing.
     * @return true if the current audio mode is a VoIP audio mode, false otherwise.
     */

    private fun isVoipAudioMode(): Boolean {
        val mode = audioManager.mode

        // Docs at https://developer.android.com/reference/android/media/AudioManager

        // MODE_IN_COMMUNICATION is supported since API 11
        if (mode == AudioManager.MODE_IN_COMMUNICATION) {
            return true
        }

        // MODE_COMMUNICATION_REDIRECT was introduced in API 33, according to docs, this only applies to VoIP calls.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && mode == AudioManager.MODE_COMMUNICATION_REDIRECT) {
            return true
        }
        return false
    }
}