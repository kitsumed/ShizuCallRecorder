package com.kitsumed.shizucallrecorder.workers

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.util.concurrent.TimeUnit

class DailyCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "DailyCleanupWorker"
        private const val TAG = "SCR:DailyCleanupWorker"
    }

    override suspend fun doWork(): Result {
        val prefs = AppPreferences(applicationContext)
        val days = prefs.getAutoDeleteDays()

        if (days <= 0) {
            AppLogger.d(TAG, "Auto-delete is disabled (days=$days). Skipping cleanup.")
            return Result.success()
        }

        val folderUri = prefs.getRecordingFolderUri()
        if (folderUri == null) {
            AppLogger.d(TAG, "Recording folder is not set. Skipping cleanup.")
            return Result.success()
        }

        val folderDoc = DocumentFile.fromTreeUri(applicationContext, folderUri)
        if (folderDoc == null || !folderDoc.canRead()) {
            AppLogger.w(TAG, "Cannot read recording folder. Skipping cleanup.")
            return Result.success()
        }

        val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        var deletedCount = 0
        var failedCount = 0

        for (file in folderDoc.listFiles()) {
            if (file.isFile && file.lastModified() < cutoffTime) {
                // Only delete audio files to avoid deleting unintended files if the folder is mixed
                if (file.type?.startsWith("audio/") == true) {
                    try {
                        if (file.delete()) {
                            deletedCount++
                            AppLogger.d(TAG, "Deleted old recording: ${file.name}")
                        } else {
                            failedCount++
                            AppLogger.w(TAG, "Failed to delete old recording: ${file.name}")
                        }
                    } catch (e: Exception) {
                        failedCount++
                        AppLogger.e(TAG, "Exception while deleting recording: ${file.name}", e)
                    }
                }
            }
        }

        AppLogger.i(TAG, "Cleanup finished. Deleted: $deletedCount, Failed: $failedCount")
        return Result.success()
    }
}
