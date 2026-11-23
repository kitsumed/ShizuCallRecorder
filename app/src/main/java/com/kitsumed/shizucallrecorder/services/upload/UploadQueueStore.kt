/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.upload

import android.content.Context
import android.util.AtomicFile
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Tiny persistent queue of audio+json pairs waiting to be uploaded. Stored as `files/upload_queue.json` (atomic writes).
 * Status is only ever surfaced inside the Settings UI, never as a notification.
 */
class UploadQueueStore private constructor(context: Context) {

    enum class Status(val key: String) {
        PENDING("pending"), FAILED("failed");

        companion object {
            fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: PENDING
        }
    }

    data class Entry(
        val id: String,
        val audioUri: String,
        val audioName: String,
        val jsonUri: String,
        val jsonName: String,
        val status: Status,
        val attempts: Int,
        val lastError: String?,
        val lastAttemptAt: Long?,
        val createdAt: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("audio_uri", audioUri)
            .put("audio_name", audioName)
            .put("json_uri", jsonUri)
            .put("json_name", jsonName)
            .put("status", status.key)
            .put("attempts", attempts)
            .put("last_error", lastError ?: JSONObject.NULL)
            .put("last_attempt_at", lastAttemptAt ?: JSONObject.NULL)
            .put("created_at", createdAt)

        companion object {
            fun fromJson(o: JSONObject) = Entry(
                id = o.getString("id"),
                audioUri = o.getString("audio_uri"),
                audioName = o.optString("audio_name"),
                jsonUri = o.getString("json_uri"),
                jsonName = o.optString("json_name"),
                status = Status.fromKey(o.optString("status")),
                attempts = o.optInt("attempts", 0),
                lastError = if (o.isNull("last_error")) null else o.optString("last_error"),
                lastAttemptAt = if (o.isNull("last_attempt_at")) null else o.optLong("last_attempt_at"),
                createdAt = o.optLong("created_at", System.currentTimeMillis())
            )
        }
    }

    companion object {
        const val FILE_NAME = "upload_queue.json"

        @Volatile
        private var INSTANCE: UploadQueueStore? = null

        fun getInstance(context: Context): UploadQueueStore = INSTANCE ?: synchronized(this) {
            INSTANCE ?: UploadQueueStore(context.applicationContext).also { INSTANCE = it }
        }
    }

    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private val entries = mutableListOf<Entry>()
    private val _count = MutableStateFlow(0)

    /** Number of pairs still stored locally (pending or failed). Observed by the Settings UI. */
    val count: StateFlow<Int> = _count.asStateFlow()

    init {
        load()
    }

    @Synchronized
    private fun load() {
        entries.clear()
        try {
            if (file.baseFile.exists()) {
                val arr = JSONArray(String(file.readFully(), Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    runCatching { entries += Entry.fromJson(arr.getJSONObject(i)) }
                }
            }
        } catch (e: Exception) {
            AppLogger.w("UploadQueueStore: failed to read queue, starting empty", e)
        }
        _count.value = entries.size
    }

    @Synchronized
    private fun persist() {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        val out = file.startWrite()
        try {
            out.write(arr.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            AppLogger.w("UploadQueueStore: failed to persist queue", e)
        }
        _count.value = entries.size
    }

    @Synchronized
    fun add(audioUri: String, audioName: String, jsonUri: String, jsonName: String): Entry {
        // De-duplicate on the audio URI
        entries.firstOrNull { it.audioUri == audioUri }?.let { return it }
        val entry = Entry(
            id = UUID.randomUUID().toString(),
            audioUri = audioUri, audioName = audioName,
            jsonUri = jsonUri, jsonName = jsonName,
            status = Status.PENDING, attempts = 0, lastError = null, lastAttemptAt = null,
            createdAt = System.currentTimeMillis()
        )
        entries += entry
        persist()
        return entry
    }

    @Synchronized
    fun get(id: String): Entry? = entries.firstOrNull { it.id == id }

    @Synchronized
    fun all(): List<Entry> = entries.toList()

    @Synchronized
    fun markFailed(id: String, error: String) {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx < 0) return
        val e = entries[idx]
        entries[idx] = e.copy(status = Status.FAILED, attempts = e.attempts + 1, lastError = error.take(300), lastAttemptAt = System.currentTimeMillis())
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        if (entries.removeAll { it.id == id }) persist()
    }

    @Synchronized
    fun lastError(): String? = entries.filter { it.lastError != null }.maxByOrNull { it.lastAttemptAt ?: 0L }?.lastError
}
