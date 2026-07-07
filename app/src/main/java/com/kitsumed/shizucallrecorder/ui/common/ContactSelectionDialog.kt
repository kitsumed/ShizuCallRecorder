/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.common

import android.graphics.ImageDecoder
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.kitsumed.shizucallrecorder.ui.theme.ShizucallrecorderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.ui.viewmodels.ContactEntry


/**
 * Full-screen dialog that lets the user select one or more contacts from a searchable list.
 *
 * @param title            Heading text shown at the top of the dialog.
 * @param contacts         Full list of device contacts to display.
 * @param initialSelection Contacts (LookupKey) that should be pre-checked when the dialog opens.
 * @param onConfirm        Called with the final [Set] of selected contacts (LookupKey) when the user taps OK.
 * @param onDismiss        Called when the user taps Cancel or dismisses the dialog.
 * @param modifier         Optional layout modifier for the dialog [Surface].
 */

@Composable
fun ContactSelectionDialog(
    title: String,
    contacts: List<ContactEntry>,
    initialSelection: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ContactSelectionContent(
            title = title,
            contacts = contacts,
            initialSelection = initialSelection,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            modifier = modifier
        )
    }
}

/**
 * The main content of the contact selection dialog, extracted to a separate composable for better
 * testability and to support Android Studio Preview.
 *
 * Shows every contact in [contacts] in a [LazyColumn] with a search bar at the top.
 *
 * @param title            Heading text shown at the top of the dialog.
 * @param contacts         Full list of device contacts to display.
 * @param initialSelection Contacts (LookupKeys) that should be pre-checked when the dialog opens.
 * @param onConfirm        Called with the final [Set] of selected contacts (LookupKeys) when the user taps OK.
 * @param onDismiss        Called when the user taps Cancel or dismisses the dialog.
 * @param modifier         Optional layout modifier for the root [Surface].
 */
@Composable
fun ContactSelectionContent(
    title: String,
    contacts: List<ContactEntry>,
    initialSelection: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }

    // mutableStateListOf gives Compose per-item granularity: toggling one row only
    // triggers a refresh (recompose) for that row, not the entire list.
    // Keyed on initialSelection so the checked state resets if the dialog re-opens with new data.
    val selectedContactsLookupKey = remember(initialSelection) {
        mutableStateListOf<String>().apply { addAll(initialSelection) }
    }

    val filteredContacts = remember(searchQuery, contacts) {
        if (searchQuery.isBlank()) contacts
        else contacts.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    it.displayNumbers.contains(searchQuery)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(24.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.contact_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = CircleShape,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(
                    items = filteredContacts,
                    key = { it.lookupKey } // Stable key enables efficient diffing in LazyColumn.
                ) { contact ->
                    val isSelected = selectedContactsLookupKey.contains(contact.lookupKey)

                    ContactListItem(
                        contact = contact,
                        isSelected = isSelected,
                        modifier = Modifier.animateItem(), // This handles the entry, exit, and reordering animations in the list
                        onToggle = {
                            if (isSelected) {
                                // Directly mutate the observable list. Because selectedContactsLookupKey is
                                // a mutableStateListOf, Compose triggers a refresh (recompose)
                                // only for this item's row — not the entire list.
                                selectedContactsLookupKey.remove(contact.lookupKey)
                            } else {
                                selectedContactsLookupKey.add(contact.lookupKey)
                            }
                        }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isAllSelected = contacts.isNotEmpty() && contacts.all { selectedContactsLookupKey.contains(it.lookupKey) }
                TextButton(
                    onClick = {
                        if (isAllSelected) {
                            selectedContactsLookupKey.clear()
                        } else {
                            selectedContactsLookupKey.clear()
                            selectedContactsLookupKey.addAll(contacts.map { it.lookupKey })
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (isAllSelected) R.string.contact_unselect_all
                            else R.string.contact_select_all
                        )
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text(stringResource(R.string.general_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            // Convert the observable list back to an immutable Set for the callback.
                            onConfirm(selectedContactsLookupKey.toSet())
                        }
                    ) {
                        Text(stringResource(R.string.general_ok))
                    }
                }
            }
        }
    }
}

/**
 * A single row in the contact selection list.
 *
 * @param contact    The contact to display.
 * @param isSelected Whether this contact is currently in the selection set.
 * @param onToggle   Called when the user taps the row to flip the selection.
 */
@Composable
private fun ContactListItem(
    contact: ContactEntry,
    isSelected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Smooth M3 background color transition when the selection state changes.
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected)
            MaterialTheme.colorScheme.secondaryContainer
        else
            Color.Transparent,
        label = "selection_bg_anim",
    )
    // Surface provides the correct background for dark-theme compatibility.
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = isSelected,
                    onValueChange = { onToggle() },
                    role = Role.Checkbox // Accessibility: Tells TalkBack this is a checkbox behavior
                ),
            headlineContent = {
                Text(contact.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
            },
            supportingContent = { Text(contact.displayNumbers) },
            leadingContent = { ContactAvatar(contact) },
            colors = ListItemDefaults.colors(
                containerColor = backgroundColor // Background is applied via the modifier above.
            )
        )
    }
}

/**
 * Circular avatar for a [ContactEntry].
 *
 * @param contact The contact whose avatar to render.
 */
@Composable
private fun ContactAvatar(contact: ContactEntry) {
    val context = LocalContext.current

    // produceState launches the image decode in the background and updates the state value
    // when done, triggering a refresh (recompose) of this item only.
    val contactBitmap by produceState<android.graphics.Bitmap?>(initialValue = null, contact.photoUri) {
        if (contact.photoUri != null) {
            value = withContext(Dispatchers.IO) {
                try {
                    val uri = contact.photoUri.toUri()
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = contactBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = contact.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewContactSelectionDialog() {
    val dummyContacts = listOf(
        ContactEntry("1","Alice Smith", "+1 555-0101", null),
        ContactEntry("2","Bob Johnson", "+1 555-0202", null),
        ContactEntry("3","Charlie Brown", "+1 555-0303", null),
        ContactEntry("4","David Wilson", "+1 555-0404", null)
    )
    val selectedContacts = setOf("2")

    ShizucallrecorderTheme(darkTheme = true) {
        ContactSelectionContent(
            title = stringResource(R.string.settings_select_contacts, selectedContacts.count()),
            contacts = dummyContacts,
            initialSelection = selectedContacts,
            onConfirm = {},
            onDismiss = {}
        )
    }
}
