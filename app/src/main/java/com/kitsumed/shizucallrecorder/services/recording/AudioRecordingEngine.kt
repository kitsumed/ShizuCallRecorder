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
import com.kitsumed.shizucallrecorder.IShellService
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioCodec
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioMuxer
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioSource
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyClient
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyConfig
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ServerExtractor
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
 * Normally manages a single [TrackSession] (one scrcpy-server capture -> one output file). When the user enables
 * dual-track recording, it manages two independent [TrackSession]s in parallel: [primaryTrack] captures
 * [ScrcpyAudioSource.VOICE_CALL_UPLINK] (your side) and [secondaryTrack] captures [ScrcpyAudioSource.VOICE_CALL_DOWNLINK]
 * (the other party's side), each writing to its own file, sharing a single wall-clock PTS origin so the two
 * files stay perfectly in sync when merged externally (e.g. for transcription).
 *
 * Call [startPipeline] to initialize and start the recording, and [release] to clean up resources when done.
 */
class AudioRecordingEngine {

    /**
     * Bundles all resources belonging to a single scrcpy capture -> output file pipeline, so
     * [startPipeline] can run one (single-track) or two (dual-track) of these concurrently.
     */
    private class TrackSession(
        val outputPfd: ParcelFileDescriptor,
        val recordingUri: Uri,
        val scrcpyAudioMuxer: ScrcpyAudioMuxer,
        val audioReadPipePfd: ParcelFileDescriptor,
        val audioPipeReadScope: CoroutineScope
    ) {
        var scrcpyClient: ScrcpyClient? = null

        /**
         * Active codec enum resolved from the user's preference and confirmed by the stream header.
         * Updated once [ScrcpyClient.AudioPacketListener.onMetadataReceived] fires.
         * Defaults to [ScrcpyAudioCodec.OPUS] as a safe initial value before the stream header is read.
         */
        var currentCodecEnum: ScrcpyAudioCodec = ScrcpyAudioCodec.OPUS

        /** The active pipe reading job, kept so [release] can wait to finish reading any late bytes. */
        var audioPipeReadJob: Job? = null
    }

    /** The uplink track (or the sole track, when dual-track recording is off) of the current session. */
    private var primaryTrack: TrackSession? = null

    /** The downlink track of the current session. Null unless dual-track recording is enabled. */
    private var secondaryTrack: TrackSession? = null

    /** Metadata captured during the [startPipeline] and locked. Used for checks in [release]. */
    var initializationMetadata: EnrichedCallData? = null
        set(value) {
            if (field == null) {
                field = value
            } else {
                AppLogger.w( "Attempt to overwrite recording session metadata ignored. THIS SHOULD NOT HAPPEN. Original: $field, New: $value")
            }
        }

    /**
     * URI of the primary (uplink, or sole) recording file.
     * Used to delete the file if recording fails to start mid-initialization, and by callers to
     * offer post-recording file actions.
     */
    val currentRecordingUri: Uri?
        get() = primaryTrack?.recordingUri

    /** True while the primary track's audio-pipe read coroutine is still active (capturing audio). */
    val isActivelyCapturingAudio: Boolean
        get() = primaryTrack?.audioPipeReadJob?.isActive == true

    /** Whether the recording is currently paused by the user. */
    @Volatile
    var isPaused: Boolean = false

    /**
     * Orchestrates the initialization and connection of the entire recording pipeline.
     * Starts one [TrackSession] normally, or two (uplink + downlink) when
     * [AppPreferences.isDualTrackRecordingEnabled] is on.
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
        val isDebuggingModeEnabled = preferences.isDebugEnabled()

        val serverPath = ScrcpyConfig.getServerPath(context)
        if (!ServerExtractor.ensureServerFile(context, serverPath)) {
            throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_server_missing),
                technicalLogMessage = "scrcpy-server missing or SHA256 check was invalid at $serverPath"
            )
        }

        // Shared wall-clock origin so dual-track files' PTS=0 lines up to the exact same instant.
        val sharedOriginNanos = System.nanoTime()

        if (preferences.isDualTrackRecordingEnabled()) {
            AppLogger.i( "Starting dual-track recording pipeline: codec=${codecEnum.cliKey} bitrate=$bitRate")

            // If the downlink track below throws, primaryTrack stays set on this engine and will be
            // torn down (and its file deleted) by the caller's cancel()/release() fallback - no
            // manual rollback needed here.
            primaryTrack = startTrack(
                context = context,
                service = service,
                metadata = metadata,
                folderUri = folderUri,
                codecEnum = codecEnum,
                bitRate = bitRate,
                serverPath = serverPath,
                isDebuggingModeEnabled = isDebuggingModeEnabled,
                audioSource = ScrcpyAudioSource.VOICE_CALL_UPLINK,
                trackSuffix = "uplink",
                sharedOriginNanos = sharedOriginNanos,
                useSecondarySlot = false
            )
            secondaryTrack = startTrack(
                context = context,
                service = service,
                metadata = metadata,
                folderUri = folderUri,
                codecEnum = codecEnum,
                bitRate = bitRate,
                serverPath = serverPath,
                isDebuggingModeEnabled = isDebuggingModeEnabled,
                audioSource = ScrcpyAudioSource.VOICE_CALL_DOWNLINK,
                trackSuffix = "downlink",
                sharedOriginNanos = sharedOriginNanos,
                useSecondarySlot = true
            )
        } else {
            val audioSourceEnum = ScrcpyAudioSource.fromKey(preferences.getAudioSource())
            AppLogger.i( "Starting recording pipeline: source=${audioSourceEnum.cliKey} codec=${codecEnum.cliKey} bitrate=$bitRate")

            primaryTrack = startTrack(
                context = context,
                service = service,
                metadata = metadata,
                folderUri = folderUri,
                codecEnum = codecEnum,
                bitRate = bitRate,
                serverPath = serverPath,
                isDebuggingModeEnabled = isDebuggingModeEnabled,
                audioSource = audioSourceEnum,
                trackSuffix = null,
                sharedOriginNanos = sharedOriginNanos,
                useSecondarySlot = false
            )
        }
    }

    /**
     * Creates the output file, muxer, shell-side capture, and [ScrcpyClient] for a single track,
     * and starts its pipe-reading coroutine.
     *
     * @param useSecondarySlot When true, uses [IShellService.startSecondaryRecording] instead of
     *                         [IShellService.startRecording] so this track runs in its own
     *                         concurrent shell-side scrcpy-server process (used for the downlink
     *                         track in dual-track mode).
     * @throws PipelineInitializationException on any failure; cleans up its own partial file/pipe/output
     *         before throwing so a failure here never leaks resources for *this* track.
     */
    private fun startTrack(
        context: Service,
        service: IShellService,
        metadata: EnrichedCallData,
        folderUri: Uri,
        codecEnum: ScrcpyAudioCodec,
        bitRate: Int,
        serverPath: String,
        isDebuggingModeEnabled: Boolean,
        audioSource: ScrcpyAudioSource,
        trackSuffix: String?,
        sharedOriginNanos: Long,
        useSecondarySlot: Boolean
    ): TrackSession {
        val fileName = RecordingFileNameFormatter.formatFileName(context, metadata, codecEnum, trackSuffix = trackSuffix)

        val safResult = SafHelper.createAudioFile(context, folderUri, fileName, codecEnum.mimeType)
            ?: throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_file_creation),
                technicalLogMessage = "Failed to create audio file in SAF storage"
            )

        AppLogger.d( "Created SAF recording file: ${safResult.uri}")

        val outputPfd = safResult.descriptor
        val scrcpyAudioMuxer = ScrcpyAudioMuxer(outputPfd.fileDescriptor, safResult.displayName, sharedOriginNanos)

        val audioReadPipePfd = try {
            (if (useSecondarySlot) {
                service.startSecondaryRecording(audioSource.cliKey, codecEnum.cliKey, bitRate, serverPath, isDebuggingModeEnabled)
            } else {
                service.startRecording(audioSource.cliKey, codecEnum.cliKey, bitRate, serverPath, isDebuggingModeEnabled)
            }) ?: throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_start_failed),
                technicalLogMessage = "Shell service returned null pipe – cannot start recording"
            )
        } catch (e: PipelineInitializationException) {
            runCatching { outputPfd.close() }
            runCatching { DocumentFile.fromSingleUri(context, safResult.uri)?.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { outputPfd.close() }
            runCatching { DocumentFile.fromSingleUri(context, safResult.uri)?.delete() }
            throw PipelineInitializationException(
                userFriendlyMessage = e.localizedMessage ?: context.getString(R.string.recording_error_start_failed),
                technicalLogMessage = "Remote exception calling startRecording",
                cause = e
            )
        }

        val track = TrackSession(
            outputPfd = outputPfd,
            recordingUri = safResult.uri,
            scrcpyAudioMuxer = scrcpyAudioMuxer,
            audioReadPipePfd = audioReadPipePfd,
            audioPipeReadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )

        track.currentCodecEnum = codecEnum
        track.scrcpyAudioMuxer.initialize(track.currentCodecEnum)

        track.scrcpyClient = ScrcpyClient(
            inputPfd = audioReadPipePfd,
            expectedCodec = codecEnum,
            listener = buildPacketListener(track)
        )

        track.audioPipeReadJob = track.audioPipeReadScope.launch(Dispatchers.IO) {
            try {
                track.scrcpyClient?.start()
            } catch (e: Exception) {
                AppLogger.w( "Audio reader ended: ${e.message}")
            }
        }

        return track
    }

    /**
     * Builds the [ScrcpyClient.AudioPacketListener] that wires a track's parsed packets into its
     * own [ScrcpyAudioMuxer], respecting [isPaused].
     */
    private fun buildPacketListener(track: TrackSession): ScrcpyClient.AudioPacketListener {
        return object : ScrcpyClient.AudioPacketListener {
            /**
             * Called once after the 4-byte codec FourCC is verified from the stream header.
             * We re-initialise the muxer with the confirmed codec in case it differs from our initial assumption.
             */
            override fun onMetadataReceived(codec: ScrcpyAudioCodec) {
                AppLogger.d( "Stream metadata confirmed: codec=${codec.cliKey} fourCC=0x${codec.codecFourCC.toString(16)}")
                track.currentCodecEnum = codec
                track.scrcpyAudioMuxer.initialize(codec)
            }

            /** Called for every audio frame received from the pipe. */
            override fun onAudioPacket(packet: ScrcpyClient.AudioPacket) {
                if (isPaused) return // Drop packets while paused, do not write to muxer
                track.scrcpyAudioMuxer.writePacket(packet, track.currentCodecEnum)
            }

            /** Called when the stream ends normally (EOF) or with an error. */
            override fun onStreamEnd(error: String?) {
                if (error != null) {
                    AppLogger.w( "Scrcpy-client reported stopping parsing due to an audio stream error: $error")
                } else {
                    AppLogger.d( "Scrcpy-client reported our pipe read stream ended normally (EOF)")
                }
            }
        }
    }

    /**
     * Safely releases all held resources in the correct order, for both tracks if dual-track
     * recording was active.
     * Everything is wrapped in runCatching to ignore any exceptions and continue the cleanup.
     */
    fun release(shellService: IShellService?) {
        AppLogger.i( "Releasing session resources and recording pipeline...")
        releaseTrack(primaryTrack, shellService, isSecondary = false)
        releaseTrack(secondaryTrack, shellService, isSecondary = true)
        primaryTrack = null
        secondaryTrack = null
    }

    /**
     * Tears down a single [TrackSession] in the correct order:
     * 1. Stops the remote shell service process natively, which gives scrcpy-server a grace period
     *    to write its final audio bytes before closing the pipe from the sender side.
     * 2. Waits for the local reading coroutine to reach EOF and finish parsing the late bytes.
     * 3. Cancels the active reading coroutine and scrcpy client as a fallback.
     * 4. Closes the inbound pipe.
     * 5. Closes the muxer and output file descriptor to finalize the container header.
     */
    private fun releaseTrack(track: TrackSession?, shellService: IShellService?, isSecondary: Boolean) {
        if (track == null) return

        runCatching {
            if (isSecondary) shellService?.stopSecondaryRecording() else shellService?.stopRecording()
        }

        runCatching {
            runBlocking {
                withTimeoutOrNull(2000L) {
                    track.audioPipeReadJob?.join()
                }
            }
        }

        runCatching { track.scrcpyClient?.stop() }
        runCatching { track.audioPipeReadScope.cancel() }
        runCatching { track.audioReadPipePfd.close() }
        runCatching { track.scrcpyAudioMuxer.close() }
        runCatching { track.outputPfd.close() }
    }

    /**
     * Trigger the normal [release] flow, then followed by an attempt to delete the incomplete recording file(s)
     * created during the pipeline initialization (both tracks', when dual-track recording was active).
     */
    fun cancel(context: Context, shellService: IShellService?) {
        val urisToDelete = listOfNotNull(primaryTrack?.recordingUri, secondaryTrack?.recordingUri)
        release(shellService)
        urisToDelete.forEach { uri ->
            try {
                DocumentFile.fromSingleUri(context, uri)?.delete()
                AppLogger.d( "Cleaned up empty file after start failure")
            } catch (e: Exception) {
                AppLogger.w( "Failed to cleanup empty file", e)
            }
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
