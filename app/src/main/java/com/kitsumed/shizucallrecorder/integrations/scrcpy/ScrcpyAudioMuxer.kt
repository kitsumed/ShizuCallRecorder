/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Writes scrcpy audio packets into an OGG (Opus) or MPEG-4/M4A (AAC) container via [MediaMuxer].
 *
 * You MUST call [close]: [MediaMuxer.stop] writes the container index (MP4 "moov" atom /
 * OGG final page flush). Without it the file may be unplayable by some audio players.
 *
 * Packet flow:
 *   CONFIG packet -> addAudioTrack() (one-time muxer setup with CSD)
 *   Audio packet  -> writeSampleData() with wall-clock PTS
 *
 * Timestamp strategy: PTS is derived from [System.nanoTime] instead of scrcpy's stream PTS so
 * that real-time silences in discontinuous sources (VOICE_CALL, MIC) produce correct gaps rather
 * than a squashed and or corrupted files.
 * See https://github.com/Genymobile/scrcpy/pull/5870 for more info
 *
 * Maintainer's guide — when updating scrcpy-server, check the server files below to see if
 * CSD formats or MIME types have changed:
 *   https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/device/Streamer.java
 *   https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/audio/AudioCodec.java
 *
 * @param outputFileDescriptor Writable [java.io.FileDescriptor] for the output file (from SAF).
 * @param outputDisplayPath    Human-readable path for logs (e.g. "Recordings/call_….ogg").
 */
class ScrcpyAudioMuxer(
    private val outputFileDescriptor: java.io.FileDescriptor,
    private val outputDisplayPath: String
) : Closeable {

    companion object {
        private const val TAG = "SCR:ScrcpyAudioMuxer"
    }

    // Muxer state

    /** The underlying [MediaMuxer] instance; null until [initialize] is called. */
    private var muxer: MediaMuxer? = null

    /**
     * The audio track index assigned by [MediaMuxer.addTrack].
     * -1 means the track has not been added yet (waiting for the config packet).
     */
    private var audioTrackIndex = -1

    /**
     * True after [MediaMuxer.start] has been called successfully.
     * Guards [writePacket] so we never write before the muxer is started.
     */
    private var isMuxerStarted = false

    /**
     * Wall-clock timestamp (from [System.nanoTime]) captured when the first audio frame
     * is written.  Used as the origin for the Linear Wall-Clock Mapper.
     * -1L means no frame has been written yet.
     */
    private var firstPacketTimeNanos: Long = -1L

    /**
     * PTS of the most recently written sample.
     * Used to enforce the strictly-monotonic-PTS requirement of [MediaMuxer]:
     * if the wall clock returns the same nanosecond twice (unlikely but theoretically possible),
     * we advance the PTS by 1 µs so [MediaMuxer] never rejects a duplicate timestamp.
     */
    private var lastWrittenPtsUs: Long = -1L

    /**
     * Wall-clock timestamp (from [System.nanoTime]) of the last processed frame.
     * Used to detect large gaps in the stream (e.g., when packets are dropped during a pause).
     */
    private var lastPacketWallClockNanos: Long = -1L

    /** Accumulated duration of large gaps (in nanoseconds) that we have stripped from the timeline. */
    private var totalIgnoredGapNanos: Long = 0L

    /**
     * The threshold for detecting a "pause" or severe frame drop (400 ms in nanoseconds).
     * Ordinary packet intervals are ~20ms. If we wait more than 400ms for a frame, we assume
     * recording was paused or significantly interrupted, and we squash the timeline.
     */
    private val GAP_THRESHOLD_NANOS = 400_000_000L

    /**
     * The natural gap (25 ms in nanoseconds) we leave behind when squashing a large gap.
     */
    private val GAP_SLACK_NANOS = 25_000_000L

    /**
     * Creates the [MediaMuxer] instance for the container format matching [codec].
     * Safe to call multiple times; subsequent calls after the first are ignored.
     *
     * @param codec The [ScrcpyAudioCodec] enum entry whose [ScrcpyAudioCodec.outputFormat]
     *              determines the output container format (OGG for Opus, MPEG-4 for AAC, etc.).
     */
    fun initialize(codec: ScrcpyAudioCodec) {
        if (muxer != null) return // Already initialised; ignore duplicate calls.

        AppLogger.d(TAG, "Initialising muxer: codec=${codec.cliKey} format=${codec.outputFormat} path='$outputDisplayPath'")
        muxer = MediaMuxer(outputFileDescriptor, codec.outputFormat)
    }

    /**
     * Processes a single audio packet received from [ScrcpyClient].
     *
     * Config packets (CSD) are used to set up the muxer track; regular frames are written
     * as audio samples with a wall-clock–derived PTS.  Packets arriving before the muxer
     * track is ready are silently dropped (this should not happen in normal operation because
     * scrcpy always sends the config packet first).
     *
     * @param packet  The decoded packet from [ScrcpyClient.AudioPacketListener.onAudioPacket].
     * @param codec   The active [ScrcpyAudioCodec], used to determine the MIME type for the track.
     */
    fun writePacket(packet: ScrcpyClient.AudioPacket, codec: ScrcpyAudioCodec) {
        if (packet.isConfigPacket) {
            // Config packet: set up the audio track if we haven't yet.
            if (audioTrackIndex < 0) {
                addAudioTrack(configData = packet.data, codec = codec)
            }
            // Config packets carry no audio data; do not write them as samples.
            return
        }

        // Guard: only write if the muxer is fully started and we have a valid track.
        if (!isMuxerStarted || audioTrackIndex < 0) {
            AppLogger.w(TAG, "writePacket(): muxer not ready – dropping frame")
            return
        }

        val nowNanos = System.nanoTime()

        // Missing/Large Gap detection: if the gap between this packet and the last one is huge,
        // it means we paused the recording or dropped a massive amount of packets. We subtract this
        // dead time from our internal timeline so the final output file doesn't have a huge silence.
        // TODO: This code was added to fix most phone audio player behaving badly with the large silence, ideally we would like to keep the silence and not corrupt the file by doing so.
        if (firstPacketTimeNanos == -1L) {
            firstPacketTimeNanos = nowNanos
            lastPacketWallClockNanos = nowNanos
            AppLogger.d(TAG, "First audio frame: wall-clock origin set, pts=0")
        } else {
            // This is the "temporary" fix for the pause feature (audio silence) we just fully cut it out to prevent corrupting the file.
            val gapNanos = nowNanos - lastPacketWallClockNanos
            if (gapNanos > GAP_THRESHOLD_NANOS) {
                val ignoredNanos = gapNanos - GAP_SLACK_NANOS
                totalIgnoredGapNanos += ignoredNanos
                AppLogger.d(TAG, "Detected huge gap silence/pause of ${gapNanos / 1_000_000} ms. Squashing ${ignoredNanos / 1_000_000} ms.")
            }
            lastPacketWallClockNanos = nowNanos
        }

        // Wall-clock PTS: derive from System.nanoTime so real silences produce real gaps.
        val wallClockPtsUs = (nowNanos - firstPacketTimeNanos - totalIgnoredGapNanos) / 1000L

        // Safety check: MediaMuxer requires strictly increasing PTS values.
        // System.nanoTime() is guaranteed monotonically non-decreasing, but integer
        // truncation to microseconds can produce equal values for back-to-back packets.
        val normalizedPtsUs = if (wallClockPtsUs > lastWrittenPtsUs) wallClockPtsUs else lastWrittenPtsUs + 1L

        val bufferInfo = MediaCodec.BufferInfo().apply {
            offset             = 0
            size               = packet.data.size
            presentationTimeUs = normalizedPtsUs
        }
        lastWrittenPtsUs = normalizedPtsUs

        // ByteBuffer.wrap() creates a view over the existing array with no copy.
        muxer?.writeSampleData(audioTrackIndex, ByteBuffer.wrap(packet.data), bufferInfo)
    }

    /**
     * Finalises the output file and releases the muxer.
     *
     * IMPORTANT: [MediaMuxer.stop] writes the container index (moov/EBML).  Without this call
     * the output file is structurally incomplete and some media players will refuse to open it.
     * [close] must therefore be called even if only a single audio frame was written.
     */
    override fun close() {
        if (isMuxerStarted) {
            AppLogger.d(TAG, "Finalising muxer for '$outputDisplayPath'")
            runCatching { muxer?.stop() }.onFailure { e ->
                AppLogger.e(TAG, "Muxer stop failed (file may be incomplete): ${e.message}")
            }
        }
        runCatching { muxer?.release() }
        muxer                  = null
        isMuxerStarted         = false
        audioTrackIndex        = -1
        firstPacketTimeNanos   = -1L
        lastWrittenPtsUs       = -1L
        lastPacketWallClockNanos = -1L
        totalIgnoredGapNanos     = 0L
        AppLogger.d(TAG, "Muxer closed")
    }

    // Private helpers

    /**
     * Configures the muxer's audio track using the codec-specific data from the config packet,
     * then calls [MediaMuxer.start] to begin accepting sample data.
     *
     * @param configData  Raw CSD bytes from the scrcpy config packet (may be empty for Opus).
     * @param codec       The [ScrcpyAudioCodec] used to select the MIME type.
     */
    private fun addAudioTrack(configData: ByteArray, codec: ScrcpyAudioCodec) {
        val csdBytes = configData.takeIf { it.isNotEmpty() }
        if (csdBytes == null) {
            AppLogger.e(TAG, "Empty config data received. Cannot initialize audio track.")
            return
        }

        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, codec.mimeType)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, ScrcpyConfig.AUDIO_SAMPLE_RATE)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, ScrcpyConfig.AUDIO_CHANNELS)
            // "csd-0" is the standard key for codec-specific data buffer 0.
            setByteBuffer("csd-0", ByteBuffer.wrap(csdBytes))
        }

        audioTrackIndex = muxer?.addTrack(mediaFormat) ?: -1
        if (audioTrackIndex < 0) {
            AppLogger.e(TAG, "Failed to add audio track (addTrack returned $audioTrackIndex)")
            return
        }

        muxer?.start()
        isMuxerStarted = audioTrackIndex >= 0
        AppLogger.d(TAG, "Audio track added (index=$audioTrackIndex mime=${codec.mimeType}) – muxer started")
    }
}
