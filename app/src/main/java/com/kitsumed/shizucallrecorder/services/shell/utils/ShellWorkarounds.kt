/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

/*
 * This script was inspired by scrcpy shell workarounds implementation.
 * Last checked commit: https://github.com/Genymobile/scrcpy/blob/19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac/server/src/main/java/com/genymobile/scrcpy/Workarounds.java
*/

package com.kitsumed.shizucallrecorder.services.shell.utils

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Looper
import android.os.Parcel
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.lang.ref.WeakReference

/**
 * Workarounds for the shell process, mainly related to replicating a valid ActivityThread that looks like an application process (shell).
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
object ShellWorkarounds {

    // Lazily resolves the `ActivityThread` class via reflection
    private val activityThreadClass: Class<*> by lazy { Class.forName("android.app.ActivityThread") }

    /**
     * Resolves the active `ActivityThread` instance, or create a fake system thread if none exist.
     */
    private val activityThreadInstance: Any by lazy {
        try {
            val currentMethod = activityThreadClass.getDeclaredMethod("currentActivityThread")
            currentMethod.isAccessible = true
            currentMethod.invoke(null) ?: createActivityThread()
        } catch (e: Exception) {
            AppLogger.e("Failed to resolve ActivityThread", e)
            throw RuntimeException(e)
        }
    }

    /**
     * Instantiates an `ActivityThread` instance via reflection and define it as a special system thread.
     *
     * Source: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/core/java/android/app/ActivityThread.java;l=479-480
     */
    private fun createActivityThread(): Any {
        val constructor = activityThreadClass.getDeclaredConstructor().apply { isAccessible = true }
        val instance = constructor.newInstance()

        // ActivityThread.sCurrentActivityThread = instance
        activityThreadClass.getDeclaredField("sCurrentActivityThread").apply {
            isAccessible = true
            set(null, instance)
        }

        // instance.mSystemThread = true
        activityThreadClass.getDeclaredField("mSystemThread").apply {
            isAccessible = true
            setBoolean(instance, true)
        }
        return instance
    }


    /**
     * Applies all required workarounds to the current ActivityThread instance to ensure system APIs function correctly in a shell process.
     */
    fun apply() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fillConfigurationController()
        }

        // On ONYX devices, fillAppInfo() breaks video mirroring in scrcpy, we keep this in case it also breaks audio:
        // https://github.com/Genymobile/scrcpy/issues/5182
        if (!Build.BRAND.equals("ONYX", ignoreCase = true)) {
            fillAppInfo()
        }
        fillAppContext()
    }

    /**
     * Fetches the system's global [Context] via `ActivityThread.getSystemContext()`.
     *
     * @return The system [Context] or `null` if reflection fails.
     * Source: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/core/java/android/app/ActivityThread.java;l=3241-3250
     */
    fun getSystemContext(): Context? {
        return try {
            val method = activityThreadClass.getDeclaredMethod("getSystemContext")
            method.isAccessible = true
            method.invoke(activityThreadInstance) as? Context
        } catch (e: Throwable) {
            AppLogger.d("Could not retrieve system context: ${e.message}")
            null
        }
    }

    /**
     * Injects a fake [ApplicationInfo] with package `com.android.shell` into `ActivityThread.mBoundApplication`.
     *
     * Prevents NPEs when system APIs check which package bound the active process.
     *
     * Source: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/core/java/android/app/ActivityThread.java;l=961
     */
    private fun fillAppInfo() {
        try {
            // Get the AppBindData class inside ActivityThread
            val appBindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
            // Create a new instance of AppBindData
            val appBindData = appBindDataClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

            // Create a new ApplicationInfo with the fake, spoofed package name
            val appInfo = ApplicationInfo().apply { packageName = FakeContext.PACKAGE_NAME }
            appBindDataClass.getDeclaredField("appInfo").apply { isAccessible = true }.set(appBindData, appInfo)

            // Set the mBoundApplication field of the ActivityThread instance to the new AppBindData instance
            activityThreadClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(activityThreadInstance, appBindData)
        } catch (t: Throwable) {
            AppLogger.d("Could not fill app info: ${t.message}")
        }
    }

    /**
     * Binds a stub [Application] instance wrapping [FakeContext] into `ActivityThread.mInitialApplication`.
     *
     * Source: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/core/java/android/app/Instrumentation.java;l=1352-1367
     */
    private fun fillAppContext() {
        try {
            val app = Instrumentation.newApplication(Application::class.java, FakeContext.get())
            activityThreadClass.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(activityThreadInstance, app)
        } catch (t: Throwable) {
            AppLogger.d("Could not fill app context: ${t.message}")
        }
    }

    /**
     * Initializes `ActivityThread.mConfigurationController` on Android 12+ (API 31+).
     *
     * Android 12 introduced `ConfigurationController` to manage UI orientation and scaling configs.
     * System audio/display APIs may query this controller and crash if it remains `null`.
     *
     * Source: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/core/java/android/app/ConfigurationController.java;l=60-62
     */
    private fun fillConfigurationController() {
        try {
            val configControllerClass = Class.forName("android.app.ConfigurationController")
            val internalClass = Class.forName("android.app.ActivityThreadInternal")

            // Create a new instance of ConfigurationController using the ActivityThreadInternal instance
            val controller = configControllerClass.getDeclaredConstructor(internalClass).apply { isAccessible = true }.newInstance(activityThreadInstance)
            activityThreadClass.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(activityThreadInstance, controller)
        } catch (t: Throwable) {
            AppLogger.d("Could not fill configuration controller: ${t.message}")
        }
    }

    /**
     * Fallback mechanism to initialize an [AudioRecord] instance using reflection.
     *
     * Certain OEMs (notably Vivo) modify the standard [AudioRecord] constructor and add code that in our case may throw errors during init.
     * We try to work around this by calling Android internal `native_setup` method directly, which is normally invoked by the constructor.
     * From there we manually perform the necessary initialization steps to create a valid [AudioRecord] instance.
     */
    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi")
    fun createAudioRecordViaReflection(
        source: Int, sampleRate: Int, channelConfig: Int, channels: Int, channelMask: Int, encoding: Int
    ): AudioRecord {

        // This method invokes the `AudioRecord(long nativeRecordInJavaObj)` constructor to create an empty `AudioRecord` instance, then uses
        // reflections to initialize it like the normal constructor do (or the `AudioRecord.Builder.build()` method do).
        // Source: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/media/java/android/media/AudioRecord.java;l=495-512
        try {
            val audioRecordClass = AudioRecord::class.java

            // 1: Bypass Constructor
            // AudioRecord audioRecord = new AudioRecord(0L);  --> This create an AudioRecord instance of STATE_UNINITIALIZED.
            val constructor = audioRecordClass.getDeclaredConstructor(Long::class.javaPrimitiveType)
            constructor.isAccessible = true
            val audioRecord = constructor.newInstance(0L)

            // 2: Initialize Basic State & Looper
            // audioRecord.mRecordingState = RECORDSTATE_STOPPED;
            val mRecordingStateField = audioRecordClass.getDeclaredField("mRecordingState").apply { isAccessible = true }
            mRecordingStateField.set(audioRecord, AudioRecord.RECORDSTATE_STOPPED)

            val looper = Looper.myLooper() ?: Looper.getMainLooper()
            // audioRecord.mInitializationLooper = looper;
            val mInitializationLooperField = audioRecordClass.getDeclaredField("mInitializationLooper").apply { isAccessible = true }
            mInitializationLooperField.set(audioRecord, looper)

            // 3: Configure AudioAttributes
            // Create `AudioAttributes` with fixed capture preset
            val audioAttributesBuilder = AudioAttributes.Builder()
            val setInternalCapturePresetMethod = AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            setInternalCapturePresetMethod.invoke(audioAttributesBuilder, source)
            val attributes = audioAttributesBuilder.build()

            // audioRecord.mAudioAttributes = attributes;
            val mAudioAttributesField = audioRecordClass.getDeclaredField("mAudioAttributes").apply { isAccessible = true }
            mAudioAttributesField.set(audioRecord, attributes)

            // 4: Perform Parameter Validation
            // audioRecord.audioParamCheck(capturePreset, sampleRate, encoding);
            val audioParamCheckMethod = audioRecordClass.getDeclaredMethod("audioParamCheck", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            audioParamCheckMethod.invoke(audioRecord, source, sampleRate, encoding)

            // 5: Configure Channels & Buffer Size
            // audioRecord.mChannelCount = channels
            val mChannelCountField = audioRecordClass.getDeclaredField("mChannelCount").apply { isAccessible = true }
            mChannelCountField.set(audioRecord, channels)

            // audioRecord.mChannelMask = channelMask
            val mChannelMaskField = audioRecordClass.getDeclaredField("mChannelMask").apply { isAccessible = true }
            mChannelMaskField.set(audioRecord, channelMask)

            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            val bufferSizeInBytes = minBufferSize * 8

            // audioRecord.audioBuffSizeCheck(bufferSizeInBytes)
            val audioBuffSizeCheckMethod = audioRecordClass.getDeclaredMethod("audioBuffSizeCheck", Int::class.javaPrimitiveType).apply { isAccessible = true }
            audioBuffSizeCheckMethod.invoke(audioRecord, bufferSizeInBytes)

            val sampleRateArray = intArrayOf(sampleRate)
            val session = intArrayOf(AudioManager.AUDIO_SESSION_ID_GENERATE)
            val initResult: Int

            // Step 6: Execute C++ native_setup
            // Binds the Java instance to the underlying hardware audio stream.

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { // API 30 (Android 11)
                // Source: https://cs.android.com/android/platform/superproject/+/android11-release:frameworks/base/media/java/android/media/AudioRecord.java;l=1990-1995
                // private native final int native_setup(Object audiorecord_this,
                // Object /*AudioAttributes*/ attributes,
                // int[] sampleRate, int channelMask, int channelIndexMask, int audioFormat,
                // int buffSizeInBytes, int[] sessionId, String opPackageName,
                // long nativeRecordInJavaObj);
                val nativeSetupMethod = audioRecordClass.getDeclaredMethod(
                    "native_setup", Any::class.java, Any::class.java, IntArray::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, IntArray::class.java, String::class.java, Long::class.javaPrimitiveType
                ).apply { isAccessible = true }

                initResult = nativeSetupMethod.invoke(
                    audioRecord, WeakReference(audioRecord), attributes, sampleRateArray,
                    channelMask, 0, audioRecord.audioFormat, bufferSizeInBytes, session,
                    FakeContext.PACKAGE_NAME, 0L
                ) as Int
            } else { // API 31+ (Android 12 and above)
                val attributionSource = FakeContext.get().attributionSource
                // Assume `attributionSource.getPackageName()` is never null

                // Lint warns that reflective access to asScopedParcelState is forbidden on API 36+,
                // but our shell UID 2000 environment bypasses standard app-facing hidden API restrictions.
                // ScopedParcelState attributionSourceState = attributionSource.asScopedParcelState()
                val asScopedParcelStateMethod = AttributionSource::class.java.getDeclaredMethod("asScopedParcelState").apply { isAccessible = true }

                (asScopedParcelStateMethod.invoke(attributionSource) as AutoCloseable).use { attributionSourceState ->
                    val getParcelMethod = attributionSourceState.javaClass.getDeclaredMethod("getParcel")
                    val attributionSourceParcel = getParcelMethod.invoke(attributionSourceState) as Parcel

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 31-33 (Android 12-13)
                        // Source: https://cs.android.com/android/platform/superproject/+/android12-release:frameworks/base/media/java/android/media/AudioRecord.java;l=2246-2250
                        // private native int native_setup(Object audiorecordThis,
                        // Object /*AudioAttributes*/ attributes,
                        // int[] sampleRate, int channelMask, int channelIndexMask, int audioFormat,
                        // int buffSizeInBytes, int[] sessionId, @NonNull Parcel attributionSource,
                        // long nativeRecordInJavaObj, int maxSharedAudioHistoryMs);
                        val nativeSetupMethod = audioRecordClass.getDeclaredMethod(
                            "native_setup", Any::class.java, Any::class.java, IntArray::class.java,
                            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType, IntArray::class.java, Parcel::class.java,
                            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType
                        ).apply { isAccessible = true }

                        initResult = nativeSetupMethod.invoke(
                            audioRecord, WeakReference(audioRecord), attributes, sampleRateArray,
                            channelMask, 0, audioRecord.audioFormat, bufferSizeInBytes, session,
                            attributionSourceParcel, 0L, 0
                        ) as Int
                    } else { // API 34+ (Android 14 and above)
                        // Added a new int parameter "halInputFlags"
                        // Source: https://cs.android.com/android/platform/superproject/+/android14-release:frameworks/base/media/java/android/media/AudioRecord.java;l=2461-2465
                        val nativeSetupMethod = audioRecordClass.getDeclaredMethod(
                            "native_setup", Any::class.java, Any::class.java, IntArray::class.java,
                            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType, IntArray::class.java, Parcel::class.java,
                            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                        ).apply { isAccessible = true }

                        initResult = nativeSetupMethod.invoke(
                            audioRecord, WeakReference(audioRecord), attributes, sampleRateArray,
                            channelMask, 0, audioRecord.audioFormat, bufferSizeInBytes, session,
                            attributionSourceParcel, 0L, 0, 0
                        ) as Int
                    }
                }
            }

            if (initResult != AudioRecord.SUCCESS) {
                throw RuntimeException("native_setup returned the error code $initResult during AudioRecord initialization")
                // For references, some of the low-level error codes are defined in jni android_media_AudioRecord.cpp
                // https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/core/jni/android_media_AudioRecord.cpp;l=131-135
            }

            // 7: Finalize Initialization
            // mSampleRate = sampleRate[0]
            val mSampleRateField = audioRecordClass.getDeclaredField("mSampleRate").apply { isAccessible = true }
            mSampleRateField.set(audioRecord, sampleRateArray[0])

            // audioRecord.mSessionId = session[0]
            val mSessionIdField = audioRecordClass.getDeclaredField("mSessionId").apply { isAccessible = true }
            mSessionIdField.set(audioRecord, session[0])

            // audioRecord.mState = AudioRecord.STATE_INITIALIZED
            val mStateField = audioRecordClass.getDeclaredField("mState").apply { isAccessible = true }
            mStateField.set(audioRecord, AudioRecord.STATE_INITIALIZED)

            return audioRecord
        } catch (e: Exception) {
            AppLogger.e("Failed to create AudioRecord manually via reflection workaround", e)
            throw IllegalStateException("AudioRecord could not be initialized via reflection", e)
        }
    }
}