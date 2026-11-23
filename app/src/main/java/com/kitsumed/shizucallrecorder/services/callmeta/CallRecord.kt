/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.callmeta

import com.kitsumed.shizucallrecorder.services.location.CallLocationTracker
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * In-memory buffer for one phone call: metadata, lifecycle/hold events and location points.
 *
 * Nothing is written to disk during the call; [toJson] is called exactly once when the recording finishes.
 * All mutators are synchronized on the instance because Telecom, PhoneState, location and service callbacks may interleave.
 */
class CallRecord(
    val startedAtMs: Long = System.currentTimeMillis(),
    val source: String
) {
    data class Event(val type: String, val timestampMs: Long, val details: Map<String, Any?> = emptyMap())

    data class LocationPoint(
        val timestampMs: Long,
        val lat: Double,
        val lng: Double,
        val accuracyM: Float?,
        val speedMps: Float?,
        val distanceFromPrevM: Float?,
        val reason: String
    )

    val callId: String = UUID.randomUUID().toString()

    var direction: String? = null
    var phoneNumber: String? = null
    var contactName: String? = null
    var simSlot: Int? = null
    var phoneAccountId: String? = null
    var phoneAccountLabel: String? = null
    var packageName: String? = null

    var connectedAtMs: Long? = null
        private set
    var endedAtMs: Long? = null
        private set

    private var holdCount = 0
    private var totalHoldMs = 0L
    private var holdStartedAtMs: Long? = null
    private var lastLifecycleEvent: String? = null

    private val events = mutableListOf<Event>()
    private val locations = mutableListOf<LocationPoint>()

    /** Active location tracker bound to this call, if any. */
    @Volatile
    var locationTracker: CallLocationTracker? = null

    val isEnded: Boolean
        @Synchronized get() = endedAtMs != null

    @Synchronized
    fun addLifecycleEvent(type: String, details: Map<String, Any?> = emptyMap(), now: Long = System.currentTimeMillis()) {
        if (endedAtMs != null) return
        if (type == lastLifecycleEvent) return // collapse duplicate broadcasts / callbacks
        lastLifecycleEvent = type
        events += Event(type, now, details)
    }

    @Synchronized
    fun markConnected(details: Map<String, Any?> = emptyMap(), now: Long = System.currentTimeMillis()) {
        if (endedAtMs != null) return
        if (holdStartedAtMs != null) {
            endHold(now)
            return
        }
        if (connectedAtMs == null) {
            connectedAtMs = now
            lastLifecycleEvent = "active"
            events += Event("active", now, details)
        }
    }

    @Synchronized
    fun startHold(now: Long = System.currentTimeMillis()) {
        if (endedAtMs != null || holdStartedAtMs != null) return
        holdStartedAtMs = now
        holdCount++
        lastLifecycleEvent = "hold_start"
        events += Event("hold_start", now, mapOf("hold_index" to holdCount))
    }

    @Synchronized
    private fun endHold(now: Long) {
        val start = holdStartedAtMs ?: return
        val holdMs = (now - start).coerceAtLeast(0)
        totalHoldMs += holdMs
        holdStartedAtMs = null
        lastLifecycleEvent = "hold_end"
        events += Event("hold_end", now, mapOf("hold_index" to holdCount, "hold_seconds" to holdMs / 1000.0))
    }

    @Synchronized
    fun markEnded(details: Map<String, Any?> = emptyMap(), now: Long = System.currentTimeMillis()) {
        if (endedAtMs != null) return
        endHold(now)
        endedAtMs = now
        lastLifecycleEvent = "ended"
        events += Event("ended", now, details)
    }

    @Synchronized
    fun addLocation(point: LocationPoint) {
        locations += point
    }

    @Synchronized
    fun lastLoggedLocation(): LocationPoint? = locations.lastOrNull()

    @Synchronized
    fun hasLocations(): Boolean = locations.isNotEmpty()

    /** Builds the unified `<audio_base_name>.json` document. Unavailable values are written as JSON null. */
    @Synchronized
    fun toJson(audioFileName: String?, audioBaseName: String?, audioExtension: String?, nowMs: Long = System.currentTimeMillis()): JSONObject {
        val ended = endedAtMs
        val reference = connectedAtMs ?: startedAtMs
        val durationEnd = ended ?: nowMs
        // Include an in-progress hold (e.g. recording finished while the call was still on hold).
        val holdMs = totalHoldMs + (holdStartedAtMs?.let { (durationEnd - it).coerceAtLeast(0) } ?: 0L)

        val audio = JSONObject()
            .put("file_name", audioFileName.orJsonNull())
            .put("base_name", audioBaseName.orJsonNull())
            .put("extension", audioExtension.orJsonNull())

        val eventsJson = JSONArray()
        events.forEach { e ->
            val details = JSONObject()
            e.details.forEach { (k, v) -> details.put(k, v ?: JSONObject.NULL) }
            eventsJson.put(
                JSONObject()
                    .put("type", e.type)
                    .put("timestamp", iso(e.timestampMs))
                    .put("elapsed_seconds", elapsedSeconds(e.timestampMs))
                    .put("details", details)
            )
        }

        val phoneAccount = if (phoneAccountId == null && phoneAccountLabel == null && packageName == null) JSONObject.NULL else JSONObject()
            .put("id", phoneAccountId.orJsonNull())
            .put("label", phoneAccountLabel.orJsonNull())
            .put("package_name", packageName.orJsonNull())

        val call = JSONObject()
            .put("call_id", callId)
            .put("direction", direction ?: "unknown")
            .put("phone_number", phoneNumber?.ifBlank { null }.orJsonNull())
            .put("contact_name", contactName?.ifBlank { null }.orJsonNull())
            .put("sim_slot", simSlot ?: JSONObject.NULL)
            .put("phone_account", phoneAccount)
            .put("started_at", iso(startedAtMs))
            .put("connected_at", connectedAtMs?.let { iso(it) }.orJsonNull())
            .put("ended_at", ended?.let { iso(it) }.orJsonNull())
            .put("duration_seconds", ((durationEnd - reference).coerceAtLeast(0) / 1000L))
            .put(
                "hold_summary", JSONObject()
                    .put("hold_count", holdCount)
                    .put("total_hold_seconds", holdMs / 1000L)
            )
            .put("events", eventsJson)

        val locationsJson = JSONArray()
        locations.forEach { p ->
            locationsJson.put(
                JSONObject()
                    .put("timestamp", iso(p.timestampMs))
                    .put("elapsed_seconds", elapsedSeconds(p.timestampMs))
                    .put("lat", p.lat)
                    .put("lng", p.lng)
                    .put("accuracy_m", p.accuracyM?.toDouble() ?: JSONObject.NULL)
                    .put("speed_mps", p.speedMps?.toDouble() ?: JSONObject.NULL)
                    .put("distance_from_prev_m", p.distanceFromPrevM?.toDouble() ?: JSONObject.NULL)
                    .put("reason", p.reason)
            )
        }

        return JSONObject()
            .put("audio_file", audio)
            .put("call", call)
            .put("locations", locationsJson)
    }

    private fun elapsedSeconds(ts: Long): Long = ((ts - startedAtMs).coerceAtLeast(0)) / 1000L

    private fun Any?.orJsonNull(): Any = this ?: JSONObject.NULL

    companion object {
        fun iso(ms: Long): String =
            OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
