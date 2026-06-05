/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import com.kitsumed.shizucallrecorder.data.call.RawCallData
import com.kitsumed.shizucallrecorder.services.recording.RecordingForegroundService
import com.kitsumed.shizucallrecorder.system.permissions.PermissionChecks
import com.kitsumed.shizucallrecorder.utils.AppLogger
import com.kitsumed.shizucallrecorder.utils.PhoneNumberManager
import com.kitsumed.shizucallrecorder.utils.PhoneNumberManager.Companion.normalisePhoneNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CallSessionManager role is to receive telephony state changes, determine call session information (like its direction and metadata),
 * and decide how to start the [RecordingForegroundService] based on user preferences and contact filtering.
 *
 * It is implemented as a singleton that should live until the app process itself die.
 */
class CallSessionManager private constructor(context: Context) {

    // Constants accessible globally
    companion object {
        private const val TAG = "SCR:CallSessionManager"

        // Singleton instance management
        @Volatile
        private var INSTANCE: CallSessionManager? = null

        /**
         * Provides a thread-safe singleton instance of [CallSessionManager].
         * The first call initializes the instance with the provided context, and subsequent calls return the same instance.
         */
        fun getInstance(context: Context): CallSessionManager {
            return INSTANCE ?: synchronized(this) {
                // We use applicationContext so we don't accidentally leak an Activity or Service context, causing memory leaks.
                val safeContext = context.applicationContext
                INSTANCE ?: CallSessionManager(safeContext).also { INSTANCE = it }
            }
        }

        // ── Debug broadcast action constants ────────────────────────────────────────────────
        const val ACTION_DEBUG_IDLE     = "com.kitsumed.shizucallrecorder.action.DEBUG_IDLE"
        const val ACTION_DEBUG_RINGING  = "com.kitsumed.shizucallrecorder.action.DEBUG_RINGING"
        const val ACTION_DEBUG_OFFHOOK  = "com.kitsumed.shizucallrecorder.action.DEBUG_OFFHOOK"
    }

    /**
     * Encapsulates properties tied to a current call session.
     * We can verify if a session is active by checking [isSessionActive], which only happens after the first transition from [TelephonyManager.CALL_STATE_IDLE] (no ongoing call).
     */
    private inner class CallSessionState {
        /**
         * Tracks the metadata of the current continuous call session (including direction and phone number).
         * Also handle early initialization of the Shizuku server when enabled in user preferences.
         *
         * **This field is LOCKED on the very first non-IDLE (null) state transition**  Once set, it CANNOT be updated again until [clear] is called.
         * This prevents scenarios like receiving a new RINGING (incoming call) event while
         * already actively handling a call in OFFHOOK (outgoing) from accidentally overwriting the original
         * call direction and metadata.
         * 
         * The only exception is if the original phone number was null/blank and a new non-blank phone number is received for the same direction.
         */
        var currentMetadata: RawCallData? = null
            set(value) {
                // Only allow updating settings when null, or to reset it back to null.
                if (field == null || value == null) {
                    // Save the direction temporarily to survive possible process death while waiting for the user to answer the call.
                    if (value != null) {
                        temporaryCache.save(value.direction)
                    }

                    field = value
                    return
                }

                val isSameCallDirection = field?.direction == value.direction
                // Verification Window: We had nothing, now we have a real number
                val isLateNumberDiscovery = field?.rawPhoneNumber.isNullOrBlank() && !value.rawPhoneNumber.isNullOrBlank()

                if (isSameCallDirection && isLateNumberDiscovery && !wasRecordingServiceStartIntentSend) {
                    // We are in the same direction, but we previously had a blank/unknown number, and now we have a real number! This is part of the
                    // "Verification Window" flow, where we MAY first receive an anonymous/unknown number, then, WE MAY receive a real number within 500ms. In this case,
                    // it's the same call session, we want to allow updating the phone number. The only exception is if we already sent a start intent to
                    // the RecordingForegroundService, in that case we want to keep the original metadata for consistency between the two, even if it's anonymous.
                    field = value
                } else
                {
                    AppLogger.v(TAG, "Attempted to change ongoing session call metadata from ${field?.direction} to ${value.direction}. This is not allowed since the current session is already locked.")
                }
            }

        /**
         * Flag to track whether we've already sent a recording service intent for the current session.
         * This helps prevent duplicate intents in edge dual-call cases.
         */
        var wasRecordingServiceStartIntentSend: Boolean = false

        /**
         * Indicates whether we are currently in an active call session (i.e., we've seen a non-IDLE state and haven't reset yet).
         * This also means that the [currentMetadata] field is locked until [clear] is called.
         */
        val isSessionActive: Boolean
            get() = currentMetadata != null

        /**
         * Resets all session state properties to their initial values.
         * This should be called when a call ends ([TelephonyManager.CALL_STATE_IDLE]) to ensure no stale data carries over to the next call session.
         */
        fun clear() {
            currentMetadata = null
            wasRecordingServiceStartIntentSend = false
            // We clear the temporary cache to prevent any stale data from being used in future sessions
            temporaryCache.clear()
        }
    }

    /**
     * Hold all the information for the current call session. Initialised only once, it can be managed and updated through its methods and properties.
     */
    private val session: CallSessionState = CallSessionState()
    private val appContext: Context = context.applicationContext
    private val preferences = AppPreferences(appContext)

    private val temporaryCache = CallSessionTemporaryCache(appContext)

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Holds a reference to a pending decision [Job] that is scheduled to run after the 500ms verification window when we receive a blank/anonymous number.
     */
    private var sessionJob: Job? = null


    init {
        AppLogger.d(TAG, "CallSessionManager initialised")
    }

    // Public API

    /**
     * Handles incoming telephony state changes to manage the recording session lifecycle.
     *
     * The process follows these steps:
     * 1. If the received state is IDLE, it means the call has ended. If a session was active, we stop recording and clear the session.
     * 2. If the received state is non-IDLE (RINGING or OFFHOOK), we determinate and lock the call direction (Incoming/Outgoing).
     * 3. When we receive an OFFHOOK state (phone is active) we check the phone number:
     *    - If we receive a state with a null/anonymous phone number, we delay processing by 500ms, this is the "Verification Window".
     *    - If another broadcast containing the real number arrive within 500ms, it takes priority, stops the "Verification Window" and processes immediately.
     *    - If the "Verification Window" expires without receiving a real number, we proceed with the recording using the anonymous number (null).
     *
     * **Call Waiting / Dual-Call Edge Case:**
     * If the user is already in a call and receives a second incoming call (whether accepted or ignored), we might receive
     * a RINGING followed by an OFFHOOK event (even if the user ignore the call! and the OFFHOOK event also contains the ignored phone number).
     * Because of this, we WILL ignore any follow-up OFFHOOK event to prevent editing the current call session metadata with wrong information until we receive a IDLE state.
     * This mean the recording (if started) will capture both calls in a single audio file under the metadata
     * of the initial call. I can't find any reliable way to fix this scenario with the permissions limitations android imposes on us.
     *
     * @param stateString The telephony state string (e.g., [TelephonyManager.EXTRA_STATE_RINGING]).
     * @param phoneNumber The phone number associated with the state change. Could be null, blank, or an OEM anonymous string.
     */
    @Synchronized
    fun handlePhoneState(stateString: String, phoneNumber: String?) {
        val receivedCallState = when (stateString) {
            TelephonyManager.EXTRA_STATE_RINGING  -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK  -> TelephonyManager.CALL_STATE_OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE     -> TelephonyManager.CALL_STATE_IDLE
            else -> return
        }

        AppLogger.i(TAG, "Received new phone state: $stateString (TelephonyManagerINT:$receivedCallState) | Number: ${phoneNumber}")

        // 1. Handle IDLE (Stop, no longer in a call)
        if (receivedCallState == TelephonyManager.CALL_STATE_IDLE) {
            sessionJob?.cancel() // Cancel pending verification window or ongoing session if any
            // Only trigger stop logic if we were previously in an active session. Prevents redundant stop commands on possible repeated IDLE broadcasts.
            if (session.isSessionActive) {
                AppLogger.d(TAG, "Phone state is now idle (call ended). Sending stop INTENT for ${session.currentMetadata?.direction} call to RecordingForegroundService.")
                sendServiceCommand(RecordingForegroundService.ACTION_STOP_RECORDING)
                session.clear()
            }
            return
        }

        // 2. Launch the Coroutine for RINGING or OFFHOOK
        // Canceling the previous job for the verification window logic
        sessionJob?.cancel()
        sessionJob = managerScope.launch {
            processSessionUpdate(receivedCallState, phoneNumber)
        }
    }

    /**
     * Processes the session update logic for RINGING and OFFHOOK states, including metadata parsing, session locking, and handling the verification window for anonymous numbers.
     */
    private suspend fun processSessionUpdate(state: Int, phoneNumber: String?) {
        // 1. Parse the metadata we received by the OS broadcast.
        val rawNumber: String? = PhoneNumberManager.sanitizeOemNumber(phoneNumber)
        val direction = CallDirection.fromCallStateOrNull(state)

        // 2. Previous metadata Restoration Logic (survive process death)
        // If the event is OFFHOOK, and we do not have any metadata in the session yet, it might be because the OS killed our process in the RINGING state,
        // this can happen when the user take too long to answer. It could also be the user making an outgoing call (directly goes to OFFHOOK).
        // NOTE: This is prone to very specific race conditions, but we don't have any solutions. In the future I would like to get rid of this whole file and use hidden api to fetch direction and phone numbers with Shizuku.
        if (state == TelephonyManager.CALL_STATE_OFFHOOK && !session.isSessionActive) {
            val restoredDirection = temporaryCache.restore()
            if (restoredDirection != null) {
                withContext(Dispatchers.Main) {
                    session.currentMetadata = RawCallData(rawNumber, restoredDirection)
                }
            }
        }

        // 3. Try to save the parsed metadata (step 1) to the session if not already defined / locked.
        if (direction != null) {
            withContext(Dispatchers.Main) {
                // This is automatically locked after the first transition from IDLE. Only allow updating the phone number for the same direction.
                session.currentMetadata = RawCallData(rawNumber, direction)
            }
        }

        // 4. Handle OFFHOOK (now in a call) + The Verification Window (Non-blocking delay)
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            // Handle anonymous and blank numbers (due to double broadcast behavior with one having no number)
            if (rawNumber.isNullOrBlank()) {
                AppLogger.d(TAG, "Number is blank or null. May be a anonymous call. Starting 500ms verification window.")
                delay(500)
                // If rawNumber is still blank after delay, we continue as anonymous. If we receive another broadcast, cancel is called and stop this logic here.
                // Meaning we would restart a new 500ms window.
            }

            // Perform metadata ENRICHMENT
            val currentMetadata = session.currentMetadata ?: throw IllegalStateException("Current metadata should not be null at this point. There is a logic error in the flow.")
            val enrichedMetadata = EnrichedCallData.enrichMetadata(appContext, currentMetadata)

            // Move to the Main thread (sync) to be thread-safe
            withContext(Dispatchers.Main) {
                evaluateAndStartService(enrichedMetadata)
            }
        }
    }

    /**
     * Evaluates the enriched metadata against user preferences to decide whether to start recording immediately or go to standby mode
     */
    private fun evaluateAndStartService(enrichedMetadata: EnrichedCallData) {
        if (session.wasRecordingServiceStartIntentSend) {
            AppLogger.d(TAG, "Current call session has already sent a intent that started RecordingForegroundService. Skipping duplicate intent.")
            return
        }

        if (shouldAutoRecord(enrichedMetadata)) {
            AppLogger.i(TAG, "Sending start INTENT for ${enrichedMetadata.direction} call to RecordingForegroundService.")
            sendServiceCommand(RecordingForegroundService.ACTION_START_RECORDING, enrichedMetadata)
        } else {
            AppLogger.i(TAG, "Sending standby INTENT for ${enrichedMetadata.direction} call to RecordingForegroundService.")
            sendServiceCommand(RecordingForegroundService.ACTION_STANDBY, enrichedMetadata)
        }
        session.wasRecordingServiceStartIntentSend = true
    }

    @Synchronized
    fun handleDebugAction(action: String) {
        if (!preferences.isDebugEnabled()) {
            AppLogger.w(TAG, "Received debug action '$action' but debug mode is disabled. Ignoring.")
            return
        }

        val debugPhoneNumber = preferences.getDebugCallerNumber()

        // Pass the action to handlePhoneState to simulate real call flow
        val telephonyExtState = when (action) {
            ACTION_DEBUG_IDLE     -> TelephonyManager.EXTRA_STATE_IDLE
            ACTION_DEBUG_RINGING  -> TelephonyManager.EXTRA_STATE_RINGING
            ACTION_DEBUG_OFFHOOK  -> TelephonyManager.EXTRA_STATE_OFFHOOK
            else -> return
        }

        AppLogger.i(TAG, "Handling debug action: '$action' ($telephonyExtState) with debug number: $debugPhoneNumber")
        handlePhoneState(telephonyExtState, debugPhoneNumber)
    }

    // Private helpers

    /**
     * Builds and fires an Intent to the [RecordingForegroundService].
     */
    private fun sendServiceCommand(action: String, metadata: EnrichedCallData? = null) {
        val intent = Intent(appContext, RecordingForegroundService::class.java).apply {
            this.action = action
            if (metadata != null) {
                putExtra(EnrichedCallData.EXTRA_METADATA, metadata)
            }
        }
        if (action == RecordingForegroundService.ACTION_STOP_RECORDING) {
            // When Stopping, we use StartService as we assume the service is already running from startForegroundService. We only send it a new Intent that trigger cleanup.
            // we don't use stopService as it would give us a very short time to do any cleanup before the OS kill the service and prevent cleanup finalization.
            appContext.startService(intent)
        } else {
            appContext.startForegroundService(intent)
        }
    }

    /**
     * Determines whether the current call session should be automatically recorded based on user preferences.
     */
    private fun shouldAutoRecord(metadata: EnrichedCallData): Boolean {
        val rawNumber = metadata.rawPhoneNumber?.trim().orEmpty()
        val normalisedNumber = normalisePhoneNumber(rawNumber)
        val isAnonymous = normalisedNumber.isBlank()

        when (metadata.direction) {
            CallDirection.INCOMING -> {
                if (!preferences.isAutoRecordIncomingEnabled()) {
                    AppLogger.i(TAG, "Auto-record for incoming call is disabled")
                    return false
                }

                if (isAnonymous) {
                    if (preferences.isIgnoreAnonymousIncomingEnabled()) {
                        AppLogger.i(TAG, "Auto-record is ignoring incoming call since it's anonymous.")
                        return false
                    }
                }

                if (metadata.isCrossCountry && preferences.isIgnoreCrossCountryIncomingEnabled()) {
                    AppLogger.i(TAG, "Auto-record is ignoring incoming call since it's cross-country.")
                    return false
                }

                if (shouldIgnoreContact(normalisedNumber, preferences.getIgnoreContactsModeIncoming(), preferences.getIgnoredContactsIncoming())) {
                    AppLogger.i(TAG, "Auto-record is ignoring incoming call based on contact filtering.")
                    return false
                }

                AppLogger.i(TAG, "Auto-record is enabled for this incoming call.")
                return true
            }
            CallDirection.OUTGOING -> {
                if (!preferences.isAutoRecordOutgoingEnabled()) {
                    AppLogger.i(TAG, "Auto-record for outgoing call is disabled")
                    return false
                }

                if (metadata.isCrossCountry && preferences.isIgnoreCrossCountryOutgoingEnabled()) {
                    AppLogger.i(TAG, "Auto-record is ignoring outgoing call since it's cross-country.")
                    return false
                }

                if (shouldIgnoreContact(normalisedNumber, preferences.getIgnoreContactsModeOutgoing(), preferences.getIgnoredContactsOutgoing())) {
                    AppLogger.i(TAG, "Auto-record is ignoring outgoing call for based on contact filtering.")
                    return false
                }

                AppLogger.i(TAG, "Auto-record is enabled for this outgoing call.")
                return true
            }
        }
    }

    /**
     * Determines whether a call from/to a specific phone number should be ignored based on the user's contact filtering preferences.
     */
    private fun shouldIgnoreContact(normalisedNumber: String, mode: AppPreferences.IgnoreContactsMode, ignoredNumbers: Set<String>): Boolean {
        val shouldIgnore = when (mode) {
            AppPreferences.IgnoreContactsMode.NONE  -> false
            AppPreferences.IgnoreContactsMode.ALL   -> {
                if (!PermissionChecks.hasContactsPermission(appContext)) {
                    false
                } else {
                    val lookupUri = Uri.withAppendedPath(
                        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                        Uri.encode(normalisedNumber)
                    )

                    // Perform the query
                    val cursor = appContext.contentResolver.query(lookupUri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)

                    // Check if we got any results
                    cursor?.use {
                        // Basically, if we can find a contact, we return the object, so it's not null/false, else it's false.
                        it.moveToFirst()
                    } ?: false
                }
            }
            AppPreferences.IgnoreContactsMode.SELECTED ->
                ignoredNumbers.any { normalisePhoneNumber(it) == normalisedNumber }
        }

        return shouldIgnore
    }
}
