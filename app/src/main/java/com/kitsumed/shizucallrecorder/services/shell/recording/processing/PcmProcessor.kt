/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.shell.recording.processing

import com.kitsumed.shizucallrecorder.data.recording.RecordingPlan
import com.kitsumed.shizucallrecorder.services.shell.recording.AudioConfig
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.nio.ByteBuffer

/**
 * This class processes PCM16 audio data from one or two mono audio sources, either mixing them into a single mono stream or interleaving them into a stereo stream based on the provided [RecordingPlan].
 *
 * Output:
 * - isAudioChannelSplitEnabled = false -> mono
 * - isAudioChannelSplitEnabled = true  -> stereo -> primary = LEFT -> secondary = RIGHT
 */
class PcmProcessor(
    private val plan: RecordingPlan
) {
    private val vadDuckingProcessor: PcmVadDuckingProcessor by lazy { PcmVadDuckingProcessor() }
    /**
     * Processes one capture block into [output].
     *
     * The output buffer must be in "write" mode when this method is called.
     * On return, its position is advanced by the number of bytes written. To switch to "read" mode, call [ByteBuffer.flip] after this method returns.
     *
     * @param primary The primary mono PCM16 source buffer.
     * @param primaryBytes The number of valid bytes in the primary buffer.
     * @param secondary The optional secondary mono PCM16 source buffer. If null, only the primary source will be processed.
     * @param secondaryBytes The number of valid bytes in the secondary buffer. Ignored if [secondary] is null.
     * @param output The output buffer where the processed audio data will be written.
     * @return number of valid bytes written to [output]. 0 if no valid data was available to process.
     */
    fun process(
        primary: ByteBuffer,
        primaryBytes: Int,
        secondary: ByteBuffer?,
        secondaryBytes: Int,
        output: ByteBuffer
    ): Int {
        // Clear the output buffer before writing, in case it contains any leftover data from previous processing.
        output.clear()

        if (secondary == null) {
            return copyMono(
                source = primary,
                byteCount = primaryBytes,
                output = output
            )
        }

        /*
         * FRAME ALIGNMENT MAGIC:
         * PCM16 means every audio sample is 2 bytes long (AudioConfig.PCM_BYTES_PER_SAMPLE). If we get an odd number of bytes,
         * for X obscure reason, processing the last byte will corrupt the audio stream (half a sample).
         *
         * "and -AudioConfig.PCM_BYTES_PER_SAMPLE" (which is `and -2`) acts as a bitwise mask.
         * -2 in binary is ...11111110. ANDing this clears the last bit, guaranteeing an even number.
         */
        val usableBytes = minOf(primaryBytes, secondaryBytes) and -AudioConfig.PCM_BYTES_PER_SAMPLE
        if (usableBytes <= 0) {
            AppLogger.v("Usable bytes for processing is zero or negative ($usableBytes). Skipping processing.")
            return 0
        }

        // Apply VAD ducking to the primary and secondary buffers before mixing/interleaving. Make audio louder when necessary.
        // This make it easier to hear, and also prevent that the two stream, when mixed, result in a too loud output / saturation.
        vadDuckingProcessor.process(primary, secondary, usableBytes)
        return if (plan.isAudioChannelSplitEnabled) {
            interleaveStereo(
                primary = primary,
                secondary = secondary,
                byteCount = usableBytes,
                output = output
            )
        } else {
            mixMono(
                primary = primary,
                secondary = secondary,
                byteCount = usableBytes,
                output = output
            )
        }
    }

    /**
     * Copies complete mono PCM16 frames into the output buffer.
     */
    private fun copyMono(
        source: ByteBuffer,
        byteCount: Int,
        output: ByteBuffer
    ): Int {
        // Ensure valid (2 byte) samples count
        val usableBytes = byteCount and -AudioConfig.PCM_BYTES_PER_SAMPLE
        require(output.remaining() >= usableBytes) { "Output buffer is too small (${output.remaining()} < $usableBytes bytes) for mono PCM block." }

        val oldLimit = source.limit()
        val oldPosition = source.position()

        source.limit(oldPosition + usableBytes)
        output.put(source)

        // Restore source state just in case the caller needs it
        source.limit(oldLimit)
        source.position(oldPosition)

        return usableBytes
    }

    /**
     * Mixes two mono PCM16 streams into one mono PCM16 stream.
     */
    private fun mixMono(
        primary: ByteBuffer,
        secondary: ByteBuffer,
        byteCount: Int,
        output: ByteBuffer
    ): Int {
        require(output.remaining() >= byteCount) { "Output buffer is too small (${output.remaining()} < $byteCount bytes) for mono mix." }
        require(primary.remaining() >= byteCount) { "Primary buffer is too small (${primary.remaining()} < $byteCount bytes) for mono mix." }
        require(secondary.remaining() >= byteCount) { "Secondary buffer is too small (${secondary.remaining()} < $byteCount bytes) for mono mix." }

        // Calculate the number of samples (shorts) to process in the total byteCount
        val shortCount = byteCount / AudioConfig.PCM_BYTES_PER_SAMPLE

        val primaryShorts = primary.asShortBuffer()
        val secondaryShorts = secondary.asShortBuffer()
        val outputShorts = output.asShortBuffer()

        // Apply the gain and mix
        for (i in 0 until shortCount) {
            // Apply gain to each sample before mixing
            val primarySample = primaryShorts.get(i).toInt()
            val secondarySample = secondaryShorts.get(i).toInt()

            // --- SOFT CLIPPING ALGORITHM ---
            val mixed = if (primarySample < 0 && secondarySample < 0) {
                // Both are negative: Blend to prevent clipping on the low end
                (primarySample + secondarySample) - ((primarySample * secondarySample) / Short.MIN_VALUE.toInt())
            } else if (primarySample > 0 && secondarySample > 0) {
                // Both are positive: Blend to prevent clipping on the high end
                (primarySample + secondarySample) - ((primarySample * secondarySample) / Short.MAX_VALUE.toInt())
            } else {
                // One positive, one negative: They naturally cancel each other out safely
                primarySample + secondarySample
            }

            // Clamp to Short range directly in the loop
            val clamped = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            outputShorts.put(i, clamped)
        }

        // Advance the underlying output byte buffer position manually
        // because ShortBuffer views do not mutate the parent position.
        output.position(output.position() + byteCount)
        return byteCount
    }

    /**
     * Interleaves two mono streams into stereo PCM16.
     *
     * Primary becomes LEFT.
     * Secondary becomes RIGHT.
     */
    private fun interleaveStereo(
        primary: ByteBuffer,
        secondary: ByteBuffer,
        byteCount: Int,
        output: ByteBuffer
    ): Int {
        val outputBytes = byteCount * 2 // 2 channels = stereo
        require(output.remaining() >= outputBytes) { "Output buffer is too small (${output.remaining()} < $outputBytes bytes) for stereo output." }

        val shortCount = byteCount / AudioConfig.PCM_BYTES_PER_SAMPLE

        val primaryShorts = primary.asShortBuffer()
        val secondaryShorts = secondary.asShortBuffer()
        val outputShorts = output.asShortBuffer()

        for (i in 0 until shortCount) {
            val primarySample = primaryShorts.get(i)
            val secondarySample = secondaryShorts.get(i)

            // Calculate the stereo index (Left, Right, Left, Right...)
            val outIndex = i * 2

            // Write Left (primary) and Right (secondary) samples
            outputShorts.put(outIndex, primarySample)
            outputShorts.put(outIndex + 1, secondarySample)
        }

        // Advance the underlying output byte buffer's position
        output.position(output.position() + outputBytes)
        return outputBytes
    }
}