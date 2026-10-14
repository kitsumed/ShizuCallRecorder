/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

/*
 * This script was inspired by scrcpy ServiceManager wrapper.
 * Last checked commit: https://github.com/Genymobile/scrcpy/blob/b8a3fca786fc7a998602670a27dc53e5b65e1b45/server/src/main/java/com/genymobile/scrcpy/wrappers/ServiceManager.java
*/

package com.kitsumed.shizucallrecorder.services.shell.utils.wrappers

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.IInterface
import java.lang.reflect.Method

/**
 * A wrapper for the Android ServiceManager, allowing access to system services via reflection.
 */
object ServiceManager {

    // Reflection-based method to access the hidden ServiceManager.getService(String) method.
    // Source: https://cs.android.com/android/platform/superproject/+/android16-release:frameworks/base/core/java/android/os/ServiceManager.java;l=160-181
    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private val getServiceMethod: Method = try {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        serviceManagerClass.getDeclaredMethod("getService", String::class.java).apply {
            isAccessible = true
        }
    } catch (e: Throwable) {
        throw AssertionError(e)
    }


    /**
     * Retrieves a system service by its binder name, and convert it to the specified type interface.
     *
     * @param service The name of the service to retrieve.
     * @param type The fully qualified class name of the service interface.
     * @return An instance of the requested service interface.
     * @throws AssertionError If the service cannot be retrieved or cast to the specified type.
     */
    fun getService(service: String, type: String): IInterface {
        return try {
            val serviceBinder = getServiceMethod.invoke(null, service) as IBinder
            val serviceInterfaceClass = Class.forName($$"$$type$Stub")
            val asInterfaceMethod = serviceInterfaceClass.getMethod("asInterface", IBinder::class.java)
            // Convert the Binder to the interface stub
            asInterfaceMethod.invoke(null, serviceBinder) as IInterface
        } catch (e: Throwable) {
            throw AssertionError(e)
        }
    }
}