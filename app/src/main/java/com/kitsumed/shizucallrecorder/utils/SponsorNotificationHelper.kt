/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.utils

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import com.kitsumed.shizucallrecorder.R
import java.util.concurrent.TimeUnit

object SponsorNotificationHelper {
    private const val CHANNEL_ID_REMINDER = "support_project_reminder_channel"
    private const val REMINDER_NOTIFICATION_ID = 228

    fun showSupportReminderNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val groupId = "app_info_channel_group"
        val group = NotificationChannelGroup(groupId, "App General & Support")
        manager.createNotificationChannelGroup(group)

        val reminderChannel = NotificationChannel(
            CHANNEL_ID_REMINDER,
            "Project Support Reminder",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            this.group = groupId
            setShowBadge(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC

        }
        manager.createNotificationChannel(reminderChannel)

        // Setup the intent to open the app directly to MainActivity
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_REMINDER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setContentTitle(context.getString(R.string.sponsor_notification_call_to_action_title))
            .setContentText(context.getString(R.string.sponsor_notification_call_to_action))
            .setStyle(NotificationCompat.BigTextStyle()
                .setBigContentTitle(context.getString(R.string.sponsor_title))
                .bigText("${context.getString(R.string.sponsor_days_used, getDaysSinceAppInstall(context))} ${context.getString(R.string.sponsor_notification_body)}"))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.btn_star, context.getString(R.string.sponsor_notification_action), pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()

        manager.notify(REMINDER_NOTIFICATION_ID, notification)
    }

    private fun getDaysSinceAppInstall(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val diffMs = System.currentTimeMillis() - packageInfo.firstInstallTime
            TimeUnit.MILLISECONDS.toDays(diffMs).coerceAtLeast(1)
        } catch (e: PackageManager.NameNotFoundException) {
            0L // Fallback to 0
        }
    }
}