/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.data.recording

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.kitsumed.shizucallrecorder.R

/**
 * The audio codecs supported by the app.
 *
 * @property key                Value used in SharedPreferences to identify the codec.
 * @property defaultBitRate     Recommended bit rate in bps.
 * @property outputFormat       [MediaMuxer.OutputFormat] constant for the output container.
 * @property mimeType           MIME type for SAF file creation. E.g. [MediaFormat.MIMETYPE_AUDIO_OPUS] or [MediaFormat.MIMETYPE_AUDIO_AAC].
 * @property containerExtension Output file extension including the dot (e.g. ".ogg").
 * @property titleResId         String resource ID for the display in the UI.
 * @property isStereoOnly       If true, this codec only supports stereo audio and SHOULD NOT be used for mono.
 * @property profile            Optional codec profile [MediaCodecInfo.CodecProfileLevel]. Null for OPUS. Currently only used for AAC variants.
 * @property minStereoBitRate   Optional minimum bit rate for stereo audio. Stereo often requires higher bitrate to prevent encoding issues.
 */
enum class RecordingCodec(
    val key: String,
    val defaultBitRate: Int,
    val outputFormat: Int,
    val mimeType: String,
    val containerExtension: String,
    val titleResId: Int,
    val isStereoOnly: Boolean = false,
    val profile: Int? = null,
    val minStereoBitRate: Int? = null,
) {
    OPUS(
        key = "opus",
        defaultBitRate = 16_000,
        outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
        mimeType = MediaFormat.MIMETYPE_AUDIO_OPUS,
        containerExtension = ".ogg",
        titleResId = R.string.audio_codec_opus,
        isStereoOnly = false,
        // Lower bitrate create "mono" files or weird bugs.
        // https://wiki.xiph.org/Opus_Recommended_Settings#:~:text=Bandwidth%20Transition%20Thresholds,-The
        minStereoBitRate = 32_000,
    ),
    AAC(
        key = "aac",
        defaultBitRate = 32_000,
        outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
        containerExtension = ".m4a",
        titleResId = R.string.audio_codec_aac,
        isStereoOnly = false,
        profile = MediaCodecInfo.CodecProfileLevel.AACObjectLC,
        // Can go 32_000, but there is a lot of sound quality issues.
        minStereoBitRate = 64_000,
    ),
    // Not all devices support HE-AAC v1. See https://developer.android.com/media/platform/supported-formats
    HE_AAC_V1(
        key = "he_aac_v1",
        defaultBitRate = 12_000,
        outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        // When MediaFormat.MIMETYPE_AUDIO_AAC_HE_V1, the encoder may not be found on some devices, so we use MIMETYPE_AUDIO_AAC instead.
        mimeType = MediaFormat.MIMETYPE_AUDIO_AAC, // MediaFormat.MIMETYPE_AUDIO_AAC_HE_V1,
        containerExtension = ".m4a",
        titleResId = R.string.audio_codec_he_aac_v1,
        isStereoOnly = false,
        profile = MediaCodecInfo.CodecProfileLevel.AACObjectHE,
        // Lower bitrate mute one of the channel or create weird bugs.
        minStereoBitRate = 32_000
    ),
    // HE-AAC v2 only support Stereo and will throw if initialized with mono (1 channel)
    HE_AAC_V2(
        key = "he_aac_v2",
        defaultBitRate = 12_000,
        outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        // When MediaFormat.MIMETYPE_AUDIO_AAC_HE_V2, the encoder may not be found on some devices, so we use MIMETYPE_AUDIO_AAC instead.
        mimeType = MediaFormat.MIMETYPE_AUDIO_AAC, // MediaFormat.MIMETYPE_AUDIO_AAC_HE_V2,
        containerExtension = ".m4a",
        titleResId = R.string.audio_codec_he_aac_v2,
        isStereoOnly = true,
        profile = MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS,
        // TODO: If I can find a device that supports it, try lower bitrates until stereo breaks.
        minStereoBitRate = 10_000
    ),
    // Not all devices support XHE-AAC.
    XHE_AAC(
        key = "xhe_aac",
        defaultBitRate = 16_000,
        outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        mimeType = MediaFormat.MIMETYPE_AUDIO_AAC, // MediaFormat.MIMETYPE_AUDIO_AAC_XHE,
        containerExtension = ".m4a",
        titleResId = R.string.audio_codec_xhe_aac,
        isStereoOnly = false,
        profile = MediaCodecInfo.CodecProfileLevel.AACObjectXHE,
        // Cannot test it in my emulator as not available, I see online it can go lower.
        // TODO: If I can find a device that supports it, try lower bitrates until stereo breaks.
        minStereoBitRate = 32_000
    ),
    AMR_WB( // Only supports 16kHz, which is the hard-coded output we use. Stereo also works, but should not?
        key = "amr_wb",
        defaultBitRate = 12_000,
        outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP,
        mimeType = MediaFormat.MIMETYPE_AUDIO_AMR_WB,
        containerExtension = ".3gp",
        titleResId = R.string.audio_codec_amr_wb,
        isStereoOnly = false,
    );

    /**
     * Applies codec-specific settings to the provided [MediaFormat].
     *
     * @param format The [MediaFormat] to which codec-specific settings will be applied.
     */
    fun applyCodecFormat(format: MediaFormat)
    {
        if (mimeType == MediaFormat.MIMETYPE_AUDIO_AAC) {
            // For AAC, set the profile if specified
            profile?.let { format.setInteger(MediaFormat.KEY_AAC_PROFILE, it) }
        } else if (mimeType == MediaFormat.MIMETYPE_AUDIO_FLAC) {
            //
            format.setInteger(MediaFormat.KEY_BIT_RATE, 0)
        }
    }

    /**
     * Checks if the current device has an encoder that supports this codec and profile.
     */
    fun isSupported(): Boolean {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            if (!codecInfo.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) continue

            // If no specific profile is required, finding an encoder with a matching MIME type is enough
            val targetProfile = profile ?: return true

            val capabilities = runCatching { codecInfo.getCapabilitiesForType(mimeType) }.getOrNull() ?: continue
            if (capabilities.profileLevels.any { it.profile == targetProfile }) {
                return true
            }
        }
        return false
    }

    /**
     * Checks if the specified bit rate is supported by any hardware or software encoder on the device for this specific codec and profile.
     * This only checks the encoder capabilities, it does not mean the encoding will work (should work if its mono, stereo may require higher bitrate).
     *
     * @param bitRate The bit rate in bits per second (e.g., 16_000).
     */
    fun isBitRateSupported(bitRate: Int): Boolean {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            if (mimeType !in codecInfo.supportedTypes) continue

            val capabilities = runCatching { codecInfo.getCapabilitiesForType(mimeType) }.getOrNull() ?: continue

            // Ensure this specific encoder supports our required profile (if any)
            if (profile != null && capabilities.profileLevels.none { it.profile == profile }) {
                continue
            }

            // Grab the audio capabilities and check if the bit rate falls within the supported range
            val audioCapabilities = capabilities.audioCapabilities ?: continue
            if (audioCapabilities.bitrateRange.contains(bitRate)) {
                return true
            }
        }

        return false
    }

    companion object {
        /**
         * Returns the RecordingCodec corresponding to the given key.
         * @param key The key representing the desired codec.
         * @returns The codec, if none is found, it defaults to OPUS.
         */
        fun fromKey(key: String): RecordingCodec {
            return entries.find { it.key == key } ?: OPUS // Default to OPUS if not found
        }
    }
}