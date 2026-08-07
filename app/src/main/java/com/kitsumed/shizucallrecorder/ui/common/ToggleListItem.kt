/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.kitsumed.shizucallrecorder.ui.theme.ShizuCallRecorderTheme

/** A list row with a switch. Tapping anywhere on the row toggles the switch.
 *
 * @param label           The text shown as the headline of the row.
 * @param checked         The current on/off state of the switch.
 * @param onCheckedChange Called with the new boolean when the user toggles.
 * @param modifier        Optional [Modifier] forwarded to the root [ListItem].
 * @param description     Optional text shown below the label.
 * @param enabled         Whether the switch and row are interactable.
 */
@Composable
fun ToggleListItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    ListItem(
        modifier = modifier
            .semantics(mergeDescendants = true) {}
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { onCheckedChange(it) }
            ),
        headlineContent = {
            Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha))
        },
        supportingContent = if (description != null) {
            { Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)) }
        } else null,
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null, // Only here for visuals
                enabled = enabled
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Preview(showBackground = true)
@Composable
private fun PreviewToggleListItem() {
    ShizuCallRecorderTheme(darkTheme = false) {
        Surface(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column() {
                ToggleListItem(
                    label = "Example Checked Toggle",
                    checked = true,
                    onCheckedChange = {},
                    description = "This is what a toggle list item looks like"
                )

                ToggleListItem(
                    label = "Example Unchecked Toggle",
                    checked = false,
                    onCheckedChange = {},
                    description = "This is what a toggle list item looks like"
                )

                ToggleListItem(
                    label = "Example Disabled Toggle",
                    checked = false,
                    enabled = false,
                    onCheckedChange = {},
                    description = "This is what a toggle list item looks like"
                )
            }
        }
    }
}
