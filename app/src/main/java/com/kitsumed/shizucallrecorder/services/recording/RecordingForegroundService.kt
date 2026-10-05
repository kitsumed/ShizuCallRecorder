/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.recording

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.IntentCompat
import androidx.documentfile.provider.DocumentFile
import com.kitsumed.shizucallrecorder.IShellService
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import com.kitsumed.shizucallrecorder.data.recording.RecordingFailure
import com.kitsumed.shizucallrecorder.integrations.shizuku.ShizukuConnectionManager
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * RecordingForegroundService is the long-running service that manage the audio-recording logic.
 */
class RecordingForegroundService : Service() {
    companion object {
        // -- Intent action for controlling and initializing the service lifecycle. --

        /** Intent action sent to this service to initialize the service and immediately start a new recording session. */
        const val ACTION_START_RECORDING = "com.kitsumed.shizucallrecorder.START_RECORDING"

        /** Intent action sent to this service to initialize and prepare the recording session in standby mode. */
        const val ACTION_STANDBY = "com.kitsumed.shizucallrecorder.STANDBY"

        /** Intent action sent to this service to stop the current recording session and kill the service. */
        const val ACTION_STOP_RECORDING = "com.kitsumed.shizucallrecorder.STOP_RECORDING"

        // -- Intent action for controlling an active recording session with notifications. --

        /** Intent action sent to this service to pause the current recording. */
        const val ACTION_PAUSE_RECORDING = "com.kitsumed.shizucallrecorder.PAUSE_RECORDING"

        /** Intent action sent to this service to resume the current recording. */
        const val ACTION_RESUME_RECORDING = "com.kitsumed.shizucallrecorder.RESUME_RECORDING"


        /**
         * Intent action sent by the standby notification's "Record" button.
         */
        const val ACTION_MANUAL_START = "com.kitsumed.shizucallrecorder.MANUAL_START_RECORDING"

        /** Intent action sent to this service when the user dismisses the notification (Android 14+). */
        const val ACTION_NOTIFICATION_DISMISSED = "com.kitsumed.shizucallrecorder.SERVICE_NOTIFICATION_DISMISSED"
    }

    // ── Dependencies ──────────────────────────────────────────────────────────
    private lateinit var shizukuManager: ShizukuConnectionManager

    private lateinit var appPreferences: AppPreferences

    private lateinit var recordingPlanResolver: RecordingPlanResolver
    private lateinit var notificationHelper: RecordingNotificationHelper

    private lateinit var overlayController: RecordingOverlayController

    /** IPC stub to the privileged ShellService running in the shell process. */
    private var shellService: IShellService? = null

    /** Scope for service lifecycle operations (binding, etc.) */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Recording session state ────────────────────────────────────────────────────────

    /* The current state of the service. */
    private val _serviceState = MutableStateFlow<RecordingServiceState>(RecordingServiceState.Standby(null))

    /* Exposes the current state of the recording service (read-only) for external observation. */
    val serviceState = _serviceState.asStateFlow()

    // ── Service lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        notificationHelper = RecordingNotificationHelper(this)
        notificationHelper.createNotificationChannels()
        overlayController = RecordingOverlayController(this)

        appPreferences = AppPreferences(this)
        recordingPlanResolver = RecordingPlanResolver(this)

        // Launch a collector to do actions on the service state changes
        serviceScope.launch(Dispatchers.Main.immediate) { // Use immediate to ensure we get the initial (oldState) value on launch
            var oldState: RecordingServiceState = _serviceState.value
            _serviceState.collect { newState ->
                if (oldState != newState) {
                    updateNotification()
                    notificationHelper.handleStateChangeToasts(oldState, newState)
                    overlayController.showOverlay(newState)
                    oldState = newState
                }
            }
        }

        shizukuManager = ShizukuConnectionManager(this) {
            AppLogger.w( "Received callback from ShizukuConnectionManager: Shizuku disconnected unexpectedly. Stopping recording service...")
            // Handle cleanup if the service dies during recording
            if (_serviceState.value.isRecordingActive) {
                notificationHelper.showErrorNotification(getString(R.string.recording_error_shizuku_disconnected_unexpectedly))
                stopRecordingSessionAndService()
            }
        }

        AppLogger.d( "RecordingForegroundService initialized")
    }

    // No binding. This is a fully command-based service. All interactions are via startService with intent actions.
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Entry point for intent-based commands (START / MANUAL_START / STOP).
     *
     * Returns [Service.START_NOT_STICKY] so Android does not auto-restart the service after a process
     * kill; recording must always be explicitly triggered by an active call.
     *
     * @param intent  The intent carrying the action constant.
     * @param flags   Standard Android service start flags.
     * @param startId Unique ID for this start request (not used; state is managed manually).
     * @return [Service.START_NOT_STICKY].
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val state = _serviceState.value

        var currentMeta = state.metadata

        // Parse metadata if present in the intent (START/STANDBY)
        if (intent != null) {
            val newMetadata = IntentCompat.getParcelableExtra(
                intent,
                EnrichedCallData.EXTRA_METADATA,
                EnrichedCallData::class.java
            )
            if (newMetadata != null) {
                currentMeta = newMetadata
            }
        }

        // Quickly show a notification to satisfy Android's foreground service requirements,
        // as starting/waiting for Shizuku can take long enough for the OS to kill the service.
        updateNotification()

        when (action) {
            ACTION_START_RECORDING, ACTION_MANUAL_START -> {
                if (state.isRecordingActive) {
                    AppLogger.w( "Start request ignored. A session is already on-going.")
                    return START_NOT_STICKY
                }

                // At this point, we should already have the metadata from the intents, if it's missing, there's a logic error to be fixed.
                if (currentMeta == null) {
                    AppLogger.e( "Start request received without metadata. Cannot start recording session.")
                    notificationHelper.showErrorNotification(getString(R.string.recording_unexpected_error))
                    stopRecordingSessionAndService()
                    return START_NOT_STICKY // We won't reach this anyway.
                }

                _serviceState.update { RecordingServiceState.Starting(currentMeta) }

                // If enabled in the user preferences, we try to start the Shizuku as we are now starting the recording.
                tryStartShizukuServer()

                serviceScope.launch {
                    try {
                        // Wait for Shizuku server to be available
                        ShizukuConnectionManager.waitForServer()
                        val service = shizukuManager.getShellService()
                        shellService = service // update local ref
                        startNewRecordingSession(service, currentMeta)
                    } catch (e: SecurityException) { // Shizuku permission not granted
                        AppLogger.e( "Shizuku permission was denied / not granted", e)
                        notificationHelper.showErrorNotification(getString(R.string.recording_shizuku_permission_denied))
                        stopRecordingSessionAndService()
                    } catch (e: Exception) { // Shizuku not running or other binding connection errors
                        // Don't catch coroutine cancellations, they are used for cleanup. This creates a false error notification when everything's fine.
                        if (e is CancellationException) throw e

                        AppLogger.e( "Failed to perform ShellService binding with Shizuku. Ensure it is running, else look at error related to failed binding.", e)
                        notificationHelper.showErrorNotification(getString(R.string.recording_shizuku_not_started) + "\nLocalized: " + e.localizedMessage)
                        stopRecordingSessionAndService()
                    } finally {
                        _serviceState.update { currentState ->
                            // If we failed to start the recording session, we should return to standby state with the current metadata.
                            if (currentState is RecordingServiceState.Starting) {
                                RecordingServiceState.Standby(currentMeta)
                            } else {
                                currentState
                            }
                        }
                    }
                }
            }

            ACTION_STANDBY -> {
                _serviceState.update { RecordingServiceState.Standby(currentMeta) }
                serviceScope.launch {
                    // If enabled in the user preferences, we try to start the Shizuku server as early as possible (in the standby state, RINGING/OUTGOING),
                    // increasing the chance it's ready by the time we need it. But this means Shizuku will be running without the user starting the recording yet.
                    if (!appPreferences.isShizukuStartOnRecordEnabled()) {
                        tryStartShizukuServer()
                    }
                    AppLogger.i( "Entered standby for ${currentMeta?.direction} call")
                }
            }

            ACTION_PAUSE_RECORDING -> {
                _serviceState.update { currentState ->
                    if (currentState is RecordingServiceState.Active) {
                        currentState.session.pause() // Pause the recording session
                        currentState.copy(isPaused = true) // Update the service state to reflect the paused state
                    } else {
                        currentState
                    }
                }
            }

            ACTION_RESUME_RECORDING -> {
                _serviceState.update { currentState ->
                    if (currentState is RecordingServiceState.Active) {
                        currentState.session.resume()
                        currentState.copy(isPaused = false)
                    } else {
                        currentState
                    }
                }
            }

            ACTION_STOP_RECORDING -> stopRecordingSessionAndService()
            ACTION_NOTIFICATION_DISMISSED -> {
                AppLogger.d( "Ongoing foreground service notification dismissed by user (Android 14+), reposting.")
                updateNotification()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Tries to start the Shizuku server if "Auto-manage Shizuku" is enabled in the user preferences.
     * If the auth key is missing, shows an error notification and stops the service since we won't be able to record without Shizuku.
     * **This does not check the user preference for if shizuku should only start when recording or directly at standby**.
     */
    private fun tryStartShizukuServer() {
        if (appPreferences.isShizukuAutoManageEnabled()) {
            val authKey = appPreferences.getShizukuAuthKey()
            if (authKey.isNotBlank()) {
                ShizukuConnectionManager.startServer(this, authKey)
            } else {
                notificationHelper.showErrorNotification(getString(R.string.recording_shizuku_auth_key_missing))
                notificationHelper.showToast(getString(R.string.recording_shizuku_auth_key_missing))
                stopRecordingSessionAndService()
            }
        }
    }

    override fun onDestroy() {
        // Always clean up, even if the OS kills the service mid-recording.
        // This is the guaranteed last callback before the service process is cleaned up.
        AppLogger.v( "RecordingForegroundService is destroying... Ensuring cleanup...")
        serviceScope.cancel()
        overlayController.hideOverlay()
        stopRecordingSessionAndService()
        shizukuManager.unbind()
        if (appPreferences.isShizukuAutoManageEnabled() && !appPreferences.isShizukuKeepAliveEnabled()) {
            ShizukuConnectionManager.stopServer(this, appPreferences.getShizukuAuthKey())
        }
        super.onDestroy()
    }

    // ── Service internal logic ───────────────────────────────────────

    /**
     * Starts a new recording session based on the provided parameters.
     *
     * If a session is already active, this method will log a warning and return without starting a new session.
     *
     * @param service The [IShellService] instance to use for recording.
     * @param metadata The [EnrichedCallData] containing call information for the recording session.
     */
    private fun startNewRecordingSession(service: IShellService, metadata: EnrichedCallData) {
        if (_serviceState.value.isRecordingActive) {
            AppLogger.w("startNewRecordingSession() called while a session is already active – ignoring")
            return
        }

        val plan = recordingPlanResolver.resolve(metadata)

        AppLogger.i("Resolved final recording plan: $plan")

        val activeSession = try {
            RecordingSession(
                context = this,
                remoteService = service,
                initializationMetadata = metadata,
                plan = plan,
                // Handle events reported by the remote recording engine
                listener = object : RecordingSession.SessionListener {
                    override fun onSessionFailed(failure: RecordingFailure) {
                        val userFriendlyMessage = getString(failure.descriptionResId)
                        AppLogger.w("Recording session was notified of a failure with error: $failure. Showing user-friendly message: $userFriendlyMessage")
                        notificationHelper.showErrorNotification(userFriendlyMessage)
                        stopRecordingSessionAndService()
                    }
                }
            )
        } catch (e: RecordingSession.InitializationException) {
            AppLogger.e(e.message ?: "Empty Message.", "Remote recording engine reported: $e")
            notificationHelper.showErrorNotification(e.userFriendlyMessage)
            stopRecordingSessionAndService()
            return
        }

        _serviceState.update {
            RecordingServiceState.Active(
                session = activeSession,
                isPaused = false,
                metadata = metadata
            )
        }

        AppLogger.i("The recording session has been started successfully. Service state updated to Active.")
    }

    /**
     * Stops the current recording session (if any) and stops the foreground service.
     */
    private fun stopRecordingSessionAndService() {
        val activeSession = (_serviceState.value as? RecordingServiceState.Active)?.session

        if (activeSession == null) {
            AppLogger.d("No active session, exiting standby state, removing foreground notification and stopping service.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        AppLogger.i("Stopping active recording session, removing foreground notification and stopping service...")
        activeSession.release()

        // If the user has enabled post-recording file actions, show a notification with options.
        if (appPreferences.isPostRecordingFileActionsNotificationEnabled()) {
            activeSession.currentRecordingUri?.let { filePathUri ->
                activeSession.initializationMetadata.let { metadata ->

                    // Ensure the file was written and exists
                    val docFile = DocumentFile.fromSingleUri(applicationContext, filePathUri)
                    if (docFile != null && docFile.exists() && docFile.length() > 0) {
                        AppLogger.d("Showing post-recording notification for user actions.")

                        notificationHelper.showPostCallNotification(
                            filePathUri,
                            metadata
                        )
                    }
                }
            }
        }

        _serviceState.update {
            RecordingServiceState.Standby(null)
        }

        AppLogger.i("Recording session stopped and resources released. Stopping foreground service.")

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf() // Stop the service since the session is over
    }

    /**
     * Updates the foreground service notification based on the current state (Recording or Standby).
     */
    private fun updateNotification() {
        val notification = notificationHelper.getServiceNotification(_serviceState.value)
        startForegroundWithType(notification)
    }


    /**
     * Calls [startForeground] with the appropriate [ServiceInfo] foreground service type.
     *
     * @see <a href="https://developer.android.com/guide/components/foreground-services#background-start-restriction">Foreground Service Restrictions (Android 12+)</a>
     * @param notification The notification to display while in the foreground.
     */
    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            // specialUse is the best type to use from Android 14+ for our call recording use-cases.
            // See: https://developer.android.com/about/versions/14/changes/fgs-types-required#special-use
            startForeground(
                RecordingNotificationHelper.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            // Android 11-13 uses the not yet restricted Data Sync type.
            // Starting Android 15, dataSync type is restricted to a total of 6 hours runtime in a specific time period.
            startForeground(
                RecordingNotificationHelper.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
    }
}