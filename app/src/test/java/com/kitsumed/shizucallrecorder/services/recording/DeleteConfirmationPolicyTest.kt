/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.recording

import com.kitsumed.shizucallrecorder.data.AppPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteConfirmationPolicyTest {

    @Test
    fun confirmationIsEnabledByDefault() {
        assertTrue(AppPreferences.DefaultsValue.SHOW_DELETE_CONFIRMATION)
    }

    @Test
    fun disabledConfirmationSkipsTheDialog() {
        assertFalse(shouldShowDeleteConfirmation(false))
    }
}
