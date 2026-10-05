/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.shell.recording.capture

import com.kitsumed.shizucallrecorder.data.recording.CapturePlan
import com.kitsumed.shizucallrecorder.services.shell.recording.AudioConfig
import java.nio.ByteBuffer

/**
 * Interface representing an audio capture mechanism based on a specific [CapturePlan].
 *
 * The capture implementation MUST use [AudioConfig] as its reference for audio configuration to ensure the processing and encoding happen without any error.
 *
 * @param T The type of [CapturePlan] associated with this audio capture instance.
 */
interface IAudioCapture<out T : CapturePlan> {

    /**
     * The capture plan (config) associated with this audio capture instance.
     */
    val plan: T

    /**
     * Starts the audio capture.
     * @throws Exception Any exception that may occur during the initialization and starting process.
     */
    fun start()

    /**
     * Reads audio data directly into a [ByteBuffer]. **It is a blocking read**.
     *
     * The buffer must be allocated with sufficient capacity to hold the requested number of bytes.
     *
     * @param buffer The buffer to read into.
     * @param size The number of bytes to read.
     * @return number of bytes read, 0 when none is available, or a negative AudioRecord error code.
     */
    fun read(buffer: ByteBuffer, size: Int): Int

    /**
     * Stops the audio capture.
     * @throws Exception Any exception that may occur during the stopping process will be thrown back after logging.
     */
    fun stop()

    /**
     * Checks whether Android audio framework has dynamically silenced (muted) this capture stream.
     *
     * Silencing typically occurs due to platform privacy rules, concurrent capture restrictions
     * (e.g., a higher-priority service or telephony layer taking over the input device), or background constraints.
     *
     * **Note**: Do not assume that this value will always tell you when the capture is returning silence (zero bytes).
     * The system may return zero bytes for other reasons and not define the capture as "silenced".
     *
     * See the rules for silencing https://source.android.com/docs/core/audio/concurrent
     * @return true if the system has explicitly silenced the active capture stream, false otherwise.
     */
    fun isCaptureSilenced(): Boolean
}