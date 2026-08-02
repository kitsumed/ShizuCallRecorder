/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder

import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import com.kitsumed.shizucallrecorder.services.callDetection.incall.normaliseCallHandleNumber
import com.kitsumed.shizucallrecorder.services.recording.getPostRecordingCallerText
import org.junit.Assert.assertEquals
import org.junit.Test

class CallPresentationRegressionTest {

    @Test
    fun notificationShowsContactNameWhenFilenameTemplateIncludesIt() {
        val metadata = EnrichedCallData(
            normalisedPhoneNumber = "+972501234567",
            formattedE164Number = "+972501234567",
            direction = CallDirection.INCOMING,
            callerName = "Test Contact"
        )

        assertEquals(
            "Test Contact (+972501234567)",
            getPostRecordingCallerText(metadata, "{phone_number}_{caller_name}", "Unknown")
        )
    }

    @Test
    fun whatsappNumberWithoutPlusIsTreatedAsInternational() {
        assertEquals(
            "+972501234567",
            normaliseCallHandleNumber("972501234567", "com.whatsapp")
        )
    }
}
