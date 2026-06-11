/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioCodec
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import com.kitsumed.shizucallrecorder.services.callDetection.CallDetectionMode
import com.kitsumed.shizucallrecorder.ui.theme.ShizucallrecorderTheme
import com.kitsumed.shizucallrecorder.utils.RecordingFileNameFormatter

/**
 * Dialog for selecting file name format.
 * @param initialFormat The format string to show when the dialog opens, usually the currently saved user preference.
 * @param activeMode The currently active CallDetectionMode, used to filter and indicate which placeholders are supported/expected to work in the current mode.
 * @param onConfirm Called with the new format string when the user taps "OK".
 * @param onDismiss Called when the user taps "Cancel" or outside the dialog.
 */
@Composable
fun FileNameFormatDialog(
    initialFormat: String,
    activeMode: CallDetectionMode,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialFormat) }
    val context = LocalContext.current

    val previewState = remember(text) {
        val fakeMetadata = EnrichedCallData(
            normalisedPhoneNumber = "+1234567890",
            direction = CallDirection.INCOMING,
            formattedE164Number = "+1234567890",
            isCrossCountry = false,
            callerName = "John Doe"
        )
        val result = RecordingFileNameFormatter.formatFileName(
            context, fakeMetadata, ScrcpyAudioCodec.OPUS, customFormat = text
        )
        result
    }

    val placeholders = remember(activeMode) {
        RecordingFileNameFormatter.FileNamePlaceholder.entries
            .filter { activeMode in it.supportedModes }
    }
    val descriptionsTexts = placeholders.map { stringResource(it.descriptionResId) }

    val descriptions = buildAnnotatedString {
        placeholders.forEachIndexed { index, placeholder ->
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append(placeholder.tag) // Bold placeholder tag
            pop()
            append(" - ${descriptionsTexts[index]}")
            // Add newline after every placeholder except last one
            if (index < placeholders.size - 1) {
                append("\n")
            }
        }
    }

    AlertDialog(
        title = { Text(stringResource(R.string.settings_file_name_template)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.settings_file_name_template_placeholders, stringResource(activeMode.titleResId)),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = descriptions,
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_file_name_template_placeholders_preview, previewState),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    onClick = { text = AppPreferences.DefaultsValue.FILE_NAME_TEMPLATE }
                ) {
                    Text(stringResource(R.string.general_reset))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text(stringResource(R.string.general_cancel))
                    }
                    Button(onClick = { onConfirm(text) }) {
                        Text(stringResource(R.string.general_ok))
                    }
                }
            }
        }
    )
}

/**
 * Dialog Previw.
 */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    ShizucallrecorderTheme(darkTheme = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            FileNameFormatDialog(
                initialFormat = AppPreferences.DefaultsValue.FILE_NAME_TEMPLATE,
                activeMode = CallDetectionMode.InCallService,
                onConfirm = {},
                onDismiss = {}
            )
        }
    }
}

