/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.scrcpy

import android.os.ParcelFileDescriptor
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileInputStream

/**
 * Reads the binary audio stream that scrcpy-server sends over the pipe.
 *
 * Stream connection header (once):
 *  - 4 bytes: Codec FourCC (e.g. "opus" 0x6F707573)
 *
 * Scrcpy v4.0 Frame Header (12 bytes per packet):
 *
 *     [. . . . . . . .|. . . .]. . . . . . . . . . . . . . . ...
 *      <-------------> <-----> <-----------------------------...
 *            PTS        packet        raw packet
 *                        size
 *      <--------------------->
 *            frame header
 *
 * The most significant bits of the 8-byte PTS are used for packet flags:
 *
 *      byte 0   byte 1   byte 2   byte 3   byte 4   byte 5   byte 6   byte 7
 *     0CK..... ........ ........ ........ ........ ........ ........ ........
 *     ^^^<------------------------------------------------------------------>
 *     |||                                PTS (bits 0-60)
 *     || `- (K)ey frame         (bit 61)
 *     | `-- (C)onfig packet     (bit 62) - The first packet is always a CONFIG packet.
 *      `--- (M)edia packet flag (bit 63) - 0 for media packet, 1 for session packet. Audio is always 0.
 *
 *      byte 8   byte 9   byte 10  byte 11
 *     ........ ........ ........ ........ ........ ........ . . .
 *     <---------------------------------> <---------------- . . .
 *                packet size                       raw packet
 *
 * Maintainer's guide — when updating scrcpy-server, verify in the files below that
 * PACKET_FLAG_CONFIG/KEY_FRAME bitmasks and the writeFrameMeta() header order are unchanged,
 * and that AudioCodec FourCC values still match [ScrcpyAudioCodec.codecFourCC]:
 *   https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/device/Streamer.java
 *   https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/audio/AudioCodec.java
 */
class ScrcpyClient(
    /** The read-end of the kernel pipe carrying the scrcpy audio stream. */
    private val inputPfd: ParcelFileDescriptor,
    /**
     * The codec enum we expect to receive, resolved from the user's codec preference.
     * The stream header FourCC is verified against this value; a mismatch is logged as a warning.
     */
    private val expectedCodec: ScrcpyAudioCodec,
    /** Callback interface that receives decoded metadata and audio packets. */
    private val listener: AudioPacketListener
) : Closeable {

    companion object {
        /**
         * Per-packet flag bitmasks — used to extract flags from the 8-byte PTS field.
         *
         * - MEDIA_PACKET_FLAG (bit 63): '0' = Media packet, '1' = Session packet (Audio streams only use Media).
         * - PACKET_FLAG_CONFIG (bit 62): '1' = Contains Codec Specific Data (CSD).
         * - PACKET_FLAG_KEY_FRAME (bit 61): '1' = Key frame.
         *
         * See: https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/device/Streamer.java
         */
        private const val MEDIA_PACKET_FLAG     = 1L shl 63
        private const val PACKET_FLAG_CONFIG    = 1L shl 62
        private const val PACKET_FLAG_KEY_FRAME = 1L shl 61

        /**
         * Hard cap on payload size (1 MiB). A legitimate Opus/AAC packet is well under 64 KB;
         * exceeding this cap indicates stream misalignment (e.g. a missed or extra byte before
         * the FourCC header that shifts all subsequent reads). Throws [java.io.IOException]
         * instead of attempting to allocate a huge buffer and crashing with OOM.
         */
        private const val MAX_PACKET_SIZE = 1 * 1024 * 1024 // 1 MiB
    }

    // Listener interface

    /**
     * Callbacks fired on the thread that called [start].
     * All methods are called synchronously from the read loop – implementations must be fast.
     */
    interface AudioPacketListener {
        /**
         * Fired once after the stream header (codec FourCC) is read and verified.
         *
         * @param codec  The resolved [ScrcpyAudioCodec] matching the FourCC from the stream header.
         */
        fun onMetadataReceived(codec: ScrcpyAudioCodec)

        /**
         * Fired for every audio packet (both config packets and regular audio frames).
         *
         * @param packet The decoded packet with PTS, flags, and raw payload bytes.
         */
        fun onAudioPacket(packet: AudioPacket)

        /**
         * Fired when the stream ends, either cleanly (EOF) or due to an error.
         *
         * @param error Human-readable error message, or null if the stream ended normally.
         */
        fun onStreamEnd(error: String?)
    }

    // Data model

    /**
     * A single decoded audio unit from the scrcpy stream.
     *
     * @param pts            Presentation timestamp in microseconds.
     * @param isConfigPacket True if this packet carries codec-config data (CSD) — must be fed
     *                       to [ScrcpyAudioMuxer] before any regular audio frames arrive.
     * @param data           Raw codec payload bytes (Opus frame or AAC access unit).
     */
    data class AudioPacket(
        val pts: Long,
        val isConfigPacket: Boolean,
        val data: ByteArray
    )

    /**
     * Decoded form of the 12-byte per-packet header (Streamer.java#writeFrameMeta):
     * putLong(ptsAndFlags) + putInt(packetSize).
     *
     * @param rawPtsAndFlags Raw wire value; kept for diagnostics in size-mismatch errors.
     * @param pts            Microseconds, with packet flags stripped.
     * @param isMedia        True if the packet is a media packet (bit 63 is 0), false for session packets.
     * @param isConfig       PACKET_FLAG_CONFIG (bit 62) was set — packet carries CSD, not audio.
     * @param isKeyFrame     PACKET_FLAG_KEY_FRAME (bit 61) was set.
     * @param payloadSize    Payload bytes that follow this header on the wire.
     */
    private data class AudioEnvelope(
        val rawPtsAndFlags: Long,
        val pts: Long,
        val isMedia: Boolean,
        val isConfig: Boolean,
        val isKeyFrame: Boolean,
        val payloadSize: Int
    )

    // State

    /** Set to false by [stop] to request a clean exit; @Volatile ensures cross-thread visibility. */
    @Volatile
    private var running = false

    // Public API

    /**
     * Blocks the calling thread reading the scrcpy stream until [stop] is called or EOF/error.
     * Must be called on a background thread or IO coroutine.
     *
     * Loop: reads 4-byte FourCC -> fires [onMetadataReceived], then for each packet reads
     * the 12-byte header via [readPacketHeader], reads the payload, fires [onAudioPacket].
     * EOF fires [onStreamEnd](null); any other exception fires [onStreamEnd](message).
     *
     * Thread resume when [stop] is called, or any exceptions stop the reading loop, for instance [EOFException],
     * meaning there is nothing left to read and parse.
     */
    fun start() {
        running = true
        // BufferedInputStream reduces the number of native read() syscalls by batching
        // small reads into a larger kernel buffer.
        val inputStream = DataInputStream(
            BufferedInputStream(FileInputStream(inputPfd.fileDescriptor))
        )
        try {
            // Read the 4-byte FourCC header sent by scrcpy-server (no dummy byte with tunnel_forward=false).
            val receivedFourCC = inputStream.readInt()
            val resolvedCodec = ScrcpyAudioCodec.fromFourCC(receivedFourCC)
            AppLogger.d( "Codec FourCC: received=0x${receivedFourCC.toString(16)} resolved=${resolvedCodec.cliKey} expected=${expectedCodec.cliKey}")

            // Warn if the server is encoding with a different codec than we requested.
            if (resolvedCodec != expectedCodec) {
                AppLogger.w( "Codec mismatch: requested ${expectedCodec.cliKey} but server sent ${resolvedCodec.cliKey} (FourCC=0x${receivedFourCC.toString(16)})")
            }

            listener.onMetadataReceived(resolvedCodec)

            var hasReceivedConfig = false
            while (running) {
                // Read the 12-byte per-packet header — mirrors Streamer.java#writeFrameMeta().
                val header = readPacketHeader(inputStream)

                // The first packet MUST be a config packet. If it's not, the protocol likely changed or parsing is misaligned.
                if (!hasReceivedConfig) {
                    if (!header.isConfig) {
                        throw java.io.IOException(
                            "Protocol error: The first packet must be a CONFIG packet, but received a standard packet! " +
                            "(Flags parsed: isMedia=${header.isMedia}, isConfig=${header.isConfig}, isKeyFrame=${header.isKeyFrame}). " +
                            "Did the scrcpy packet format change again?"
                        )
                    }
                    hasReceivedConfig = true
                }

                if (!header.isMedia) {
                    AppLogger.w( "Unexpected session packet detected on audio stream! flags=0x${header.rawPtsAndFlags.toString(16)}")
                }

                // Guard against stream misalignment: sizes this large are never legitimate.
                if (header.payloadSize <= 0 || header.payloadSize > MAX_PACKET_SIZE) {
                    val ptsHex  = "0x${header.rawPtsAndFlags.toString(16)}"
                    val sizeHex = "0x${Integer.toHexString(header.payloadSize)}"
                    throw java.io.IOException(
                        "Implausible packet size ${header.payloadSize} ($sizeHex) after PTS/Flags $ptsHex (parsed pts=${header.pts}, config=${header.isConfig}, keyFrame=${header.isKeyFrame}, media=${header.isMedia}) — " +
                        "probable stream misalignment (check no extra byte was read before the FourCC header)."
                    )
                }

                // Read exactly payloadSize bytes into a fresh array.
                val payloadBytes = ByteArray(header.payloadSize)
                inputStream.readFully(payloadBytes)

                AppLogger.v( "Packet: pts=${header.pts} config=${header.isConfig} keyFrame=${header.isKeyFrame} media=${header.isMedia} size=${header.payloadSize}")

                listener.onAudioPacket(
                    AudioPacket(
                        pts            = header.pts,
                        isConfigPacket = header.isConfig,
                        data           = payloadBytes
                    )
                )
            }
        } catch (e: EOFException) {
            // EOF, in almost EVERY case, should be normal, clean end of the stream (pipe closed by shell process, and we have read it all).
            AppLogger.d( "Given stream was fully read (EOF), nothing else to parse, ending normally.")
            listener.onStreamEnd(null)
        } catch (e: Exception) {
            // Any other exception indicates an abnormal termination.
            AppLogger.e( "Stream ended with error: ${e.message}", e)
            listener.onStreamEnd(e.message)
        } finally {
            runCatching { inputStream.close() } // do NOT close inputPfd here; that is done in close()
        }
    }

    /** Signals [start] to exit on its next loop iteration. Thread-safe. */
    fun stop() {
        running = false
    }

    /**
     * Stops the loop and closes [inputPfd]. Closing the PFD causes any blocking read inside
     * [start] to throw, allowing the loop to exit even if [stop] hasn't been observed yet.
     */
    override fun close() {
        stop()
        runCatching { inputPfd.close() }
    }

    // Private helpers

    /**
     * Reads the 12-byte per-packet header from [stream], mirroring Streamer.java#writeFrameMeta():
     * putLong(ptsAndFlags) then putInt(packetSize) — same order read here.
     * See: https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/device/Streamer.java
     */
    private fun readPacketHeader(stream: DataInputStream): AudioEnvelope {
        val ptsAndFlags = stream.readLong()  // Mirrors: headerBuffer.putLong(ptsAndFlags)
        val payloadSize = stream.readInt()   // Mirrors: headerBuffer.putInt(packetSize)
        return AudioEnvelope(
            rawPtsAndFlags = ptsAndFlags,
            // Mask out the top 3 bits to get the raw PTS (61 bits).
            pts            = ptsAndFlags and (MEDIA_PACKET_FLAG or PACKET_FLAG_CONFIG or PACKET_FLAG_KEY_FRAME).inv(),
            isMedia        = (ptsAndFlags and MEDIA_PACKET_FLAG) == 0L,
            isConfig       = (ptsAndFlags and PACKET_FLAG_CONFIG)    != 0L,
            isKeyFrame     = (ptsAndFlags and PACKET_FLAG_KEY_FRAME) != 0L,
            payloadSize    = payloadSize
        )
    }
}
