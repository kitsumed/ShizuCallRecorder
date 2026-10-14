/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

/*
 * This script was inspired by scrcpy ActivityManager Wrapper.
 * Last checked commit: https://github.com/Genymobile/scrcpy/blob/b8a3fca786fc7a998602670a27dc53e5b65e1b45/server/src/main/java/com/genymobile/scrcpy/wrappers/ActivityManager.java
*/

package com.kitsumed.shizucallrecorder.services.shell.utils.wrappers

import android.annotation.SuppressLint
import android.content.Context
import android.content.IContentProvider
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import com.kitsumed.shizucallrecorder.services.shell.utils.FakeContext
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.lang.reflect.Method

/**
 * A wrapper for Android system-level ActivityManageService.
 * Source : https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object ActivityManager {

    /**
     * Get the system ActivityManagerService (implements IActivityManager) via reflection.
     * Source: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/core/java/android/app/IActivityManager.aidl
     */
    private val manager: IInterface by lazy {
        ServiceManager.getService(Context.ACTIVITY_SERVICE, "android.app.IActivityManager")
    }

    /**
     * Source Android 16: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java;l=7085-7094
     * Source Android 11: https://cs.android.com/android/platform/superproject/+/android11-release:frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java;l=7598-7606
     * Note: scrcpy has two method since older android versions had a different signature, but we only support Android11+.
     */
    private val getContentProviderExternalMethod: Method by lazy {
        manager.javaClass.getMethod(
            "getContentProviderExternal",
            String::class.java, // String name
            Int::class.javaPrimitiveType, // int userId
            IBinder::class.java, // IBinder token
            String::class.java // String tag
        )
    }

    /**
     * Get a content provider by its name and token.
     * @param name The name of the content provider.
     * @param token The token associated with the content provider.
     * @return The IContentProvider instance if found, null otherwise.
     */
    fun getContentProviderExternal(name: String?, token: IBinder): IContentProvider? {
        return try {
            // ActivityManagerService.getContentProviderExternal(name, userId, token, tag) return a ContentProviderHolder object.
            // Source: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/core/java/android/app/ContentProviderHolder.java
            val providerHolder = getContentProviderExternalMethod.invoke(
                manager,
                name,
                FakeContext.ROOT_UID,
                token,
                // The tag can be null like scrcpy, but can help make debugging easier in dumps.
                FakeContext.PACKAGE_NAME) ?: return null
            // Get the provider interface from the Holder object
            val providerField = providerHolder.javaClass.getDeclaredField("provider").apply {
                isAccessible = true
            }
            providerField.get(providerHolder) as? IContentProvider
        } catch (e: ReflectiveOperationException) {
            AppLogger.e("Could not invoke getContentProviderExternal method", e)
            null
        }
    }

    /**
     * Source Android 16: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java;l=3254-3262
     * Note: scrcpy use the deprecated startActivityAsUser because it supports older android versions, but we only support Android11+.
     */
    private val getStartActivityAsUserWithFeatureMethod: Method by lazy {
        val iApplicationThreadClass = Class.forName("android.app.IApplicationThread")
        val profilerInfoClass = Class.forName("android.app.ProfilerInfo")
        manager.javaClass.getMethod(
            "startActivityAsUserWithFeature",
            iApplicationThreadClass, // IApplicationThread caller
            String::class.java, // String callingPackage
            String::class.java, // String callingFeatureId
            Intent::class.java, // Intent intent
            String::class.java, // String resolvedType
            IBinder::class.java, // IBinder resultTo
            String::class.java, // String resultWho
            Int::class.javaPrimitiveType, // int requestCode
            Int::class.javaPrimitiveType, // int startFlags
            profilerInfoClass, // ProfilerInfo profilerInfo
            Bundle::class.java, // Bundle bOptions
            Int::class.javaPrimitiveType // int userId
        )
    }

    /**
     * Start an activity as the current user.
     * @param intent The intent to start the activity.
     * @param options Optional bundle of options to pass to the activity.
     * @return The result code of the activity start operation.
     */
    fun startActivity(intent: Intent, options: Bundle? = null): Int {
        return try {
            getStartActivityAsUserWithFeatureMethod.invoke(
                manager,
                null, // IApplicationThread caller
                FakeContext.PACKAGE_NAME, // String callingPackage
                null, // String callingFeatureId
                intent, // Intent intent
                null, // String resolvedType
                null, // IBinder resultTo
                null, // String resultWho
                0, // int requestCode
                0, // int startFlags
                null, // ProfilerInfo profilerInfo
                options, // Bundle bOptions
                -2 // Int userId = UserHandle.USER_CURRENT
            ) as Int
        } catch (e: Throwable) {
            AppLogger.e("Could not invoke getStartActivityAsUserWithFeatureMethod method", e)
            0
        }
    }

    /**
     * Source Android 16: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java;l=3805-3808
     */
    private val forceStopPackageMethod: Method by lazy {
        manager.javaClass.getMethod(
            "forceStopPackage",
            String::class.java, // String packageName
            Int::class.javaPrimitiveType // int userId
        )
    }

    /**
     * Force stop a package for the current user.
     * @param packageName The package name to force stop.
     */
    fun forceStopPackage(packageName: String) {
        try {
            forceStopPackageMethod.invoke(
                manager,
                packageName,
                -2 // Int userId = UserHandle.USER_CURRENT
            )
        } catch (e: Throwable) {
            AppLogger.e("Could not invoke forceStopPackage method", e)
        }
    }
}