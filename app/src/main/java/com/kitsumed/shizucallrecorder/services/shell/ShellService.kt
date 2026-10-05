/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.shell

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import com.kitsumed.shizucallrecorder.ILogCallback
import com.kitsumed.shizucallrecorder.IRecordingCallback
import com.kitsumed.shizucallrecorder.IShellService
import com.kitsumed.shizucallrecorder.data.recording.RecordingPlan
import com.kitsumed.shizucallrecorder.services.shell.recording.ShellRecordingEngine
import com.kitsumed.shizucallrecorder.services.shell.utils.FakeContext
import com.kitsumed.shizucallrecorder.services.shell.utils.ShellWorkarounds
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * ShellService runs inside the privileged shell process (UID 2000 or 0) managed by Shizuku.
 *
 * Shizuku requirements:
 *  - Must have a no-arg constructor AND a single-Context constructor (Shizuku v13+).
 *  - Must be annotated with [@Keep] so ProGuard/R8 does not remove/rename the class.
 *  - [destroy] must call [kotlin.system.exitProcess] to terminate the shell process when Shizuku asks.
 */
@Keep
class ShellService : IShellService.Stub {

    private val isInitialized = AtomicBoolean(false)
    private val recordingEngine by lazy { ShellRecordingEngine() }
    private val commandExecutor by lazy { ShellCommandExecutor() }

    // ---- Shizuku-required constructors

    /**
     * No-arg constructor required by older versions of Shizuku.
     */
    @Keep
    constructor() : this(null)

    /**
     * Context constructor required by Shizuku v13+ for user-service instantiation.
     *
     * @param context [android.content.Context] provided by Shizuku, or null on older versions.
     */
    @Keep
    constructor(context: Context?)
    
    /**
     * Ensure the shell service thread is initialized. Should generally be called in every method.
     *
     * Note: We do not use init because we prefer to have AppLogger to be initialized as remote to send backlogs,
     * which is impossible inside init scope
     */
    private fun ensureInitialized() {
        if (isInitialized.compareAndSet(false, true)) {
            AppLogger.d("ShellService is running its first initialization, running as UID ${android.os.Process.myUid()}")
            ShellWorkarounds.apply()
            AppLogger.d("Applied workarounds to replicate a valid shell ActivityThread context")
        } else {
            AppLogger.d("ShellService already initialized")
        }
    }

    // -------- IShellService AIDL implementation

    override fun setLogCallback(listener: ILogCallback, isRedactionEnabled: Boolean) {
        AppLogger.initAsRemote(listener, isRedactionEnabled)
        ensureInitialized() // Call after so AppLogger can send output via IPC
    }

    override fun startRecording(
        outputFile: ParcelFileDescriptor,
        plan: RecordingPlan,
        callback: IRecordingCallback
    ) {
        ensureInitialized()
        recordingEngine.start(
            outputFile = outputFile,
            plan = plan,
            callback = callback
        )
    }

    override fun stopRecording() {
        ensureInitialized()
        recordingEngine.stop()
    }

    override fun pauseRecording() {
        ensureInitialized()
        recordingEngine.pause()
    }

    override fun resumeRecording() {
        ensureInitialized()
        recordingEngine.resume()
    }

    override fun grantAppOpByPackage(packageName: String, opName: String, userProfileId: Int): Boolean {
        ensureInitialized()
        return commandExecutor.grantAppOpByPackage(packageName, opName, userProfileId)
    }

    override fun grantAppOpByUid(uid: Int, opName: String, userProfileId: Int): Boolean {
        ensureInitialized()
        return commandExecutor.grantAppOpByUid(uid, opName, userProfileId)
    }

    override fun grantRole(packageName: String, roleName: String, userProfileId: Int): Boolean {
        ensureInitialized()
        return commandExecutor.grantRole(packageName, roleName, userProfileId)
    }


    /**
     * Called by Shizuku when it wants to shut down this user service.
     * @see IShellService.destroy
     */
    override fun destroy() {
        AppLogger.i("ShellService.destroy() – terminating shell process")
        stopRecording()
        exitProcess(0)
    }
}