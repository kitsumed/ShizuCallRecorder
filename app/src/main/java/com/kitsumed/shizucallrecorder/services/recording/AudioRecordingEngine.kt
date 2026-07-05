/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.recording

import android.app.Service
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.kitsumed.shizucallrecorder.BuildConfig
import com.kitsumed.shizucallrecorder.IShellService
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioCodec
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioMuxer
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioSource
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyClient
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyConfig
import com.kitsumed.shizucallrecorder.system.storage.SafHelper
import com.kitsumed.shizucallrecorder.utils.AppLogger
import com.kitsumed.shizucallrecorder.utils.RecordingFileNameFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages the audio recording pipeline, including the connection to the shell service, reading from the audio pipe,
 * parsing scrcpy-server custom stream format, and writing to the output container via [ScrcpyAudioMuxer].
 *
 * Call [startPipeline] to initialize and start the recording, and [release] to clean up resources when done.
 */
class AudioRecordingEngine {

    companion object {
        private const val TAG = "SCR:AudioRecordingEngine"
    }

    /**
     * Parses the raw byte stream that arrives from the shell process pipe.
     *
     * Calls the attached callbacks with parsed audio packets and stream metadata.
     */
    var scrcpyClient: ScrcpyClient? = null

    /** Writes scrcpy decoded audio packets into the output container (OPUS/AAC). */
    var scrcpyAudioMuxer: ScrcpyAudioMuxer? = null

    /** Metadata captured during the [startPipeline] and locked. Used for checks in [release] if we need to query call logs for the final file name if phone number is empty. */
    var initializationMetadata: EnrichedCallData? = null
        set(value) {
            if (field == null) {
                field = value
            } else {
                AppLogger.w(TAG, "Attempt to overwrite recording session metadata ignored. THIS SHOULD NOT HAPPEN. Original: $field, New: $value")
            }
        }

    /**
     * Read end of the kernel pipe owned by the shell process.
     * The shell process writes scrcpy-server audio bytes into the write end; this service
     * reads from the read end. Android's [ParcelFileDescriptor] wraps a native file descriptor
     * so it can be transferred across processes via Binder.
     */
    var audioReadPipePfd: ParcelFileDescriptor? = null

    /**
     * Write-access file descriptor for the output file.
     * This is kept open for the duration of the recording so [ScrcpyAudioMuxer] can write to it,
     * and is closed in [release] after the muxer finalizes the container header.
     */
    var outputPfd: ParcelFileDescriptor? = null

    /**
     * URI of the current recording file.
     * Used to delete the file if recording fails to start mid-initialization.
     */
    var currentRecordingUri: Uri? = null

    /**
     * Active codec enum resolved from the user's preference and confirmed by the stream header.
     * Updated once [ScrcpyClient.AudioPacketListener.onMetadataReceived] fires.
     * Defaults to [ScrcpyAudioCodec.OPUS] as a safe initial value before the stream header is read.
     */
    var currentCodecEnum: ScrcpyAudioCodec = ScrcpyAudioCodec.OPUS

    /**
     * Coroutine scope for reading from the audio pipe data returned by the shell service.
     * Initialised in [startPipeline] and cancelled in [release].
     */
    var audioPipeReadScope: CoroutineScope? = null

    /**
     * The active pipe reading job.
     * We keep a reference so we can wait to finish reading any late bytes during [release].
     */
    var audioPipeReadJob: Job? = null

    /** Whether the recording is currently paused by the user. */
    @Volatile
    var isPaused: Boolean = false

    /**
     * Orchestrates the initialization and connection of the entire recording pipeline.
     * @throws PipelineInitializationException if any step of the initialization fails, with details for user-friendly and technical error reporting.
     */
    fun startPipeline(context: Service, service: IShellService, metadata: EnrichedCallData) {
        initializationMetadata = metadata
        val preferences = AppPreferences(context)
        val folderUri = preferences.getRecordingFolderUri()

        if (!SafHelper.isFolderValid(context, folderUri)) {
            throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_folder_missing),
                technicalLogMessage = "Cannot start recording: Selected Output folder is missing, invalid, or we do not have permission to write to it"
            )
        }

        val codecEnum = ScrcpyAudioCodec.fromKey(preferences.getAudioCodec())
        val bitRate = preferences.getAudioBitRate().takeIf { it > 0 } ?: codecEnum.defaultBitRate
        val audioSourceEnum = ScrcpyAudioSource.fromKey(preferences.getAudioSource())

        AppLogger.i(TAG, "Starting recording pipeline: source=${audioSourceEnum.cliKey} codec=${codecEnum.cliKey} bitrate=$bitRate")

        val fileName = RecordingFileNameFormatter.formatFileName(context, metadata, codecEnum)

        val safResult = SafHelper.createAudioFile(context, folderUri, fileName, codecEnum.mimeType)
            ?: throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_file_creation),
                technicalLogMessage = "Failed to create audio file in SAF storage"
            )

        AppLogger.d(TAG, "Created SAF recording file: ${safResult.uri}")

        currentRecordingUri = safResult.uri
        outputPfd = safResult.descriptor

        val serverPath = installServerForShell(context, service)

        scrcpyAudioMuxer = ScrcpyAudioMuxer(outputPfd!!.fileDescriptor, safResult.displayName)

        try {
            audioReadPipePfd = service.startRecording(
                audioSourceEnum.cliKey,
                codecEnum.cliKey,
                bitRate,
                serverPath,
                preferences.isDebugEnabled()
            )
        } catch (e: Exception) {
            throw PipelineInitializationException(
                userFriendlyMessage = e.localizedMessage ?: context.getString(R.string.recording_error_start_failed),
                technicalLogMessage = "Remote exception calling startRecording",
                cause = e
            )
        }

        val inputPfd = audioReadPipePfd ?: throw PipelineInitializationException(
            userFriendlyMessage = context.getString(R.string.recording_error_start_failed),
            technicalLogMessage = "Shell service returned null pipe – cannot start recording"
        )

        currentCodecEnum = codecEnum
        scrcpyAudioMuxer?.initialize(currentCodecEnum)

        scrcpyClient = ScrcpyClient(
            inputPfd = inputPfd,
            expectedCodec = codecEnum,
            listener = object : ScrcpyClient.AudioPacketListener {
                /**
                 * Called once after the 4-byte codec FourCC is verified from the stream header.
                 * We re-initialise the muxer with the confirmed codec in case it differs from our initial assumption.
                 */
                override fun onMetadataReceived(codec: ScrcpyAudioCodec) {
                    AppLogger.d(TAG, "Stream metadata confirmed: codec=${codec.cliKey} fourCC=0x${codec.codecFourCC.toString(16)}")
                    currentCodecEnum = codec
                    scrcpyAudioMuxer?.initialize(codec)
                }

                /** Called for every audio frame received from the pipe. */
                override fun onAudioPacket(packet: ScrcpyClient.AudioPacket) {
                    if (isPaused) return // Drop packets while paused, do not write to muxer
                    scrcpyAudioMuxer?.writePacket(packet, currentCodecEnum)
                }

                /** Called when the stream ends normally (EOF) or with an error. */
                override fun onStreamEnd(error: String?) {
                    if (error != null) {
                        AppLogger.w(TAG, "Scrcpy-client reported stopping parsing due to an audio stream error: $error")
                    } else {
                        AppLogger.d(TAG, "Scrcpy-client reported our pipe read stream ended normally (EOF)")
                    }
                }
            }
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        audioPipeReadScope = scope
        audioPipeReadJob = scope.launch(Dispatchers.IO) {
            try {
                scrcpyClient?.start()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Audio reader ended: ${e.message}")
            }
        }
    }

    private fun installServerForShell(context: Context, service: IShellService): String {
        val serverPath = ScrcpyConfig.getShellServerPath()
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        var writerFailure: Throwable? = null

        val writerThread = Thread({
            try {
                context.assets.open(BuildConfig.SCRCPY_SERVER_ASSET_NAME).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (t: Throwable) {
                writerFailure = t
                runCatching { writeEnd.close() }
            }
        }, "ScrcpyServerPipeWriter")

        writerThread.start()
        val installed = try {
            service.installServerFile(readEnd, serverPath)
        } catch (e: Exception) {
            throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_server_missing),
                technicalLogMessage = "Remote exception installing scrcpy-server at $serverPath",
                cause = e
            )
        } finally {
            runCatching { readEnd.close() }
            runCatching { writerThread.join() }
        }

        if (!installed) {
            writerFailure?.let { failure ->
                throw PipelineInitializationException(
                    userFriendlyMessage = context.getString(R.string.recording_error_server_missing),
                    technicalLogMessage = "Failed to stream bundled scrcpy-server asset to ShellService",
                    cause = failure
                )
            }

            throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_server_missing),
                technicalLogMessage = "ShellService could not install or verify scrcpy-server at $serverPath"
            )
        }

        return serverPath
    }

    /**
     * Safely releases all held resources in the correct order.
     * Everything is wrapped in runCatching to ignore any exceptions and continue the cleanup.
     *
     * 1. Stops the remote shell service process natively, which gives scrcpy-server a grace period
     *    to write its final audio bytes before closing the pipe from the sender side.
     * 2. Waits for the local reading coroutine to reach EOF and finish parsing the late bytes.
     * 3. Cancels the active reading coroutine and scrcpy client as a fallback.
     * 4. Closes the inbound pipe.
     * 5. Closes the muxer and output file descriptor to finalize the container header.
     */
    fun release(shellService: IShellService?) {
        AppLogger.i(TAG, "Releasing session resources and recording pipeline...")
        runCatching { shellService?.stopRecording() }

        runCatching {
            runBlocking {
                withTimeoutOrNull(2000L) {
                    audioPipeReadJob?.join()
                }
            }
        }

        runCatching { scrcpyClient?.stop() }
        runCatching { audioPipeReadScope?.cancel() }
        runCatching { audioReadPipePfd?.close() }
        runCatching { scrcpyAudioMuxer?.close() }
        runCatching { outputPfd?.close() }
    }

    /**
     * Trigger the normal [release] flow, then followed by an attempt to delete the incomplete recording file if it was created
     * during the pipeline initialization.
     */
    fun cancel(context: Context, shellService: IShellService?) {
        release(shellService)
        try {
            currentRecordingUri?.let { uri ->
                DocumentFile.fromSingleUri(context, uri)?.delete()
            }
            AppLogger.d(TAG, "Cleaned up empty file after start failure")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to cleanup empty file", e)
        }
    }
}

/**
 * Custom exception to carry a user-friendly message for UI display
 * and a technical log message for debugging when the pipeline initialization fails.
 */
class PipelineInitializationException(
    val userFriendlyMessage: String,
    technicalLogMessage: String,
    cause: Throwable? = null
) : Exception(technicalLogMessage, cause)
