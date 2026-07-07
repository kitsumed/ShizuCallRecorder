/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.viewmodels

import android.app.Application
import android.provider.ContactsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.system.permissions.PermissionChecks
import com.kitsumed.shizucallrecorder.ui.common.ContactSelectionDialog
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whether the contact-filter rule applies to incoming or outgoing calls.
 * Used when opening the contact picker so the app knows which list to update.
 */
enum class ContactPickerType {
    INCOMING,
    OUTGOING
}

/**
 * All the data needed to display the [ContactSelectionDialog].
 *
 * [ContactPickerViewModel.contactPickerState] is null when the dialog is closed and non-null
 * when it should be shown. [SettingsScreen] checks this value and opens the dialog automatically.
 *
 * @param type            Whether the picker is for incoming or outgoing calls.
 * @param contacts        The list of contacts loaded from the device.
 * @param selectedContactsLookupId Contacts lookup ID that are already saved as ignored (shown pre-checked).
 */
data class ContactPickerState(
    val type: ContactPickerType,
    val contacts: List<ContactEntry>,
    val selectedContactsLookupId: Set<String>
)

/**
 * A single device contact entry shown in [ContactSelectionDialog].
 *
 * @param lookupKey The unique key/ID for this contact (used as the selection key).
 * @param name The contact's display name from the Contacts provider.
 * @param displayNumbers A string representations of the phone numbers associated with this entry.
 * @param photoUri Content URI of the contact's thumbnail photo, or null if none exists.
 */
data class ContactEntry(
    val lookupKey: String,
    val name: String,
    val displayNumbers: String,
    val photoUri: String? = null
)

/**
 * The "Brain" of the contact-picker dialog.
 *
 * [contactPickerState] is null when the dialog is closed and non-null when it should be
 * shown. [SettingsScreen] observes this via `collectAsState()` — the "bridge" that watches
 * the `StateFlow` and triggers a refresh (recompose) whenever the dialog opens or closes.
 */
class ContactPickerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SCR:ContactPickerViewModel"
    }

    /**
     * Application context - safe to store in a ViewModel because it lives as long as the
     * app process, unlike an Activity context which is destroyed and recreated on every rotation.
     */
    private val appContext  = application.applicationContext

    /**
     * Read and Manager AppPreference settings
     */
    private val preferences = AppPreferences(appContext)

    private val _contactPickerState = MutableStateFlow<ContactPickerState?>(null)

    /**
     * The current state of the [ContactSelectionDialog] - the "Snapshot" driving the dialog UI.
     *
     * The UI uses `collectAsState()` to observe this flow; setting it to a non-null
     * [ContactPickerState] triggers a refresh (recompose) that opens the dialog, and setting
     * it back to `null` triggers a refresh (recompose) that closes it.
     */
    val contactPickerState: StateFlow<ContactPickerState?> = _contactPickerState.asStateFlow()

    /**
     * Opens the contact-picker dialog for either incoming or outgoing calls.
     *
     * Loads the device contacts list in the background (so the screen doesn't freeze),
     * then shows the [ContactSelectionDialog] with the contacts already pre-selected based
     * on the saved settings.
     *
     * @param type Whether the picker is for incoming or outgoing call filtering.
     */
    fun openContactPicker(type: ContactPickerType) {
        if (!PermissionChecks.hasContactsPermission(appContext)) return
        // Run in the background so loading contacts doesn't freeze the screen.
        viewModelScope.launch {
            val contacts = loadContactsFromDevice()

            // Fetch raw saved keys
            val rawSelectedKeys = when (type) {
                ContactPickerType.INCOMING -> preferences.getIgnoredContactsIncoming()
                ContactPickerType.OUTGOING -> preferences.getIgnoredContactsOutgoing()
            }

            // List of all contacts lookup keys.
            val validContactsLookupKeys = contacts.map { it.lookupKey }.toSet()
            // Keep only the saved keys that still exist on the device. Ignore old/stale keys so they later get removed when user save.
            val cleanSelectedLookupKeys = rawSelectedKeys.intersect(validContactsLookupKeys)

            if (rawSelectedKeys.size != cleanSelectedLookupKeys.size) {
                AppLogger.d(TAG, "Ignored ${rawSelectedKeys.size - cleanSelectedLookupKeys.size} stale contacts lookup keys.")
            }

            _contactPickerState.value = ContactPickerState(type, contacts, cleanSelectedLookupKeys)
        }
    }

    /**
     * Saves the contacts the user selected in the [ContactSelectionDialog] and closes the dialog.
     * Call [SettingsViewModel.refresh] afterward to update the settings screen with the new list.
     *
     * @param contactsLookupId The contacts (lookup id) the user chose to ignore.
     */
    fun confirmContactPicker(contactsLookupId: Set<String>) {
        val currentType = _contactPickerState.value?.type
        when (currentType) {
            ContactPickerType.INCOMING -> preferences.setIgnoredContactsIncoming(contactsLookupId)
            ContactPickerType.OUTGOING -> preferences.setIgnoredContactsOutgoing(contactsLookupId)
            null -> throw IllegalStateException("Contact picker state is null when trying to confirm selection. This should never happen.")
        }
        _contactPickerState.value = null
    }

    /** Closes the [ContactSelectionDialog] without saving any changes. */
    fun dismissContactPicker() {
        _contactPickerState.value = null
    }

    /**
     * Loads all contacts from the device address book.
     * Runs in the background so it doesn't freeze the screen.
     *
     * @return A sorted list of [ContactEntry] objects, or an empty list if the Contacts
     *         permission is missing.
     */
    private suspend fun loadContactsFromDevice(): List<ContactEntry> {
        if (!PermissionChecks.hasContactsPermission(appContext)) return emptyList()
        // withContext(Dispatchers.IO) runs the database query in the background.
        return withContext(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            )

            val contactsMap = mutableMapOf<String, ContactEntry>()

            // .use{} automatically closes the cursor when done, even if something goes wrong.
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
                val lookupKeyIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)

                while (cursor.moveToNext()) {
                    val lookupKey = cursor.getString(lookupKeyIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: ""
                    val number = cursor.getString(numberIndex)?.trim() ?: ""
                    val photoUri = cursor.getString(photoIndex)

                    if (number.isNotBlank()) {
                        val existing = contactsMap[lookupKey]
                        if (existing != null) {
                            // Append the number if it's not already in the string
                            if (!existing.displayNumbers.contains(number)) {
                                contactsMap[lookupKey] = existing.copy(
                                    displayNumbers = "${existing.displayNumbers}, $number"
                                )
                            }
                        } else {
                            contactsMap[lookupKey] = ContactEntry(lookupKey, name, number, photoUri)
                        }
                    }
                }
            }
            contactsMap.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
    }
}
