/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import com.kitsumed.shizucallrecorder.data.AddonPreferences
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Whole-app calculator passcode lock.
 *
 * - The passcode is stored only as a salted PBKDF2-HMAC-SHA256 hash.
 * - The unlocked state lives in memory only, so a process death always re-locks the app.
 * - This only gates the UI (MainActivity). Background call detection and the recording service are never affected.
 */
object AppLockManager {

    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    const val MIN_PASSCODE_LENGTH = 4
    const val MAX_PASSCODE_LENGTH = 12

    private const val ALIAS_DEFAULT = "com.kitsumed.shizucallrecorder.LauncherDefault"
    private const val ALIAS_CALCULATOR = "com.kitsumed.shizucallrecorder.LauncherCalculator"

    private val _isUnlocked = MutableStateFlow(false)

    /** True once the correct passcode was entered, until the user leaves the app UI. */
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _isLockEnabled = MutableStateFlow(false)

    /** Mirrors the "Enable Calculator Lock" preference. Refresh with [refreshLockEnabled] after changing it. */
    val isLockEnabled: StateFlow<Boolean> = _isLockEnabled.asStateFlow()

    fun refreshLockEnabled(context: Context) {
        _isLockEnabled.value = AddonPreferences(context).isCalculatorLockEnabled()
    }

    fun lock() {
        _isUnlocked.value = false
    }

    /** Marks the app as unlocked without a passcode check (used right after the user configures a new passcode in Settings). */
    fun markUnlocked() {
        _isUnlocked.value = true
    }

    /**
     * Checks [candidate] against the stored hash. Unlocks the app on success.
     * @return true if the passcode matched.
     */
    fun tryUnlock(context: Context, candidate: String): Boolean {
        if (candidate.length !in MIN_PASSCODE_LENGTH..MAX_PASSCODE_LENGTH || !candidate.all { it.isDigit() }) return false
        val prefs = AddonPreferences(context)
        val storedHash = prefs.getPasscodeHash() ?: return false
        val salt = prefs.getPasscodeSalt()?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() } ?: return false
        val candidateHash = hash(candidate, salt)
        val expected = runCatching { Base64.decode(storedHash, Base64.NO_WRAP) }.getOrNull() ?: return false
        // Constant-time comparison
        val matches = MessageDigest.isEqual(candidateHash, expected)
        if (matches) _isUnlocked.value = true
        return matches
    }

    /** Validates and stores a new numeric passcode. */
    fun setPasscode(context: Context, passcode: String): Boolean {
        if (!isValidPasscode(passcode)) return false
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = hash(passcode, salt)
        AddonPreferences(context).setPasscodeHash(
            Base64.encodeToString(hash, Base64.NO_WRAP),
            Base64.encodeToString(salt, Base64.NO_WRAP)
        )
        return true
    }

    fun isValidPasscode(passcode: String) =
        passcode.length in MIN_PASSCODE_LENGTH..MAX_PASSCODE_LENGTH && passcode.all { it.isDigit() }

    private fun hash(passcode: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passcode.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Swaps the launcher entry between the original icon/name and the "Calculator" disguise.
     * Exactly one launcher alias is enabled at any time so the app never disappears from the drawer.
     */
    fun setDisguiseEnabled(context: Context, enabled: Boolean) {
        val pm = context.packageManager
        val pkg = context.packageName
        val enable = if (enabled) ALIAS_CALCULATOR else ALIAS_DEFAULT
        val disable = if (enabled) ALIAS_DEFAULT else ALIAS_CALCULATOR
        try {
            // Enable the new entry first, then disable the old one, so there is never a moment with no launcher entry.
            pm.setComponentEnabledSetting(ComponentName(pkg, enable), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(ComponentName(pkg, disable), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            AddonPreferences(context).setDisguiseIconEnabled(enabled)
            AppLogger.i("Launcher disguise updated: calculator=$enabled")
        } catch (e: Exception) {
            AppLogger.e("Failed to switch launcher alias", e)
        }
    }
}
