/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.callmeta

import android.content.Context
import android.telecom.Call
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.documentfile.provider.DocumentFile
import com.kitsumed.shizucallrecorder.data.AddonPreferences
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import com.kitsumed.shizucallrecorder.services.location.CallLocationTracker
import com.kitsumed.shizucallrecorder.services.recording.RecordingSession
import com.kitsumed.shizucallrecorder.services.upload.UploadScheduler
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.WeakHashMap

/**
 * Passive observer of the existing call-detection hooks and recording service.
 *
 * It never changes how calls are detected or recorded: every entry point is wrapped so that any failure is logged and swallowed.
 *
 * Flow:
 *  1. Telecom ([com.kitsumed.shizucallrecorder.services.callDetection.incall.InCallService]) or PhoneState hooks feed lifecycle events into a [CallRecord].
 *  2. When the recording service reports an active recording, the record is bound to that [RecordingSession] and the location logger starts.
 *  3. When the recording is released (file closed), `<audio_base_name>.json` is written once next to the audio, and the single
 *     post-call upload attempt is enqueued.
 */
object CallMetadataTracker {

    private const val SOURCE_TELECOM = "telecom"
    private const val SOURCE_PHONE_STATE = "phone_state"

    /** Background scope for the single end-of-call disk write. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()

    /** The call currently (or most recently) observed by the call-detection hooks. */
    private var current: CallRecord? = null

    /** Slot index reported by the last PHONE_STATE broadcast (PhoneState detection mode only). */
    @Volatile
    private var lastPhoneStateSlot: Int? = null

    /** Recording sessions -> the call they belong to. Weak keys so a leaked session can never keep a record alive. */
    private val sessions: MutableMap<RecordingSession, CallRecord> = Collections.synchronizedMap(WeakHashMap())

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            AppLogger.w("CallMetadataTracker: $what failed, ignoring (add-on must never affect recording).", e)
        }
    }

    private fun newRecord(source: String): CallRecord = synchronized(lock) {
        CallRecord(source = source).also { current = it }
    }

    private fun aliveRecord(): CallRecord? = synchronized(lock) { current?.takeIf { !it.isEnded } }

    // ───────────────────────────── Telecom (InCallService) hooks ─────────────────────────────

    fun onTelecomCallAdded(context: Context, call: Call) = safely("onTelecomCallAdded") {
        val record = newRecord(SOURCE_TELECOM)
        val details = call.details
        record.direction = when (details.callDirection) {
            Call.Details.DIRECTION_INCOMING -> CallDirection.INCOMING.token
            Call.Details.DIRECTION_OUTGOING -> CallDirection.OUTGOING.token
            else -> null
        }
        record.phoneNumber = details.handle?.schemeSpecificPart?.ifBlank { null }
        record.contactName = (details.contactDisplayName ?: details.callerDisplayName)?.ifBlank { null }
        fillPhoneAccount(context, record, call)
        onTelecomStateChanged(context, call, details.state)
    }

    fun onTelecomStateChanged(context: Context, call: Call, state: Int) = safely("onTelecomStateChanged") {
        val record = aliveRecord() ?: return@safely
        if (record.phoneAccountId == null) fillPhoneAccount(context, record, call)
        if (record.phoneNumber == null) record.phoneNumber = call.details.handle?.schemeSpecificPart?.ifBlank { null }
        val src = mapOf("source" to SOURCE_TELECOM)
        when (state) {
            Call.STATE_RINGING -> record.addLifecycleEvent("ringing", src)
            Call.STATE_NEW, Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL -> record.addLifecycleEvent("dialing", src)
            Call.STATE_ACTIVE -> record.markConnected(src)
            Call.STATE_HOLDING -> record.startHold()
            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> endCall(record, mapOf("source" to SOURCE_TELECOM, "cause" to disconnectCause(call)))
        }
    }

    fun onTelecomCallRemoved(call: Call) = safely("onTelecomCallRemoved") {
        val record = aliveRecord() ?: return@safely
        endCall(record, mapOf("source" to SOURCE_TELECOM, "cause" to disconnectCause(call)))
    }

    private fun disconnectCause(call: Call): String? =
        runCatching { call.details.disconnectCause?.reason?.ifBlank { null } ?: call.details.disconnectCause?.code?.toString() }.getOrNull()

    private fun fillPhoneAccount(context: Context, record: CallRecord, call: Call) {
        val handle = runCatching { call.details.accountHandle }.getOrNull() ?: return
        record.phoneAccountId = handle.id
        record.packageName = handle.componentName?.packageName
        runCatching {
            val telecom = context.getSystemService(TelecomManager::class.java)
            record.phoneAccountLabel = telecom?.getPhoneAccount(handle)?.label?.toString()
        }
        runCatching {
            val tm = context.getSystemService(TelephonyManager::class.java)
            val subId = tm?.getSubscriptionId(handle) ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                val slot = SubscriptionManager.getSlotIndex(subId)
                if (slot >= 0) record.simSlot = slot
            }
        }
    }

    // ───────────────────────────── PhoneState hooks ─────────────────────────────

    /** Called by the PHONE_STATE receiver with the SIM slot extra of the broadcast, when present. */
    fun notePhoneStateSlot(slot: Int?) = safely("notePhoneStateSlot") {
        if (slot != null && slot >= 0) {
            lastPhoneStateSlot = slot
            aliveRecord()?.let { if (it.simSlot == null) it.simSlot = slot }
        }
    }

    /**
     * @param stateString One of TelephonyManager.EXTRA_STATE_*.
     * @param number Number from the broadcast (may be null/blank).
     */
    fun onPhoneState(stateString: String, number: String?) = safely("onPhoneState") {
        val src = mapOf("source" to SOURCE_PHONE_STATE)
        when (stateString) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val record = aliveRecord() ?: newRecord(SOURCE_PHONE_STATE).also { it.direction = CallDirection.INCOMING.token }
                applyPhoneStateBasics(record, number)
                record.addLifecycleEvent("ringing", src)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val existing = aliveRecord()
                if (existing == null) {
                    // Direct transition to OFFHOOK: outgoing call (PhoneState cannot tell when the remote side answers).
                    val record = newRecord(SOURCE_PHONE_STATE).also { it.direction = CallDirection.OUTGOING.token }
                    applyPhoneStateBasics(record, number)
                    record.addLifecycleEvent("dialing", src)
                } else {
                    applyPhoneStateBasics(existing, number)
                    if (existing.direction == CallDirection.INCOMING.token) existing.markConnected(src)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> aliveRecord()?.let { endCall(it, src) }
        }
    }

    private fun applyPhoneStateBasics(record: CallRecord, number: String?) {
        if (record.phoneNumber == null && !number.isNullOrBlank()) record.phoneNumber = number
        if (record.simSlot == null) record.simSlot = lastPhoneStateSlot
    }

    private fun endCall(record: CallRecord, details: Map<String, Any?>) {
        record.markEnded(details)
        // Location updates must stop the moment the call ends.
        record.locationTracker?.stop()
        record.locationTracker = null
        lastPhoneStateSlot = null
    }

    // ───────────────────────────── Recording service hooks ─────────────────────────────

    /** Called by the recording service right after a recording session became Active. */
    fun onRecordingStarted(context: Context, session: RecordingSession, metadata: EnrichedCallData) = safely("onRecordingStarted") {
        val record = aliveRecord() ?: newRecord("recording_service").also {
            // No call-detection hook saw this call (e.g. process restarted mid-call): the call is necessarily active now.
            it.markConnected(mapOf("source" to "recording_service"))
        }
        // PhoneState mode restores the direction from its own cache after a process death, so prefer the app's resolved value there.
        if (record.direction == null || record.source == SOURCE_PHONE_STATE) record.direction = metadata.direction.token
        if (record.phoneNumber.isNullOrBlank()) record.phoneNumber = metadata.getBestNumber().ifBlank { null }
        else metadata.formattedE164Number?.let { record.phoneNumber = it } // prefer the normalized E.164 form
        if (record.contactName.isNullOrBlank()) record.contactName = metadata.callerName?.ifBlank { null }
        if (record.packageName == null) record.packageName = metadata.packageName

        sessions[session] = record

        if (record.locationTracker == null) {
            record.locationTracker = CallLocationTracker(context.applicationContext, record).also { it.start() }
        }
    }

    /** Called by the recording service right after the session was released (audio file finalized and closed). */
    fun onRecordingFinished(context: Context, session: RecordingSession) = safely("onRecordingFinished") {
        val record = sessions.remove(session) ?: return@safely
        val audioUri = session.currentRecordingUri ?: return@safely

        // Nothing else consumes location points once the recording is done.
        val stillRecording = synchronized(sessions) { sessions.values.any { it === record } }
        if (!stillRecording) {
            record.locationTracker?.stop()
            record.locationTracker = null
        }

        val appContext = context.applicationContext
        ioScope.launch {
            safely("write call metadata json") {
                val audioDoc = DocumentFile.fromSingleUri(appContext, audioUri)
                if (audioDoc == null || !audioDoc.exists() || audioDoc.length() <= 0L) {
                    AppLogger.i("CallMetadataTracker: audio file missing or empty (cancelled/silent recording), skipping JSON.")
                    return@safely
                }
                val audioName = audioDoc.name
                val (base, ext) = CallMetadataJsonWriter.splitName(audioName)
                val json = record.toJson(audioName, base, ext)
                val written = CallMetadataJsonWriter.writeNextToAudio(appContext, audioUri, base ?: "call_${record.callId}", json)
                if (written == null) {
                    AppLogger.w("CallMetadataTracker: failed to write metadata JSON next to $audioName")
                    return@safely
                }
                AppLogger.i("CallMetadataTracker: wrote ${written.displayName}")

                val prefs = AddonPreferences(appContext)
                if (prefs.isUploadEnabled() && prefs.isUploadConfigured()) {
                    UploadScheduler.enqueuePostCallUpload(appContext, audioUri, audioName ?: "", written.uri, written.displayName)
                }
            }
        }
    }
}
