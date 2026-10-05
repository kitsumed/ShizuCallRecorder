/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.recording

import android.app.Service
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import androidx.documentfile.provider.DocumentFile
import com.kitsumed.shizucallrecorder.IRecordingCallback
import com.kitsumed.shizucallrecorder.IShellService
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import com.kitsumed.shizucallrecorder.data.recording.RecordingFailure
import com.kitsumed.shizucallrecorder.data.recording.RecordingPlan
import com.kitsumed.shizucallrecorder.system.storage.SafHelper
import com.kitsumed.shizucallrecorder.utils.AppLogger
import com.kitsumed.shizucallrecorder.utils.RecordingFileNameFormatter

/**
 * Application-side owner of a single recording session.
 *
 * **Creating an instance of this class will immediately start a recording session (capturing audio) with the remote recording engine.**
 *
 * This class owns:
 *
 * - SAF destination creation
 * - The output ParcelFileDescriptor for the remote recording engine
 * - The resolved recording plan and metadata
 * - The communication with the remote recording engine through IShellService
 *
 * @throws InitializationException when failure happens during the initialization **on the application side** (SAF folder invalid, file creation failed, etc).
 */
class RecordingSession(
    val context: Service,
    remoteService: IShellService,
    val initializationMetadata: EnrichedCallData,
    val plan: RecordingPlan,
    private val listener: SessionListener
) {

    /**
     * Callback interface for receiving recording session events from the recording engine, such as failures.
     */
    interface SessionListener {
        /**
         * Called when the recording session receives a failure from the remote recording engine.
         *
         * **The session should be considered terminated after this callback.**
         */
        fun onSessionFailed(failure: RecordingFailure)
    }

    /**
     * Exception thrown when the recording session cannot be initialized.
     *
     * @property userFriendlyMessage A message suitable for displaying to the user.
     * @property technicalLogMessage A detailed technical message for logging purposes.
     * @property cause The underlying cause of the exception, if any.
     */
    class InitializationException(
        val userFriendlyMessage: String,
        technicalLogMessage: String,
        cause: Throwable? = null
    ) : Exception(technicalLogMessage, cause)


    /**
     * The URI of the current recording file, or null if no recording is active.
     */
    var currentRecordingUri: Uri? = null
        private set // Prevent modification from outside the class.

    /**
     * The ParcelFileDescriptor for the current recording file, or null if no recording is active.
     *
     * This is the file descriptor passed to the remote recording engine.
     */
    private var outputPfd: ParcelFileDescriptor? = null

    /**
     * The remote shell service, or null if the recording session of this specific instance has been released or canceled.
     */
    private var shellService: IShellService? = remoteService

    /**
     * The callback implementation for receiving recording events from the remote recording engine.
     */
    private val recordingCallback = object : IRecordingCallback.Stub() {
        override fun onRecordingFailure(error: RecordingFailure) {
            AppLogger.e("Remote recording engine reported failure with error: $error")
            // Release resources and notify listener about failure
            release()
            listener.onSessionFailed(error)
        }
    }

    init {
        val folderUri = AppPreferences(context).getRecordingFolderUri()

        if (!SafHelper.isFolderValid(context, folderUri)) {
            throw InitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_folder_missing),
                technicalLogMessage = "Cannot start recording: selected output folder is missing, invalid, or inaccessible."
            )
        }

        val fileName = RecordingFileNameFormatter.formatFileName(
            context,
            initializationMetadata,
            plan.codec
        )

        val safResult = SafHelper.createAudioFile(
            context,
            folderUri,
            fileName,
            plan.codec.mimeType
        ) ?: throw InitializationException(
            userFriendlyMessage = context.getString(R.string.recording_error_file_creation),
            technicalLogMessage = "Failed to create audio file in SAF storage."
        )

        currentRecordingUri = safResult.uri
        outputPfd = safResult.descriptor

        try {
            remoteService.startRecording(outputPfd!!, plan, recordingCallback)
            AppLogger.i("Recording session initialized the remote recording engine successfully.")
        } catch (e: RemoteException) {
            // Since this is a RemoteException, recording never started, so we can delete the file and cleanup.
            cancel(context)
            throw InitializationException(
                userFriendlyMessage = e.localizedMessage ?: context.getString(R.string.recording_error_cannot_reach_binder),
                technicalLogMessage = "Failed to communicate with remote recording engine via Binder.",
                cause = e
            )
        }
    }

    /**
     * Pauses the recording session.
     */
    fun pause() {
        shellService?.pauseRecording()
    }

    /**
     * Resumes the recording session.
     */
    fun resume() {
        shellService?.resumeRecording()
    }

    /**
     * Releases the recording session, stopping the recording and closing resources.
     *
     * **This method should be called when the recording session is no longer needed.**
     */
    fun release() {
        runCatching {
            shellService?.stopRecording()
        }

        /*
         * The ShellRecordingEngine closes the FD after finalization.
         * Closing again here is safe as a fallback.
         */
        runCatching {
            outputPfd?.close()
        }

        shellService = null
        outputPfd = null
    }

    /**
     * Releases the recording session, stopping the recording and DELETING the output file.
     *
     * **This method should be called when the output file should not be kept. When no data are expected.**
     */
    fun cancel(context: Service) {
        runCatching {
            shellService?.stopRecording()
        }

        runCatching {
            outputPfd?.close()
        }

        outputPfd = null
        shellService = null

        currentRecordingUri?.let { uri ->
            try {
                DocumentFile.fromSingleUri(context, uri)?.delete()
            } catch (e: Exception) {
                AppLogger.w("Failed to delete incomplete recording file.", e)
            }
        }
    }
}