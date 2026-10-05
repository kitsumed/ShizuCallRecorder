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
     * Checks if the phone current audio mode to attempt to detect if the ongoing call is using VoIP audio routing.
     * **This must be called when the call is ongoing to reflect the current audio mode of the phone.**
     *
     * @return true if the current audio mode is a VoIP audio mode, false otherwise.
     */
    fun isVoipAudioMode(): Boolean {
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