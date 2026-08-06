/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 * Copyright (C) 2026-present kitsumed (Med)
 * This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 * The full license text is available in the LICENSE file at the root of this project.
 * This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.recording

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.system.permissions.PermissionChecks
import com.kitsumed.shizucallrecorder.ui.common.RecordingOverlay
import com.kitsumed.shizucallrecorder.ui.theme.ShizuCallRecorderTheme
import kotlin.math.max
import kotlin.math.min

/**
 * Controller that hosts a ComposeView inside WindowManager for the optional recording overlay popup.
 * This involves a bit of complicated stuff as compose was not made for this : https://helw.net/2025/08/31/compose-ui-without-an-activity/
 */
class RecordingOverlayController(private val context: Context) {
    private val overlayContext: Context by lazy {
        // Get a display context on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            context
        }
    }

    private val windowManager by lazy { overlayContext.getSystemService(WindowManager::class.java) }
    private val appPreferences = AppPreferences(context)

    private var composeView: ComposeView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var lifecycleOwner: ComposeWindowLifecycleOwner? = null

    /**
     * Displays the recording overlay with the current state.
     * If the overlay is disabled in preferences or the app lacks overlay permission, it will hide the overlay instead.
     */
    fun showOverlay(state: RecordingServiceState) {
        if (!appPreferences.isOverlayEnabled() || !PermissionChecks.hasOverlayPermission(context)) {
            hideOverlay()
            return
        }

        if (composeView == null) {
            initOverlayView()
        }

        composeView?.setContent {
            val darkTheme = when (appPreferences.getThemeMode()) {
                AppPreferences.ThemeMode.LIGHT -> false
                AppPreferences.ThemeMode.DARK   -> true
                AppPreferences.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val dynamicColor = appPreferences.isDynamicColorEnabled()
            // State false -> true for the animation
            val isVisible = remember { MutableTransitionState(false).apply { targetState = true } }

            ShizuCallRecorderTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                AnimatedVisibility(
                    visibleState = isVisible,
                    enter = slideInHorizontally(
                        animationSpec = tween(durationMillis = 400),
                        initialOffsetX = { fullWidth -> fullWidth } // Start it from outside the screen
                    ) + fadeIn(animationSpec = tween(durationMillis = 400))
                ) {
                    RecordingOverlay(
                        isRecordingActive = state.isRecordingActive,
                        isRecordingPaused = state.isRecordingPaused,
                        onActionClick = { sendServiceAction(state) },
                        onDragY = { deltaY -> updateOverlayY(deltaY) },
                        onDragEnd = { saveOverlayY() }
                    )
                }
            }
        }
    }

    private fun initOverlayView() {
        val savedY = appPreferences.getOverlayYPosition()
        // Calculate the screen height to center it if default (-1) is used
        val screenHeight =  windowManager.currentWindowMetrics.bounds.height()
        // Approximate height of 120dp converted to pixels to center it a little bit
        val overlayHeightPx = (120 * overlayContext.resources.displayMetrics.density).toInt()

        val safeY = if (savedY == -1) {
            max(0, (screenHeight / 2) - (overlayHeightPx / 2))
        } else {
            max(0, savedY)
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    // FLAG_SHOW_WHEN_LOCKED and FLAG_TURN_SCREEN_ON are deprecated.
                    // The new alternative (like Activity.setShowWhenLocked) only function on Activity.
                    // Because this UI is injected via WindowManager from a Service, these layout flags
                    // are the fastest and easier way to display the overlay over the lock screen. We do not have an Activity context here.
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = safeY
        }

        // Attach the required artificial lifecycle to the ComposeView
        composeView = ComposeView(overlayContext).also { view ->
            lifecycleOwner = ComposeWindowLifecycleOwner().apply {
                attachToView(view)
            }
        }

        windowManager.addView(composeView, windowParams)
    }

    private fun updateOverlayY(deltaY: Float) {
        val params = windowParams ?: return
        val view = composeView ?: return
        val newY = params.y + deltaY.toInt()

        val screenHeight = windowManager.currentWindowMetrics.bounds.height()

        // Update Y position and prevent dragging completely off-screen
        params.y = min(screenHeight - 250, max(100, newY))
        windowManager.updateViewLayout(view, params)
    }

    private fun saveOverlayY() {
        windowParams?.let { appPreferences.setOverlayYPosition(it.y) }
    }

    /**
     * Hides the overlay and cleans up resources.
     */
    fun hideOverlay() {
        composeView?.let { view ->
            runCatching { windowManager.removeView(view) }

            // Clean up the lifecycle to prevent memory leaks
            lifecycleOwner?.destroy()
            lifecycleOwner = null

            composeView = null
            windowParams = null
        }
    }

    private fun sendServiceAction(state: RecordingServiceState) {
        val intentAction = when {
            !state.isRecordingActive -> RecordingForegroundService.ACTION_MANUAL_START
            state.isRecordingPaused -> RecordingForegroundService.ACTION_RESUME_RECORDING
            else -> RecordingForegroundService.ACTION_PAUSE_RECORDING
        }
        context.startService(Intent(context, RecordingForegroundService::class.java).apply { action = intentAction })
    }

    /**
     * A fake LifecycleOwner/ViewModelStoreOwner/SavedStateRegistryOwner for ComposeView when used in a raw WindowManager context.
     * This is required to prevent crashes when using ComposeView in a raw WindowManager context.
     */
    private class ComposeWindowLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store

        init {
            savedStateRegistryController.performRestore(Bundle())
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun attachToView(view: View) {
            view.setViewTreeLifecycleOwner(this)
            view.setViewTreeViewModelStoreOwner(this)
            view.setViewTreeSavedStateRegistryOwner(this)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }
}