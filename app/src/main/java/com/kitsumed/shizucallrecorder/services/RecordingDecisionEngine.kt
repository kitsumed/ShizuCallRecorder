/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import com.kitsumed.shizucallrecorder.data.call.RawCallData
import com.kitsumed.shizucallrecorder.services.recording.RecordingForegroundService
import com.kitsumed.shizucallrecorder.system.permissions.PermissionChecks
import com.kitsumed.shizucallrecorder.utils.AppLogger
import com.kitsumed.shizucallrecorder.utils.PhoneNumberManager

/**
 * RecordingDecisionEngine is responsible for making autonomous decisions about call recording.
 *
 * Every call detections methods must use this class to initiate the [RecordingForegroundService].
 *
 * It transforms raw call data into enriched metadata, evaluates user preferences and ignore lists,
 * and fires appropriate intents to the [RecordingForegroundService] to start recording or enter standby mode.
 *
 * @see RecordingForegroundService
 * @see EnrichedCallData
 */
class RecordingDecisionEngine private constructor(context: Context) {

    companion object {
        private const val TAG = "SCR:RecordingDecisionEngine"

        // Singleton instance management
        @Volatile
        private var INSTANCE: RecordingDecisionEngine? = null

        /**
         * Provides a thread-safe singleton instance of [RecordingDecisionEngine].
         * The first call initializes the instance with the provided context,
         * and subsequent calls return the same instance.
         */
        fun getInstance(context: Context): RecordingDecisionEngine {
            return INSTANCE ?: synchronized(this) {
                // We use applicationContext to avoid accidentally leaking an Activity or Service context, causing memory leaks.
                val safeContext = context.applicationContext
                INSTANCE ?: RecordingDecisionEngine(safeContext).also { INSTANCE = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val appPreferences = AppPreferences(appContext)

    init {
        AppLogger.d(TAG, "RecordingDecisionEngine initialised")
    }

    /**
     * Executes the recording decision pipeline for incoming raw call data.
     *
     * This method orchestrates the full flow:
     * 1. Transforms RawCallData into EnrichedCallData (sanitize number, format E164, check cross-country)
     * 2. Evaluates shouldAutoRecord() and ignore lists using the enriched data
     * 3. Fires the appropriate Intent to [com.kitsumed.shizucallrecorder.services.recording.RecordingForegroundService]:
     *    - [com.kitsumed.shizucallrecorder.services.recording.RecordingForegroundService.Companion.ACTION_START_RECORDING] if auto-record is enabled
     *    - [com.kitsumed.shizucallrecorder.services.recording.RecordingForegroundService.Companion.ACTION_STANDBY] otherwise
     *
     * @param rawData The [RawCallData] received from the OS or third-party source
     * @return true is an Intent to start/standby the [com.kitsumed.shizucallrecorder.services.recording.RecordingForegroundService] was fired successfully, false if there was an error
     */
    suspend fun executeDecisionPipeline(rawData: RawCallData): Boolean {
        AppLogger.i(TAG, "Starting recording decision pipeline for ${rawData.direction} call")

        // Step 1: Transform to EnrichedCallData
        val enrichedData = EnrichedCallData.enrichMetadata(appContext, rawData)
        AppLogger.d(TAG, "Enriched call data: BestNumber=${enrichedData.getBestNumber()}, crossCountry=${enrichedData.isCrossCountry}")

        // Step 2: Evaluate recording decision
        val shouldRecord = shouldAutoRecord(enrichedData)
        AppLogger.i(TAG, "Recording decision for ${enrichedData.direction} call: shouldRecord=$shouldRecord")

        // Step 3: Fire appropriate Intent
        return fireRecordingServiceIntent(enrichedData, shouldRecord)
    }

    /**
     * Ends the current recording session, firing the STOP intent to [com.kitsumed.shizucallrecorder.services.recording.RecordingForegroundService].
     */
    fun endRecordingSession() {
        AppLogger.d(TAG, "Ending recording session. Sending STOP INTENT to RecordingForegroundService.")

        val intent = Intent(appContext, RecordingForegroundService::class.java).apply {
            action = RecordingForegroundService.ACTION_STOP_RECORDING
        }
        appContext.startService(intent)
    }

    /**
     * Determines whether the current call should be automatically recorded based on user preferences and ignore rules.
     *
     * Evaluates:
     * - Auto-record enablement for the call direction
     * - Anonymous call ignoring (incoming only)
     * - Cross-country call ignoring
     * - Contact-based filtering (ignore selected or all contacts)
     *
     * @param metadata The enriched call data to evaluate
     * @return true if the call should be recorded, false if it should enter standby
     */
    private fun shouldAutoRecord(metadata: EnrichedCallData): Boolean {
        val isAnonymous = metadata.normalisedPhoneNumber.isBlank()

        return when (metadata.direction) {
            CallDirection.INCOMING -> evaluateIncomingCall(metadata, metadata.normalisedPhoneNumber, isAnonymous)
            CallDirection.OUTGOING -> evaluateOutgoingCall(metadata, metadata.normalisedPhoneNumber)
        }
    }

    /**
     * Evaluates recording decision for incoming calls.
     *
     * Checks:
     * - Auto-record enabled for incoming calls
     * - Anonymous call ignoring
     * - Cross-country call ignoring
     * - Contact filter
     */
    private fun evaluateIncomingCall(
        metadata: EnrichedCallData,
        normalisedNumber: String,
        isAnonymous: Boolean
    ): Boolean {
        if (!appPreferences.isAutoRecordIncomingEnabled()) {
            AppLogger.i(TAG, "Auto-record for incoming call is disabled")
            return false
        }

        if (isAnonymous && appPreferences.isIgnoreAnonymousIncomingEnabled()) {
            AppLogger.i(TAG, "Ignoring incoming call: call is anonymous")
            return false
        }

        if (metadata.isCrossCountry && appPreferences.isIgnoreCrossCountryIncomingEnabled()) {
            AppLogger.i(TAG, "Ignoring incoming call: call was detected as cross-country")
            return false
        }

        if (shouldIgnoreContact(
            normalisedNumber,
            appPreferences.getIgnoreContactsModeIncoming(),
            appPreferences.getIgnoredContactsIncoming()
        )
        ) {
            AppLogger.i(TAG, "Ignoring incoming call: contact filter matched")
            return false
        }

        AppLogger.i(TAG, "Auto-record enabled for this incoming call")
        return true
    }

    /**
     * Evaluates recording decision for outgoing calls.
     *
     * Checks:
     * - Auto-record enabled for outgoing calls
     * - Cross-country call ignoring
     * - Contact filter
     */
    private fun evaluateOutgoingCall(
        metadata: EnrichedCallData,
        normalisedNumber: String
    ): Boolean {
        if (!appPreferences.isAutoRecordOutgoingEnabled()) {
            AppLogger.i(TAG, "Auto-record for outgoing call is disabled")
            return false
        }

        if (metadata.isCrossCountry && appPreferences.isIgnoreCrossCountryOutgoingEnabled()) {
            AppLogger.i(TAG, "Ignoring outgoing call: call was detected as cross-country")
            return false
        }

        if (shouldIgnoreContact(
            normalisedNumber,
            appPreferences.getIgnoreContactsModeOutgoing(),
            appPreferences.getIgnoredContactsOutgoing()
        )
        ) {
            AppLogger.i(TAG, "Ignoring outgoing call: contact filter matched")
            return false
        }

        AppLogger.i(TAG, "Auto-record enabled for this outgoing call")
        return true
    }

    /**
     * Determines whether a call from/to a specific phone number should be ignored
     * based on the user contact filtering preferences.
     *
     * @param normalisedNumber The normalized phone number to check
     * @param mode The contact ignore mode (NONE, ALL, or SELECTED)
     * @param ignoredNumbers Set of phone numbers to check against (for SELECTED mode)
     * @return true if the call should be ignored, false otherwise
     */
    private fun shouldIgnoreContact(
        normalisedNumber: String,
        mode: AppPreferences.IgnoreContactsMode,
        ignoredNumbers: Set<String>
    ): Boolean {
        return when (mode) {
            AppPreferences.IgnoreContactsMode.NONE -> false

            AppPreferences.IgnoreContactsMode.ALL -> {
                if (!PermissionChecks.hasContactsPermission(appContext)) {
                    false
                } else {
                    val lookupUri = Uri.withAppendedPath(
                        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                        Uri.encode(normalisedNumber)
                    )
                    appContext.contentResolver.query(
                        lookupUri,
                        arrayOf(ContactsContract.PhoneLookup._ID),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        cursor.moveToFirst()
                    } ?: false
                }
            }

            AppPreferences.IgnoreContactsMode.SELECTED ->
                ignoredNumbers.any { PhoneNumberManager.Companion.normalisePhoneNumber(it) == normalisedNumber }
        }
    }

    /**
     * Fires an Intent to [RecordingForegroundService] with the appropriate action.
     *
     * @param enrichedData The enriched call data to attach to the intent
     * @param shouldRecord If true, fires ACTION_START_RECORDING; otherwise ACTION_STANDBY
     * @return true if the intent was fired successfully, false if there was an error
     */
    private fun fireRecordingServiceIntent(enrichedData: EnrichedCallData, shouldRecord: Boolean): Boolean {
        val action = if (shouldRecord) {
            AppLogger.i(TAG, "Firing Intent: ACTION_START_RECORDING for ${enrichedData.direction} call")
            RecordingForegroundService.ACTION_START_RECORDING
        } else {
            AppLogger.i(TAG, "Firing Intent: ACTION_STANDBY for ${enrichedData.direction} call")
            RecordingForegroundService.ACTION_STANDBY
        }

        val intent = Intent(appContext, RecordingForegroundService::class.java).apply {
            this.action = action
            putExtra(EnrichedCallData.EXTRA_METADATA, enrichedData)
        }

        try {
            appContext.startForegroundService(intent)
            AppLogger.d(TAG, "Intent fired successfully")
            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to fire intent to RecordingForegroundService", e)
        }
        return false
    }
}