/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.shell.recording

import android.media.AudioRecord
import android.os.ParcelFileDescriptor
import android.os.Process
import com.kitsumed.shizucallrecorder.IRecordingCallback
import com.kitsumed.shizucallrecorder.data.recording.RecordingFailure
import com.kitsumed.shizucallrecorder.data.recording.RecordingPlan
import com.kitsumed.shizucallrecorder.services.shell.recording.capture.IAudioCapture
import com.kitsumed.shizucallrecorder.services.shell.recording.encoding.AudioEncoder
import com.kitsumed.shizucallrecorder.services.shell.recording.processing.PcmProcessor
import com.kitsumed.shizucallrecorder.services.shell.recording.processing.RecordingClock
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
/**
 * Owns the lifetime of one recording session, manage the recording state, and run the capture logic.
 *
 * On the app side, this may be referred as the "remote recording engine".
 */
class ShellRecordingEngine {

    companion object {
        /**
         * Translates native [AudioRecord.read] error codes into human-readable strings.
         */
        private fun parseAudioRecordError(errorCode: Int): String {
            return when (errorCode) {
                AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                AudioRecord.ERROR -> "ERROR (GENERIC)"
                else -> "UNKNOWN_ERROR ($errorCode)"
            } + " ($errorCode)"
        }
    }
    // Thread-safe flags for recording / paused state.
    private val recordingActive = AtomicBoolean(false)
    private val recordingPaused = AtomicBoolean(false)

    private var outputFile: ParcelFileDescriptor? = null
    private var currentPlan: RecordingPlan? = null

    private var primaryCapture: IAudioCapture<*>? = null
    private var secondaryCapture: IAudioCapture<*>? = null

    private var captureThread: Thread? = null
    private var encoderThread: Thread? = null
    private var encoder: AudioEncoder? = null

    private var recordingCallback: IRecordingCallback? = null

    // Make the pool size 2 second worth of audio frames (blocks). Calculation is in milliseconds.
    private val poolSize: Int = (2_000L / AudioConfig.BLOCK_DURATION_MS).toInt()
    private val emptyQueue = ArrayBlockingQueue<AudioFrame>(poolSize)
    private val readyQueue = ArrayBlockingQueue<AudioFrame>(poolSize)

    /**
     * Represents a single audio frame, containing the primary and secondary audio buffers.
     * @param hasSecondary Whether the frame should include a secondary buffer.
     */
    private class AudioFrame(hasSecondary: Boolean) {
        val primaryBuffer: ByteBuffer = ByteBuffer.allocateDirect(AudioConfig.BLOCK_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val secondaryBuffer: ByteBuffer? = if (hasSecondary) {
            ByteBuffer.allocateDirect(AudioConfig.BLOCK_SIZE_BYTES).order(ByteOrder.nativeOrder())
        } else null

        var primaryRead: Int = 0
        var secondaryRead: Int = 0
    }

    fun start(outputFile: ParcelFileDescriptor, plan: RecordingPlan, callback: IRecordingCallback) {
        synchronized(this) {
            if (!recordingActive.compareAndSet(false, true)) {
                throw IllegalStateException("A recording session is already active.")
            }

            try {
                this.recordingCallback = callback
                this.outputFile = outputFile
                this.currentPlan = plan
                this.recordingPaused.set(false)

                AppLogger.i(
                    "Recording session accepted. " +
                            "primary=${plan.primary}, " +
                            "secondary=${plan.secondary}, " +
                            "isAudioChannelSplitEnabled=${plan.isAudioChannelSplitEnabled}, " +
                            "codec=${plan.codec.key}, " +
                            "bitrate=${plan.bitrate}, poolSize=$poolSize"
                )

                // Pre-allocate the object pool
                emptyQueue.clear()
                readyQueue.clear()
                repeat(poolSize) {
                    emptyQueue.add(AudioFrame(hasSecondary = plan.secondary != null))
                }

                // We create the capture objects
                primaryCapture = plan.primary.createCapture()
                // Secondary capture is optional, so we only create it if the plan specifies a secondary source.
                secondaryCapture = plan.secondary?.createCapture()

                // Mono (1) or stereo (2) output channels.
                val outputChannelCount = if (plan.isAudioChannelSplitEnabled) 2 else 1
                encoder = AudioEncoder(
                    outputFileDescriptor = outputFile.fileDescriptor,
                    codec = plan.codec,
                    channelCount = outputChannelCount,
                    bitrate = plan.bitrate
                )


                // We start both captures before starting to process the audio.
                // For dual-source (secondary), this help avoid mis-aligned audio streams.
                primaryCapture!!.start()
                secondaryCapture?.start()

                encoderThread = thread(name = "ShellRecordingEngine-EncoderThread", isDaemon = false) {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
                    encoderLoop()
                }

                captureThread = thread(name = "ShellRecordingEngine-CaptureThread", isDaemon = false) {
                    // Set thread priority to reduce logging "RecordThread: buffer overflow" and audio glitches.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    captureLoop()
                }

                AppLogger.i("Native audio capture started.")
            } catch (e: Throwable) { // Catch ALL (include low-level exceptions) that may occur.
                AppLogger.e("Failed to start native recording session.", e)
                reportFailure(RecordingFailure.CAPTURE_START_FAILED)
                // Stop the recording session to clean up resources and reset the state.
                stop()
            }
        }
    }

    private fun captureLoop() {
        val primary = checkNotNull(primaryCapture)
        val secondary = secondaryCapture

        // Trash buffers for shedding load when the encoder is backed up
        // NOTE: WE MUST use allocateDirect() here because AudioRecord.read(ByteBuffer) requires a direct buffer. Else read() will always return 0.
        val trashPrimaryBuffer = ByteBuffer.allocateDirect(AudioConfig.BLOCK_SIZE_BYTES).order(ByteOrder.nativeOrder())
        val trashSecondaryBuffer = secondary?.let { ByteBuffer.allocateDirect(AudioConfig.BLOCK_SIZE_BYTES).order(ByteOrder.nativeOrder()) }

        var consecutivePrimaryZeroReads = 0L
        var consecutiveSecondaryZeroReads = 0L
        var droppedFrames = 0L

        try {
            while (recordingActive.get()) {
                // Try to get a clean buffer. If null, the encoder is lagging.
                val frame = emptyQueue.poll(5, TimeUnit.MILLISECONDS)

                val pBuffer = frame?.primaryBuffer ?: trashPrimaryBuffer
                val sBuffer = frame?.secondaryBuffer ?: trashSecondaryBuffer

                // [Primary Capture]
                // Clear the buffers before each read to ensure we don't have leftover data from previous reads.
                pBuffer.clear()
                val primaryRead = primary.read(pBuffer, AudioConfig.BLOCK_SIZE_BYTES)
                if (primaryRead < 0) throw IllegalStateException("Primary audio capture failed: ${parseAudioRecordError(primaryRead)}")

                if (primaryRead == 0) {
                    consecutivePrimaryZeroReads++
                    if (consecutivePrimaryZeroReads % AudioConfig.ZERO_READ_WARNING_INTERVAL == 0L) {
                        AppLogger.w("Primary audio capture is returning 0 bytes ($consecutivePrimaryZeroReads consecutive zero-byte reads). Was reported as silenced by Android? --> ${primaryCapture!!.isCaptureSilenced()}")
                    }
                } else consecutivePrimaryZeroReads = 0

                // We set the limit of the buffer to the number of bytes read, and reset the position to 0 so that the processor can read from the start of the buffer.
                if (primaryRead > 0) {
                    pBuffer.limit(primaryRead)
                    pBuffer.position(0)
                }

                // [Secondary capture]
                var secondaryRead = 0
                if (secondary != null && sBuffer != null) {
                    sBuffer.clear()
                    secondaryRead = secondary.read(sBuffer, AudioConfig.BLOCK_SIZE_BYTES)

                    if (secondaryRead < 0) throw IllegalStateException("Secondary audio capture failed: ${parseAudioRecordError(secondaryRead)}")

                    if (secondaryRead == 0) {
                        consecutiveSecondaryZeroReads++
                        if (consecutiveSecondaryZeroReads % AudioConfig.ZERO_READ_WARNING_INTERVAL == 0L) {
                            AppLogger.w("Secondary audio capture is returning 0 bytes ($consecutiveSecondaryZeroReads consecutive zero-byte reads). Was reported as silenced by Android? --> ${secondaryCapture?.isCaptureSilenced()}")
                        }
                    } else consecutiveSecondaryZeroReads = 0

                    if (secondaryRead > 0) {
                        sBuffer.limit(secondaryRead)
                        sBuffer.position(0)
                    }
                }

                // If we couldn't get a clean buffer from the pool, we drop the frame to prevent OS buffer overflow.
                if (frame == null) {
                    droppedFrames++
                    if (droppedFrames % 10 == 0L) AppLogger.w("Encoder is lagging behind. Capture loop dropped $droppedFrames frames to prevent OS overflow.")
                    continue
                }

                // If paused, recycle the frame without processing/adding to queue as to not advance the recording clock.
                if (recordingPaused.get()) {
                    emptyQueue.put(frame)
                    continue
                }

                frame.primaryRead = primaryRead
                frame.secondaryRead = secondaryRead
                readyQueue.put(frame) // Send to encoder thread
            }
        } catch (t: Throwable) {
            if (recordingActive.get()) AppLogger.e("Audio capture loop failed.", t)
            reportFailure(RecordingFailure.CAPTURE_READ_FAILED)
            stop()
        }
    }

    private fun encoderLoop() {
        val plan = currentPlan ?: return
        val processor = PcmProcessor(plan)
        val clock = RecordingClock()

        val outputBuffer = ByteBuffer.allocateDirect(
            if (plan.isAudioChannelSplitEnabled) AudioConfig.STEREO_BLOCK_SIZE_BYTES else AudioConfig.BLOCK_SIZE_BYTES
        ).order(ByteOrder.nativeOrder())

        var blocksRead = 0L

        try {
            while (recordingActive.get() || !readyQueue.isEmpty()) {
                // Use poll with a timeout so it doesn't block forever if the session ends abruptly
                val frame = readyQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue

                blocksRead++
                // Clear the output buffer before processing to ensure we don't have leftover data from previous processing.
                outputBuffer.clear()

                val processedBytes = processor.process(
                    primary = frame.primaryBuffer,
                    primaryBytes = frame.primaryRead,
                    secondary = frame.secondaryBuffer,
                    secondaryBytes = frame.secondaryRead,
                    output = outputBuffer
                )

                if (processedBytes <= 0) {
                    AppLogger.w("Audio processing failed for frame $blocksRead, it returned $processedBytes. Skipping this frame.")
                    emptyQueue.put(frame)
                    continue
                }

                val presentationTimeUs = clock.currentPresentationTimeUs()
                // Mono (1) or stereo (2) output channels.
                val outputChannelCount = if (plan.isAudioChannelSplitEnabled) 2 else 1

                val outputFrameSize = outputChannelCount * AudioConfig.PCM_BYTES_PER_SAMPLE
                val frameCount = processedBytes / outputFrameSize

                // The processor has already written the processed PCM data into the output buffer, so we just need to flip it to prepare for reading.
                outputBuffer.flip()

                encoder?.encode(
                    pcm = outputBuffer,
                    presentationTimeUs = presentationTimeUs
                )

                clock.advance(frameCount.toLong())

                if (blocksRead % AudioConfig.LOG_INTERVAL_BLOCKS == 0L) {
                    AppLogger.d("Audio encoding running. blocks=$blocksRead, ptsUs=$presentationTimeUs")
                }

                // Return buffer to pool
                emptyQueue.put(frame)
            }
        } catch (t: Throwable) {
            AppLogger.e("Audio encoding loop failed.", t)
            reportFailure(RecordingFailure.CAPTURE_READ_FAILED)
            stop()
        }
    }

    fun pause() {
        synchronized(this) {
            if (!recordingActive.get()) {
                AppLogger.w("pause() called without an active session.")
                return
            }

            if (!recordingPaused.compareAndSet(false, true)) {
                AppLogger.d("pause() ignored because session is already paused.")
                return
            }

            AppLogger.i("Native recording session paused.")
        }
    }

    fun resume() {
        synchronized(this) {
            if (!recordingActive.get()) {
                AppLogger.w("resume() called without an active session.")
                return
            }

            if (!recordingPaused.compareAndSet(true, false)) {
                AppLogger.d("resume() ignored because session is not paused.")
                return
            }

            AppLogger.i("Native recording session resumed.")
        }
    }

    fun stop() {
        synchronized(this) {
            val isActive = recordingActive.compareAndSet(true, false)
            val hasResources = primaryCapture != null || secondaryCapture != null || encoder != null || outputFile != null

            if (!isActive && !hasResources) {
                AppLogger.d("stop() called without an active session and without resources.")
                return
            } else if (!isActive && hasResources) {
                AppLogger.w("stop() called without an active session, but resources are still allocated. Cleaning up resources.")
            }

            AppLogger.i("Stopping native recording session...")

            /*
             * Stop the audio captures first so blocking read() calls can return on the captureThread loop.
             */
            runCatching {
                primaryCapture?.stop()
            }.onFailure {
                AppLogger.w("Failed to stop primary audio capture.", it)
            }
            primaryCapture = null

            runCatching {
                secondaryCapture?.stop()
            }.onFailure {
                AppLogger.w("Failed to stop secondary audio capture.", it)
            }
            secondaryCapture = null

            val cThread = captureThread
            if (cThread != null && cThread !== Thread.currentThread()) {
                runCatching {
                    // Wait for up to 2 seconds for the capture thread to finish processing read.
                    cThread.join(2_000L)
                }.onFailure {
                    AppLogger.w("Failed waiting for capture thread shutdown.", it)
                }
            }
            captureThread = null

            val eThread = encoderThread
            if (eThread != null && eThread !== Thread.currentThread()) {
                runCatching {
                    eThread.join(4_000L)
                }.onFailure {
                    AppLogger.w("Failed waiting for encoder thread shutdown.", it)
                }
            }
            encoderThread = null

            /*
             * At this point no more new PCM is being submitted to MediaCodec.
             * Now flush/finalize the encoder and close the output file descriptor.
             */
            runCatching {
                // Write the end-of-stream signal to the encoder and wait for it to finish.
                encoder?.finish()
            }.onFailure {
                AppLogger.e("Failed to finalize audio encoder.", it)
            }

            runCatching {
                encoder?.release()
            }.onFailure {
                AppLogger.w("Failed to release audio encoder.", it)
            }

            encoder = null
            currentPlan = null

            // We close the output file descriptor last, after the encoder has been finalized and released.
            runCatching {
                outputFile?.close()
            }.onFailure {
                AppLogger.w("Failed to close recording output descriptor.", it)
            }

            outputFile = null
            recordingCallback = null
            recordingPaused.set(false)

            AppLogger.i("Native recording session stopped.")
        }
    }

    /**
     * Reports a recording failure to the registered callback, if any.
     *
     * @param error The [RecordingFailure] to report.
     */
    private fun reportFailure(error: RecordingFailure) {
        runCatching {
            recordingCallback?.onRecordingFailure(error)
        }.onFailure {
            AppLogger.w("Failed to deliver recording failure callback.", it)
        }
    }
}