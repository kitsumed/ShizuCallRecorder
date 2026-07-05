/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kitsumed.shizucallrecorder.services.callDetection.CallDetectionOrchestrator
import com.kitsumed.shizucallrecorder.utils.AppLogger
import com.kitsumed.shizucallrecorder.workers.DailyCleanupWorker
import java.util.concurrent.TimeUnit

/**
 * ShizuApplication is run when the app process is created. Can be seen as the very first entry point of the app.
 */
class ShizuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)
        // Sync configurations down to PackageManager mapping immediately on launch
        CallDetectionOrchestrator(applicationContext).syncComponents()

        // Enqueue daily cleanup worker
        val cleanupWorkRequest = PeriodicWorkRequestBuilder<DailyCleanupWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            DailyCleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupWorkRequest
        )
    }
}