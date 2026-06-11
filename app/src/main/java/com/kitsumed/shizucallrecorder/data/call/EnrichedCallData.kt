/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.data.call

import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.provider.ContactsContract
import com.kitsumed.shizucallrecorder.system.permissions.PermissionChecks
import com.kitsumed.shizucallrecorder.utils.AppLogger
import com.kitsumed.shizucallrecorder.utils.PhoneNumberManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize

/**
 * Carries the enriched metadata associated with a single call that is being (or will be) recorded.
 * @param normalisedPhoneNumber The normalized original phone number string [PhoneNumberManager.normalisePhoneNumber], which may be in an empty string if anonymous.
 * @param formattedE164Number The standardized E.164 format of the phone number, if parsing and formatting were successful.
 * @param direction Whether the call is incoming or outgoing.
 * @param isCrossCountry Whether the call is cross-country.
 * @param callerName The caller/contact name associated with the phone number in the user contacts, then fallback to Telecom/CallerID name, if available.
 */
@Parcelize
data class EnrichedCallData(
    val normalisedPhoneNumber: String,
    val formattedE164Number: String? = null,
    val direction: CallDirection,
    val isCrossCountry: Boolean = false,
    val callerName: String? = null,
    val packageName: String? = null
) : Parcelable {
    /**
     * Returns the best available phone number for display and filename purposes.
     * Try the standardized E.164 number first, then fall back to the normalized phone number if necessary.
     */
    fun getBestNumber() = formattedE164Number ?: normalisedPhoneNumber

    companion object {
        const val TAG = "SCR:EnrichedCallData"

        /**
         * The key used to pass RecordingMetadata in an Intent when starting the recording service.
         */
        const val EXTRA_METADATA = "com.kitsumed.shizucallrecorder.EXTRA_RECORDING_METADATA"

        /**
         * Attempts to enrich the provided RawCallData with additional information.
         */
        suspend fun enrichMetadata(context: Context, base: RawCallData): EnrichedCallData =
            withContext(Dispatchers.Default) {
                val raw = base.rawPhoneNumber

                // Blank phone number is a common occurrence (ex: anonymous caller, third-party VoIP apps)
                if (raw.isBlank()) {
                    return@withContext EnrichedCallData(
                        normalisedPhoneNumber = "",
                        direction = base.direction,
                        isCrossCountry = true, // We should assume it's cross-country to be safe, since we don't know where its from.
                        callerName = base.osProvidedCallerName,
                        packageName = base.packageName
                    )
                }

                val phoneNumberManager = PhoneNumberManager.getInstance(context)
                val parsedNumber = phoneNumberManager.parsePhoneNumber(raw)
                // If parsing failed
                if (parsedNumber == null) {
                    return@withContext EnrichedCallData(
                        normalisedPhoneNumber = PhoneNumberManager.normalisePhoneNumber(raw), // Safety normalizing, the number should already have been.
                        direction = base.direction,
                        isCrossCountry = true, // If we can't parse the number, we should assume it's cross-country to be safe, since we don't know where it's from.
                        callerName = base.osProvidedCallerName,
                        packageName = base.packageName
                    )
                }

                // Perform Enrichment & Fetch missing data when possible
                val standardized = phoneNumberManager.formatToE164(parsedNumber)
                val crossCountry = phoneNumberManager.isNumberFromDifferentCountry(parsedNumber)
                var callerName = base.osProvidedCallerName
                // If the raw call data did not provide us with a contact name, we attempt a lookup ourselves.
                if (base.osProvidedCallerName.isNullOrBlank()) {
                    callerName = getContactName(context, raw)
                    if (callerName != null) {
                        AppLogger.v(TAG, "Found contact name '$callerName' for number '$raw'")
                    } else {
                        AppLogger.v(TAG, "No contact name found for number '$raw'")
                    }
                }

                AppLogger.v(TAG, "Enriched metadata for number: raw='$raw', standardized='$standardized', crossCountry=$crossCountry, callerName='$callerName'")
                return@withContext EnrichedCallData(
                    normalisedPhoneNumber = PhoneNumberManager.normalisePhoneNumber(raw), // Safety normalizing, the number should already have been.
                    formattedE164Number = standardized,
                    direction = base.direction,
                    isCrossCountry = crossCountry,
                    callerName = callerName,
                    packageName = base.packageName
                )
            }

        /**
         * Looks up the contact name associated with a given phone number, if the app has permission to read contacts.
         *
         * @param context The context used to access the ContentResolver and check permissions.
         * @param phoneNumber The phone number to look up, in any format (e.g. raw or E.164).
         * @return The contact name if found and permission is granted, or null otherwise.
         */
        private fun getContactName(context: Context, phoneNumber: String): String? {
            if (!PermissionChecks.hasContactsPermission(context)) return null

            val lookupUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

            return context.contentResolver.query(lookupUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        cursor.getString(nameIndex)
                    } else null
                } else null
            }
        }

    }
}