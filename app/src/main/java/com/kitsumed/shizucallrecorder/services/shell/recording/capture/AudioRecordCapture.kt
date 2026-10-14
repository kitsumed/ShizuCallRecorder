/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

/*
 * This script was inspired by scrcpy audio record implementation.
 * Last checked commit: https://github.com/Genymobile/scrcpy/blob/19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac/server/src/main/java/com/genymobile/scrcpy/audio/AudioDirectCapture.java
*/

package com.kitsumed.shizucallrecorder.services.shell.recording.capture

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.SystemClock
import com.kitsumed.shizucallrecorder.data.recording.CapturePlan
import com.kitsumed.shizucallrecorder.services.shell.recording.AudioConfig
import com.kitsumed.shizucallrecorder.services.shell.utils.FakeContext
import com.kitsumed.shizucallrecorder.services.shell.utils.ShellWorkarounds
import com.kitsumed.shizucallrecorder.services.shell.utils.wrappers.ActivityManager
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.nio.ByteBuffer


/**
 * Implementation of [IAudioCapture] using [AudioRecord] API and the physical/fake hardware inputs.
 *
 * Note: Android has concurrent audio restrictions that silence a capture source when triggered. Normal apps would hit this when capturing VOICE_COM during call.
 * However (Android 11+), we run with the shell UID (2000) and have CAPTURE_AUDIO_OUTPUT permission, which allows us to bypass this restriction.
 * As of Android 11, CAPTURE_AUDIO_OUTPUT act as the bypass for this restriction, so we are not affected.
 * Android 17+ seems to be slowly migrating this to a new perm BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION.
 * Source: https://cs.android.com/android/platform/superproject/+/android17-release:frameworks/av/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp;l=880-889
 *
 * @property plan The [CapturePlan.AudioRecord] configuration for this audio capture instance.
 */
class AudioRecordCapture(override val plan: CapturePlan.AudioRecord) : IAudioCapture<CapturePlan.AudioRecord> {
    private var recorder: AudioRecord? = null

    override fun start() {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            // Android 11 strictly enforces that the capturing UID has a visible foreground UI.
            // Launching a pre-existing shell system activity tricks the OS into granting foreground status.
            startWorkaroundAndroid11()
            try {
                tryStartRecording(attempts = 5, delayMs = 100L)
            } finally {
                stopWorkaroundAndroid11()
            }
        } else {
            startRecording()
        }
    }

    override fun read(buffer: ByteBuffer, size: Int): Int {
        val currentRecorder = recorder ?: return -1
        return currentRecorder.read(buffer, size, AudioRecord.READ_BLOCKING)
    }


    override fun stop() {
        val rec = recorder
        recorder = null // Clear reference immediately to prevent double-stops

        try {
            rec?.let {
                // Ensure we only call stop() if the recorder is initialized and currently recording to avoid IllegalStateException.
                if (it.state == AudioRecord.STATE_INITIALIZED && it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Error while stopping AudioRecord", e)
            throw e
        } finally {
            rec?.release()
        }
    }

    override fun isCaptureSilenced(): Boolean {
        return recorder?.activeRecordingConfiguration?.isClientSilenced == true
    }

    /**
     * Attempts to start recording with retries, handling potential [UnsupportedOperationException] on Android 11.
     *
     * @param attempts The number of attempts to try starting the recording.
     * @param delayMs The delay in milliseconds between attempts.
     * @throws IllegalStateException if all attempts fail, indicating that audio capture cannot be started.
     */
    private fun tryStartRecording(attempts: Int, delayMs: Long) {
        var remainingAttempts = attempts
        while (remainingAttempts-- > 0) {
            SystemClock.sleep(delayMs)
            try {
                startRecording()
                return // Success
            } catch (e: UnsupportedOperationException) {
                if (remainingAttempts == 0) {
                    AppLogger.e("Failed to start audio capture. On Android 11, ensure the device is unlocked so the foreground workaround activity can run.")
                    throw IllegalStateException("Audio capture start failed", e)
                } else {
                    AppLogger.d("Failed to start audio capture, retrying ($remainingAttempts/$attempts)...", e)
                }
            }
        }
    }

    /**
     * Create an [AudioRecord] instance and start recording audio.
     * @throws IllegalStateException if the [AudioRecord] instance is null after initialization attempts,
     *         meaning we failed to create a valid audio capture instance.
     */
    private fun startRecording() {
        try {
            recorder = createAudioRecord(plan.audioSource)
        } catch (e: NullPointerException) {
            // Creating an AudioRecord using an AudioRecord.Builder does not work on Vivo phones / maybe other OEMs:
            // - https://github.com/Genymobile/scrcpy/issues/3805
            // - https://github.com/Genymobile/scrcpy/pull/3862
            AppLogger.w("AudioRecord.Builder failed (NullPointerException). Attempting fallback, creating AudioRecord manually with reflection.", e)
            recorder = ShellWorkarounds.createAudioRecordViaReflection(plan.audioSource, AudioConfig.SAMPLE_RATE, AudioConfig.CAPTURE_CHANNEL_CONFIG, AudioConfig.CAPTURE_CHANNEL_COUNT, AudioConfig.CAPTURE_CHANNEL_CONFIG, AudioConfig.PCM_ENCODING)
        }

        recorder?.startRecording() ?: throw IllegalStateException("AudioRecord is null after initialization attempts")
    }

    /**
     * Creates an [AudioRecord] instance using the standard [AudioRecord.Builder] API.
     *
     * @param audioSource The audio source to capture from.
     * @return A configured [AudioRecord] instance.
     */
    @SuppressLint("MissingPermission")
    private fun createAudioRecord(audioSource: Int): AudioRecord {
        val builder = AudioRecord.Builder()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // On older APIs, ShellWorkarounds.fillAppInfo() must be called beforehand
            builder.setContext(FakeContext.get())
        }

        builder.setAudioSource(audioSource)
        builder.setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioConfig.PCM_ENCODING)
                .setSampleRate(AudioConfig.SAMPLE_RATE)
                .setChannelMask(AudioConfig.CAPTURE_CHANNEL_CONFIG)
                .build()
        )

        val minBufferSize = AudioRecord.getMinBufferSize(AudioConfig.SAMPLE_RATE, AudioConfig.CAPTURE_CHANNEL_CONFIG, AudioConfig.PCM_ENCODING)
        if (minBufferSize > 0) {
            builder.setBufferSizeInBytes(8 * minBufferSize)
        }

        return builder.build()
    }

    private fun startWorkaroundAndroid11() {
        try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            intent.component = ComponentName(
                FakeContext.PACKAGE_NAME,
                "com.android.shell.HeapDumpActivity"
            )
            ActivityManager.startActivity(intent)
            // Wait for activity to start
            SystemClock.sleep(150)
        } catch (e: Exception) {
            AppLogger.e("Failed to launch Android 11 foreground workaround", e)
        }
    }

    private fun stopWorkaroundAndroid11() {
        try {
            ActivityManager.forceStopPackage(FakeContext.PACKAGE_NAME)
        } catch (e: Exception) {
            AppLogger.e("Failed to stop Android 11 foreground workaround", e)
        }
    }
}