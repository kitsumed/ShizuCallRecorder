/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.callDetection.phoneState

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * PhoneStateReceiver is a [BroadcastReceiver] that listens for phone call state changes INTENTS.
 * It extracts the relevant extras from the intent and forwards them to [PhoneStateSessionManager], which owns the recording decision logic.
 *
 * It is registered in AndroidManifest.xml to receive [TelephonyManager.ACTION_PHONE_STATE_CHANGED]
 * broadcasts. Android delivers this broadcast whenever a call starts ringing, is answered, or ends.
 *
 * **Note on "Double Broadcast" Behavior**:
 * As stated in [TelephonyManager.ACTION_PHONE_STATE_CHANGED] KDoc, the system sends two broadcasts for a single state transition:
 * 1. The first one always happen, and it has a null phone number.
 * 2. A second broadcast is received if the app has the READ_CALL_LOG permission, but this time containing the phone number.
 *
 * **Note on Phone Number Retrieval**:
 * Currently, we "cheats" and use the deprecated [TelephonyManager.EXTRA_INCOMING_NUMBER] to get the phone number (valid in the second broadcast!). If google ever fully discontinues this extra, we will need to
 * do call log polling (meaning whe will need to wait for the call to end, then query the latest phone number from the call log). This is not ideal, prone to race conditions. The other, preferred alternative
 * would be to use Shizuku and call a hidden api to get that data, but I'm unsure now, will make an issue.
 * For every new Android releases, we must ensure they still send the EXTRA here:
 * Android 16: https://cs.android.com/android/platform/superproject/+/android-16.0.0_r4:frameworks/base/services/core/java/com/android/server/TelephonyRegistry.java;l=4349-4351
 * Android 11: https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/services/core/java/com/android/server/TelephonyRegistry.java;l=2566-2568
 */
class PhoneStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SCR:PhoneStateReceiver"
    }

    /**
     * Called by the Android framework when a phone-state change broadcast is received.
     *
     * @param context The [Context] in which the receiver is running.
     * @param intent  The [Intent] being received; must carry action
     *                [TelephonyManager.ACTION_PHONE_STATE_CHANGED].
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        // EXTRA_STATE is one of "IDLE", "RINGING", or "OFFHOOK".
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: throw IllegalArgumentException("Missing EXTRA_STATE in phone state change intent. How is this possible??")

        // EXTRA_INCOMING_NUMBER is set for both incoming and outgoing calls (I know the naming is confusing), but there is a nuance.
        // See this class KDoc comment "Double Broadcast" for more information on this. The first broadcast is always null.
        // NOTE: According to the Android source code, in case of "Double Broadcast", if the incoming number is anonymous, it should be an empty string.
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        AppLogger.v(TAG, "Raw broadcast received: state=$state number=$number")

        // Forward the phone state and number to the session manager.
        // We now forward everything (including null) to let the PhoneStateSessionManager handle the Verification Window.
        PhoneStateSessionManager.getInstance(context).handlePhoneState(state, number)
    }
}
