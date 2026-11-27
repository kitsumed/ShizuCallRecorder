/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks the manifest declarations that Android uses to enforce the external recording API authorization.
 * A mistake here would expose the recording controls to every installed application.
 */
class ExternalRecordingManifestTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    // Unit tests run with the module directory as working directory.
    private val manifest = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))

    private fun findElement(tag: String, nameSuffix: String): Element {
        val nodes = manifest.getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            if (element.getAttributeNS(androidNs, "name").endsWith(nameSuffix)) return element
        }
        throw AssertionError("<$tag> ending with $nameSuffix not found in the manifest")
    }

    private fun Element.androidAttr(name: String): String = getAttributeNS(androidNs, name)

    @Test
    fun controlPermissionRequiresUserGrant() {
        val permission = findElement("permission", ExternalRecordingApi.PERMISSION_CONTROL_RECORDING)
        assertEquals(ExternalRecordingApi.PERMISSION_CONTROL_RECORDING, permission.androidAttr("name"))
        assertEquals("dangerous", permission.androidAttr("protectionLevel"))
    }

    @Test
    fun controlReceiverIsProtectedByPermission() {
        val receiver = findElement("receiver", ".ExternalRecordingControlReceiver")
        assertEquals("true", receiver.androidAttr("exported"))
        assertEquals(ExternalRecordingApi.PERMISSION_CONTROL_RECORDING, receiver.androidAttr("permission"))

        val actions = receiver.getElementsByTagName("action")
        val actionNames = (0 until actions.length).map { (actions.item(it) as Element).androidAttr("name") }.toSet()
        assertEquals(setOf(ExternalRecordingApi.ACTION_START_RECORDING, ExternalRecordingApi.ACTION_STOP_RECORDING), actionNames)
    }

    @Test
    fun recordingServiceStaysPrivate() {
        // External apps must go through the protected receiver, never directly to the service.
        val service = findElement("service", ".RecordingForegroundService")
        assertNotNull(service)
        assertEquals("false", service.androidAttr("exported"))
    }
}
