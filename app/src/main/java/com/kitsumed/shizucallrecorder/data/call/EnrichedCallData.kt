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
 * @param rawPhoneNumber The original phone number string as provided by the OS, which may be in any format or even null/blank.
 * @param formattedE164Number The standardized E.164 format of the phone number, if parsing and formatting were successful.
 * @param direction Whether the call is incoming or outgoing.
 * @param isCrossCountry Whether the call is cross-country.
 * @param contactName The contact name associated with the phone number / call, if available.
 */
@Parcelize
data class EnrichedCallData(
    val rawPhoneNumber: String?,
    val formattedE164Number: String? = null,
    val direction: CallDirection,
    val isCrossCountry: Boolean = false,
    val contactName: String? = null
) : Parcelable {
    /**
     * Returns the best available phone number for display and filename purposes.
     * Try the standardized E.164 number first, then fall back to the raw phone number if necessary.
     */
    fun getBestNumber() = formattedE164Number ?: rawPhoneNumber

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

                // Null or blank phone number is a common occurrence (ex: anonymous caller, third-party VoIP apps)
                if (raw.isNullOrBlank()) {
                    return@withContext EnrichedCallData(
                        rawPhoneNumber = raw,
                        direction = base.direction,
                        isCrossCountry = true, // We should assume it's cross-country to be safe, since we don't know where its from.
                        contactName = base.osProvidedContactName
                    )
                }

                val phoneNumberManager = PhoneNumberManager.getInstance(context)
                val parsedNumber = phoneNumberManager.parsePhoneNumber(raw)
                // If parsing failed
                if (parsedNumber == null) {
                    return@withContext EnrichedCallData(
                        rawPhoneNumber = raw,
                        direction = base.direction,
                        isCrossCountry = true, // If we can't parse the number, we should assume it's cross-country to be safe, since we don't know where it's from.
                        contactName = base.osProvidedContactName
                    )
                }

                // Perform Enrichment & Fetch missing data when possible
                val standardized = phoneNumberManager.formatToE164(parsedNumber)
                val crossCountry = phoneNumberManager.isNumberFromDifferentCountry(parsedNumber)
                var contactName = base.osProvidedContactName
                // If the raw call data did not provide us with a contact name, we attempt a lookup ourselves.
                if (base.osProvidedContactName.isNullOrBlank()) {
                    contactName = getContactName(context, raw)
                    if (contactName != null) {
                        AppLogger.v(TAG, "Found contact name '$contactName' for number '$raw'")
                    } else {
                        AppLogger.v(TAG, "No contact name found for number '$raw'")
                    }
                }

                AppLogger.i(TAG, "Enriched metadata for number: raw='$raw', standardized='$standardized', crossCountry=$crossCountry")
                return@withContext EnrichedCallData(
                    rawPhoneNumber = raw,
                    formattedE164Number = standardized,
                    direction = base.direction,
                    isCrossCountry = crossCountry,
                    contactName = contactName
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