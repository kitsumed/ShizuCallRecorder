/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.kitsumed.shizucallrecorder.services.callmeta.CallRecord
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * Battery-first, movement-rate adaptive location logger bound to ONE call.
 *
 * - Runs only between [start] (recording active during a call) and [stop] (call ended / recording finished).
 * - No wake locks, no timers, no ActivityRecognition: speed is derived from displacement between consecutive fixes over elapsed time.
 * - Low-movement mode: balanced power, 3 min interval with hardware batching; log only on >= 50 m displacement or a 20 min heartbeat.
 * - Movement mode (>= 1.5 m/s): high accuracy, ~25 s interval, speed-scaled displacement threshold; steps back down after
 *   2 consecutive low-speed readings.
 * - Every failure (missing permission, no Play Services, ...) is swallowed: the call simply gets an empty `locations` array.
 */
class CallLocationTracker(
    private val context: Context,
    private val record: CallRecord
) {
    private enum class Mode { LOW, MOVING }

    companion object {
        const val MOVING_SPEED_THRESHOLD_MPS = 1.5f

        // Baseline / low-movement mode
        const val LOW_INTERVAL_MS = 180_000L
        const val LOW_MIN_UPDATE_INTERVAL_MS = 60_000L
        const val LOW_MAX_UPDATE_DELAY_MS = 180_000L
        const val LOW_MIN_DISPLACEMENT_M = 50f

        // Active movement mode
        const val MOVING_INTERVAL_MS = 25_000L
        const val MOVING_MIN_UPDATE_INTERVAL_MS = 20_000L
        const val MOVING_MAX_UPDATE_DELAY_MS = 30_000L

        const val HEARTBEAT_MS = 1_200_000L
        const val LOW_SPEED_READINGS_TO_STEP_DOWN = 2

        /** Displacement threshold scaled with speed: walking ~50 m, city driving 150 m, fast driving 250 m. */
        fun minDisplacementFor(speedMps: Float): Float = when {
            speedMps < 3f -> 50f    // walking / brisk pace
            speedMps < 8f -> 100f   // running / cycling / slow traffic
            speedMps < 20f -> 150f  // city driving (< 72 km/h)
            else -> 250f            // highway driving
        }

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private var client: FusedLocationProviderClient? = null
    private var cancellation: CancellationTokenSource? = null
    private var mode = Mode.LOW
    private var lowSpeedReadings = 0
    private var lastReceived: Location? = null
    private var lastLoggedLocation: Location? = null
    private var lastLoggedAtMs = 0L

    @Volatile
    private var running = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // Batched results are delivered oldest first.
            result.locations.forEach { onLocation(it) }
        }
    }

    @SuppressLint("MissingPermission") // Checked by hasLocationPermission()
    fun start() {
        if (running) return
        if (!hasLocationPermission(context)) {
            AppLogger.i("CallLocationTracker: no location permission, location logging skipped for this call.")
            return
        }
        try {
            val c = LocationServices.getFusedLocationProviderClient(context)
            client = c
            running = true
            mode = Mode.LOW
            // One-shot initial fix (no continuous radio use), then the batched low-power stream.
            val cts = CancellationTokenSource().also { cancellation = it }
            c.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnSuccessListener { loc -> if (loc != null) onLocation(loc) }
                .addOnFailureListener { AppLogger.w("CallLocationTracker: initial fix failed", it) }
            c.requestLocationUpdates(buildRequest(Mode.LOW), callback, Looper.getMainLooper())
                .addOnFailureListener {
                    AppLogger.w("CallLocationTracker: location updates unavailable (Play Services missing?)", it)
                }
            AppLogger.i("CallLocationTracker: started (low-movement mode)")
        } catch (e: Throwable) {
            AppLogger.w("CallLocationTracker: failed to start", e)
            stop()
        }
    }

    fun stop() {
        if (!running && client == null) return
        running = false
        runCatching { cancellation?.cancel() }
        runCatching { client?.removeLocationUpdates(callback) }
        cancellation = null
        client = null
        AppLogger.i("CallLocationTracker: stopped")
    }

    private fun buildRequest(mode: Mode): LocationRequest = when (mode) {
        Mode.LOW -> LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, LOW_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOW_MIN_UPDATE_INTERVAL_MS)
            .setMaxUpdateDelayMillis(LOW_MAX_UPDATE_DELAY_MS)
            .setWaitForAccurateLocation(false)
            .build()
        Mode.MOVING -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, MOVING_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MOVING_MIN_UPDATE_INTERVAL_MS)
            .setMaxUpdateDelayMillis(MOVING_MAX_UPDATE_DELAY_MS)
            .setWaitForAccurateLocation(false) // never hold the GPS radio open waiting for a marginal fix
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun switchMode(newMode: Mode) {
        if (newMode == mode || !running) return
        mode = newMode
        lowSpeedReadings = 0
        try {
            // Re-requesting with the same callback replaces the previous request.
            client?.requestLocationUpdates(buildRequest(newMode), callback, Looper.getMainLooper())
            AppLogger.d("CallLocationTracker: switched to $newMode mode")
        } catch (e: Throwable) {
            AppLogger.w("CallLocationTracker: failed to reconfigure request", e)
        }
    }

    /**
     * Speed from displacement between the previous and current fix over elapsed seconds.
     * Displacement smaller than the fix's own accuracy radius is treated as drift (0 m/s).
     */
    private fun estimateSpeed(prev: Location?, cur: Location): Float {
        if (prev == null) return if (cur.hasSpeed()) cur.speed else 0f
        val dtSec = (cur.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000_000.0
        if (dtSec < 1.0) return if (cur.hasSpeed()) cur.speed else 0f
        val distance = prev.distanceTo(cur)
        if (cur.hasAccuracy() && distance < cur.accuracy) return 0f
        return (distance / dtSec).toFloat()
    }

    private fun onLocation(loc: Location) {
        if (!running) return
        try {
            // Ignore duplicate deliveries (e.g. initial fix also delivered by the stream).
            val prev = lastReceived
            if (prev != null && loc.elapsedRealtimeNanos <= prev.elapsedRealtimeNanos) return
            val speed = estimateSpeed(prev, loc)
            lastReceived = loc

            val now = System.currentTimeMillis()
            val lastLogged = lastLoggedLocation
            if (lastLogged == null) {
                log(loc, speed, null, "initial_fix", now)
            } else {
                val distance = lastLogged.distanceTo(loc)
                val sinceLastLog = now - lastLoggedAtMs
                val threshold = if (mode == Mode.MOVING) minDisplacementFor(speed) else LOW_MIN_DISPLACEMENT_M
                when {
                    distance >= threshold -> log(loc, speed, distance, "movement_threshold", now)
                    sinceLastLog >= HEARTBEAT_MS -> log(loc, speed, distance, "heartbeat", now)
                }
            }

            // Adapt polling to the movement rate.
            if (speed >= MOVING_SPEED_THRESHOLD_MPS) {
                lowSpeedReadings = 0
                if (mode == Mode.LOW) switchMode(Mode.MOVING)
            } else if (mode == Mode.MOVING) {
                lowSpeedReadings++
                if (lowSpeedReadings >= LOW_SPEED_READINGS_TO_STEP_DOWN) switchMode(Mode.LOW)
            }
        } catch (e: Throwable) {
            AppLogger.w("CallLocationTracker: failed to process location", e)
        }
    }

    private fun log(loc: Location, speed: Float, distanceFromPrev: Float?, reason: String, now: Long) {
        lastLoggedLocation = loc
        lastLoggedAtMs = now
        record.addLocation(
            CallRecord.LocationPoint(
                timestampMs = if (loc.time > 0) loc.time else now,
                lat = loc.latitude,
                lng = loc.longitude,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                speedMps = speed,
                distanceFromPrevM = distanceFromPrev,
                reason = reason
            )
        )
    }
}
