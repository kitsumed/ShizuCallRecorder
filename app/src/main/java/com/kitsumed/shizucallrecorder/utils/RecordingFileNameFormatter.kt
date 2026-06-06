/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.utils

import android.content.Context
import android.icu.text.SimpleDateFormat
import androidx.annotation.StringRes
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioCodec
import java.util.Date
import java.util.Locale

object RecordingFileNameFormatter {
    const val TAG = "SCR:RecordingFileNameFormatter"
    /**
     * Represents the supported placeholders that can be used in the file name template.
     * Binds the literal tag used in formatting to a localized description for the UI.
     * @param tag The literal placeholder string that will be replaced in the template (e.g., "{date}").
     * @param descriptionResId The string resource ID for the description of this placeholder
     */
    enum class FileNamePlaceholder(val tag: String, @param:StringRes val descriptionResId: Int) {
        DATE("{date}", R.string.placeholder_date_desc),
        DIRECTION("{direction}", R.string.placeholder_direction_desc),
        PHONE_NUMBER("{phone_number}", R.string.placeholder_phone_number_desc),
        CONTACT_NAME("{contact_name}", R.string.placeholder_contact_name_desc),
        CROSS_COUNTRY("{cross_country}", R.string.placeholder_cross_country_desc)
    }

    /**
     * Formats a filename based on the user defined string template and the recording metadata and audio codec.
     * Supported placeholders:
     * - {date}: The current date and time
     * - {direction}: The call direction (in/out)
     * - {phone_number}: The best available phone number
     * - {contact_name}: The contact name, if available
     * - {cross_country}: true/false indicating if the call is cross-country
     *
     * @param context The context needed to resolve contacts and read preferences.
     * @param metadata Defines the main properties (direction, phone number, cross country).
     * @param codec The selected ScrcpyAudioCodec used to determine the file extension.
     * @param customFormat An optional custom format string to use instead of the one from preferences. Useful for testing or one-off formatting without changing user settings.
     * @return A filesystem-safe filename string.
     */
    fun formatFileName(
        context: Context,
        metadata: EnrichedCallData,
        codec: ScrcpyAudioCodec,
        customFormat: String? = null
    ): String {
        val template = customFormat ?: AppPreferences(context).getFileNameTemplate()

        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss.SSSZ", Locale.CANADA).format(Date())

        val directionStr = when (metadata.direction) {
            CallDirection.INCOMING -> "in"
            CallDirection.OUTGOING -> "out"
        }

        val phoneStr = metadata.getBestNumber()
        var contactStr = ""

        if (template.contains(FileNamePlaceholder.CONTACT_NAME.tag) && phoneStr.isNotEmpty()) {
            contactStr = metadata.contactName ?: ""
        }

        val crossCountryStr = metadata.isCrossCountry.toString()

        val baseName = template
            .replace(FileNamePlaceholder.DATE.tag, dateStr)
            .replace(FileNamePlaceholder.DIRECTION.tag, directionStr)
            .replace(FileNamePlaceholder.PHONE_NUMBER.tag, phoneStr)
            .replace(FileNamePlaceholder.CONTACT_NAME.tag, contactStr)
            .replace(FileNamePlaceholder.CROSS_COUNTRY.tag, crossCountryStr)

        AppLogger.v(TAG, "Formatted base filename: '$baseName' with template '$template'")
        return "$baseName${codec.containerExtension}"
    }
}
