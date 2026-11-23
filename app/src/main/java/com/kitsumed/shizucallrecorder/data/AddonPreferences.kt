/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * Preferences for the add-on features (calculator lock, detailed notifications toggle, remote upload).
 *
 * Kept separate from [AppPreferences] so the original preference file and its keys are untouched.
 *
 * - Non-secret values live in a regular private SharedPreferences file.
 * - Secrets (FTP/SFTP password, SSH private key & passphrase) live in an [EncryptedSharedPreferences]
 *   file whose master key is stored in the Android Keystore.
 * - The calculator passcode is never stored: only a salted PBKDF2-HMAC-SHA256 hash is (see AppLockManager).
 */
class AddonPreferences(context: Context) {

    companion object {
        const val PREFS_NAME = "shizucallrecorder_addon_prefs"
        const val SECURE_PREFS_NAME = "shizucallrecorder_secure_prefs"

        // -- Keys (plain) --
        const val KEY_CALCULATOR_LOCK_ENABLED = "calculator_lock_enabled"
        const val KEY_PASSCODE_HASH = "calculator_passcode_hash"
        const val KEY_PASSCODE_SALT = "calculator_passcode_salt"
        const val KEY_DISGUISE_ICON_ENABLED = "calculator_disguise_icon_enabled"

        /** Public so the recording service can listen to it and update the existing notification in place. */
        const val KEY_DETAILED_NOTIFICATIONS = "detailed_notifications"

        const val KEY_FTP_UPLOAD_ENABLED = "ftp_upload_enabled"
        const val KEY_FTP_PROTOCOL = "ftp_protocol"
        const val KEY_FTP_HOST = "ftp_host"
        const val KEY_FTP_PORT = "ftp_port"
        const val KEY_FTP_USERNAME = "ftp_username"
        const val KEY_FTP_REMOTE_DIR = "ftp_remote_dir"
        const val KEY_SFTP_AUTH_WITH_KEY = "sftp_auth_with_key"
        const val KEY_SFTP_HOST_KEY_FINGERPRINT = "sftp_host_key_fingerprint"

        // -- Keys (secure) --
        private const val KEY_FTP_PASSWORD = "ftp_password"
        private const val KEY_SFTP_PRIVATE_KEY = "sftp_private_key"
        private const val KEY_SFTP_PASSPHRASE = "sftp_passphrase"

        const val DEFAULT_REMOTE_DIR = "/call_logs/"

        @Volatile
        private var securePrefsInstance: SharedPreferences? = null

        /**
         * EncryptedSharedPreferences creation is relatively expensive (Keystore access), so cache a single instance per process.
         */
        private fun securePrefs(context: Context): SharedPreferences {
            securePrefsInstance?.let { return it }
            return synchronized(this) {
                securePrefsInstance ?: createSecurePrefs(context.applicationContext).also { securePrefsInstance = it }
            }
        }

        private fun createSecurePrefs(appContext: Context): SharedPreferences {
            fun build(): SharedPreferences {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    appContext,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
            return try {
                build()
            } catch (e: Exception) {
                // Happens if the Keystore key was lost (e.g. restored backup / cleared credentials). The stored secrets are unrecoverable anyway,
                // so wipe the file and start fresh instead of crashing the app.
                AppLogger.e("Failed to open encrypted preferences, resetting stored credentials.", e)
                appContext.deleteSharedPreferences(SECURE_PREFS_NAME)
                build()
            }
        }
    }

    /** Upload protocol supported by the remote storage feature. */
    enum class Protocol(val key: String, val label: String, val defaultPort: Int) {
        FTP("ftp", "FTP", 21),
        FTPS("ftps", "FTPS", 21),
        SFTP("sftp", "SFTP", 22);

        companion object {
            fun fromKey(key: String?): Protocol = entries.firstOrNull { it.key == key } ?: FTP
        }
    }

    private val appContext = context.applicationContext
    val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secure: SharedPreferences by lazy { securePrefs(appContext) }

    // -------- Calculator lock --------

    fun isCalculatorLockEnabled() = prefs.getBoolean(KEY_CALCULATOR_LOCK_ENABLED, false) && hasPasscode()
    fun setCalculatorLockEnabled(enabled: Boolean) = prefs.edit { putBoolean(KEY_CALCULATOR_LOCK_ENABLED, enabled) }

    fun hasPasscode() = !prefs.getString(KEY_PASSCODE_HASH, null).isNullOrEmpty() && !prefs.getString(KEY_PASSCODE_SALT, null).isNullOrEmpty()
    fun getPasscodeHash(): String? = prefs.getString(KEY_PASSCODE_HASH, null)
    fun getPasscodeSalt(): String? = prefs.getString(KEY_PASSCODE_SALT, null)
    fun setPasscodeHash(hash: String, salt: String) = prefs.edit {
        putString(KEY_PASSCODE_HASH, hash)
        putString(KEY_PASSCODE_SALT, salt)
    }

    fun isDisguiseIconEnabled() = prefs.getBoolean(KEY_DISGUISE_ICON_ENABLED, false)
    fun setDisguiseIconEnabled(enabled: Boolean) = prefs.edit { putBoolean(KEY_DISGUISE_ICON_ENABLED, enabled) }

    // -------- Notifications --------

    fun isDetailedNotificationsEnabled() = prefs.getBoolean(KEY_DETAILED_NOTIFICATIONS, true)
    fun setDetailedNotificationsEnabled(enabled: Boolean) = prefs.edit { putBoolean(KEY_DETAILED_NOTIFICATIONS, enabled) }

    // -------- Remote upload --------

    fun isUploadEnabled() = prefs.getBoolean(KEY_FTP_UPLOAD_ENABLED, false)
    fun setUploadEnabled(enabled: Boolean) = prefs.edit { putBoolean(KEY_FTP_UPLOAD_ENABLED, enabled) }

    fun getProtocol() = Protocol.fromKey(prefs.getString(KEY_FTP_PROTOCOL, Protocol.FTP.key))
    fun setProtocol(protocol: Protocol) = prefs.edit { putString(KEY_FTP_PROTOCOL, protocol.key) }

    fun getHost() = prefs.getString(KEY_FTP_HOST, "") ?: ""
    fun setHost(host: String) = prefs.edit { putString(KEY_FTP_HOST, host.trim()) }

    /** Returns the configured port, or the protocol default when unset/invalid. */
    fun getPort(): Int {
        val port = prefs.getInt(KEY_FTP_PORT, -1)
        return if (port in 1..65535) port else getProtocol().defaultPort
    }
    fun setPort(port: Int?) = prefs.edit {
        if (port == null || port !in 1..65535) remove(KEY_FTP_PORT) else putInt(KEY_FTP_PORT, port)
    }
    fun hasCustomPort() = prefs.getInt(KEY_FTP_PORT, -1) in 1..65535

    fun getUsername() = prefs.getString(KEY_FTP_USERNAME, "") ?: ""
    fun setUsername(username: String) = prefs.edit { putString(KEY_FTP_USERNAME, username.trim()) }

    fun getRemoteDir() = prefs.getString(KEY_FTP_REMOTE_DIR, DEFAULT_REMOTE_DIR)?.ifBlank { DEFAULT_REMOTE_DIR } ?: DEFAULT_REMOTE_DIR
    fun setRemoteDir(dir: String) = prefs.edit { putString(KEY_FTP_REMOTE_DIR, dir.trim()) }

    fun isSftpKeyAuth() = prefs.getBoolean(KEY_SFTP_AUTH_WITH_KEY, false)
    fun setSftpKeyAuth(enabled: Boolean) = prefs.edit { putBoolean(KEY_SFTP_AUTH_WITH_KEY, enabled) }

    /** SHA-256 fingerprint of the SFTP server host key, pinned on first successful connection (trust-on-first-use). */
    fun getSftpHostKeyFingerprint(): String? = prefs.getString(KEY_SFTP_HOST_KEY_FINGERPRINT, null)
    fun setSftpHostKeyFingerprint(fingerprint: String?) = prefs.edit {
        if (fingerprint == null) remove(KEY_SFTP_HOST_KEY_FINGERPRINT) else putString(KEY_SFTP_HOST_KEY_FINGERPRINT, fingerprint)
    }

    fun getPassword() = secure.getString(KEY_FTP_PASSWORD, "") ?: ""
    fun setPassword(password: String) = secure.edit { putString(KEY_FTP_PASSWORD, password) }

    fun getSftpPrivateKey() = secure.getString(KEY_SFTP_PRIVATE_KEY, "") ?: ""
    fun setSftpPrivateKey(key: String) = secure.edit { putString(KEY_SFTP_PRIVATE_KEY, key) }

    fun getSftpPassphrase() = secure.getString(KEY_SFTP_PASSPHRASE, "") ?: ""
    fun setSftpPassphrase(passphrase: String) = secure.edit { putString(KEY_SFTP_PASSPHRASE, passphrase) }

    /** True when enough information is present to attempt a connection. */
    fun isUploadConfigured(): Boolean {
        if (getHost().isBlank() || getUsername().isBlank()) return false
        return if (getProtocol() == Protocol.SFTP && isSftpKeyAuth()) getSftpPrivateKey().isNotBlank() else true
    }
}
