/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.utils

import android.content.Context
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.MetadataLoader
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * A singleton manager for handling phone number parsing and validation, also include an implementation of Google libphonenumber library for Android.
 * To comply with FAQ recommendations most methods are suspend functions to ensure they do not block the main UI thread.
 * https://github.com/google/libphonenumber/blob/master/FAQ.md#system-considerations
 */
class PhoneNumberManager private constructor(context: Context) {
    /** Store the application context to avoid passing it around and to prevent memory leaks. */
    private val appContext: Context = context.applicationContext
    private val phoneUtil: PhoneNumberUtil

    /**
     * Initializes the PhoneNumberUtil instance.
     * This instance overrides the default metadata loading mechanism to load from the app's assets,
     * as recommended by the FAQ at https://github.com/google/libphonenumber/blob/master/FAQ.md#optimize-loads for Android Apps
     */
    init {
        val assetLoader = MetadataLoader { metadataFileName ->
            // The library passes paths like "/com/google/i18n/phonenumbers/data/PhoneNumberMetadataProto_US".
            // Since our Gradle task flattened the files into "phonenumber_data/", we just need the file name.
            val fileName = metadataFileName.substringAfterLast("/")
            appContext.assets.open("phonenumber_data/$fileName")
        }
        phoneUtil = PhoneNumberUtil.createInstance(assetLoader)
    }

    companion object {
        private const val TAG = "SCR:PhoneNumberManager"

        // Singleton instance management
        @Volatile
        private var INSTANCE: PhoneNumberManager? = null

        /**
         * Gets the singleton instance of PhoneNumberManager.
         */
        fun getInstance(context: Context): PhoneNumberManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PhoneNumberManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Normalizes a phone number by removing all non-digit characters, while preserving a leading '+' if present.
         * Example: "+1 (202) 555-0173" would become "+12025550173", and "202-555-0173" would become "2025550173".
         *
         * Also performs a very basic check for anonymous tokens, I have yet to personally see them, but I have read online about it.
         * When it matches one, it returns an empty string since that's how we expect to see anonymous numbers.
         */
        fun normalisePhoneNumber(phoneNumber: String): String {
            val trimmed = phoneNumber.trim().lowercase()

            val anonymousTokens = listOf("unknown", "private", "anonymous", "+anonymous", "+", "#")
            if (trimmed in anonymousTokens) {
                return ""
            }

            val digits  = trimmed.filter { it.isDigit() }
            return if (trimmed.startsWith("+")) "+$digits" else digits
        }
    }

    /**
     * Determines the device's country ISO code using a multi-step approach:
     * 1. Network Country ISO: Based on the current cellular network, which reflects the user's physical location.
     * 2. SIM Country ISO: Based on the SIM card's home country, which is useful if the user is offline but has a SIM card.
     * 3. Locale Country: Based on the device's default locale, which serves as a fallback for tablets or devices in airplane mode without SIM cards.
     * @param context The context used to access the TelephonyManager. **Use the application context to avoid memory leaks**.
     * @return The determined country ISO code in uppercase (e.g., "US", "GB"). If all methods fail, it will return an empty string.
     */
    fun getDeviceCountryIso(): String {
        val telephonyManager = appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val networkIso = runCatching { telephonyManager.networkCountryIso }.getOrNull()
        if (!networkIso.isNullOrBlank()) return networkIso.lowercase()

        val simIso = runCatching { telephonyManager.simCountryIso }.getOrNull()
        if (!simIso.isNullOrBlank()) return simIso.lowercase()

        return Locale.getDefault().country.lowercase()
    }

    /**
     * Parses a phone number string into a structured [Phonenumber.PhoneNumber] object using the specified default region.
     * @param rawNumber The raw phone number string to parse (e.g., "202-555-0173").
     * @param defaultRegion The default region ISO code to use for parsing (e.g., "US"). If not provided, it will default to the device's country ISO.
     * @return A [Phonenumber.PhoneNumber] object if parsing is successful, or null if parsing fails.
     */
    suspend fun parsePhoneNumber(rawNumber: String, defaultRegion: String = getDeviceCountryIso()): Phonenumber.PhoneNumber? = withContext(Dispatchers.Default) {
        return@withContext try {
            phoneUtil.parse(rawNumber, defaultRegion.uppercase())
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error parsing phone number: ${e.message}", e)
            null
        }
    }

    /**
     * Formats the provided phone number into E.164 format if it is valid.
     * @param phoneNumber The structured phone number object to format.
     * @return The formatted phone number in E.164 format.
     */
    suspend fun formatToE164(phoneNumber: Phonenumber.PhoneNumber): String? = withContext(Dispatchers.Default) {
        return@withContext phoneUtil.format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    /**
     * Extracts the region code (ISO country code) from a phone number.
     * @param phoneNumber The phone number to check.
     * @return The region code (e.g., "FR", "US").
     */
    suspend fun getRegionCode(phoneNumber: Phonenumber.PhoneNumber): String? = withContext(Dispatchers.Default) {
        return@withContext phoneUtil.getRegionCodeForNumber(phoneNumber)
    }

    /**
     * Checks if a phone number belongs to a different country than the one provided.
     * Use this to detect if a call is "Cross-Region".
     * @param phoneNumber The incoming/outgoing phone number object.
     * @param compareCountryIso The ISO code to compare against (e.g., your home country).
     * @return True if the number's region is different from the provided ISO code, false if it's the same or if the number is invalid.
     */
    suspend fun isNumberFromDifferentCountry(phoneNumber: Phonenumber.PhoneNumber, compareCountryIso: String = getDeviceCountryIso()): Boolean {
        val numberRegion = getRegionCode(phoneNumber)
        return numberRegion != null && !numberRegion.equals(compareCountryIso, ignoreCase = true)
    }
}
