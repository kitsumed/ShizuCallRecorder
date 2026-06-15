/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import com.kitsumed.shizucallrecorder.AppUrls
import com.kitsumed.shizucallrecorder.BuildConfig
import com.kitsumed.shizucallrecorder.integrations.shizuku.ShizukuConnectionManager
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * SystemIntentHelpers.kt contains shortcuts for opening system screens and doing
 * other [Context] related tasks.
 */

/** Package name of the Shizuku app. */
private const val TAG = "SCR:SystemIntentHelpers"

/**
 * A folder-picker that asks for long-term read and write access to the chosen folder.
 *
 * Android normally only grants temporary access to a folder. This contract also requests
 * "persistable" access so the app can still read and write the folder after a reboot -
 * without asking the user again.
 */
class PersistentFolderPickerContract : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        return super.createIntent(context, input).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            // Makes the access survive app restarts and reboots.
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
    }
}

/**
 * Locks in long-term read and write access to [uri] so it remains valid after a reboot.
 * Call this immediately after the user picks a folder with [PersistentFolderPickerContract].
 *
 * @param uri The folder URI returned by [PersistentFolderPickerContract].
 */
fun Context.takePersistableFolderPermission(uri: Uri) {
    contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )
}

/**
 * Opens the App Info page for this app.
 * The user can manually grant or revoke permissions from here.
 */
fun Context.openAppSettings() {
    launchSmartIntent(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$packageName".toUri()
        }
    )
}

/**
 * Opens the Shizuku app.
 * If Shizuku is not installed, opens the Shizuku website so the user can download it.
 */
fun Context.openShizukuManager() {
    val packageName = ShizukuConnectionManager.getPackageName(this) ?: ""
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    if (launchIntent != null) {
        launchSmartIntent(launchIntent)
    } else {
        launchSmartIntent(Intent(Intent.ACTION_VIEW).apply { data = AppUrls.SHIZUKU_WEBSITE.toUri() })
    }
}

/** Opens the project GitHub page in the browser. */
fun Context.openGithub() {
    launchSmartIntent(Intent(Intent.ACTION_VIEW).apply { data = AppUrls.GITHUB_REPOSITORY.toUri() })
}

/** Opens the Github wiki/documentation page in the browser. */
fun Context.openGithubWiki() {
    launchSmartIntent(Intent(Intent.ACTION_VIEW).apply { data = AppUrls.GITHUB_WIKI.toUri() })
}

/** Opens the Github report issue page in the browser. */
fun Context.openGithubReportIssue() {
    launchSmartIntent(Intent(Intent.ACTION_VIEW).apply { data = AppUrls.GITHUB_NEW_ISSUE.toUri() })
}

fun Context.openGithubSponsor() {
    launchSmartIntent(Intent(Intent.ACTION_VIEW).apply { data = AppUrls.GITHUB_SPONSOR.toUri() })
}

/**
 * Launches [intent] safely regardless of whether this [Context] is an [Activity] or not.
 *
 * When called from a non-Activity context (e.g. a ViewModel's applicationContext or a
 * background Service), Android requires [Intent.FLAG_ACTIVITY_NEW_TASK] to start a new
 * Activity.
 * @param intent The [Intent] to launch. The flag is added in-place only when needed.
 */
private fun Context.launchSmartIntent(intent: Intent) {
    if (this !is Activity) {
        if (BuildConfig.DEBUG) {
            AppLogger.w(
                TAG,
                "launchSmartIntent called from a non-Activity context (${this::class.simpleName}). " +
                "FLAG_ACTIVITY_NEW_TASK will be added automatically, but the user may not be able " +
                "to press Back to return to this app from the launched screen."
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}
