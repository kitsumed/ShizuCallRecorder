/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.ui.theme.ShizuCallRecorderTheme

/**
 * The floating overlay UI for controlling the recording.
 */
@Composable
fun RecordingOverlay(
    isRecordingActive: Boolean,
    isRecordingPaused: Boolean,
    onActionClick: () -> Unit,
    onDragY: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val isActivelyRecording = isRecordingActive && !isRecordingPaused
    val isActivelyPaused = isRecordingActive && isRecordingPaused

    // Infinite transition for the blinking red dot alpha
    val dotAlpha by rememberInfiniteTransition(label = "recordingDotBlink").animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    // Animate the button background color based on recording state
    val buttonBackgroundColor by animateColorAsState(
        targetValue = if (isActivelyRecording)
            MaterialTheme.colorScheme.errorContainer
        else if (isActivelyPaused)
            Color.Green.copy(alpha = 0.4f)
        else
            MaterialTheme.colorScheme.primaryContainer,
        animationSpec = tween(durationMillis = 400),
        label = "buttonColorAnim"
    )

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Max)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDragY(dragAmount.y)
                        }
                    )
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Action start/pause/resume
            Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onActionClick,
                    modifier = Modifier
                        .size(64.dp)
                        .border(
                            width = 4.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            shape = CircleShape
                        )
                        .background(color = buttonBackgroundColor, shape = CircleShape)
                ) {
                    val iconRes = when {
                        !isRecordingActive -> R.drawable.ic_mic
                        isRecordingPaused -> R.drawable.ic_baseline_play_arrow
                        else -> R.drawable.ic_baseline_pause
                    }

                    AnimatedContent(
                        targetState = iconRes,
                        transitionSpec = {
                            val enterTransition = fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.7f)
                            val exitTransition = fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.7f)
                            val combinedTransition = enterTransition.togetherWith(exitTransition)

                            combinedTransition},
                        label = "iconChangeAnim"
                    ) { targetIconRes ->
                        Icon(
                            painter = painterResource(id = targetIconRes),
                            contentDescription = "Control Recording",
                            tint = if (isActivelyRecording)
                                MaterialTheme.colorScheme.onErrorContainer
                            else
                                MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(42.dp)
                        )
                    }
                }

                // Blinking red dot
                if (isActivelyRecording) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .align(Alignment.TopEnd)
                            .alpha(dotAlpha)
                            .background(
                                color = Color.Red,
                                shape = CircleShape
                            )
                    )
                }
            }

            // Drag Handle
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(26.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_outline_drag_indicator),
                    contentDescription = "Move Overlay",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(42.dp)
                )
            }
        }
    }
}


@Preview(name = "Standby")
@Composable
private fun PreviewRecordingOverlayStandby() {
    ShizuCallRecorderTheme(darkTheme = false) {
        RecordingOverlay(isRecordingActive = false, isRecordingPaused = false, onActionClick = {}, onDragY = {}, onDragEnd = {})
    }
}

@Preview(name = "Recording")
@Composable
private fun PreviewRecordingOverlayRecording() {
    ShizuCallRecorderTheme(darkTheme = false) {
        RecordingOverlay(isRecordingActive = true, isRecordingPaused = false, onActionClick = {}, onDragY = {}, onDragEnd = {})
    }
}

@Preview(name = "Paused")
@Composable
private fun PreviewRecordingOverlayPaused() {
    ShizuCallRecorderTheme(darkTheme = false) {
        RecordingOverlay(isRecordingActive = true, isRecordingPaused = true, onActionClick = {}, onDragY = {}, onDragEnd = {})
    }
}