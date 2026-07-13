/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.integrations.shizuku.ShizukuConnectionManager
import com.kitsumed.shizucallrecorder.onboarding.OnboardingStatus
import com.kitsumed.shizucallrecorder.services.callDetection.CallDetectionMode
import com.kitsumed.shizucallrecorder.system.openAppSettings
import com.kitsumed.shizucallrecorder.system.openGithubReportIssue
import com.kitsumed.shizucallrecorder.ui.common.M3DropdownField
import com.kitsumed.shizucallrecorder.ui.common.OptionItem
import com.kitsumed.shizucallrecorder.ui.common.ToggleListItem
import com.kitsumed.shizucallrecorder.ui.theme.ShizucallrecorderTheme
import com.kitsumed.shizucallrecorder.ui.viewmodels.PermissionsViewModel
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    // Quick debug dialog for when issues arise on the permission setup.
    var showDebugDialog by remember() { mutableStateOf(false) }
    val activityScope = rememberCoroutineScope()

    val isProcessingGrantingRequest by viewModel.isProcessingGrantingRequest.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

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

                AlertDialog(
                    onDismissRequest = { exitProcess(0) },
                    title = { Text(text = stringResource(R.string.general_system_limitation)) },
                    text = { Text(text = dialogMessage) },
                    confirmButton = {
                        TextButton(onClick = { exitProcess(0) }) {
                            Text(text = stringResource(R.string.general_close))
                        }
                    },
                    dismissButton = null,
                    properties = DialogProperties(
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,
                    ),
                    icon = { Icon(Icons.Default.Warning, contentDescription = null) }
                )
            }
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dissmissError() },
            title = { Text(text = stringResource(R.string.general_system_limitation)) },
            text = { Text(text = errorMessage.toString()) },
            confirmButton = {
                TextButton(onClick = { viewModel.dissmissError() }) {
                    Text(text = stringResource(R.string.general_close))
                }
            },
            icon = { Icon(Icons.Default.ErrorOutline, contentDescription = null) }
        )
    }

    if (showDebugDialog) {
        val prefs = remember { AppPreferences(activityContext) }
        val exportLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            if (uri != null) {
                activityScope.launch(Dispatchers.IO) {
                    AppLogger.exportReport(activityContext, uri)
                }
            }
        }
        var isLoggingEnabled by remember { mutableStateOf(prefs.isLoggingEnabled()) }

        Dialog(
            onDismissRequest = { showDebugDialog = false }
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_section_debug),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    )

                    ToggleListItem(
                        label = stringResource(R.string.settings_debug_logging_enabled),
                        description = stringResource(R.string.settings_debug_logging_enabled_description),
                        checked = isLoggingEnabled,
                        onCheckedChange = { checked ->
                            prefs.setLoggingEnabled(checked)
                            isLoggingEnabled = checked
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { exportLogLauncher.launch("shizucallrecorder_debug_report.txt") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.settings_debug_logging_generate_report))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { activityContext.openGithubReportIssue() }
                            ) {
                                Text(stringResource(R.string.settings_debug_logging_report_on_github))
                            }

                            TextButton(
                                onClick = { showDebugDialog = false }
                            ) {
                                Text(text = stringResource(R.string.general_close))
                            }
                        }
                    }
                }
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
        onCallDetectionModeChanged = { newMode ->
            viewModel.onCallDetectionModeChanged(newMode)
            onPermissionGranted() // Refresh the UI after changing the mode
        },
        isProcessingGrantingRequest = isProcessingGrantingRequest,
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onLongPress = {
                    showDebugDialog = true
                }
            )
        }
    )
}

/**
 * Stateless visual layer for the permissions checklist screen.
 *
 * Renders a scrollable list of [PermissionCard] items based on the [OnboardingStatus.Status]
 *
 * @param status                 The current "Snapshot" of every permission and setup step.
 * @param onGrantAccessButtonClick Forwarded to [PermissionsViewModel.onGrantAccess] when user taps the button.
 * @param onGrantAccessButtonLongClick Forwarded to [PermissionsViewModel.onGrantAccess] when user long-presses the button.
 * @param modifier
 */
@Composable
fun PermissionsContent(
    status: OnboardingStatus.Status,
    onGrantAccessButtonClick: () -> Unit,
    onCallDetectionModeChanged: (CallDetectionMode) -> Unit,
    isProcessingGrantingRequest: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .fillMaxSize(),
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
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
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

                val globalPermissions = listOf(
                    Triple(stringResource(R.string.permission_notifications_label), stringResource(R.string.permission_notifications_description), status.notificationsGranted to Icons.Default.QuestionAnswer),
                    Triple(stringResource(R.string.permission_contacts_label), stringResource(R.string.permission_contacts_description), status.contactsGranted to Icons.Default.RecentActors),
                    Triple(stringResource(R.string.permission_battery_label), stringResource(R.string.permission_battery_description), status.batteryExempted to Icons.Default.BatterySaver),
                    Triple(stringResource(R.string.settings_recording_folder_label), stringResource(R.string.permission_storage_description), status.storageSelected to Icons.Default.Folder)
                )

                // How global permissions
                globalPermissions.forEach { (label, desc, grantInfo) ->
                    PermissionCard(
                        label = label,
                        description = desc,
                        granted = grantInfo.first,
                        iconOverride = grantInfo.second
                    )
                }

                HorizontalDivider() // Call detection specific permissions section

                val detectionOptions = CallDetectionMode.entries.map { mode ->
                    OptionItem(
                        key = mode.name,
                        label = stringResource(mode.titleResId),
                        description = stringResource(mode.descriptionResId),
                        enabled = mode.isSupportedOnCurrentApi()
                    )
                }

                M3DropdownField(
                    label = stringResource(R.string.settings_call_detection_method),
                    selected = detectionOptions.find { it.key == status.callDetectionMode.name } ?: detectionOptions.first(),
                    options = detectionOptions,
                    onOptionSelected = { selectedItem ->
                        onCallDetectionModeChanged(CallDetectionMode.valueOf(selectedItem.key))
                    }
                )

                AnimatedContent(
                    targetState = status.callDetectionMode,
                    transitionSpec = {
                        val enterTransition = fadeIn(tween(300)) + expandVertically(tween(300))
                        val exitTransition = fadeOut(tween(250)) + shrinkVertically(tween(250))

                        enterTransition togetherWith exitTransition
                    },
                    label = "CallDetectionModeSettingsTransition"
                ) { selectedCallDetectionMode ->
                    // Child-Column required so cards don't show on top of each other
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Show the required permissions for the selected call detection mode
                        selectedCallDetectionMode.requiredPermissions.forEach { currentPermission ->
                            val isGranted = status.callDetectionModeGrantedPermissions.contains(currentPermission)
                            PermissionCard(
                                label = stringResource(currentPermission.titleResId),
                                description = stringResource(currentPermission.descriptionResId),
                                granted = isGranted,
                                iconOverride = currentPermission.icon
                            )
                        }
                    }
                }


            }

            // Footer with action button
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))

            Button(
                onClick = onGrantAccessButtonClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isProcessingGrantingRequest,
                shape = MaterialTheme.shapes.medium
            ) {
                if (isProcessingGrantingRequest) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
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
}

@Composable
private fun PermissionCard(
    label: String,
    description: String,
    granted: Boolean,
    statusOverride: String? = null,
    iconOverride: ImageVector? = null
) {
    // Animate the background container color smoothly
    val containerColor by animateColorAsState(
        targetValue = if (granted) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
        },
        animationSpec = tween(durationMillis = 500),
        label = "cardBackgroundColor"
    )

    // Animate the status/icon color
    val statusColor by animateColorAsState(
        targetValue = if (granted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
        animationSpec = tween(durationMillis = 600),
        label = "cardStatusColor"
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
                        imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = statusColor // Used the animated color here
                    )
                    Text(
                        text = statusOverride ?: if (granted) stringResource(R.string.permissions_status_granted) else stringResource(R.string.permissions_status_required),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor, // Used the animated color here
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PermissionsScreenPreview() {
    ShizucallrecorderTheme(darkTheme = false) {
        PermissionsContent(
            status = OnboardingStatus.Status(
                disclaimerAccepted = true,
                notificationsGranted = false,
                contactsGranted = true,
                batteryExempted = false,
                storageSelected = false,
                shizukuRunning = false,
                shizukuPermissionGranted = false,
                callDetectionMode = CallDetectionMode.PhoneState,
                callDetectionModeGrantedPermissions = emptySet()
            ),
            onGrantAccessButtonClick = {},
            onCallDetectionModeChanged = {},
            isProcessingGrantingRequest = false
        )
    }
}
