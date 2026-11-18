/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.external

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.services.recording.RecordingForegroundService
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * Public entry point for external applications to control the recording.
 *
 * This receiver is exported but protected with [ExternalRecordingApi.PERMISSION_CONTROL_RECORDING] in the manifest,
 * Android refuses to deliver the broadcast if the sender was not granted the permission by the user.
 *
 * It does not record anything itself. Commands are only forwarded to the [RecordingForegroundService] when it is
 * already running for a detected call, the external application can never start the service by itself.
 *
 * @see <a href="https://developer.android.com/guide/topics/manifest/receiver-element#prmsn">receiver android:permission</a>
 */
class ExternalRecordingControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.action
        AppLogger.i("Received external recording command: $command")

        if (RecordingForegroundService.hasActiveCallSession) {
            // The service makes the final decision since it knows the current recording state.
            val serviceIntent = Intent(context, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_EXTERNAL_COMMAND
                putExtra(RecordingForegroundService.EXTRA_EXTERNAL_COMMAND, command)
            }
            try {
                // The service is already a foreground service, so startService is allowed.
                context.startService(serviceIntent)
                return
            } catch (e: Exception) {
                AppLogger.e("Failed to forward the external command to RecordingForegroundService.", e)
            }
        }

        // No call detected (or the service could not be reached), reply directly.
        val decision = ExternalRecordingApi.decideCommand(
            command,
            AppPreferences(context).isExternalRecordingControlEnabled(),
            ExternalRecordingApi.ServicePhase.NO_CALL
        )
        ExternalRecordingStateNotifier(context).publishDecision(decision)
    }
}
