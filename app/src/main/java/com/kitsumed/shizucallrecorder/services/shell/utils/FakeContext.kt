/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

/*
 * This script was inspired by the scrcpy fake context implementation.
 * Last checked commit: https://github.com/Genymobile/scrcpy/blob/19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac/server/src/main/java/com/genymobile/scrcpy/FakeContext.java
 */

package com.kitsumed.shizucallrecorder.services.shell.utils

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.IContentProvider
import android.os.Build
import android.os.Process
import androidx.annotation.Keep
import androidx.annotation.RequiresApi

/**
 * A mock [Context] that spoofs the system identity as `com.android.shell` (UID 2000).
 *
 * This fake context uses the system context (`android`) as a base, overriding identity
 * and attribution methods to match the process running via ADB/Shizuku (shell, UID 2000),
 * preventing permission issues when accessing system services or APIs. (Mismatching process identity, android vs shell).
 *
 * It also re-implements parts of the system context to prevent crashes when the android framework attempts to use them.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class FakeContext private constructor() : ContextWrapper(ShellWorkarounds.getSystemContext()) {

    companion object {
        /**
         * The standard Android shell package name.
         */
        const val PACKAGE_NAME = "com.android.shell"

        /**
         * Singleton instance of [FakeContext].
         */
        val instance: FakeContext by lazy { FakeContext() }

        /**
         * Retrieves the singleton [FakeContext] instance.
         *
         * @return The [FakeContext] instance.
         */
        fun get(): FakeContext = instance
    }


    /**
     * These methods implicitly override @hide methods in the ContentResolver.
     * The compiler does not know they override superclass methods, so we cannot use @Override.
     * R8 will remove them if we don't use @Keep, since it will consider them dead code.
     *
     * If the app start crashing with NoSuchMethodError, NullPointerException, AbstractMethodError, etc
     * We need to check the latest signatures / OEM specific one.
     */
    private val contentResolver = object : ContentResolver(this) {
        /*
         * By default, Java objects can always be null, kotlin require an annotation (??), but for both,
         * the return type (nullable or not) is not taken into account for the method signature.
         * The only risks in kotlin is that if java send us null, and we don't tell kotlin side it can,
         * it will crash with NullPointerException.
         */

        @Suppress("unused", "ProtectedInFinal")
        @Keep
        // @Override (but super-class method not visible)
        // Android 11-16: https://cs.android.com/android/platform/superproject/+/android-latest-release:frameworks/base/core/java/android/content/ContentResolver.java;l=893-896
        protected fun acquireProvider(c: Context?, name: String?): IContentProvider? {
            return null // TODO: Scrcpy return an object here to prevent 2000 UID security exceptions
        }

        @Suppress("unused")
        @Keep
        // @Override (but super-class method not visible)
        // Android 11-16: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/core/java/android/content/ContentResolver.java;l=887-889
        fun releaseProvider(icp: IContentProvider?): Boolean {
            return false
        }

        @Suppress("unused", "ProtectedInFinal")
        @Keep
        // @Override (but super-class method not visible)
        // Android 11-16: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/core/java/android/content/ContentResolver.java;l=891-893
        protected fun acquireUnstableProvider(c: Context?, name: String?): IContentProvider? {
            return null
        }

        @Suppress("unused")
        @Keep
        // @Override (but super-class method not visible)
        // Android 11-16: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/core/java/android/content/ContentResolver.java;l=895-897
        fun releaseUnstableProvider(icp: IContentProvider?): Boolean {
            return false
        }

        @Suppress("unused")
        @Keep
        // @Override (but super-class method not visible)
        // Android 11-16: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/core/java/android/content/ContentResolver.java;l=899-901
        fun unstableProviderDied(icp: IContentProvider?) {
            // ignore
        }
    }

    override fun getPackageName(): String = PACKAGE_NAME
    override fun getOpPackageName(): String = PACKAGE_NAME

    /**
     * Builds an [AttributionSource] identifying the caller as `com.android.shell` under UID 2000.
     *
     * Required on Android 12+ (API 31+) for system permission checks when calling `AudioRecord.Builder.setContext()`.
     *
     * @see <a href="https://cs.android.com/android/platform/superproject/+/android12-release:frameworks/base/core/java/android/content/AttributionSource.java;l=54-55">AOSP Android 12 AttributionSource.java</a>
     */
    @RequiresApi(Build.VERSION_CODES.S)
    override fun getAttributionSource(): AttributionSource {
        return AttributionSource.Builder(Process.SHELL_UID)
            .setPackageName(PACKAGE_NAME)
            .build()
    }

    // Fix audio issues for Android 14+ (Preview2): https://github.com/Genymobile/scrcpy/commit/2fff9b9edf749dd7a8ccf36fe2df3c3587f535ab
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Suppress("unused")
    override fun getDeviceId(): Int {
        return Context.DEVICE_ID_DEFAULT // Default value should be 0
    }

    override fun getApplicationContext(): Context = this

    override fun createPackageContext(packageName: String, flags: Int): Context = this

    override fun getContentResolver(): ContentResolver {
        return contentResolver
    }
}