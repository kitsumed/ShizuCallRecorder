/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kitsumed.shizucallrecorder.MainActivity
import com.kitsumed.shizucallrecorder.data.AddonPreferences
import com.kitsumed.shizucallrecorder.security.AppLockManager
import com.kitsumed.shizucallrecorder.ui.common.M3DropdownField
import com.kitsumed.shizucallrecorder.ui.common.OptionItem
import com.kitsumed.shizucallrecorder.ui.common.ToggleListItem
import com.kitsumed.shizucallrecorder.ui.viewmodels.AddonSettingsViewModel
import com.kitsumed.shizucallrecorder.ui.viewmodels.AddonSettingsViewModel.ActionState
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Settings sections for the add-on features. Rendered by [SettingsScreen] as extra LazyColumn items.
 */
@Composable
fun AppLockSettingsSection(viewModel: AddonSettingsViewModel) {
    val trigger by viewModel.updateTrigger.collectAsState()
    val prefs = viewModel.prefs
    val lockEnabled = remember(trigger) { prefs.isCalculatorLockEnabled() }
    val hasPasscode = remember(trigger) { prefs.hasPasscode() }
    val disguised = remember(trigger) { prefs.isDisguiseIconEnabled() }

    var showPasscodeDialog by remember { mutableStateOf(false) }
    var enableAfterPasscode by remember { mutableStateOf(false) }

    AddonSection(title = "App Passcode / Calculator Lock") {
        ToggleListItem(
            label = "Enable Calculator Lock",
            checked = lockEnabled,
            onCheckedChange = { enabled ->
                if (!viewModel.setCalculatorLockEnabled(enabled)) {
                    enableAfterPasscode = true
                    showPasscodeDialog = true
                }
            },
            description = "Opening the app shows a working calculator. Type your passcode and press \"=\" to unlock. " +
                    "The app re-locks whenever you leave it; call recording keeps working in the background."
        )
        ListItem(
            headlineContent = { Text(if (hasPasscode) "Change Passcode" else "Set Passcode") },
            supportingContent = { Text("Numeric, ${AppLockManager.MIN_PASSCODE_LENGTH}–${AppLockManager.MAX_PASSCODE_LENGTH} digits. Stored only as a salted hash.") },
            trailingContent = {
                TextButton(onClick = { enableAfterPasscode = false; showPasscodeDialog = true }) {
                    Text(if (hasPasscode) "Change" else "Set")
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
        ToggleListItem(
            label = "Disguise App Icon & Name as Calculator",
            checked = disguised,
            onCheckedChange = { viewModel.setDisguiseIconEnabled(it) },
            description = "The app drawer shows a \"Calculator\" icon and label. Your launcher may take a few seconds to refresh, and existing home-screen shortcuts may need to be re-added."
        )
    }

    if (showPasscodeDialog) {
        PasscodeDialog(
            onDismiss = { showPasscodeDialog = false },
            onConfirm = { code ->
                if (viewModel.savePasscode(code, enableLockAfter = enableAfterPasscode)) {
                    showPasscodeDialog = false
                    true
                } else false
            }
        )
    }
}

@Composable
fun DetailedNotificationsSettingsItem(viewModel: AddonSettingsViewModel) {
    val trigger by viewModel.updateTrigger.collectAsState()
    val detailed = remember(trigger) { viewModel.prefs.isDetailedNotificationsEnabled() }
    AddonSection(title = "Call Notifications") {
        ToggleListItem(
            label = "Detailed Call Notifications",
            checked = detailed,
            onCheckedChange = { viewModel.setDetailedNotifications(it) },
            description = "When off, the notification shown during an active call becomes a minimal, silent \"Call in progress\" message " +
                    "without phone number, contact name or recording details. Changes apply immediately, even mid-call."
        )
    }
}

@Composable
fun CallMetadataSettingsSection() {
    val context = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var foreground by remember { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) }
    var background by remember { mutableStateOf(granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        foreground = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        background = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { background = it }
    val foregroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        foreground = result.values.any { it }
    }

    AddonSection(title = "Call Metadata & Location") {
        Text(
            text = "A <recording name>.json file with call details, hold events and location points is saved next to every recording. " +
                    "Location is only sampled while a call is being recorded, and adapts to how fast you move (3 min when still, ~25 s when moving).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        val status = when {
            foreground && background -> "Location logging: allowed"
            foreground -> "Location logging: needs \"Allow all the time\" (calls are recorded in the background)"
            else -> "Location logging: off (no location permission)"
        }
        ListItem(
            headlineContent = { Text(status) },
            trailingContent = {
                if (!foreground || !background) {
                    TextButton(onClick = {
                        // Permission screens leave the activity; don't re-lock the calculator gate for this.
                        (context as? MainActivity)?.allowNextExternalReturn()
                        if (!foreground) {
                            foregroundLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        } else {
                            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    }) { Text("Grant") }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
fun RemoteUploadSettingsSection(viewModel: AddonSettingsViewModel) {
    val trigger by viewModel.updateTrigger.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val testState by viewModel.testState.collectAsState()
    val retryState by viewModel.retryState.collectAsState()
    val prefs = viewModel.prefs

    val enabled = remember(trigger) { prefs.isUploadEnabled() }
    val protocol = remember(trigger) { prefs.getProtocol() }
    val keyAuth = remember(trigger) { prefs.isSftpKeyAuth() }
    val pinned = remember(trigger) { prefs.getSftpHostKeyFingerprint() }

    AddonSection(title = "FTP / SFTP Storage") {
        ToggleListItem(
            label = "Enable Remote Upload",
            checked = enabled,
            onCheckedChange = { viewModel.setUploadEnabled(it) },
            description = "After each call, the recording and its .json are uploaded once in the background. Local copies are deleted only when both files upload and verify. " +
                    "Failed pairs stay on the device and are retried daily around midnight (when connected and battery is not low)."
        )

        val protocolOptions = AddonPreferences.Protocol.entries.map { OptionItem(it.key, it.label) }
        M3DropdownField(
            label = "Protocol",
            selected = protocolOptions.first { it.key == protocol.key },
            options = protocolOptions,
            onOptionSelected = { viewModel.setProtocol(AddonPreferences.Protocol.fromKey(it.key)) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        DebouncedTextField(
            label = "Host / Server",
            initial = remember(trigger) { prefs.getHost() },
            onCommit = viewModel::setHost,
            placeholder = "ftp.example.com (domain or IP only, no ftp:// or credentials)"
        )
        DebouncedTextField(
            label = "Port",
            initial = remember(trigger, protocol) { prefs.getPort().toString() },
            onCommit = viewModel::setPort,
            keyboardType = KeyboardType.Number,
            placeholder = protocol.defaultPort.toString()
        )
        DebouncedTextField(label = "Username", initial = remember(trigger) { prefs.getUsername() }, onCommit = viewModel::setUsername)

        if (protocol == AddonPreferences.Protocol.SFTP) {
            ToggleListItem(
                label = "Use SSH private key",
                checked = keyAuth,
                onCheckedChange = { viewModel.setSftpKeyAuth(it) },
                description = "Authenticate with an OpenSSH/PEM private key instead of a password."
            )
        }
        if (protocol == AddonPreferences.Protocol.SFTP && keyAuth) {
            DebouncedTextField(
                label = "SSH private key",
                initial = remember(trigger) { prefs.getSftpPrivateKey() },
                onCommit = viewModel::setSftpPrivateKey,
                secret = true,
                singleLine = false
            )
            DebouncedTextField(label = "Key passphrase (optional)", initial = remember(trigger) { prefs.getSftpPassphrase() }, onCommit = viewModel::setSftpPassphrase, secret = true)
        } else {
            DebouncedTextField(label = "Password", initial = remember(trigger) { prefs.getPassword() }, onCommit = viewModel::setPassword, secret = true)
        }

        DebouncedTextField(
            label = "Remote Target Directory",
            initial = remember(trigger) { prefs.getRemoteDir() },
            onCommit = viewModel::setRemoteDir,
            placeholder = AddonPreferences.DEFAULT_REMOTE_DIR
        )

        if (protocol == AddonPreferences.Protocol.SFTP && pinned != null) {
            ListItem(
                headlineContent = { Text("Pinned host key") },
                supportingContent = { Text(pinned.substringAfter('|')) },
                trailingContent = { TextButton(onClick = viewModel::forgetPinnedHostKey) { Text("Forget") } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }

        // Test connection
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = viewModel::testConnection, enabled = testState !is ActionState.Running) {
                Text("Test Connection")
            }
            Spacer(Modifier.width(12.dp))
            if (testState is ActionState.Running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        ActionResultText(testState)

        // Queue + manual retry
        ListItem(
            headlineContent = { Text("Pending uploads") },
            supportingContent = {
                Text(if (pendingCount == 0) "No call pairs waiting on this device." else "$pendingCount call pair(s) waiting locally.")
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = viewModel::retryFailedNow, enabled = pendingCount > 0 && retryState !is ActionState.Running) {
                Text("Retry Failed Uploads Now")
            }
            Spacer(Modifier.width(12.dp))
            if (retryState is ActionState.Running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        ActionResultText(retryState)
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

// ───────────────────────────── helpers ─────────────────────────────

@Composable
private fun ActionResultText(state: ActionState) {
    if (state is ActionState.Done) {
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

/** Text field that saves ~400 ms after the user stops typing (same pattern as the Shizuku auth key field). */
@Composable
private fun DebouncedTextField(
    label: String,
    initial: String,
    onCommit: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    singleLine: Boolean = true,
    placeholder: String? = null
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(text) {
        delay(400.milliseconds)
        if (text != initial) onCommit(text)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 6,
        visualTransformation = if (secret && !focused) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret && singleLine) KeyboardType.Password else keyboardType,
            autoCorrectEnabled = false
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .onFocusChanged { focused = it.isFocused }
    )
}

@Composable
private fun PasscodeDialog(onDismiss: () -> Unit, onConfirm: (String) -> Boolean) {
    var code by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Calculator Passcode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter ${AppLockManager.MIN_PASSCODE_LENGTH}–${AppLockManager.MAX_PASSCODE_LENGTH} digits. To unlock, type it on the calculator and press \"=\".")
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> if (v.all { it.isDigit() } && v.length <= AppLockManager.MAX_PASSCODE_LENGTH) code = v },
                    label = { Text("Passcode") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { v -> if (v.all { it.isDigit() } && v.length <= AppLockManager.MAX_PASSCODE_LENGTH) confirm = v },
                    label = { Text("Confirm passcode") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    !AppLockManager.isValidPasscode(code) -> "Use ${AppLockManager.MIN_PASSCODE_LENGTH}–${AppLockManager.MAX_PASSCODE_LENGTH} digits."
                    code != confirm -> "Passcodes don't match."
                    !onConfirm(code) -> "Could not save passcode."
                    else -> null
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Same look as the private SettingsSection in SettingsScreen.kt. */
@Composable
private fun AddonSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}
