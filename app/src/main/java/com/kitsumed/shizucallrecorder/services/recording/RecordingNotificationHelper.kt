/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import com.kitsumed.shizucallrecorder.utils.RecordingFileNameFormatter
import com.kitsumed.shizucallrecorder.ui.theme.Green40

class RecordingNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID_SERVICE = "recording_channel_service"
        const val CHANNEL_ID_ERROR = "recording_channel_error"
        const val CHANNEL_ID_POST_RECORDING_FILE_ACTIONS = "recording_channel_post_recording_file_actions"

        const val SERVICE_NOTIFICATION_ID = 1
        const val ERROR_NOTIFICATION_ID = 2
        const val POST_RECORDING_FILE_ACTIONS_NOTIFICATION_ID = 3
        private const val REQUEST_CODE_OPEN_RECORDING = 1001
        private const val REQUEST_CODE_SHARE_RECORDING = 1002
        private const val REQUEST_CODE_DELETE_RECORDING = 1003
    }

    /**
     * Creates the Android notification channel for recording notifications.
     */

    fun createNotificationChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)

        // Recording Group
        val groupId = "recording_channel_group"
        val group = NotificationChannelGroup(groupId, "Recording Group")
        manager.createNotificationChannelGroup(group)

        // Recording Service Channel
        val serviceChannel = NotificationChannel(
            CHANNEL_ID_SERVICE, "Foreground Recording Service", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            this.group = groupId
            // Alert channel should be visible but we handle vibration manually
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(serviceChannel)

        // Error Channel
        val errorChannel = NotificationChannel(
            CHANNEL_ID_ERROR, "Recording Errors", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            this.group = groupId
            enableVibration(false) // We handle vibration manually
            setShowBadge(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(errorChannel)

        // Post Recording File Actions Channel
        val postCallChannel = NotificationChannel(CHANNEL_ID_POST_RECORDING_FILE_ACTIONS,
            "Post-Call Quick Actions",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            this.group = groupId
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
            setShowBadge(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(postCallChannel)
    }

    /**
     * Creates a notification for the recording service based on the current state.
     * @param state The current state of the recording service.
     * @return A Notification object that can be used to update the foreground service notification.
     */
    fun getServiceNotification(state: RecordingServiceState): Notification {
        val titleRes: Int
        val contentRes: Int
        val actionIcon: Int?
        val actionText: String?
        val actionIntentAction: String?
        // We want to show the cross-country tip if we are unsure about the metadata, as it is better to be safe than sorry.
        val subRes: Int = if (state.metadata == null || state.metadata?.isCrossCountry == true) R.string.recording_notification_cross_country_tip else R.string.recording_notification_current_country_tip

        when (state) {
            is RecordingServiceState.Starting -> {
                titleRes = R.string.recording_standby_notification_title
                contentRes = R.string.recording_notification_waiting_shizuku
                actionIcon = null
                actionText = null
                actionIntentAction = null
            }
            is RecordingServiceState.Active -> {
                if (state.isPaused) {
                    titleRes = R.string.recording_standby_notification_title
                    contentRes = R.string.recording_notification_press_to_resume
                    actionIcon = R.drawable.ic_stop
                    actionText = context.getString(R.string.general_resume)
                    actionIntentAction = RecordingForegroundService.ACTION_RESUME_RECORDING
                } else {
                    titleRes = R.string.recording_notification_title
                    contentRes = R.string.recording_notification_press_to_pause
                    actionIcon = R.drawable.ic_mic
                    actionText = context.getString(R.string.general_pause)
                    actionIntentAction = RecordingForegroundService.ACTION_PAUSE_RECORDING
                }
            }
            is RecordingServiceState.Standby -> {
                titleRes = R.string.recording_standby_notification_title
                contentRes = R.string.recording_notification_press_to_start
                actionIcon = R.drawable.ic_mic
                actionText = context.getString(R.string.general_record)
                actionIntentAction = RecordingForegroundService.ACTION_MANUAL_START
            }
        }

        // The delete intent is triggered when the user dismisses the notification (Thanks Android 14+).
        val deletePendingIntent = PendingIntent.getService(
            context, 99,
            Intent(context, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_NOTIFICATION_DISMISSED
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_mic)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(contentRes))
            .setSubText(context.getString(subRes))
            .setOngoing(true) // Almost useless starting Android 14+ :)
            .setDeleteIntent(deletePendingIntent) // Android 14+ workaround :)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Nothing sensible here, and we want to show it on lockscreen.
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setColor(Green40.toArgb())
            .setColorized(state.isRecordingActive && !state.isRecordingPaused)
            .setSilent(state.isStarting || state.isRecordingActive) // Don't do a screen-incursion if we are already recording.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (actionText != null && actionIntentAction != null && actionIcon != null) {
            val actionPendingIntent = PendingIntent.getService(
                context, 1,
                Intent(context, RecordingForegroundService::class.java).apply {
                    action = actionIntentAction
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(actionIcon, actionText, actionPendingIntent)
        }

        return builder.build()
    }

    /**
     * Handles showing toasts for state changes.  It determines a toast based on the old and new state.
     * @param oldState The previous state of the recording service.
     * @param newState The new state of the recording service.
     */
    fun handleStateChangeToasts(oldState: RecordingServiceState, newState: RecordingServiceState) {
        if (oldState == newState) return // Ignore duplicates

        when (newState) {
            is RecordingServiceState.Standby  -> {
                if (newState.metadata == null) {
                    showToast(context.getString(R.string.recording_toast_ended))
                    vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), intArrayOf(0, 64, 0, 128), -1))
                } else {
                    val dirLabel = newState.metadata.direction.labelResId.let { context.getString(it) }
                    showToast(context.getString(R.string.recording_toast_standby, dirLabel))
                }
            }

            is RecordingServiceState.Active -> {
                val wasActiveAndPaused = oldState.isRecordingPaused

                if (newState.isPaused && !wasActiveAndPaused) {
                    // Recording was paused
                    showToast(context.getString(R.string.recording_toast_paused))
                    vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), intArrayOf(0, 64, 0, 128), -1))
                } else if (!newState.isPaused && wasActiveAndPaused) {
                    // Recording was resumed
                    showToast(context.getString(R.string.recording_toast_resumed))
                    vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), intArrayOf(0, 64, 0, 128), -1))
                } else if (!newState.isPaused) {
                    // Recording was started
                    showToast(context.getString(R.string.recording_started))
                    vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), intArrayOf(0, 64, 0, 128), -1))
                }
            }
            else -> {}
        }
    }

    /**
     * Shows a notification after a call recording has been completed, allowing the user to play, share, or delete the recording.
     * @param fileUri The URI of the recorded audio file.
     * @param callMetadata Metadata about the recorded call.
     */
    fun showPostCallNotification(fileUri: Uri, callMetadata: EnrichedCallData) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val number = callMetadata.getBestNumber()
        val callerText = when {
            callMetadata.callerName != null && number.isNotEmpty() -> "${callMetadata.callerName} ($number)"
            callMetadata.callerName != null -> callMetadata.callerName
            number.isNotEmpty() -> number
            else -> context.getString(R.string.post_recording_notification_unknown_caller)
        }

        // Play action
        val playIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, "audio/*")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION // Allows the receiving app to read the SAF file
        }
        val playPendingIntent = PendingIntent.getActivity(
            context, REQUEST_CODE_OPEN_RECORDING, playIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Share action
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        val chooserIntent = Intent.createChooser(shareIntent, null)
        val sharePendingIntent = PendingIntent.getActivity(
            context, REQUEST_CODE_SHARE_RECORDING, chooserIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Delete action (Triggers our DeleteDialogConfirmationActivity)
        val deleteIntent = Intent(context, DeleteDialogConfirmationActivity::class.java).apply {
            putExtra(Intent.EXTRA_STREAM, fileUri)
        }
        val deletePendingIntent = PendingIntent.getActivity(
            context, REQUEST_CODE_DELETE_RECORDING, deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_POST_RECORDING_FILE_ACTIONS)
            .setSmallIcon(R.drawable.ic_audio_file)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setContentTitle(context.getString(R.string.post_recording_notification_title))
            .setContentText(callerText)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_media_play, context.getString(R.string.general_play), playPendingIntent)
            .addAction(android.R.drawable.ic_menu_share, context.getString(R.string.general_share), sharePendingIntent)
            .addAction(android.R.drawable.ic_menu_delete, context.getString(R.string.general_delete), deletePendingIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        manager.notify(POST_RECORDING_FILE_ACTIONS_NOTIFICATION_ID, notification)
    }

    /**
     * Shows a short Toast message on the UI thread.
     */
    fun showToast(message: String) {
        if (!AppPreferences(context).isShowToastsEnabled()) return

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Posts an error notification visible.
     *
     * @param message Human-readable error description to show in the notification body.
     */
    fun showErrorNotification(message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ERROR)
            .setSmallIcon(R.drawable.ic_outline_error)
            .setContentTitle(context.getString(R.string.recording_error_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ERROR_NOTIFICATION_ID, notification)
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 800), intArrayOf(0, 46, 184), -1))
    }

    /**
     * Triggers a vibration if enabled in settings.
     */
    fun vibrate(effect: VibrationEffect) {
        if (!AppPreferences(context).isVibrationEnabled()) return

        if (Build.VERSION.SDK_INT >= 31) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            if (manager.defaultVibrator.hasVibrator()) {
                manager.defaultVibrator.vibrate(effect)
            }
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(effect)
            }
        }
    }
}
