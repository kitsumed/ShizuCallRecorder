/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.upload

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.kitsumed.shizucallrecorder.data.AddonPreferences
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Uploads queued audio+json pairs. One pass per call: no internal retry loops, no notifications.
 *
 * Local files are deleted ONLY when both the audio and the JSON uploaded and the server reported a matching, non-zero size.
 */
object UploadProcessor {

    /** Serializes all upload passes (post-call, midnight, manual) so a pair is never uploaded twice concurrently. */
    private val mutex = Mutex()

    data class PassResult(val uploaded: Int, val failed: Int, val skipped: Int)

    /** Processes a single queue entry (post-call attempt). */
    suspend fun processEntry(context: Context, entryId: String): PassResult = run(context) { store ->
        listOfNotNull(store.get(entryId))
    }

    /** Processes every pending/failed entry (midnight job & "Retry Failed Uploads Now"). */
    suspend fun processAll(context: Context): PassResult = run(context) { store -> store.all() }

    private suspend fun run(context: Context, select: (UploadQueueStore) -> List<UploadQueueStore.Entry>): PassResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val appContext = context.applicationContext
                val prefs = AddonPreferences(appContext)
                val store = UploadQueueStore.getInstance(appContext)
                val entries = select(store)
                if (entries.isEmpty()) return@withLock PassResult(0, 0, 0)
                if (!prefs.isUploadEnabled() || !prefs.isUploadConfigured()) {
                    AppLogger.i("UploadProcessor: upload disabled or not configured, keeping ${entries.size} pair(s) locally.")
                    return@withLock PassResult(0, 0, entries.size)
                }

                val config = RemoteConfig.from(prefs)
                val client = try {
                    RemoteStorageClient.connect(config).also { c ->
                        c.ensureRemoteDir()
                        c.newHostKeyPin?.let { prefs.setSftpHostKeyFingerprint(it) }
                    }
                } catch (e: Exception) {
                    val msg = UploadErrorMessages.describe(e, config)
                    AppLogger.w("UploadProcessor: connection failed: $msg", e)
                    entries.forEach { store.markFailed(it.id, msg) }
                    return@withLock PassResult(0, entries.size, 0)
                }

                var uploaded = 0
                var failed = 0
                var skipped = 0
                client.use { c ->
                    for (entry in entries) {
                        when (uploadPair(appContext, c, config, entry, store)) {
                            Outcome.UPLOADED -> uploaded++
                            Outcome.FAILED -> failed++
                            Outcome.DROPPED -> skipped++
                        }
                    }
                }
                AppLogger.i("UploadProcessor: pass done uploaded=$uploaded failed=$failed dropped=$skipped")
                PassResult(uploaded, failed, skipped)
            }
        }

    private enum class Outcome { UPLOADED, FAILED, DROPPED }

    private fun uploadPair(context: Context, client: RemoteStorageClient, config: RemoteConfig, entry: UploadQueueStore.Entry, store: UploadQueueStore): Outcome {
        val audioDoc = DocumentFile.fromSingleUri(context, entry.audioUri.toUri())
        val jsonDoc = DocumentFile.fromSingleUri(context, entry.jsonUri.toUri())

        if (audioDoc == null || !audioDoc.exists()) {
            // The user deleted the recording locally: nothing meaningful left to upload.
            AppLogger.i("UploadProcessor: local audio ${entry.audioName} no longer exists, dropping queue entry.")
            store.remove(entry.id)
            return Outcome.DROPPED
        }

        return try {
            val audioName = audioDoc.name ?: entry.audioName
            uploadAndVerify(context, client, audioDoc, audioName)

            if (jsonDoc == null || !jsonDoc.exists()) throw IOException("Local JSON ${entry.jsonName} is missing")
            val jsonName = jsonDoc.name ?: entry.jsonName
            uploadAndVerify(context, client, jsonDoc, jsonName)

            // Both verified: free local space.
            val audioDeleted = runCatching { audioDoc.delete() }.getOrDefault(false)
            val jsonDeleted = runCatching { jsonDoc.delete() }.getOrDefault(false)
            if (!audioDeleted || !jsonDeleted) AppLogger.w("UploadProcessor: uploaded ${entry.audioName} but could not delete all local copies.")
            store.remove(entry.id)
            Outcome.UPLOADED
        } catch (e: Exception) {
            // Keep BOTH local files untouched and stop for this pair. Next attempt: midnight job or manual retry.
            val msg = UploadErrorMessages.describe(e, config)
            AppLogger.w("UploadProcessor: upload of ${entry.audioName} failed: $msg", e)
            store.markFailed(entry.id, msg)
            Outcome.FAILED
        }
    }

    private fun uploadAndVerify(context: Context, client: RemoteStorageClient, doc: DocumentFile, remoteName: String) {
        val localSize = doc.length()
        val remoteSize = context.contentResolver.openInputStream(doc.uri)?.use { input ->
            client.upload(input.buffered(), remoteName)
        } ?: throw IOException("Cannot open local file $remoteName")

        if (remoteSize <= 0L) throw IOException("Verification failed for $remoteName: remote size is $remoteSize")
        if (localSize > 0L && remoteSize != localSize) throw IOException("Verification failed for $remoteName: remote $remoteSize B != local $localSize B")
    }

    /**
     * Connects with the current settings, enters/creates the remote directory and disconnects. For the Settings "Test Connection" button.
     * @return a user-readable result line.
     */
    suspend fun testConnection(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val prefs = AddonPreferences(context.applicationContext)
        if (!prefs.isUploadConfigured()) return@withContext Result.failure(IOException("Host, username and credentials are required."))
        val config = RemoteConfig.from(prefs)
        runCatching {
            RemoteStorageClient.connect(config).use { c ->
                c.ensureRemoteDir()
                val pin = c.newHostKeyPin
                if (pin != null) prefs.setSftpHostKeyFingerprint(pin)
                buildString {
                    append("Connected. Remote directory is ready.")
                    if (pin != null) append("\nPinned host key: ${pin.substringAfter('|')}")
                }
            }
        }.recoverCatching { e -> throw IOException(UploadErrorMessages.describe(e, config), e) }
    }
}
