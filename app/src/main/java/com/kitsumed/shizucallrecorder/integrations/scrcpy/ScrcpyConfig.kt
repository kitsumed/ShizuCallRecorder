/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.scrcpy

import android.content.Context
import android.os.Build
import com.kitsumed.shizucallrecorder.BuildConfig
import java.security.SecureRandom

/**
 * ScrcpyConfig centralises all configuration constants and helper functions for interacting
 * with scrcpy-server.
 *
 * What is scrcpy-server?
 * It runs with `app_process`, JVM launch it with the shell user (UID 2000) and provides audio capture
 * capabilities that are not available to normal apps.  We invoke it with `audio=true` and
 * `video=false` so it acts purely as an audio source.
 *
 * References:
 *  • https://github.com/Genymobile/scrcpy/blob/master/doc/develop.md
 *  • https://github.com/Genymobile/scrcpy/blob/master/doc/audio.md
 */
object ScrcpyConfig {

    // -- Build-time constants (injected by Gradle build script)

    /**
     * The scrcpy-server version bundled with this APK (e.g. "3.3.4").
     * Injected at compile time by the Gradle `buildConfigField` in app/build.gradle.kts.
     */
    const val SCRCPY_VERSION: String = BuildConfig.SCRCPY_VERSION

    /**
     * The expected SHA-256 hex digest of the bundled scrcpy-server JAR.
     * Used to verify the JAR's integrity before executing it.
     * Injected at compile time by the Gradle `buildConfigField` in app/build.gradle.kts.
     */
    const val EXPECTED_SERVER_SHA256: String = BuildConfig.SCRCPY_SERVER_SHA256

    /**
     * Returns the absolute path where the scrcpy-server JAR is (or should be) stored on
     * the device's shared storage.
     *
     * Why shared storage (external files dir)?
     * The shell process (UID 2000) cannot access the app's private data directory
     * (/data/data/<package>/...) but CAN access paths under /storage/emulated/0/Android/data/.
     * The external files directory is therefore the only location that is writable by the app
     * AND readable by the shell process without root.
     *
     * Path resolved:
     *  Primary: /storage/emulated/0/Android/data/<pkg>/files/scrcpy-VERSION-server.jar
     *  Fallback: /storage/emulated/0/Android/data/<pkg>/cache/scrcpy-VERSION-server.jar
     *
     * IMPORTANT: The [context] passed here must belong to ShizuCallRecorder, NOT to Shizuku or
     * the shell process.  Using the shell context would produce a path the app cannot write to.
     *
     * @param context The ShizuCallRecorder app context.
     * @return The absolute path to the server JAR file.
     * @throws IllegalStateException if no writable shared-storage path is available.
     */
    fun getServerPath(context: Context): String {
        val folder = context.getExternalFilesDir(null)
            ?: context.externalCacheDir
            ?: throw IllegalStateException(
                "Shared storage unavailable."
            )
        return folder.absolutePath + "/scrcpy-${SCRCPY_VERSION}-server.jar"
    }

    /**
     * Returns a destination path owned by the shell process.
     *
     * Work profiles expose app external storage under paths such as /storage/emulated/10,
     * which the Shizuku shell process may not be able to read. Installing the server under
     * /data/local/tmp keeps the CLASSPATH visible from the process that launches app_process.
     */
    fun getShellServerPath(): String {
        val hashPrefix = EXPECTED_SERVER_SHA256.take(12)
        return "/data/local/tmp/${BuildConfig.APPLICATION_ID}/scrcpy-${SCRCPY_VERSION}-$hashPrefix-server.jar"
    }

    /** The fully qualified main class name inside scrcpy-server.jar. */
    const val SERVER_MAIN_CLASS = "com.genymobile.scrcpy.Server"

    /**See: https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/device/DesktopConnection.java */
    const val SERVER_SOCKET_NAME_PREFIX = "scrcpy_"


    // Audio Format Constants

    /**
     * Audio format constants matching the fixed output format of scrcpy-server.
     *
     * Reference:
     *  https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/audio/AudioConfig.java
     */

    /** Audio sample rate used by scrcpy-server for both Opus and AAC (48 kHz). */
    const val AUDIO_SAMPLE_RATE = 48000

    /** Channel count; scrcpy-server always outputs stereo. */
    const val AUDIO_CHANNELS = 2

    // Helpers methods

    /**
     * Builds the argument list passed to scrcpy-server after the version string.
     *
     * Resulting command (shell-side) looks like:
     *   app_process / com.genymobile.scrcpy.Server 3.3.4 \
     *       log_level=info video=false audio=true control=false \
     *       tunnel_forward=false send_dummy_byte=false scid=<socketName> \
     *       audio_source=<audioSource.cliKey> audio_codec=<audioCodec.cliKey> \
     *       send_device_meta=false send_frame_meta=true send_codec_meta=true \
     *       [audio_bit_rate=<audioBitRate>] [audio_dup=true]
     *
     * @param socketName   8-hex-digit socket identifier parsed by scrcpy as Integer.parseInt(…, 16).
     * @param audioSource  The [ScrcpyAudioSource] entry to capture (e.g. [ScrcpyAudioSource.VOICE_COMMUNICATION]).
     * @param audioCodec   The [ScrcpyAudioCodec] to use for encoding ([ScrcpyAudioCodec.OPUS] or [ScrcpyAudioCodec.AAC]).
     * @param audioBitRate Bit rate in bps; omitted from the argument list if ≤ 0.
     * @return An ordered list of argument strings (excluding "app_process", "/", and the main class).
     */
    fun buildServerArgs(
        socketName: String,
        audioSource: ScrcpyAudioSource,
        audioCodec: ScrcpyAudioCodec,
        audioBitRate: Int
    ): List<String> {
        val args = mutableListOf(
            SCRCPY_VERSION,
            "log_level=info",
            "video=false",
            "audio=true",
            "control=false",
            // tunnel_forward=false: scrcpy-server dials OUR LocalServerSocket (not the reverse).
            // With this mode the server writes no dummy byte before the codec data - the very
            // first bytes on the socket are the 4-byte FourCC (written by Streamer.writeAudioHeader).
            // See DesktopConnection.java in scrcpy-server: the dummy byte is only emitted on the
            // tunnel_forward=true (LocalServerSocket.accept) path.
            "tunnel_forward=false",
            // explicitly suppress the 1-byte result code. We specify it as a double safety
            "send_dummy_byte=false",
            "scid=$socketName",
            "audio_source=${audioSource.cliKey}",
            "audio_codec=${audioCodec.cliKey}",
            // send_device_meta=false: skip the 64-byte device-name prefix in the stream header.
            "send_device_meta=false",
            "send_frame_meta=true",
            "send_stream_meta=true"
        )
        if (audioBitRate > 0) {
            args.add("audio_bit_rate=$audioBitRate")
        }
        // audio_dup=true allows capturing playback audio while another app is already using it.
        // Only supported on API 33+ (Android 13).
        if (audioSource == ScrcpyAudioSource.PLAYBACK && Build.VERSION.SDK_INT >= 33) {
            args.add("audio_dup=true")
        }
        return args
    }

    /**
     * Generates a random socket name for scrcpy-server to use when connecting back to the app.
     * @return [String] A unique and random 8 hex digits (e.g. "scrcpy_1a2b3c4d"). **Does not contains [ScrcpyConfig.SERVER_SOCKET_NAME_PREFIX] as a prefix.**
     */
    fun getRandomSocketName(): String {
        // Produce a unique socket name that scrcpy-server parses with Integer.parseInt(…, 16).
        // The name must be exactly 8 hex digits so it fits inside an int (scrcpy limitation).
        return SecureRandom().nextInt(Int.MAX_VALUE).toString(16).padStart(8, '0')
    }
}
