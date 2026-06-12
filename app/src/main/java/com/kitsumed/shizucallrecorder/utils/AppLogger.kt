/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.utils

import android.content.Context
import android.util.Log
import com.kitsumed.shizucallrecorder.ILogCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.net.Uri
import android.os.Build
import com.kitsumed.shizucallrecorder.BuildConfig
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyConfig
import java.io.OutputStreamWriter
import java.io.PrintWriter
import com.kitsumed.shizucallrecorder.data.AppPreferences
import rikka.shizuku.Shizuku

/**
 * A unified, thread-safe, and asynchronous logging utility with built-in log rotation and redaction capabilities.
 * Also serves as an IPC bridge for receiving logs from the ShellService process via AIDL callbacks.
 */
object AppLogger {

    private const val TAG = "AppLogger"

    /**
     * Reference to a remote callback. When set, this process acts as a producer
     * and forwards all logs to the remote process instead of writing locally.
     */
    private var remoteCallback: ILogCallback? = null

    /** Maximum number of lines the log file can hold before being trimmed. */
    private const val MAX_LOG_LINES = 1000

    /** Number of lines to retain when the log file is trimmed. */
    private const val LINES_TO_KEEP = 500

    /** Coroutine scope dedicated to background log persistence. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Unbounded channel acting as the internal queue for log strings. */
    private val channel = Channel<String>(capacity = Channel.UNLIMITED)

    /** Mutex to safely synchronize file writes, trimming, and deletions. */
    private val fileMutex = Mutex()

    /** Ongoing buffered writer for appending to the log file. */
    private var logWriter: BufferedWriter? = null

    /** Tracks the current number of lines in the log file to trigger rotation. */
    private var lineCount = 0

    /** Reference to the app preferences to check logging enablement dynamically. */
    private var prefs: AppPreferences? = null

    /** Pointer to the internal application diagnostic log file. */
    private var logFile: File? = null

    /** Indicates whether log redaction is explicitly enabled for the remote process context. */
    private var remoteRedactionEnabled: Boolean = true

    /**
     * Helper to determine if we are currently running in a secondary/remote process context
     * (like a Shizuku service or an isolated process) instead of the main application process.
     */
    private val isRemoteProcess: Boolean
        get() {
            // The main process name accurately matches the APPLICATION_ID.
            // Any other process (Shizuku, :remote service, etc.) will have a different or suffixed name.
            return android.app.Application.getProcessName() != BuildConfig.APPLICATION_ID
        }

    /**
     * Helper to gracefully determine if log redaction is active without relying solely on AppPreferences,
     * which are unavailable in remote IPC contexts.
     */
    private val isRedactionEnabled: Boolean
        get() = if (isRemoteProcess) remoteRedactionEnabled else prefs?.isDebugEnabled() != true

    /**
     * An AIDL stub implementation acting as an IPC hook.
     * Used to receive native logs emitted from fully separated process context, like our ShellService.
     */
    val callback: ILogCallback.Stub by lazy {
        if (isRemoteProcess) {
            throw IllegalStateException("AppLogger.callback IPC stub must only be accessed and hosted from the main application process.")
        }
        object : ILogCallback.Stub() {
            override fun onLogEvent(level: String, tag: String, message: String, throwableStackTrace: String?) {
                val fullMessage = if (throwableStackTrace != null) "$message\n$throwableStackTrace" else message
                val redacted = if (isRedactionEnabled) redact(fullMessage) else fullMessage

                // Pipe IPC logs using the level provided by the remote process
                logInternal(level, tag, redacted, null)
            }
        }
    }

    /**
     * Initializes the logging mechanism for the main application process.
     * Sets up the primary log file, attaches an uncaught exception handler, and launches the persistent IO loop.
     *
     * @param context The application context.
     * @throws IllegalStateException if called from a remote process context. Use [initAsRemote] instead for remote contexts.
     */
    fun init(context: Context) {
        if (isRemoteProcess) {
            throw IllegalStateException("init() should not be called from a remote process context. Use initAsRemote() instead.")
        }
        prefs = AppPreferences(context)
        logFile = File(context.cacheDir, "app_debug.log")

        // Store the original default uncaught exception handler to ensure we can forward exceptions after flushing
        // logs
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e(TAG, "Caught an uncaught exception, flushing logs to disk before process death...", throwable)
            flushSync()
            // Forward runtime exception to original uncaught handler
            defaultHandler?.uncaughtException(thread, throwable)
        }

        scope.launch {
            // On initialization, we need to determine how many lines are already in the log file (if it exists)
            fileMutex.withLock {
                lineCount = if (logFile?.exists() == true) logFile!!.readLines().size else 0
                logWriter = logFile?.let { BufferedWriter(FileWriter(it, true)) }
            }

            // Continuously consume log messages from the channel and write them to disk, while managing log rotation.
            for (line in channel) {
                fileMutex.withLock {
                    logWriter?.apply {
                        write(line)
                        newLine()
                        flush()
                        lineCount++
                    }

                    if (lineCount >= MAX_LOG_LINES) {
                        logWriter?.close()
                        logFile?.let { file ->
                            if (file.exists()) {
                                val lines = file.readLines()
                                val keptLines = lines.takeLast(LINES_TO_KEEP)
                                file.writeText(keptLines.joinToString("\n") + "\n")
                                lineCount = keptLines.size
                            }
                        }
                        logWriter = logFile?.let { BufferedWriter(FileWriter(it, true)) }
                    }
                }
            }
        }
    }

    /**
     * Initializes the logging mechanism for a remote process (e.g., ShellService).
     * All logs will be forwarded via IPC to the main application.
     * This allows us to capture logs from a separate Shizuku process context and send them back to this application process context.
     *
     * **NOTE**: Remote logging cannot pass the raw Throwable object in case of exceptions, so it is converted as a string and appended to the message.
     *
     * @param callback The AIDL interface hooked to the main process.
     * @param isRedactionEnabled Whether log redaction is active (passed from the main app to control log redaction).
     * @throws IllegalStateException if called from the main application process context. Use [init] instead for the main app process.
     */
    fun initAsRemote(callback: ILogCallback, isRedactionEnabled: Boolean = true) {
        if (!isRemoteProcess) {
            throw IllegalStateException("initAsRemote() should only be called from a remote process context. Use init() for the main app process.")
        }
        this.remoteCallback = callback
        this.remoteRedactionEnabled = isRedactionEnabled
        Log.i(TAG, "Initialized in remote process context. All logs will be forwarded via IPC to the main application process.")
    }

    /**
     * Safely deletes the existing internal log file and resets the writing stream
     * and line tracking metrics. Execution is managed sequentially via a Mutex lock.
     */
    fun clearLogs() {
        scope.launch {
            fileMutex.withLock {
                logWriter?.close()
                logFile?.delete()
                logWriter = logFile?.let { BufferedWriter(FileWriter(it, true)) }
                lineCount = 0
            }
        }
    }

    /**
     * Gathers system/app metadata and concatenates it with the existing debug log history,
     * streaming the complete report to a destination URI via the Storage Access Framework.
     *
     * @param context Application context.
     * @param destinationUri Target SAF URI to which the file will be generated.
     */
    fun exportReport(context: Context, destinationUri: Uri) {
        val file = logFile ?: return
        val prefs = AppPreferences(context)
        context.contentResolver.openOutputStream(destinationUri, "w")?.use { outputStream ->
            PrintWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { writer ->
                writer.println("=== ShizuCallRecorder AppLogger Export ===")
                writer.println("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.CANADA).format(Date())}")
                writer.println("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                writer.println("Shizuku Supported Server API: ${Shizuku.getLatestServiceVersion()}")
                writer.println("Scrcpy Server: ${ScrcpyConfig.SCRCPY_VERSION}")
                writer.println("Manufacturer: ${Build.MANUFACTURER}")
                writer.println("Model: ${Build.MODEL}")
                writer.println("Device: ${Build.DEVICE}")
                writer.println("Product: ${Build.PRODUCT}")
                writer.println("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                writer.println("Device Country Iso Estimation: ${PhoneNumberManager.getInstance(context).getDeviceCountryIso()}")
                writer.println("Log Redaction Disabled / Debug Mode : ${prefs.isDebugEnabled()}")
                writer.println("Call Detection Method: ${prefs.getCallDetectionMode().key}")
                writer.println("===========================================")
                writer.println()
                writer.flush()

                if (file.exists()) {
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } else {
                    writer.println("[No logs found in internal storage]")
                }
            }
        }
    }

    /** Logs a Verbose level message and optionally its throwable trace. */
    fun v(tag: String, message: String, t: Throwable? = null) {
        val finalMessage = if (isRedactionEnabled) redact(message) else message
        if (t != null) Log.v(tag, finalMessage, t) else Log.v(tag, finalMessage)
        logInternal("V", tag, finalMessage, t)
    }

    /** Logs a Debug level message and optionally its throwable trace. */
    fun d(tag: String, message: String, t: Throwable? = null) {
        val finalMessage = if (isRedactionEnabled) redact(message) else message
        if (t != null) Log.d(tag, finalMessage, t) else Log.d(tag, finalMessage)
        logInternal("D", tag, finalMessage, t)
    }

    /** Logs an Info level message and optionally its throwable trace. */
    fun i(tag: String, message: String, t: Throwable? = null) {
        val finalMessage = if (isRedactionEnabled) redact(message) else message
        if (t != null) Log.i(tag, finalMessage, t) else Log.i(tag, finalMessage)
        logInternal("I", tag, finalMessage, t)
    }

    /** Logs a Warning level message and optionally its throwable trace. */
    fun w(tag: String, message: String, t: Throwable? = null) {
        val finalMessage = if (isRedactionEnabled) redact(message) else message
        if (t != null) Log.w(tag, finalMessage, t) else Log.w(tag, finalMessage)
        logInternal("W", tag, finalMessage, t)
    }

    /** Logs an Error level message and optionally its throwable trace. */
    fun e(tag: String, message: String, t: Throwable? = null) {
        val finalMessage = if (isRedactionEnabled) redact(message) else message
        if (t != null) Log.e(tag, finalMessage, t) else Log.e(tag, finalMessage)
        logInternal("E", tag, finalMessage, t)
    }

    /** Logs a "What a Terrible Failure" (assert) message and optionally its throwable trace. */
    fun wtf(tag: String, message: String, t: Throwable? = null) {
        val finalMessage = if (isRedactionEnabled) redact(message) else message
        if (t != null) Log.wtf(tag, finalMessage, t) else Log.wtf(tag, finalMessage)
        logInternal("WTF", tag, finalMessage, t)
    }

    /**
     * Prepares a log message by enriching it with more detailed metadata (timestamp, log level, tag) and then
     * forwarding it to the channel.
     *
     * **WARNING**: YOU MUST ENSURE THE MESSAGE IS [redact] BEFORE CALLING THIS METHOD TO TRY TO AVOID LEAKING SENSITIVE DATA INTO THE LOG FILE.
     */
    private fun logInternal(level: String, tag: String, message: String, t: Throwable?) {
        // Handle remote process execution securely
        if (isRemoteProcess) {
            if (remoteCallback == null) {
                Log.w(TAG, "IPC Drop: Log event triggered in remote process, but initAsRemote() was never called.")
                return
            }
            try {
                remoteCallback?.onLogEvent(level, tag, message, t?.let { Log.getStackTraceString(it) })
            } catch (e: Exception) {
                Log.v(TAG, "Failed to send log event via IPC callback, likely due to remote process death. Message was: $message", e)
            }
            // In remote mode, we rely entirely on the main process to handle log persistence. We do not write anything locally.
            return
        }

        if (prefs?.isLoggingEnabled() != true) return

        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val fullMessage = message + (t?.let { "\n${Log.getStackTraceString(it)}" } ?: "")

        val formattedLine = "$time [$level] $tag: $fullMessage"
        channel.trySend(formattedLine)
    }

    /**
     * Redacts highly sensitive personal information (e.g. phone numbers) from the given text
     * before it gets committed to physical storage.
     */
    private fun redact(msg: String): String {
        val phoneRedactionRegex = Regex(
            "(?<!\\d)" +              // Negative Lookbehind: Don't start in the middle of another number
                    "(?:\\+?\\d{1,3}[-.\\s]?)?" + // Optional Country Code (e.g., +1 or 33)
                    "(?:\\(\\d{1,4}\\)|\\d{1,4})" + // Area code (with or without parentheses)
                    "[-.\\s]?\\d{3,4}" +      // Prefix
                    "[-.\\s]?\\d{3,4}" +      // Line number
                    "(?!\\d)"                 // Negative Lookahead: Don't end in the middle of another number
        )
        return msg.replace(phoneRedactionRegex, "[PHONE_REDACTED]")
    }

    /**
     * Synchronously drains the logging channel and forcefully writes all pending messages to disk.
     * This ensures that crucial crash traces and late logs are not lost if the process is
     * abruptly killed before the asynchronous IO worker can process them.
     */
    private fun flushSync() {
        val file = logFile ?: return
        try {
            FileWriter(file, true).use { writer ->
                var message = channel.tryReceive().getOrNull()
                while (message != null) {
                    writer.write(message)
                    writer.append('\n')
                    message = channel.tryReceive().getOrNull()
                }
                writer.flush()
            }
        } catch (_: Exception) {
            // We're already crashing, ignore I/O errors here so we don't block the actual crash from propagating.
        }
    }
}
