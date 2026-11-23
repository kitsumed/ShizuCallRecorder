/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.upload

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kitsumed.shizucallrecorder.data.AddonPreferences
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Silent background upload job. Never calls setForeground(), never posts a notification, never asks WorkManager to retry:
 * a failed pair simply stays in the local queue until the midnight job or a manual "Retry Failed Uploads Now".
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_ENTRY_ID = "entry_id"
        const val MODE_SINGLE = "single"
        const val MODE_ALL = "all"
    }

    override suspend fun doWork(): Result {
        try {
            when (inputData.getString(KEY_MODE)) {
                MODE_SINGLE -> inputData.getString(KEY_ENTRY_ID)?.let { UploadProcessor.processEntry(applicationContext, it) }
                else -> UploadProcessor.processAll(applicationContext)
            }
        } catch (e: Exception) {
            AppLogger.w("UploadWorker: unexpected failure", e)
        }
        // Always success: retries are handled by the daily job / manual retry only (no tight retry/backoff loops).
        return Result.success()
    }
}

/** Enqueues the add-on upload work with battery-friendly constraints. No exact alarms, no expedited/foreground work. */
object UploadScheduler {

    private const val TAG = "addon_upload"
    private const val WORK_POST_CALL_PREFIX = "addon_upload_post_call_"
    private const val WORK_RETRY_NOW = "addon_upload_retry_now"
    private const val WORK_MIDNIGHT = "addon_upload_midnight_retry"

    /** Saves the pair in the local queue and enqueues the single post-call upload attempt. */
    fun enqueuePostCallUpload(context: Context, audioUri: Uri, audioName: String, jsonUri: Uri, jsonName: String) {
        val entry = UploadQueueStore.getInstance(context).add(audioUri.toString(), audioName, jsonUri.toString(), jsonName)
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(UploadWorker.KEY_MODE to UploadWorker.MODE_SINGLE, UploadWorker.KEY_ENTRY_ID to entry.id))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_POST_CALL_PREFIX + entry.id, ExistingWorkPolicy.KEEP, request)
        AppLogger.i("UploadScheduler: post-call upload enqueued for $audioName")
    }

    /** "Retry Failed Uploads Now" button: one silent pass over the whole queue. */
    fun retryAllNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(UploadWorker.KEY_MODE to UploadWorker.MODE_ALL))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_RETRY_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** Observes the state of the manual retry job, for inline status in Settings. */
    fun retryNowWorkInfo(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_RETRY_NOW)

    /**
     * Schedules (or cancels) the daily retry targeted at local midnight.
     * @param reanchor true to re-align the schedule to the next midnight (used when the user turns upload on).
     */
    fun syncMidnightRetry(context: Context, reanchor: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        if (!AddonPreferences(context).isUploadEnabled()) {
            wm.cancelUniqueWork(WORK_MIDNIGHT)
            return
        }
        val request = PeriodicWorkRequestBuilder<UploadWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(millisUntilNextMidnight(), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setInputData(workDataOf(UploadWorker.KEY_MODE to UploadWorker.MODE_ALL))
            .addTag(TAG)
            .build()
        wm.enqueueUniquePeriodicWork(
            WORK_MIDNIGHT,
            if (reanchor) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun millisUntilNextMidnight(): Long {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val nextMidnight = LocalDate.now(zone).plusDays(1).atTime(LocalTime.MIDNIGHT).atZone(zone)
        return Duration.between(now, nextMidnight).toMillis().coerceAtLeast(0L)
    }
}
