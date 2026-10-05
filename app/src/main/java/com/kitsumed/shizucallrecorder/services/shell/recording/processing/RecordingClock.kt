/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.shell.recording.processing

import com.kitsumed.shizucallrecorder.services.shell.recording.AudioConfig

/**
 * The logical recording clock, time based on audio frames, not wall-clock time.
 *
 * This is what allows pause/resume to produce contiguous timestamps.
 */
class RecordingClock {
    private var processedFrames = 0L

    /**
     * Returns the current presentation time in microseconds, based on the number of processed frames.
     */
    fun currentPresentationTimeUs(): Long {
        return (processedFrames * 1_000_000L) / AudioConfig.SAMPLE_RATE
    }

    /**
     * Advances the clock by the given number of frames.
     *
     * @param frameCount The number of frames to advance the clock by. Must be non-negative.
     * @throws IllegalArgumentException if [frameCount] is negative.
     */
    fun advance(frameCount: Long) {
        require(frameCount >= 0) {
            "frameCount must not be negative."
        }

        processedFrames += frameCount
    }

    /**
     * Resets the clock to zero.
     */
    fun reset() {
        processedFrames = 0L
    }
}