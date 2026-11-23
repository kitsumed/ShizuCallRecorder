/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.kitsumed.shizucallrecorder.security.AppLockManager
import com.kitsumed.shizucallrecorder.ui.screens.CalculatorScreen

class MainActivity : AppCompatActivity() {

    /**
     * Set when the app itself launches a system picker for a result (e.g. SAF folder picker, log export),
     * so returning from it does not re-lock the app mid-flow. Any other way of leaving the UI re-locks it.
     */
    private var isAwaitingInternalResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLockManager.refreshLockEnabled(this)
        setContent {
            val lockEnabled by AppLockManager.isLockEnabled.collectAsState()
            val unlocked by AppLockManager.isUnlocked.collectAsState()
            val showGate = lockEnabled && !unlocked

            // Hide the unlocked app content from the Recents switcher / screenshots while the lock is enabled.
            SideEffect { setSecureFlag(lockEnabled && unlocked) }

            if (showGate) {
                CalculatorScreen(onUnlocked = { /* isUnlocked flow swaps the content */ })
            } else {
                AppNavigationScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppLockManager.refreshLockEnabled(this)
    }

    override fun onResume() {
        super.onResume()
        // A permission dialog only pauses the activity; clear the one-shot allowance so the next real exit re-locks.
        isAwaitingInternalResult = false
    }

    /** Call right before launching a system screen from inside the unlocked app (e.g. permission settings) to avoid re-locking on return. */
    fun allowNextExternalReturn() {
        isAwaitingInternalResult = true
    }

    override fun onStop() {
        super.onStop()
        // Re-lock the whole UI whenever the user leaves it. Background call detection / recording is unaffected.
        if (!isChangingConfigurations && !isAwaitingInternalResult) {
            AppLockManager.lock()
        }
        isAwaitingInternalResult = false
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        // ActivityResult launchers (pickers) use a non-negative request code; plain startActivity() uses -1.
        if (requestCode >= 0) isAwaitingInternalResult = true
        super.startActivityForResult(intent, requestCode, options)
    }

    private fun setSecureFlag(secure: Boolean) {
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
