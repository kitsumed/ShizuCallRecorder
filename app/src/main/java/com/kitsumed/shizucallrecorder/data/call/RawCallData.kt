/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.data.call

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents the raw metadata about a call, as received from the OS or third-party.
 *
 * @param rawPhoneNumber The phone number string given by the OS, **which may be an empty string, when it's anonymous or unknown**.
 * @param direction The direction of the call (incoming or outgoing).
 * @param osProvidedCallerName An optional contact/caller name provided by the OS, if any.
 * @param packageName An optional package name of the app associated with the call, if any. This can be used for special handling of calls from specific apps.
 */
@Parcelize
data class RawCallData(
    val rawPhoneNumber: String,
    val direction: CallDirection,
    val osProvidedCallerName: String? = null,
    val packageName: String? = null
) : Parcelable

