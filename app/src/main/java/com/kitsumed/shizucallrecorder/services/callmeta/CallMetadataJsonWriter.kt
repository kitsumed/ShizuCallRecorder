/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.callmeta

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.utils.AppLogger
import org.json.JSONObject

/**
 * Writes `<audio_base_name>.json` in the same SAF folder as the audio file produced by the original recording pipeline.
 */
object CallMetadataJsonWriter {

    private const val JSON_MIME = "application/json"

    data class WrittenFile(val uri: Uri, val displayName: String)

    /**
     * Splits "20261123_170300_call.ogg" into ("20261123_170300_call", ".ogg"). Works for any extension.
     */
    fun splitName(fileName: String?): Pair<String?, String?> {
        if (fileName.isNullOrBlank()) return null to null
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) to fileName.substring(dot) else fileName to null
    }

    fun writeNextToAudio(context: Context, audioUri: Uri, baseName: String, json: JSONObject): WrittenFile? {
        val resolver = context.contentResolver
        val parent = resolveParentDocumentUri(context, audioUri) ?: run {
            AppLogger.w("CallMetadataJsonWriter: could not resolve the parent folder of $audioUri")
            return null
        }
        val targetName = "$baseName.json"
        val jsonUri = try {
            DocumentsContract.createDocument(resolver, parent, JSON_MIME, targetName)
        } catch (e: Exception) {
            AppLogger.w("CallMetadataJsonWriter: createDocument failed", e)
            null
        } ?: return null

        return try {
            resolver.openOutputStream(jsonUri, "wt")?.use { out ->
                out.write(json.toString(2).toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: throw IllegalStateException("openOutputStream returned null")
            val displayName = DocumentFile.fromSingleUri(context, jsonUri)?.name ?: targetName
            WrittenFile(jsonUri, displayName)
        } catch (e: Exception) {
            AppLogger.w("CallMetadataJsonWriter: failed writing JSON content, removing partial file", e)
            runCatching { DocumentsContract.deleteDocument(resolver, jsonUri) }
            null
        }
    }

    /**
     * Resolves the folder that contains [audioUri] (the audio may live in a sub-folder created by the file name template).
     */
    private fun resolveParentDocumentUri(context: Context, audioUri: Uri): Uri? {
        val resolver = context.contentResolver

        // 1. Provider-supported path lookup (ExternalStorageProvider and most others).
        runCatching {
            val path = DocumentsContract.findDocumentPath(resolver, audioUri)?.path
            if (path != null && path.size >= 2) {
                return DocumentsContract.buildDocumentUriUsingTree(audioUri, path[path.size - 2])
            }
        }

        // 2. Path-like document ids (e.g. "primary:Recordings/sub/file.ogg").
        runCatching {
            val docId = DocumentsContract.getDocumentId(audioUri)
            val treeId = DocumentsContract.getTreeDocumentId(audioUri)
            if (docId.contains('/')) {
                val parentId = docId.substringBeforeLast('/')
                if (parentId.length >= treeId.length) {
                    return DocumentsContract.buildDocumentUriUsingTree(audioUri, parentId)
                }
            }
        }

        // 3. Fallback: the configured recording folder root.
        val treeUri = AppPreferences(context).getRecordingFolderUri() ?: return null
        return runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        }.getOrNull()
    }
}
