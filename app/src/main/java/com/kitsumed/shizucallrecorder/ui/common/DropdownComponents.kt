/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.common

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.ui.tooling.preview.Preview
import com.kitsumed.shizucallrecorder.ui.theme.ShizucallrecorderTheme

/**
 * A key/label/description/etc... data class used to populate dropdown menus.
 *
 * @property key         Machine-readable identifier, identifies the selection in the data layer persisted.
 * @property label       Human-readable text shown inside the dropdown field.
 * @property description Optional one-line description shown below the dropdown for the selected item.
 * @property enabled     Whether this item can be selected; `false` grays it out and disables clicks.
 */
data class OptionItem(
    val key: String,
    val label: String,
    val description: String? = null,
    val enabled: Boolean = true
)

/**
 * A Material 3 [ExposedDropdownMenuBox]-based dropdown field.
 *
 * @param label            Text label shown above the field.
 * @param selected         The currently selected [OptionItem].
 * @param options          All available options shown in the dropdown menu.
 * @param onOptionSelected Called with the chosen [OptionItem] when the user picks a new option.
 * @param modifier         Optional layout modifier forwarded to the root [ExposedDropdownMenuBox].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3DropdownField(
    label: String,
    selected: OptionItem,
    options: List<OptionItem>,
    onOptionSelected: (OptionItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded         = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier         = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        OutlinedTextField(
            value         = selected.label,
            onValueChange = {},
            readOnly      = true,
            label         = { Text(label) },
            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors        = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier      = Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column(
                            modifier = Modifier.padding(vertical = if (option.description != null) 4.dp else 0.dp)
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            option.description?.let { desc ->
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (option.enabled) {
                                        LocalContentColor.current.copy(alpha = 0.7f)
                                    } else {
                                        LocalContentColor.current
                                    }
                                )
                            }
                        }
                    },
                    onClick = {
                        if (option.enabled) {
                            onOptionSelected(option)
                            expanded = false
                        }
                    },
                    enabled        = option.enabled,
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewM3DropdownField() {
    val options = listOf(
        OptionItem("opt1", "Standard Option", "Description for option 1"),
        OptionItem("opt2", "Another Option", "Description for option 2"),
        OptionItem("opt3", "Disabled Option", "This cannot be selected", enabled = false)
    )
    ShizucallrecorderTheme(darkTheme = false) {
        Surface {
            M3DropdownField(
                label = "Select an option",
                selected = options[0],
                options = options,
                onOptionSelected = {},
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
