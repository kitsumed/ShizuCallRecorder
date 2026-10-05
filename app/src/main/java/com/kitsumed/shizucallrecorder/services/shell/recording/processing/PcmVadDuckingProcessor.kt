/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.shell.recording.processing

import com.kitsumed.shizucallrecorder.services.shell.recording.AudioConfig
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A VAD based audio ducker paired with an Auto-Gain Control (AGC) compressor.
 *
 * Independently measures the RMS of two audio streams, applies AGC to normalize volume levels,
 * and applies a dynamic ducking envelope to a stream when voice activity is detected on the opposing stream.
 */
class PcmVadDuckingProcessor {

    companion object {
        // AGC Parameters
        /**
         * Target RMS volume level the AGC attempts to maintain.
         * Math: 20 * log10(2500 / 32767) = -22.3 dBFS
         * 32767 is the maximum amplitude for 16-bit PCM audio (Short.MAX_VALUE)
         */
        private const val TARGET_RMS = 2500f
        /**
         * Minimum audio volume multiplier; acts as a compressor floor to actively reduce loud audio.
         * Math: 20 * log10(0.4) = -8.0 dB
         */
        private const val MIN_AGC_GAIN = 0.4f
        /**
         * Maximum audio volume multiplier; acts as an amplifier ceiling to boost quiet voices.
         * Math: 20 * log10(8.0) = +18.0 dB
         */
        private const val MAX_AGC_GAIN = 8.0f
        /** How fast the AGC reduces gain for loud sounds (per-sample slew rate). */
        private const val AGC_ATTACK_RATE = 0.04f
        /** How fast the AGC recovers gain for quiet sounds (per-sample slew rate). */
        private const val AGC_RELEASE_RATE = 0.004f


        // Transient Limiter Parameters
        /** The gain reduction difference required to trigger the zero-attack limiter. */
        private const val TRANSIENT_THRESHOLD = 1.0f

        /** Aggressive slew rate used to instantly crush sudden loud peaks/saturation (e.g., beeps, loud static noise). */
        private const val TRANSIENT_SLEW_RATE = 0.5f

        // Ducking & VAD Parameters
        /**
         * The attenuation multiplier applied to a ducked stream.
         * Math: 20 * log10(0.45) = -7.0 dB
         */
        private const val TARGET_DUCKING_MULTIPLIER = 0.45f
        /**
         * Absolute minimum RMS threshold to differentiate ambient line noise from actual audio.
         * Math: 20 * log10(55 / 32767) = -55.5 dBFS
         */
        private const val BASE_NOISE_FLOOR = 55f
        /**
         * Signal-to-Noise Ratio proxy required to classify a sound as dynamic speech rather than drone noise.
         * Math: 20 * log10(1.5) = +3.5 dB SNR
         */
        private const val SNR_VOICE_THRESHOLD = 1.5f
        /** Slow adaptation rate to raise the noise floor during continuous sounds (e.g., hums, music). */
        private const val NOISE_ADAPTATION_UP = 0.005f
        /** Fast adaptation rate to drop the noise floor during natural speech pauses. */
        private const val NOISE_ADAPTATION_DOWN = 0.1f


        // --- Envelope Timing Constants ---
        /** Ducking fade-out duration. Fast enough to catch syllables without causing digital clicks. */
        private const val ATTACK_MS = 20
        /** Duration to maintain ducking to bridge the micro-pauses between spoken words. */
        private const val HOLD_MS = 400
        /** Ducking fade-in duration. Smooth linear recovery to prevent volume pumping artifacts. */
        private const val RELEASE_MS = 200

        private const val ATTACK_SAMPLES = (ATTACK_MS * AudioConfig.SAMPLE_RATE) / 1000
        private const val HOLD_SAMPLES = (HOLD_MS * AudioConfig.SAMPLE_RATE) / 1000
        private const val RELEASE_SAMPLES = (RELEASE_MS * AudioConfig.SAMPLE_RATE) / 1000
        private const val ATTACK_STEP = (1.0f - TARGET_DUCKING_MULTIPLIER) / ATTACK_SAMPLES
        private const val RELEASE_STEP = (1.0f - TARGET_DUCKING_MULTIPLIER) / RELEASE_SAMPLES
    }

    /**
     * Represents the distinct phases of the ducking envelope.
     */
    private enum class DuckingState {
        IDLE, ATTACK, HOLD, RELEASE
    }

    private class StreamState {
        var backgroundNoiseRms = BASE_NOISE_FLOOR
        var vadActive = false

        var targetAgcGain = 1.0f
        var currentAgcGain = 1.0f

        var state = DuckingState.IDLE
        var currentDuckingMultiplier = 1.0f
        var holdCounter = 0

        /**
         * Updates dynamic noise floor and calculates Signal-to-Noise Ratio (SNR) proxy for voice detection.
         */
        fun updateVad(rms: Float) {
            backgroundNoiseRms = if (rms > backgroundNoiseRms) {
                backgroundNoiseRms + (rms - backgroundNoiseRms) * NOISE_ADAPTATION_UP
            } else {
                backgroundNoiseRms + (rms - backgroundNoiseRms) * NOISE_ADAPTATION_DOWN
            }
            backgroundNoiseRms = max(backgroundNoiseRms, BASE_NOISE_FLOOR)

            val snr = rms / backgroundNoiseRms
            vadActive = snr > SNR_VOICE_THRESHOLD && rms > BASE_NOISE_FLOOR
        }

        /**
         * Calculates required AGC multiplier to hit the target RMS, returning to unity gain on silence.
         */
        fun updateAgcTarget(rms: Float) {
            targetAgcGain = if (rms > BASE_NOISE_FLOOR) {
                (TARGET_RMS / rms).coerceIn(MIN_AGC_GAIN, MAX_AGC_GAIN)
            } else {
                1.0f
            }
        }
    }

    private val primaryState = StreamState()
    private val secondaryState = StreamState()

    /**
     * Processes identical blocks of PCM audio concurrently, applying cross-ducking and AGC normalization.
     */
    fun process(primary: ByteBuffer, secondary: ByteBuffer, byteCount: Int) {
        require(primary.remaining() >= byteCount) { "Primary buffer is too small for $byteCount bytes." }
        require(secondary.remaining() >= byteCount) { "Secondary buffer is too small for $byteCount bytes." }
        val sampleCount = byteCount / AudioConfig.PCM_BYTES_PER_SAMPLE

        val primaryShorts = primary.asShortBuffer()
        val secondaryShorts = secondary.asShortBuffer()

        // Calculate per-block RMS concurrently
        var primarySumSquares = 0L
        var secondarySumSquares = 0L
        for (i in 0 until sampleCount) {
            val pSample = primaryShorts.get(i).toLong()
            val sSample = secondaryShorts.get(i).toLong()
            primarySumSquares += pSample * pSample
            secondarySumSquares += sSample * sSample
        }
        val primaryRms = sqrt((primarySumSquares / sampleCount.toDouble())).toFloat()
        val secondaryRms = sqrt((secondarySumSquares / sampleCount.toDouble())).toFloat()

        // Evaluate VAD and calculate target AGC per stream
        primaryState.updateVad(primaryRms)
        secondaryState.updateVad(secondaryRms)

        primaryState.updateAgcTarget(primaryRms)
        secondaryState.updateAgcTarget(secondaryRms)

        // Evaluate ducking logic (one source ducked over another)
        val primaryDuckRequested = secondaryState.vadActive && !primaryState.vadActive
        val secondaryDuckRequested = primaryState.vadActive && !secondaryState.vadActive

        // Force release resolves double-talk scenarios immediately
        val primaryForceRelease = primaryState.vadActive
        val secondaryForceRelease = secondaryState.vadActive

        // Apply sample-accurate envelopes and transient limiters
        for (i in 0 until sampleCount) {

            // Process Primary Stream
            processDuckingState(primaryState, primaryDuckRequested, primaryForceRelease)

            // Transient, instantly crush violent transient peaks, otherwise use smooth attack/release
            val pAgcSlew = if (primaryState.targetAgcGain < primaryState.currentAgcGain) {
                if (primaryState.currentAgcGain - primaryState.targetAgcGain > TRANSIENT_THRESHOLD) {
                    TRANSIENT_SLEW_RATE
                } else {
                    AGC_ATTACK_RATE
                }
            } else {
                AGC_RELEASE_RATE
            }
            primaryState.currentAgcGain += (primaryState.targetAgcGain - primaryState.currentAgcGain) * pAgcSlew

            // Combine the AGC boost with the Ducking attenuation
            val pFinalMultiplier = primaryState.currentAgcGain * primaryState.currentDuckingMultiplier

            // Apply multiplier and clamp to the 16-bit PCM max
            val pSample = primaryShorts.get(i).toInt()
            val pAdjusted = (pSample * pFinalMultiplier).toInt()
            // Write back the new audio sample
            primaryShorts.put(i, pAdjusted.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())

            // Process Secondary Stream
            processDuckingState(secondaryState, secondaryDuckRequested, secondaryForceRelease)

            val sAgcSlew = if (secondaryState.targetAgcGain < secondaryState.currentAgcGain) {
                if (secondaryState.currentAgcGain - secondaryState.targetAgcGain > TRANSIENT_THRESHOLD) {
                    TRANSIENT_SLEW_RATE
                } else {
                    AGC_ATTACK_RATE
                }
            } else {
                AGC_RELEASE_RATE
            }
            secondaryState.currentAgcGain += (secondaryState.targetAgcGain - secondaryState.currentAgcGain) * sAgcSlew

            val sFinalMultiplier = secondaryState.currentAgcGain * secondaryState.currentDuckingMultiplier
            val sSample = secondaryShorts.get(i).toInt()
            val sAdjusted = (sSample * sFinalMultiplier).toInt()
            secondaryShorts.put(i, sAdjusted.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
    }

    /**
     * Steps the state machine forward by a single sample.
     */
    private fun processDuckingState(stream: StreamState, duckRequest: Boolean, forceRelease: Boolean) {
        // Double-talk instantly breaks the ducking hold/attack phases
        if (forceRelease) {
            if (stream.state == DuckingState.ATTACK || stream.state == DuckingState.HOLD) {
                stream.state = DuckingState.RELEASE
            }
        } else if (duckRequest) {
            if (stream.state == DuckingState.IDLE || stream.state == DuckingState.RELEASE) {
                stream.state = DuckingState.ATTACK
            } else if (stream.state == DuckingState.HOLD) {
                stream.holdCounter = HOLD_SAMPLES
            }
        }

        when (stream.state) {
            DuckingState.IDLE -> {
                stream.currentDuckingMultiplier = 1.0f // No ducking applied, neutral volume
            }
            DuckingState.ATTACK -> {
                stream.currentDuckingMultiplier -= ATTACK_STEP // Fast fade-out to the target ducking multiplier
                if (stream.currentDuckingMultiplier <= TARGET_DUCKING_MULTIPLIER) {
                    stream.currentDuckingMultiplier = TARGET_DUCKING_MULTIPLIER
                    stream.state = DuckingState.HOLD
                    stream.holdCounter = HOLD_SAMPLES
                }
            }
            DuckingState.HOLD -> {
                stream.currentDuckingMultiplier = TARGET_DUCKING_MULTIPLIER // Maintain the target ducking multiplier
                if (!duckRequest) {
                    // Decrement hold counter only if no new duck request is present
                    stream.holdCounter--
                    if (stream.holdCounter <= 0) {
                        stream.state = DuckingState.RELEASE
                    }
                } else {
                    stream.holdCounter = HOLD_SAMPLES
                }
            }
            DuckingState.RELEASE -> {
                stream.currentDuckingMultiplier += RELEASE_STEP // Gradually restore volume to normal
                if (stream.currentDuckingMultiplier >= 1.0f) {
                    stream.currentDuckingMultiplier = 1.0f
                    stream.state = DuckingState.IDLE
                }
            }
        }
    }
}