/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

/*
 * This script was inspired by the scrcpy playback capture implementation. https://github.com/Genymobile/scrcpy/issues/4380#issuecomment-2310987282
 * Last checked commit: https://github.com/Genymobile/scrcpy/blob/3acffaae57238ee47e05f97f8e762a04550fdad8/server/src/main/java/com/genymobile/scrcpy/audio/AudioPlaybackCapture.java
*/

package com.kitsumed.shizucallrecorder.services.shell.recording.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Binder
import androidx.annotation.RequiresPermission
import com.kitsumed.shizucallrecorder.data.recording.CapturePlan
import com.kitsumed.shizucallrecorder.services.shell.recording.AudioConfig
import com.kitsumed.shizucallrecorder.services.shell.utils.FakeContext
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.nio.ByteBuffer

/**
 * Implementation of [IAudioCapture] using [AudioRecord] and the internal AudioPolicy & AudioMixer.
 *
 * This is the equivalent of using the "Playback Capture" public API added in Android 10, but we use the lower-level API
 * to bypass the restrictions of the public API and without requiring the user to grant the "Media projection" permission.
 *
 * @property plan The [CapturePlan.AudioPlayback] configuration for this audio capture instance.
 */
class AudioPlaybackCapture(override val plan: CapturePlan.AudioPlayback) : IAudioCapture<CapturePlan.AudioPlayback> {
    private var recorder: AudioRecord? = null
    private var activeAudioPolicy: Any? = null


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() {
        recorder = createAudioRecordSink()
        recorder?.startRecording()
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
            unregisterAudioPolicy()
        }
    }

    override fun isCaptureSilenced(): Boolean {
        return recorder?.activeRecordingConfiguration?.isClientSilenced == true
    }

    /**
     * Creates an [AudioRecord] instance that captures audio playback.
     *
     * Inspired by the "Playback Capture" API introduced in Android 10, but uses the lower-level hidden API
     * to bypass the restrictions and permissions requirement applied when using the public "Playback Capture" API.
     *
     * AudioMixingRule source: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/media/java/android/media/audiopolicy/AudioMixingRule.java
     * AudioMix source: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/media/java/android/media/audiopolicy/AudioMix.java
     * AudioPolicy source: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/media/java/android/media/audiopolicy/AudioPolicy.java
     * AudioManager source: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/media/java/android/media/AudioManager.java
     *
     * @throws Throwable Thrown at any point if something fail.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @SuppressLint("PrivateApi")
    private fun createAudioRecordSink(): AudioRecord {
        // For java method reflection, when looking at generic parameters, primitive types are written with the first letter in lowercase "int",
        // while boxed types are written in uppercase "Int"/"Integer". So we use Int::class.javaPrimitiveType or Int::class.java in kotlin.

        try {
            val audioMixingRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            // The inner class `AudioMixingRule.Builder` is represented as `AudioMixingRule$Builder`.
            val audioMixingRuleBuilderClass =
                Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")

            // AudioMixingRule.Builder audioMixingRuleBuilder = new AudioMixingRule.Builder();
            val audioMixingRuleBuilder = audioMixingRuleBuilderClass.getConstructor().newInstance()

            // audioMixingRuleBuilder.setTargetMixRole(AudioMixingRule.MIX_ROLE_PLAYERS);
            val mixRolePlayersConstant: Int =
                audioMixingRuleClass.getField("MIX_ROLE_PLAYERS").getInt(null)
            val setTargetMixRoleMethod = audioMixingRuleBuilderClass.getMethod(
                "setTargetMixRole",
                Int::class.javaPrimitiveType
            )
            setTargetMixRoleMethod.invoke(audioMixingRuleBuilder, mixRolePlayersConstant)

            // USAGE_VOICE_COM + voiceCommunicationCaptureAllowed flag both require shell to have the CAPTURE_VOICE_COMMUNICATION_OUTPUT permission 
            val voiceCaptureAttributes =
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build() // Downlink audio
            // Shell must have CAPTURE_MEDIA_OUTPUT
            val mediaCaptureAttributes =
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .build() // Optional, mainly here for testing and edges cases.

            // audioMixingRuleBuilder.addMixRule(AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE, voiceCaptureAttributes);
            // audioMixingRuleBuilder.addMixRule(AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE, mediaCaptureAttributes);
            val ruleMatchAttributeUsageConstant: Int =
                audioMixingRuleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
            val addMixRuleMethod = audioMixingRuleBuilderClass.getMethod(
                "addMixRule",
                Int::class.javaPrimitiveType,
                Any::class.java
            )
            addMixRuleMethod.invoke(
                audioMixingRuleBuilder,
                ruleMatchAttributeUsageConstant,
                voiceCaptureAttributes
            )
            addMixRuleMethod.invoke(
                audioMixingRuleBuilder,
                ruleMatchAttributeUsageConstant,
                mediaCaptureAttributes
            )

            // audioMixingRuleBuilder.voiceCommunicationCaptureAllowed(true);
            val voiceCommunicationCaptureAllowedMethod = audioMixingRuleBuilderClass.getMethod(
                "voiceCommunicationCaptureAllowed",
                Boolean::class.javaPrimitiveType
            )
            voiceCommunicationCaptureAllowedMethod.invoke(audioMixingRuleBuilder, true)

            // This flag is not 100% necessary for call capture, but it does allow capturing app that restrict capture to SYSTEM instead of ALL.
            // audioMixingRuleBuilder.allowPrivilegedPlaybackCapture(true);
            val allowPrivilegedPlaybackCaptureMethod = audioMixingRuleBuilderClass.getMethod(
                "allowPrivilegedPlaybackCapture",
                Boolean::class.javaPrimitiveType
            )
            allowPrivilegedPlaybackCaptureMethod.invoke(audioMixingRuleBuilder, true)

            // AudioMixingRule audioMixingRule = audioMixingRuleBuilder.build();
            val audioMixingRule =
                audioMixingRuleBuilderClass.getMethod("build").invoke(audioMixingRuleBuilder)

            // Safety checks to ensure flags where set correctly and not changed after the build() call. Here for easier debugging.
            try {
                // audioMixingRuleBuilder.voiceCommunicationCaptureAllowed()
                val isVoiceCommunicationCaptureAllowedMethod =
                    audioMixingRuleClass.getMethod("voiceCommunicationCaptureAllowed")
                val isVoiceAllowed =
                    isVoiceCommunicationCaptureAllowedMethod.invoke(audioMixingRule) as Boolean
                if (!isVoiceAllowed) {
                    AppLogger.w("Voice Communication Capture flag is $isVoiceAllowed (Expected: True). This may indicate that the flag was not set correctly or has been changed after the build() call.")
                }

                // audioMixingRuleBuilder.allowPrivilegedPlaybackCapture()
                val allowsPrivilegedMethod =
                    audioMixingRuleClass.getMethod("allowPrivilegedMediaPlaybackCapture")
                val isPrivilegedAllowed = allowsPrivilegedMethod.invoke(audioMixingRule) as Boolean
                if (!isPrivilegedAllowed) {
                    AppLogger.w("Privileged Playback Capture flag is $isPrivilegedAllowed (Expected: True). This may indicate that the flag was not set correctly or has been changed after the build() call.")
                }
            } catch (e: Exception) {
                AppLogger.e("Error while performing safety checks on AudioMixingRule flags", e)
            }

            val audioMixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val audioMixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")

            // AudioMix.Builder audioMixBuilder = new AudioMix.Builder(audioMixingRule);
            val audioMixBuilder = audioMixBuilderClass.getConstructor(audioMixingRuleClass)
                .newInstance(audioMixingRule)

            // audioMixBuilder.setFormat(new AudioFormat.Builder())
            val setFormatMethod =
                audioMixBuilder.javaClass.getMethod("setFormat", AudioFormat::class.java)
            setFormatMethod.invoke(
                audioMixBuilder, AudioFormat.Builder()
                    .setEncoding(AudioConfig.PCM_ENCODING)
                    .setSampleRate(AudioConfig.SAMPLE_RATE)
                    .setChannelMask(AudioConfig.CAPTURE_CHANNEL_CONFIG)
                    .build()
            )

            // This flag tell the audio mixing to capture loopback AND continue playing to the speaker.
            val routeFlags = audioMixClass.getField("ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null)

            // audioMixBuilder.setRouteFlags(routeFlags);
            val setRouteFlagsMethod =
                audioMixBuilder.javaClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
            setRouteFlagsMethod.invoke(audioMixBuilder, routeFlags)

            val audioMix = audioMixBuilder.javaClass.getMethod("build").invoke(audioMixBuilder)

            val audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            val audioPolicyBuilderClass =
                Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")

            // AudioPolicy.Builder audioPolicyBuilder = new AudioPolicy.Builder(FakeContext.get());
            val audioPolicyBuilder = audioPolicyBuilderClass.getConstructor(Context::class.java)
                .newInstance(FakeContext.get())

            // audioPolicyBuilder.addMix(audioMix);
            val addMixMethod = audioPolicyBuilderClass.getMethod("addMix", audioMixClass)
            addMixMethod.invoke(audioPolicyBuilder, audioMix)

            // AudioPolicy audioPolicy = audioPolicyBuilder.build();
            val audioPolicy = audioPolicyBuilderClass.getMethod("build").invoke(audioPolicyBuilder)
            // Store the active audio policy to unregister it later
            activeAudioPolicy = audioPolicy

            // AudioManager.registerAudioPolicyStatic(audioPolicy);
            val registerAudioPolicyStaticMethod = AudioManager::class.java.getDeclaredMethod(
                "registerAudioPolicyStatic",
                audioPolicyClass
            ).apply { isAccessible = true }
            val result: Int = registerAudioPolicyStaticMethod.invoke(null, audioPolicy) as Int
            if (result != 0) {
                throw IllegalStateException("Failed to register audio policy. registerAudioPolicyStatic() returned error code: $result")
            }

            val createAudioRecordSinkMethod = audioPolicyClass.getMethod("createAudioRecordSink", audioMixClass)
            // AudioRecord audioRecord = audioPolicy.createAudioRecordSink(audioMix);
            val audioRecord: AudioRecord = createAudioRecordSinkMethod.invoke(audioPolicy, audioMix) as AudioRecord? ?: throw IllegalStateException("Failed to create AudioRecord sink. createAudioRecordSink() returned null");

            return audioRecord
        } catch (e: Throwable) {
            // Ensure we clean up the audio policy if something fails during creation
            unregisterAudioPolicy()
            throw e
        }
    }

    /**
     * Unregisters the active audio policy if it exists.
     *
     * This method should be called when the audio capture is no longer needed to clean up resources.
     */
    @SuppressLint("PrivateApi")
    private fun unregisterAudioPolicy() {
        // Run "adb shell dumpsys audio" and search "Audio policies:" to see registered policies and the events history.
        val audioPolicy = activeAudioPolicy ?: return
        try {
            val audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy")

            val unregisterAudioPolicyAsyncStaticMethod = AudioManager::class.java.getDeclaredMethod(
                "unregisterAudioPolicyAsyncStatic",
                audioPolicyClass
            ).apply { isAccessible = true }

            unregisterAudioPolicyAsyncStaticMethod.invoke(null, audioPolicy)
            AppLogger.d("Audio policy unregistration request sent successfully.")
        } catch (e: Exception) {
            AppLogger.e("Error while unregistering audio policy", e)
        } finally {
            activeAudioPolicy = null
        }
    }
}