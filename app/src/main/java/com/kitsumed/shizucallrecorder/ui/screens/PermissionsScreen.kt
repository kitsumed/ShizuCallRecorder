/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.screens

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.integrations.shizuku.ShizukuConnectionManager
import com.kitsumed.shizucallrecorder.onboarding.OnboardingStatus
import com.kitsumed.shizucallrecorder.system.openAppSettings
import com.kitsumed.shizucallrecorder.ui.theme.ShizucallrecorderTheme
import com.kitsumed.shizucallrecorder.ui.viewmodels.PermissionsViewModel
import kotlin.system.exitProcess

/**
 * Stateful wrapper that connects [PermissionsViewModel] to [PermissionsContent].
 *
 * It owns the Android-specific launchers ([rememberLauncherForActivityResult]) that must live
 * inside a composable and passes them into [PermissionsViewModel.onGrantAccess] as lambdas so
 * the ViewModel stays free of Compose and Activity references.
 *
 * @param status              The current [OnboardingStatus.Status] snapshot, observed by the
 *                            router in [AppNavigationScreen] via [collectAsState].
 * @param onPermissionGranted Called after any grant action completes so the router can refresh state.
 * @param modifier            Optional size/position modifier forwarded to [PermissionsContent].
 * @param viewModel           The "Brain" that decides which permission to request next.
 */
@Composable
fun PermissionsScreen(
    status: OnboardingStatus.Status,
    onPermissionGranted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PermissionsViewModel = viewModel()
) {

    val activityContext = LocalContext.current

    // Permission launchers must live inside a composable - the system dialog can only be
    // triggered from a composable context.  We pass these into the ViewModel as lambdas so
    // the ViewModel never needs to import Compose or hold a UI reference.
    val permissionRequestLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        // false = permission denied or permanently blocked by the OS.
        // In the blocked case we open App Info so the user can grant it manually.
        if (!result) {
            activityContext.openAppSettings()
        }
        onPermissionGranted()
    }
    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // takePersistableUriPermission locks in long-term read/write access so the
            // folder URI remains valid after a device reboot.
            activityContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            AppPreferences(activityContext).setRecordingFolderUri(uri)
        }
        onPermissionGranted()
    }

    // Run safety check once Shizuku permission is granted
    if (status.shizukuPermissionGranted && ShizukuConnectionManager.hasPermission(activityContext)) {
        // We still need to check if the Shell app has all required permissions.
        if (ShizukuConnectionManager.isAvailable()) {
            // If Shizuku server is running, just ensure we have all required permissions on the Shell app level. If not, then the app won't work.
            val requiredPermissions = listOf(
                Manifest.permission.CAPTURE_AUDIO_OUTPUT
            )

            val missingPermissions = requiredPermissions.filter {
                !ShizukuConnectionManager.checkServerPermission(it)
            }

            if (missingPermissions.isNotEmpty()) {
                val cleanPermissionsString = missingPermissions
                    .joinToString("\n") { it.substringAfterLast(".") }

                val dialogMessage = stringResource(R.string.general_system_limitation_message, cleanPermissionsString)

                AlertDialog.Builder(activityContext)
                    .setTitle(R.string.general_system_limitation)
                    .setMessage(dialogMessage)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setCancelable(false)
                    .setPositiveButton("Exit") { _, _ ->
                        exitProcess(0)
                    }.show()
            }
        }
    }

    PermissionsContent(
        status = status,
        onGrantAccessButtonClick = {
            viewModel.onGrantAccess(
                status = status,
                onPermissionGranted = onPermissionGranted,
                requestRuntimePermission = { permission -> permissionRequestLauncher.launch(permission) },
                launchFolderPicker = { folderPickerLauncher.launch(null) },
            )
        },
        modifier = modifier
    )
}

/**
 * Stateless visual layer for the permissions checklist screen.
 *
 * Renders a scrollable list of [PermissionCard] items based on the [OnboardingStatus.Status]
 * "Snapshot" and fires [onGrantAccessButtonClick] when the action button is pressed.
 * Contains no logic - all decisions live in [PermissionsViewModel].
 *
 * Accepting [OnboardingStatus.Status] directly (instead of a separate mapping type) ensures
 * that adding a new prerequisite to [OnboardingStatus] is reflected here automatically,
 * without maintaining a redundant parallel data structure.
 *
 * @param status                 The current "Snapshot" of every permission and setup step.
 * @param onGrantAccessButtonClick Forwarded to [PermissionsViewModel.onGrantAccess] by the
 *                               stateful [PermissionsScreen] wrapper.
 * @param modifier               Optional size/position modifier for the root [Surface].
 */
@Composable
fun PermissionsContent(
    status: OnboardingStatus.Status,
    onGrantAccessButtonClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.navigationBarsPadding().fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 16.dp)
        ) {
            // Header
            Text(
                text = stringResource(R.string.permissions_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.permissions_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Scrollable permission cards
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PermissionCard(
                    label = stringResource(R.string.permission_shizuku_label),
                    description = stringResource(R.string.permission_shizuku_description),
                    granted = status.shizukuRunning && status.shizukuPermissionGranted,
                    statusOverride = when {
                        !status.shizukuRunning -> stringResource(R.string.permission_shizuku_not_running)
                        !status.shizukuPermissionGranted -> stringResource(R.string.permissions_status_required)
                        else -> null
                    }
                )

                val permissions = listOf(
                    Triple(stringResource(R.string.permission_notifications_label), stringResource(R.string.permission_notifications_description), status.notificationsGranted to Icons.Default.QuestionAnswer),
                    Triple(stringResource(R.string.permission_contacts_label), stringResource(R.string.permission_contacts_description), status.contactsGranted to Icons.Default.RecentActors),
                    Triple(stringResource(R.string.permission_phone_state_label), stringResource(R.string.permission_phone_state_description), status.phoneStateGranted to Icons.Default.Phone),
                    Triple(stringResource(R.string.permission_call_log_label), stringResource(R.string.permission_call_log_description), status.callLogGranted to Icons.Default.History),
                    Triple(stringResource(R.string.permission_manage_ongoing_calls_label), stringResource(R.string.permission_manage_ongoing_calls_description), status.manageOngoingCallsGranted to Icons.Default.ImportantDevices),
                    Triple(stringResource(R.string.permission_battery_label), stringResource(R.string.permission_battery_description), status.batteryExempted to Icons.Default.BatterySaver),
                    Triple(stringResource(R.string.settings_recording_folder_label), stringResource(R.string.permission_storage_description), status.storageSelected to Icons.Default.Folder)
                )

                permissions.forEach { (label, desc, grantInfo) ->
                    PermissionCard(
                        label = label,
                        description = desc,
                        granted = grantInfo.first,
                        iconOverride = grantInfo.second
                    )
                }
            }

            // Footer with action button
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))

            Button(
                onClick = onGrantAccessButtonClick,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = when {
                        status.isComplete()       -> stringResource(R.string.general_continue)
                        !status.shizukuRunning    -> stringResource(R.string.permission_shizuku_open)
                        else                      -> stringResource(R.string.permissions_grant_access)
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    label: String,
    description: String,
    granted: Boolean,
    statusOverride: String? = null,
    iconOverride: ImageVector? = null
) {
    val containerColor by animateColorAsState(
        targetValue = if (granted) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
        label = "cardColor"
    )

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = containerColor),
            headlineContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(iconOverride ?: Icons.Default.Adb, null, Modifier.size(20.dp))
                    Text(label, fontWeight = FontWeight.SemiBold)

                    Spacer(modifier = Modifier.weight(1f))

                    Icon(
                        if (granted) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        null, Modifier.size(16.dp),
                        if (!granted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = statusOverride ?: if (granted) stringResource(R.string.permissions_status_granted) else stringResource(R.string.permissions_status_required),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (!granted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            supportingContent = { Text(description,  style = MaterialTheme.typography.bodySmall) },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PermissionsScreenPreview() {
    ShizucallrecorderTheme(darkTheme = false) {
        PermissionsContent(
            status = OnboardingStatus.Status(
                disclaimerAccepted       = true,
                notificationsGranted     = false,
                contactsGranted          = true,
                phoneStateGranted        = false,
                callLogGranted           = false,
                batteryExempted          = false,
                manageOngoingCallsGranted = false,
                storageSelected          = false,
                shizukuRunning           = false,
                shizukuPermissionGranted = false
            ),
            onGrantAccessButtonClick = {}
        )
    }
}
