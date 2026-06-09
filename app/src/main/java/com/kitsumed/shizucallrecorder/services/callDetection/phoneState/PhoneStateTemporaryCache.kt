/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.callDetection.phoneState

import android.content.Context
import androidx.core.content.edit
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * Handles the Ephemeral State Persistence (Temporary Cache).
 * Used to save call metadata during the RINGING state and restore it during OFFHOOK.
 * in case the Android system kills our app process while waiting for the user to answer.
 *
 * This workaround could cause some edges cases. The user receving a ringing call, the app die, the user make an outgoing call, but the app restore the ringing state.
 * The "fix" is to only keep a direction valid for a short period of time [MAX_AGE_MS]. It reduces the chances of this happening.
 */
class PhoneStateTemporaryCache(private val context: Context) {

    companion object {
        private const val TAG = "SCR:CallSessionTemporaryCache"
        private const val PREFS_CACHE = "CallSessionManagerTemporaryCache"
        private const val KEY_CACHE_DIRECTION = "cache_direction"
        private const val KEY_CACHE_TIMESTAMP = "cache_timestamp"

        // From online research, Phone Carriers allow a limit of up to 30s ringing time. 34s to be safe.
        private const val MAX_AGE_MS = 34000L
    }

    /**
     * Persist the direction asynchronously.
     * Android often kills background processes before the user answers (within 5-10s).
     * This ensures we don't lose the call direction while waiting for the OFFHOOK state.
     */
    fun save(direction: CallDirection?) {
        context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE).edit {
            putString(KEY_CACHE_DIRECTION, direction?.token)
            putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
        }
    }

    /**
     * Attempt to recover direction in case the process was killed during the RINGING state.
     * @return The restored [CallDirection] if valid and not stale, or null if no valid cache exists.
     */
    fun restore(): CallDirection? {
        val prefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0L)

        if (timestamp <= 0) return null

        val savedDirToken = prefs.getString(KEY_CACHE_DIRECTION, "")

        // Restore if data is less than MAX_AGE_MS seconds old
        if (System.currentTimeMillis() - timestamp <= MAX_AGE_MS) {
            val restoredDir = savedDirToken?.let { CallDirection.fromToken(it) }
            if (restoredDir != null) {
                AppLogger.d(TAG, "Restored direction (${restoredDir.token}) from TemporaryCache (process was killed during RINGING).")
                return restoredDir
            }
        } else clear() // Clear stale data if it's old
        return null
    }

    /**
     * Blindly clear the temporary cache storage so no stale data is left behind.
     */
    fun clear() {
        context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE).edit { clear() }
    }
}