/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.screens

import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.system.PersistentFolderPickerContract
import com.kitsumed.shizucallrecorder.system.copyToClipboard
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioCodec
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioSource
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyConfig
import com.kitsumed.shizucallrecorder.system.storage.SafHelper
import com.kitsumed.shizucallrecorder.system.openGithub
import com.kitsumed.shizucallrecorder.system.takePersistableFolderPermission
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kitsumed.shizucallrecorder.ui.common.ContactSelectionDialog
import com.kitsumed.shizucallrecorder.ui.common.FileNameFormatDialog
import com.kitsumed.shizucallrecorder.ui.common.M3DropdownField
import com.kitsumed.shizucallrecorder.ui.common.OptionItem
import com.kitsumed.shizucallrecorder.ui.common.ToggleListItem
import com.kitsumed.shizucallrecorder.ui.viewmodels.ContactPickerType
import com.kitsumed.shizucallrecorder.ui.viewmodels.ContactPickerViewModel
import com.kitsumed.shizucallrecorder.ui.viewmodels.DebugAction
import com.kitsumed.shizucallrecorder.ui.viewmodels.SettingsActions
import com.kitsumed.shizucallrecorder.ui.viewmodels.SettingsViewModel
import com.kitsumed.shizucallrecorder.ui.viewmodels.ContactPickerState
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.kitsumed.shizucallrecorder.system.openGithubReportIssue
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * Stateful wrapper for the Settings screen that connects [SettingsViewModel] to [SettingsContent].
 *
 * @param viewModel Handles saving whenever the user changes a setting.
 * @param modifier  Optional modifier for the root [Surface].
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Trigger recomposition when settings change by viewmodel.refresh()
    val updateTrigger by viewModel.updateTrigger.collectAsState()

    // ContactPickerViewModel owns the contact-loading logic and dialog state.
    val contactPickerViewModel: ContactPickerViewModel = viewModel()
    val contactPickerState by contactPickerViewModel.contactPickerState.collectAsState()

    // Folder picker — PersistentFolderPickerContract keeps access alive after a reboot.
    val folderPickerLauncher = rememberLauncherForActivityResult(PersistentFolderPickerContract()) { uri ->
        if (uri != null) {
            context.takePersistableFolderPermission(uri)
            viewModel.preferences.setRecordingFolderUri(uri)
        }
        viewModel.refresh()
    }

    // Export logs picker — creates a new text file and gives us access to write to it, then passes the URI to the viewmodel for writing.
    val exportLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportLogs(uri)
        }
    }

    SettingsContent(
        preferences = viewModel.preferences,
        updateTrigger = updateTrigger,
        actions = viewModel,
        contactPickerState = contactPickerState,
        onSelectFolder = { folderPickerLauncher.launch(null) },
        onOpenContactsIncoming = { contactPickerViewModel.openContactPicker(ContactPickerType.INCOMING) },
        onOpenContactsOutgoing = { contactPickerViewModel.openContactPicker(ContactPickerType.OUTGOING) },
        onConfirmContacts = { numbers ->
            contactPickerViewModel.confirmContactPicker(numbers)
            // Refresh the screen so the new contact list information is shown immediately after confirming and closing the dialog.
            viewModel.refresh()
        },
        onDismissContacts = { contactPickerViewModel.dismissContactPicker() },
        onExportLogs = { exportLogLauncher.launch("shizucallrecorder_bug_report.log") },
        modifier = modifier
    )
}

/**
 * Stateless visual layer for the Settings screen.
 *
 * @param preferences            The [AppPreferences] instance to read data from.
 * @param updateTrigger          Trigger value to force/detect recomposition when settings change.
 * @param actions                Implementation of [SettingsActions] to handle user interaction.
 * @param contactPickerState     Current state of the contact picker dialog.
 * @param onSelectFolder         Called when the user taps the recording-folder row.
 * @param onOpenContactsIncoming Called to open picker for incoming contacts.
 * @param onOpenContactsOutgoing Called to open picker for outgoing contacts.
 * @param onConfirmContacts      Called when contacts are confirmed from the dialog.
 * @param onDismissContacts      Called when we want to close the dialog without confirmation/saving.
 * @param onExportLogs           Called to export diagnostic logs using SAF.
 * @param modifier               Optional size/position modifier.
 */
@Composable
fun SettingsContent(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    contactPickerState: ContactPickerState?,
    onSelectFolder: () -> Unit,
    onOpenContactsIncoming: () -> Unit,
    onOpenContactsOutgoing: () -> Unit,
    onConfirmContacts: (Set<String>) -> Unit,
    onDismissContacts: () -> Unit,
    onExportLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showLicensesDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.general_settings),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            item { AboutSection(versionString = actions.getAppVersion(), onShowLicenses = { showLicensesDialog = true }) }
            item {
                RecordingSection(
                    preferences = preferences,
                    updateTrigger = updateTrigger,
                    actions = actions,
                    onSelectFolder = onSelectFolder,
                    onOpenContactsIncoming = onOpenContactsIncoming,
                    onOpenContactsOutgoing = onOpenContactsOutgoing
                )
            }
            item { AudioSection(preferences, updateTrigger, actions) }
            item { SecuritySection(preferences, updateTrigger, actions) }
            item { VisualSection(preferences, updateTrigger, actions) }
            item { DebugSection(preferences, updateTrigger, actions, onExportLogs) }
        }
    }

    if (showLicensesDialog) {
        Dialog(
            onDismissRequest = { showLicensesDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.general_licenses),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp)
                    )

                    val libraries by produceLibraries(R.raw.aboutlibraries)
                    LibrariesContainer(libraries,Modifier
                        .fillMaxSize()
                        .weight(1f),
                        showAuthor = true, showLicenseBadges = true, showFundingBadges = false, showVersion = true, showDescription = true)
                    TextButton(
                        onClick = { showLicensesDialog = false },
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(8.dp)
                    ) {
                        Text(stringResource(R.string.general_close))
                    }
                }
            }
        }
    }

    // The contact-picker dialog sits on top of the settings content.
    contactPickerState?.let { picker ->
        ContactSelectionDialog(
            title = when (picker.type) {
                ContactPickerType.INCOMING -> stringResource(R.string.settings_select_contacts_incoming)
                ContactPickerType.OUTGOING -> stringResource(R.string.settings_select_contacts_outgoing)
            },
            contacts = picker.contacts,
            initialSelection = picker.selectedNumbers,
            onConfirm = onConfirmContacts,
            onDismiss = onDismissContacts
        )
    }
}

// ── Settings sections ──────────────────────────────────────────────────────────────────────

/** Shows the app version, server version, clipboard buttons, and a GitHub link.
 */
@Composable
private fun AboutSection(versionString: String, onShowLicenses: () -> Unit) {
    val context = LocalContext.current
    val serverVersion = ScrcpyConfig.SCRCPY_VERSION

    SettingsSection(title = stringResource(R.string.settings_section_about)) {
        ListItem(
            headlineContent = { Text(versionString) },
            supportingContent = {
                Text(stringResource(R.string.settings_scrcpy_server, serverVersion))
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { context.copyToClipboard("Scrcpy-Server Version", ScrcpyConfig.SCRCPY_VERSION) },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.settings_copy_version)) }
            OutlinedButton(
                onClick = onShowLicenses,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.settings_view_licenses)) }
        }
        Button(
            onClick = { context.openGithub() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) { Text(stringResource(R.string.settings_open_github)) }
    }
}

/** Shows the theme and dynamic colour settings.
 *
 * @param preferences   The [AppPreferences] instance to read data from.
 * @param updateTrigger Trigger value to force recomposition when settings change.
 * @param actions       Implementation of [SettingsActions] to handle user interaction.
 */
@Composable
private fun VisualSection(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val currentThemeMode = remember(updateTrigger) { preferences.getThemeMode() }
    val isDynamicColorEnabled = remember(updateTrigger) { preferences.isDynamicColorEnabled() }
    val isShowToastsEnabled = remember(updateTrigger) { preferences.isShowToastsEnabled() }
    val isVibrationEnabled = remember(updateTrigger) { preferences.isVibrationEnabled() }
    val context = LocalContext.current
    val resources = LocalResources.current

    // Read the current applied language without warnings
    val currentLanguage = remember {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        if (currentLocales.isEmpty) "" else currentLocales[0]?.toLanguageTag() ?: ""
    }

    // Fetch available languages from dynamically generated XML resource file.
    val languageOptions = remember(context) {
        val options = mutableListOf(OptionItem("", resources.getString(R.string.settings_language_system)))

        // Suppress the warning right here since AGP create this file dynamically at compile time
        @SuppressLint("DiscouragedApi")
        val resId = resources.getIdentifier("_generated_res_locale_config", "xml", context.packageName)

        try {
            val parser = resources.getXml(resId)
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    val localeName = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                    if (localeName != null) {
                        val locale = Locale.forLanguageTag(localeName)
                        val displayName = locale.getDisplayName(locale).replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(locale) else it.toString()
                        }
                        options.add(OptionItem(localeName, displayName))
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            options.add(OptionItem("en", "English (Provided as fallback)"))
        }
        options.distinctBy { it.key }
    }

    SettingsSection(title = stringResource(R.string.settings_section_visual)) {
        M3DropdownField(
            label = stringResource(R.string.settings_language),
            selected = languageOptions.find { it.key == currentLanguage } ?: languageOptions.first(),
            options = languageOptions,
            onOptionSelected = { actions.setAppLanguage(it.key) }
        )

        val themeOptions = AppPreferences.ThemeMode.entries.map { mode ->
            val labelRes = when (mode) {
                AppPreferences.ThemeMode.SYSTEM -> R.string.settings_theme_mode_system
                AppPreferences.ThemeMode.LIGHT -> R.string.settings_theme_mode_light
                AppPreferences.ThemeMode.DARK -> R.string.settings_theme_mode_dark
            }
            OptionItem(mode.key, stringResource(labelRes))
        }
        val defaultThemeMode = AppPreferences.DefaultsValue.THEME_MODE.key
        
        M3DropdownField(
            label    = stringResource(R.string.settings_theme_mode),
            selected = themeOptions.find { it.key == currentThemeMode.key } 
                ?: themeOptions.find { it.key == defaultThemeMode } 
                ?: themeOptions.first(),
            options  = themeOptions,
            onOptionSelected = { actions.setThemeMode(AppPreferences.ThemeMode.fromKey(it.key)) }
        )
        ToggleListItem(
            label           = stringResource(R.string.settings_dynamic_color),
            checked         = isDynamicColorEnabled,
            onCheckedChange = { actions.setDynamicColorEnabled(it) }
        )
        ToggleListItem(
            label           = stringResource(R.string.settings_show_toasts),
            checked         = isShowToastsEnabled,
            onCheckedChange = { actions.setShowToastsEnabled(it) }
        )
        ToggleListItem(
            label           = stringResource(R.string.settings_vibration_enabled),
            checked         = isVibrationEnabled,
            onCheckedChange = { actions.setVibrationEnabled(it) }
        )
    }
}

/** Shows the security settings.
 *
 * @param preferences   The [AppPreferences] instance to read data from.
 * @param updateTrigger Trigger value to force recomposition when settings change.
 * @param actions       Implementation of [SettingsActions] to handle user interaction.
 */
@Composable
private fun SecuritySection(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val autoManageShizuku = remember(updateTrigger) { preferences.isShizukuAutoManageEnabled() }
    val shizukuStartOnRecord = remember(updateTrigger) { preferences.isShizukuStartOnRecordEnabled() }
    val shizukuKeepAlive = remember(updateTrigger) { preferences.isShizukuKeepAliveEnabled() }
    val shizukuAuthKey = remember(updateTrigger) { preferences.getShizukuAuthKey() }

    SettingsSection(title = stringResource(R.string.settings_section_security)) {
        ToggleListItem(
            label           = stringResource(R.string.settings_shizuku_auto_manage),
            checked         = autoManageShizuku,
            onCheckedChange = { actions.setShizukuAutoManageEnabled(it) },
            description     = stringResource(R.string.settings_shizuku_auto_manage_desc)
        )

        AnimatedVisibility(visible = autoManageShizuku,enter = fadeIn() + expandVertically(),exit = fadeOut() + shrinkVertically()) {
            Column {
                var textState by remember(shizukuAuthKey) { mutableStateOf(shizukuAuthKey) }
                val keyboardController = LocalSoftwareKeyboardController.current
                var isFocused by remember { mutableStateOf(false) }

                OutlinedTextField(
                    value    = textState,
                    onValueChange = { textState = it },
                    label    = { Text(stringResource(R.string.settings_shizuku_auth_key)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        .onFocusChanged { isFocused = it.isFocused },
                    singleLine = true,
                    visualTransformation = if (isFocused) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Password, showKeyboardOnFocus = true),
                    keyboardActions = KeyboardActions(onDone = {
                        actions.setShizukuAuthKey(textState)
                        keyboardController?.hide()
                    })
                )

                ToggleListItem(
                    label           = stringResource(R.string.settings_shizuku_start_on_record),
                    checked         = shizukuStartOnRecord,
                    onCheckedChange = { actions.setShizukuStartOnRecordEnabled(it) },
                    description     = stringResource(R.string.settings_shizuku_start_on_record_desc)
                )

                ToggleListItem(
                    label           = stringResource(R.string.settings_shizuku_keep_alive),
                    checked         = shizukuKeepAlive,
                    onCheckedChange = { actions.setShizukuKeepAliveEnabled(it) },
                    description     = stringResource(R.string.settings_shizuku_keep_alive_desc)
                )
            }
        }
    }
}

/** Shows the recording folder, auto-record toggles, and contact-filter options.
 *
 * @param preferences              The [AppPreferences] instance to read data from.
 * @param updateTrigger          Trigger value to force recomposition when settings change.
 * @param actions                Implementation of [SettingsActions] to handle user interaction.
 * @param onSelectFolder         Called when the user taps the recording-folder row; opens the SAF picker
 *                               whose launcher lives in AppNavigation.
 * @param onOpenContactsIncoming Called when the user wants to pick incoming contacts to ignore;
 *                               opens the [ContactSelectionDialog] via [ContactPickerViewModel].
 * @param onOpenContactsOutgoing Called when the user wants to pick outgoing contacts to ignore;
 *                               opens the [ContactSelectionDialog] via [ContactPickerViewModel].
 */
@Composable
private fun RecordingSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    onSelectFolder: () -> Unit,
    onOpenContactsIncoming: () -> Unit,
    onOpenContactsOutgoing: () -> Unit
) {
    val context = LocalContext.current
    
    // Evaluate these here so they are fetched on every recomposition.
    val recordingFolderLabel = remember(updateTrigger) { SafHelper.getFolderDisplayNameOrNull(context, preferences.getRecordingFolderUri()) }
    val fileNameFormat = remember(updateTrigger) { preferences.getFileNameTemplate() }
    val autoRecordIncoming = remember(updateTrigger) { preferences.isAutoRecordIncomingEnabled() }
    val autoRecordOutgoing = remember(updateTrigger) { preferences.isAutoRecordOutgoingEnabled() }
    val ignoreAnonymousIncoming = remember(updateTrigger) { preferences.isIgnoreAnonymousIncomingEnabled() }
    val ignoreCrossCountryIncoming = remember(updateTrigger) { preferences.isIgnoreCrossCountryIncomingEnabled() }
    val ignoreContactsModeIncoming = remember(updateTrigger) { preferences.getIgnoreContactsModeIncoming() }
    val ignoreContactsModeOutgoing = remember(updateTrigger) { preferences.getIgnoreContactsModeOutgoing() }
    val ignoreCrossCountryOutgoing = remember(updateTrigger) { preferences.isIgnoreCrossCountryOutgoingEnabled() }
    val ignoredContactsIncomingCount = remember(updateTrigger) { preferences.getIgnoredContactsIncoming().size }
    val ignoredContactsOutgoingCount = remember(updateTrigger) { preferences.getIgnoredContactsOutgoing().size }

    var showFileNameFormatDialog by remember { mutableStateOf(false) }

    SettingsSection(title = stringResource(R.string.settings_section_recording)) {
        ListItem(
            modifier = Modifier.clickable { onSelectFolder() },
            headlineContent = { Text(stringResource(R.string.settings_recording_folder_label)) },
            supportingContent = {
                Text(
                    text = recordingFolderLabel ?: stringResource(R.string.settings_tap_to_select_folder),
                    color = MaterialTheme.colorScheme.primary
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        ListItem(
            modifier = Modifier.clickable { showFileNameFormatDialog = true },
            headlineContent = { Text(stringResource(R.string.settings_file_name_template)) },
            supportingContent = {
                Text(
                    text = fileNameFormat,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

        ToggleListItem(
            label           = stringResource(R.string.settings_auto_record_incoming),
            checked         = autoRecordIncoming,
            onCheckedChange = { actions.setAutoRecordIncoming(it) }
        )
        AnimatedVisibility(
            visible = autoRecordIncoming,
            enter   = fadeIn() +  expandVertically(),
            exit    = fadeOut() + shrinkVertically()
        ) {
            Column {
                ToggleListItem(
                    label           = stringResource(R.string.settings_ignore_anonymous_incoming),
                    checked         = ignoreAnonymousIncoming,
                    onCheckedChange = { actions.setIgnoreAnonymousIncoming(it) }
                )
                ToggleListItem(
                    label           = stringResource(R.string.settings_ignore_cross_country_incoming),
                    checked         = ignoreCrossCountryIncoming,
                    onCheckedChange = { actions.setIgnoreCrossCountryIncoming(it) },
                    enabled         = ignoreAnonymousIncoming
                )
                IgnoreContactsOptions(
                    label           = stringResource(R.string.settings_ignore_contacts_incoming),
                    selectedEnum     = ignoreContactsModeIncoming,
                    selectedCount    = ignoredContactsIncomingCount,
                    onSelected      = { actions.setIgnoreContactsModeIncoming(it) },
                    onSelectContacts = onOpenContactsIncoming
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

        ToggleListItem(
            label           = stringResource(R.string.settings_auto_record_outgoing),
            checked         = autoRecordOutgoing,
            onCheckedChange = { actions.setAutoRecordOutgoing(it) }
        )
        AnimatedVisibility(
            visible = autoRecordOutgoing,
            enter   = fadeIn() +  expandVertically(),
            exit    = fadeOut() + shrinkVertically()
        ) {
            Column {
                ToggleListItem(
                    label           = stringResource(R.string.settings_ignore_cross_country_outgoing),
                    checked         = ignoreCrossCountryOutgoing,
                    onCheckedChange = { actions.setIgnoreCrossCountryOutgoing(it) }
                )
                IgnoreContactsOptions(
                    label           = stringResource(R.string.settings_ignore_contacts_outgoing),
                    selectedEnum     = ignoreContactsModeOutgoing,
                    selectedCount    = ignoredContactsOutgoingCount,
                    onSelected      = { actions.setIgnoreContactsModeOutgoing(it) },
                    onSelectContacts = onOpenContactsOutgoing
                )
            }
        }
    }

    if (showFileNameFormatDialog) {
        FileNameFormatDialog(
            initialFormat = fileNameFormat,
            onConfirm = { format ->
                actions.setFileNameTemplate(format)
                showFileNameFormatDialog = false
            },
            onDismiss = { showFileNameFormatDialog = false }
        )
    }
}

/** Shows the audio source, codec, and bit-rate dropdowns.
 *
 * The audio-source list is generated from [ScrcpyAudioSource.entries], filtered by
 * [ScrcpyAudioSource.isDebugOnly] based on [AppPreferences.isDebugEnabled]. Items whose
 * [ScrcpyAudioSource.minApi]/[ScrcpyAudioSource.maxApi] range does not include the current
 * device's API level are shown grayed out and cannot be selected.
 *
 * @param preferences   The [AppPreferences] instance to read data from.
 * @param updateTrigger Trigger value to force recomposition when settings change.
 * @param actions       Implementation of [SettingsActions] to handle user interaction.
 */
@Composable
private fun AudioSection(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {

    val isDebugEnabled = remember(updateTrigger) { preferences.isDebugEnabled() }
    val audioSource = remember(updateTrigger) { preferences.getAudioSource() }
    val audioCodec = remember(updateTrigger) { preferences.getAudioCodec() }
    val savedBitRate = remember(updateTrigger) { preferences.getAudioBitRate() }
        
    SettingsSection(title = stringResource(R.string.settings_section_audio)) {
        val currentSdk = Build.VERSION.SDK_INT

        // Build the source list from the enum, hiding debug-only entries when debug is off.
        // Items that require an API level not available on this device are shown as disabled.
        val audioSourceOptions = ScrcpyAudioSource.entries
            .filter { !it.isDebugOnly || isDebugEnabled }
            .map { source ->
                OptionItem(
                    key         = source.cliKey,
                    label       = stringResource(source.titleResId),
                    description = stringResource(source.descriptionResId),
                    // Enabled only when the current SDK is within the source's API range.
                    enabled     = currentSdk >= source.minApi &&
                                  (source.maxApi == null || currentSdk <= source.maxApi)
                )
            }

        val selectedAudio = audioSourceOptions.find { it.key == audioSource }
            ?: audioSourceOptions.first()

        M3DropdownField(
            label    = stringResource(R.string.settings_audio_source),
            selected = selectedAudio,
            options  = audioSourceOptions,
            onOptionSelected = { actions.setAudioSource(it.key) }
        )
        // Show the description of the currently selected audio source below the dropdown.
        selectedAudio.description?.let { desc ->
            Text(
                text     = desc,
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        val codecOptions = ScrcpyAudioCodec.entries
            .map { OptionItem(it.cliKey, stringResource(it.titleResId)) }
        
        M3DropdownField(
            label    = stringResource(R.string.settings_audio_codec),
            selected = codecOptions.find { it.key == audioCodec } 
                ?: codecOptions.first(),
            options  = codecOptions,
            onOptionSelected = { actions.setAudioCodec(it.key) },
        )
        // Show the AAC recommendation if the user has issues.
        // LocalInspectionMode.current is true in Android Preview, it prevents a preview compilation error.
        if (!LocalInspectionMode.current && audioCodec != ScrcpyAudioCodec.AAC.cliKey) {
            Text(
                text     = stringResource(R.string.settings_audio_bitrate_recommendation),
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        val bitrateOptions = listOf(8000, 16000, 32000, 64000, 128000)
            .map { OptionItem(it.toString(), stringResource(R.string.audio_bitrate_kbps, it / 1000)) }

        M3DropdownField(
            label    = stringResource(R.string.settings_audio_bitrate),
            selected = bitrateOptions.find { it.key == savedBitRate.toString() } 
                ?: bitrateOptions.first(), // fallback gracefully if bitrate was removed from expected options
            options  = bitrateOptions,
            onOptionSelected = { actions.setAudioBitRate(it.key.toInt()) }
        )
    }
}

/** Shows the debug toggle, and when enabled, a caller-number field and test-call buttons.
 *
 * @param preferences   The [AppPreferences] instance to read data from.
 * @param updateTrigger Trigger value to force recomposition when settings change.
 * @param actions       Implementation of [SettingsActions] to handle user interaction.
 * @param onExportLogs  Called to export logs via SAF when logging is enabled.
 */
@Composable
private fun DebugSection(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions, onExportLogs: () -> Unit) {
    val isDebugEnabled = remember(updateTrigger) { preferences.isDebugEnabled() }
    val debugCallerNumber = remember(updateTrigger) { preferences.getDebugCallerNumber() }
    val isLoggingEnabled = remember(updateTrigger) { preferences.isLoggingEnabled() }
    val context = LocalContext.current

    SettingsSection(title = stringResource(R.string.settings_section_debug)) {
        ToggleListItem(
            label           = stringResource(R.string.settings_debug_logging_enabled),
            checked         = isLoggingEnabled,
            onCheckedChange = { actions.setLoggingEnabled(it) },
            description     = if (!isLoggingEnabled) stringResource(R.string.settings_debug_logging_enabled_description) else null
        )

        if (isLoggingEnabled) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.settings_debug_logging_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = stringResource(R.string.settings_debug_logging_steps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.settings_debug_logging_step_warning),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )

                if (isDebugEnabled) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = stringResource(R.string.settings_debug_logging_step_warning_no_redaction),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onExportLogs,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.settings_debug_logging_generate_report))
                    }

                    OutlinedButton(
                        onClick = { context.openGithubReportIssue()},
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.settings_debug_logging_report_on_github))
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), thickness = 0.5.dp)

        ToggleListItem(
            label           = stringResource(R.string.settings_debug_mode),
            checked         = isDebugEnabled,
            onCheckedChange = { actions.setDebugEnabled(it) },
            description = stringResource(R.string.settings_debug_mode_description)
        )
        if (isDebugEnabled) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                var textState by remember(debugCallerNumber) { mutableStateOf(debugCallerNumber) }
                val allowedChars = "^[0-9+-]*$".toRegex()
                val keyboardController = LocalSoftwareKeyboardController.current

                OutlinedTextField(
                    value    = textState,
                    onValueChange = { newValue ->
                        if (newValue.matches(allowedChars)) {
                            textState = newValue
                            // We aggressively update the setting on every change so that
                            // the test buttons always use the latest number.
                            actions.setDebugCallerNumber(newValue)
                        }
                    },
                    label    = { Text(stringResource(R.string.settings_debug_caller_number)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Phone, showKeyboardOnFocus = true),
                    keyboardActions = KeyboardActions(onDone = {
                        actions.setDebugCallerNumber(textState)
                        keyboardController?.hide()
                    })
                )
                DebugActionGrid(actions)
            }
        }
    }
}

// ── Internal helper composables ────────────────────────────────────────────────────────────


/** A titled card that groups related settings together.
 *
 * @param title   Section heading shown in the app's primary colour above the card.
 * @param content The slot for child Composables rendered inside the [ElevatedCard].
 */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text       = title,
            style      = MaterialTheme.typography.titleSmall,
            color      = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier   = Modifier.padding(start = 4.dp)
        )
        ElevatedCard(
            modifier  = Modifier.fillMaxWidth(),
            colors    = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

/**
 * A radio-button group for choosing which contacts to ignore.
 * When "selected" is active, shows a text field and a "Pick Contacts" button.
 *
 * @param label           Label shown above the radio buttons.
 * @param selectedEnum     The currently active mode ("none", "all", or "selected").
 * @param selectedCount    The number of contacts currently selected
 * @param onSelected      Called with the new active mode when the user taps a radio button.
 * @param onSelectContacts Called when the user taps the "Select Contacts" button; opens the
 *                        [ContactSelectionDialog] via [ContactPickerViewModel].
 */
@Composable
private fun IgnoreContactsOptions(
    label: String,
    selectedEnum: AppPreferences.IgnoreContactsMode,
    selectedCount: Int,
    onSelected: (AppPreferences.IgnoreContactsMode) -> Unit,
    onSelectContacts: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(8.dp))

        val enumEntries = AppPreferences.IgnoreContactsMode.entries
        enumEntries.forEach { ignoreContactMode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    // This make the box/text next to the radio button clickable, not just the button itself, which is more user-friendly.
                    .clickable { onSelected(ignoreContactMode) }
                    .padding(vertical = 4.dp)
            ) {
                // Make the actual radio button (circle) clickable (it's quite small)
                RadioButton(selected = selectedEnum == ignoreContactMode, onClick = { onSelected(ignoreContactMode) })
                Text(
                    text = when (ignoreContactMode) {
                        AppPreferences.IgnoreContactsMode.NONE -> stringResource(R.string.settings_ignore_contacts_none)
                        AppPreferences.IgnoreContactsMode.ALL -> stringResource(R.string.settings_ignore_contacts_all)
                        AppPreferences.IgnoreContactsMode.SELECTED   -> stringResource(R.string.settings_ignore_contacts_selected)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (selectedEnum == AppPreferences.IgnoreContactsMode.SELECTED) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick  = onSelectContacts,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.small
            ) { Text(stringResource(R.string.settings_select_contacts, selectedCount)) }
        }
    }
}

/** A row of buttons for simulating different call events during testing.
 *
 * @param actions Called via proxy for each button press.
 */
@Composable
private fun DebugActionGrid(actions: SettingsActions) {
    val items = listOf(
        DebugAction.RINGING  to stringResource(R.string.settings_debug_action_ringing),
        DebugAction.OFFHOOK  to stringResource(R.string.settings_debug_action_offhook),
        DebugAction.IDLE     to stringResource(R.string.settings_debug_action_idle)
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items.forEach { (action, label) ->
            FilledTonalButton(
                onClick = { actions.triggerDebugAction(action) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Safe Compose Preview for Settings.
 */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    MaterialTheme {
        val mockContext = LocalContext.current
        val dummyPreferences = AppPreferences(mockContext)
        val dummyActions = object : SettingsActions {
            override fun setAutoRecordIncoming(enabled: Boolean) {}
            override fun setAutoRecordOutgoing(enabled: Boolean) {}
            override fun setVibrationEnabled(enabled: Boolean) {}
            override fun setIgnoreAnonymousIncoming(enabled: Boolean) {}
            override fun setIgnoreCrossCountryIncoming(enabled: Boolean) {}
            override fun setIgnoreCrossCountryOutgoing(enabled: Boolean) {}
            override fun setIgnoreContactsModeIncoming(modeEnum: AppPreferences.IgnoreContactsMode) {}
            override fun setIgnoreContactsModeOutgoing(modeEnum: AppPreferences.IgnoreContactsMode) {}
            override fun setAudioSource(source: String) {}
            override fun setAudioCodec(codec: String) {}
            override fun setAudioBitRate(bitRate: Int) {}
            override fun setThemeMode(mode: AppPreferences.ThemeMode) {}
            override fun setDynamicColorEnabled(enabled: Boolean) {}
            override fun setShowToastsEnabled(enabled: Boolean) {}
            override fun setAppLanguage(languageCode: String) {}
            override fun setLoggingEnabled(enabled: Boolean) {}
            override fun setDebugEnabled(enabled: Boolean) {}
            override fun setDebugCallerNumber(number: String) {}
            override fun triggerDebugAction(action: DebugAction) {}
            override fun exportLogs(uri: Uri) {}
            override fun getAppVersion(): String = "Version 1.0.0 (Mock)"
            override fun setShizukuAutoManageEnabled(enabled: Boolean) {}
            override fun setShizukuStartOnRecordEnabled(enabled: Boolean) {}
            override fun setShizukuKeepAliveEnabled(enabled: Boolean) {}
            override fun setShizukuAuthKey(key: String) {}
            override fun setFileNameTemplate(template: String) {}
        }

        // File name template selection dialog
        //FileNameFormatDialog(AppPreferences.DefaultsValue.FILE_NAME_TEMPLATE, {},{})

        SettingsContent(
            preferences = dummyPreferences,
            updateTrigger = 0,
            actions = dummyActions,
            contactPickerState = null,
            onSelectFolder = {},
            onOpenContactsIncoming = {},
            onOpenContactsOutgoing = {},
            onConfirmContacts = {},
            onDismissContacts = {},
            onExportLogs = {}
        )
    }
}
