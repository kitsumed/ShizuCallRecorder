/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kitsumed.shizucallrecorder.data.AddonPreferences
import com.kitsumed.shizucallrecorder.security.AppLockManager
import com.kitsumed.shizucallrecorder.services.upload.UploadProcessor
import com.kitsumed.shizucallrecorder.services.upload.UploadQueueStore
import com.kitsumed.shizucallrecorder.services.upload.UploadScheduler
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings state for the add-on sections: calculator lock, detailed notifications, remote upload.
 * All upload/queue status is surfaced here (inline in Settings) and never as notifications.
 */
class AddonSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val prefs = AddonPreferences(appContext)
    private val queue = UploadQueueStore.getInstance(appContext)

    private val _updateTrigger = MutableStateFlow(0)
    /** Bumped after every preference write so `remember(updateTrigger)` reads refresh. */
    val updateTrigger: StateFlow<Int> = _updateTrigger.asStateFlow()

    /** Number of audio+json pairs waiting locally. */
    val pendingCount: StateFlow<Int> = queue.count

    private val _testState = MutableStateFlow<ActionState>(ActionState.Idle)
    val testState: StateFlow<ActionState> = _testState.asStateFlow()

    private val _retryState = MutableStateFlow<ActionState>(ActionState.Idle)
    val retryState: StateFlow<ActionState> = _retryState.asStateFlow()

    sealed class ActionState {
        data object Idle : ActionState()
        data object Running : ActionState()
        data class Done(val success: Boolean, val message: String) : ActionState()
    }

    fun refresh() = _updateTrigger.update { it + 1 }

    fun lastUploadError(): String? = queue.lastError()

    // -------- Calculator lock --------

    /** @return false if no passcode exists yet (the UI must then show the "create PIN" dialog). */
    fun setCalculatorLockEnabled(enabled: Boolean): Boolean {
        if (enabled && !prefs.hasPasscode()) return false
        prefs.setCalculatorLockEnabled(enabled)
        // The user is inside the app right now, keep the current session unlocked.
        if (enabled) AppLockManager.markUnlocked()
        AppLockManager.refreshLockEnabled(appContext)
        refresh()
        return true
    }

    /** Saves a new passcode. When [enableLockAfter] is true, the lock is switched on right after. */
    fun savePasscode(passcode: String, enableLockAfter: Boolean): Boolean {
        if (!AppLockManager.setPasscode(appContext, passcode)) return false
        if (enableLockAfter) setCalculatorLockEnabled(true) else refresh()
        return true
    }

    fun setDisguiseIconEnabled(enabled: Boolean) {
        AppLockManager.setDisguiseEnabled(appContext, enabled)
        refresh()
    }

    // -------- Notifications --------

    fun setDetailedNotifications(enabled: Boolean) {
        // The recording service listens for this key and updates the existing notification in place.
        prefs.setDetailedNotificationsEnabled(enabled)
        refresh()
    }

    // -------- Remote upload --------

    fun setUploadEnabled(enabled: Boolean) {
        prefs.setUploadEnabled(enabled)
        UploadScheduler.syncMidnightRetry(appContext, reanchor = enabled)
        refresh()
    }

    fun setProtocol(protocol: AddonPreferences.Protocol) {
        val previous = prefs.getProtocol()
        prefs.setProtocol(protocol)
        // Drop a port that was just the previous protocol's default so the new default applies.
        if (prefs.hasCustomPort() && prefs.getPort() == previous.defaultPort) prefs.setPort(null)
        refresh()
    }

    fun setHost(host: String) { prefs.setHost(host); refresh() }
    fun setPort(port: String) { prefs.setPort(port.trim().toIntOrNull()); refresh() }
    fun setUsername(username: String) { prefs.setUsername(username); refresh() }
    fun setPassword(password: String) { prefs.setPassword(password); refresh() }
    fun setRemoteDir(dir: String) { prefs.setRemoteDir(dir); refresh() }
    fun setSftpKeyAuth(enabled: Boolean) { prefs.setSftpKeyAuth(enabled); refresh() }
    fun setSftpPrivateKey(key: String) { prefs.setSftpPrivateKey(key); refresh() }
    fun setSftpPassphrase(passphrase: String) { prefs.setSftpPassphrase(passphrase); refresh() }
    fun forgetPinnedHostKey() { prefs.setSftpHostKeyFingerprint(null); refresh() }

    fun testConnection() {
        if (_testState.value is ActionState.Running) return
        _testState.value = ActionState.Running
        viewModelScope.launch {
            val result = UploadProcessor.testConnection(appContext)
            _testState.value = result.fold(
                onSuccess = { ActionState.Done(true, it) },
                onFailure = { ActionState.Done(false, it.message ?: it.javaClass.simpleName) }
            )
            refresh()
        }
    }

    /** Enqueues one silent WorkManager pass over all queued pairs right now (runs as soon as a network is available). */
    fun retryFailedNow() {
        if (!prefs.isUploadEnabled() || !prefs.isUploadConfigured()) {
            _retryState.value = ActionState.Done(false, "Enable and configure remote upload first.")
            return
        }
        UploadScheduler.retryAllNow(appContext)
    }

    init {
        // Mirror the manual retry job state inline in Settings.
        viewModelScope.launch {
            var sawWork = false
            UploadScheduler.retryNowWorkInfo(appContext).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                _retryState.value = when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> { sawWork = true; ActionState.Done(false, "Waiting for a network connection…") }
                    WorkInfo.State.RUNNING -> { sawWork = true; ActionState.Running }
                    WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        if (!sawWork) ActionState.Idle // stale result from an earlier app session
                        else if (queue.count.value == 0) ActionState.Done(true, "All pairs uploaded.")
                        else ActionState.Done(false, "${queue.count.value} pair(s) still waiting. ${lastUploadError().orEmpty()}".trim())
                    }
                }
                refresh()
            }
        }
    }
}
