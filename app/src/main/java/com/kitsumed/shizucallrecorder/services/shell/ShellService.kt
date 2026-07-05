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
import android.util.Log
import androidx.annotation.Keep
import com.kitsumed.shizucallrecorder.ILogCallback
import com.kitsumed.shizucallrecorder.IShellService
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ServerExtractor
import com.kitsumed.shizucallrecorder.utils.AppLogger
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
    private companion object {
        const val TAG = "SCR:ShellService"
    }

    private val audioPipeline by lazy { ShellAudioPipeline() }
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
     * @param context The fake [android.content.Context] provided by Shizuku, or null on older versions.
     */
    @Keep
    constructor(context: Context?) {
        Log.i(TAG,"===============================\n" +
             "ShellService process started!\n" +
             "Running as UID=(${android.os.Process.myUid()})\n" +
             "===============================")
    }

    // -------- IShellService AIDL implementation

    override fun setLogCallback(listener: ILogCallback, isRedactionEnabled: Boolean) {
        AppLogger.initAsRemote(listener, isRedactionEnabled)
    }

    override fun startRecording(
        audioSource: String,
        audioCodec: String,
        audioBitRate: Int,
        serverPath: String,
        isDebuggingModeEnabled: Boolean
    ): ParcelFileDescriptor? {
        return audioPipeline.startCapture(audioSource, audioCodec, audioBitRate, serverPath, isDebuggingModeEnabled)
    }

    override fun installServerFile(serverData: ParcelFileDescriptor, serverPath: String): Boolean {
        return ServerExtractor.installFromPipe(serverData, serverPath)
    }

    override fun stopRecording() {
        audioPipeline.stopCapture()
    }

    override fun grantAppOpByPackage(packageName: String, opName: String, userProfileId: Int): Boolean {
        return commandExecutor.grantAppOpByPackage(packageName, opName, userProfileId)
    }

    override fun grantAppOpByUid(uid: Int, opName: String, userProfileId: Int): Boolean {
        return commandExecutor.grantAppOpByUid(uid, opName, userProfileId)
    }

    override fun grantRole(packageName: String, roleName: String, userProfileId: Int): Boolean {
        return commandExecutor.grantRole(packageName, roleName, userProfileId)
    }


    /**
     * Called by Shizuku when it wants to shut down this user service.
     * @see IShellService.destroy
     */
    override fun destroy() {
        AppLogger.i(TAG,"ShellService.destroy() – terminating shell process")
        stopRecording()
        exitProcess(0)
    }
}
