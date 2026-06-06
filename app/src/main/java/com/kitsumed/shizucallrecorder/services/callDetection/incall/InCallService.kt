/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.callDetection.incall

import android.telecom.Call
import android.telecom.InCallService
import com.kitsumed.shizucallrecorder.utils.AppLogger
// TODO: Write a real implementation, this is just some tests
class InCallService : InCallService() {
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)

        val details = call.details
        val phoneAccountHandle = details.accountHandle

        if (phoneAccountHandle != null) {
            val unredactedPhoneNumber = details.handle.schemeSpecificPart
            val callDirection = details.callDirection
            val packageName = details.accountHandle.componentName.packageName
            val callerTelecomDisplayName = details.callerDisplayName
            val callerContactName = details.contactDisplayName
            AppLogger.i("SCR:TelecomCompanion", "Call session started. Number: $unredactedPhoneNumber, Direction: $callDirection, Package: $packageName, Caller Name: $callerTelecomDisplayName, Caller Contact Name: $callerContactName")
        }

        // Register callback to track state changes
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                if (state == Call.STATE_ACTIVE) {
                    AppLogger.i("SCR:TelecomCompanion", "Call is active.")
                    // Call is now connected, trigger your audio capture
                }
            }
        })
    }

    override fun onCallRemoved(call: Call?) {
        super.onCallRemoved(call)
        AppLogger.i("SCR:TelecomCompanion", "Call session ended.")
    }
}