/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.Keep
import com.kitsumed.shizucallrecorder.ILogCallback
import com.kitsumed.shizucallrecorder.IShellService
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioCodec
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioSource
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyConfig
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ServerExtractor
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * ShellService runs inside the privileged shell process (UID 2000 or 0) managed by Shizuku.
 *
 * By running under the app shell as ADB or root via Shizuku, we can
 * launch scrcpy-server with app_process and capture audio that a normal app cannot access.
 *
 * AI generated Overview:
 *
 *   ┌─────────────────────────────────────────────────────────────┐
 *   │  Shell Process (UID 2000 or 0)                              │
 *   │                                                             │
 *   │  ShellService (this class, AIDL stub)                       │
 *   │    │                                                        │
 *   │    ├── launches  scrcpy-server (app_process)                │
 *   │    │     └── connects to  LocalServerSocket                 │
 *   │    │                          │                             │
 *   │    ├── AudioRelayCoroutine ◄──┘  (socket → pipe)            │
 *   │    │         │                                              │
 *   │    │     Pipe[1] write-end (kept in Shell)                  │
 *   │    │     Pipe[0] read-end  ───────────────► App Process     │
 *   │    │                                          ScrcpyClient  │
 *   │    ├── LogConsumerCoroutine   (drain stdout)                │
 *   │    └── ProcessMonitorCoroutine (wait for exit)              │
 *   └─────────────────────────────────────────────────────────────┘
 *
 * Shizuku requirements:
 *  • Must have a no-arg constructor AND a single-Context constructor (Shizuku v13+).
 *  • Must be annotated with [@Keep] so ProGuard/R8 does not remove/rename the class.
 *  • [destroy] must call [exitProcess] to terminate the shell process when Shizuku asks.
 */
@Keep
class ShellService : IShellService.Stub {

    private companion object {
        const val TAG = "SCR:ShellService"

        /**
         * Size of the byte buffer used when copying data from the socket to the pipe.
         * 32 KB is a balance between latency (larger = more delay per flush) and syscall
         * overhead (smaller = more read/write pairs per second).  At 16 kbps Opus the server
         * produces ≈ 2 KB/s, so 32 KB means ≈ 16 s of audio per buffer; this is fine since
         * the relay loop flushes on every read().
         */
        const val RELAY_BUFFER_SIZE = 32 * 1024

        /**
         * How long to wait for scrcpy-server to finish writing its final bytes after we call
         * [Process.destroy].  Giving it a short grace period avoids truncating the last audio
         * frame if the server is encoding when the stop request arrives.
         */
        const val PROCESS_STOP_GRACE_PERIOD_SEC = 2L
    }

    /**
     * Atomic flag that controls the relay loop and prevents concurrent sessions.
     *
     * Why [AtomicBoolean]?  [stopRecording] can be called from any thread (e.g. the AIDL
     * thread pool) while [spawnAudioRelayCoroutine]'s loop runs on a coroutine.  AtomicBoolean
     * provides a lock-free compare-and-set operation that is visible across threads without
     * needing a `synchronized` block.
     */
    private val isRecordingActive = AtomicBoolean(false)

    // -- System resources (all nullable; null = not allocated in current session)

    /** The running scrcpy-server child process. Null when not recording. */
    private var scrcpyProcess: Process? = null

    /**
     * The Unix-domain server socket that waits for scrcpy-server to connect.
     */
    private var serverSocket: LocalServerSocket? = null

    /** The accepted connection from scrcpy-server after it dials our server socket. */
    private var clientConnection: LocalSocket? = null

    /**
     * Write end of the kernel pipe.  The relay coroutine copies bytes from the scrcpy-server
     * socket into this end; the app process holds the read end wrapped in a [ParcelFileDescriptor].
     *
     * **IMPORTANT**: Do NOT close this before the scrcpy-server process exits. The server may be
     * buffering its final audio frame and will write it after receiving SIGTERM. Closing the
     * write end early would cause a broken-pipe error in the relay coroutine and truncate the
     * recording.
     */
    private var audioWriteEnd: ParcelFileDescriptor? = null

    // --- Coroutine infrastructure

    /**
     * Coroutine scope for all background work in this recording session.
     */
    private var shellScope: CoroutineScope? = null

    /**
     * The relay job that copy bytes of the socket to the pipe for downstream app.
     * We keep this [shellScope] job reference so we can wait for late bytes to be relayed when shutting down in [stopRecording].
     */
    private var audioPipeRelayJob: Job? = null

    // ---- Shizuku-required constructors

    /**
     * No-arg constructor required by older versions of Shizuku.
     */
    @Keep constructor() : this(null)

    /**
     * Context constructor required by Shizuku v13+ for user-service instantiation.
     * The context is the shell process's context (not the app's).
     *
     * @param context The shell-process [Context] provided by Shizuku, or null on older versions.
     */
    @Keep constructor(context: Context?) {
        Log.i(TAG,"===============================\n" +
             "ShellService process started!\n" +
             "Running as UID=(${android.os.Process.myUid()})\n" +
             "===============================")
    }

    // -------- IShellService AIDL implementation

    /**
     * Starts the audio-capture pipeline.  Called from the app process via Binder IPC.
     *
     * Steps performed in this method (all in the shell process):
     *  1. Guard: reject if already recording.
     *  2. Verify scrcpy-server JAR hash
     *  3. Create a kernel pipe; keep the write-end here, return the read-end to the app.
     *  4. Open a [LocalServerSocket] and start an audio-relay coroutine that calls accept().
     *  5. Build the `app_process` launch command with scrcpy arguments.
     *  6. Start the scrcpy-server child process.
     *  7. Start log-consumer and process-monitor coroutines as background helpers.
     *
     * @param audioSource        scrcpy audio_source parameter (e.g. "mic-voice-communication").
     * @param audioCodec         scrcpy audio_codec parameter (e.g. "opus", "aac").
     * @param audioBitRate       scrcpy audio_bit_rate in bps (e.g. 16000 for 16 kbps Opus).
     * @param serverPath      Absolute path to scrcpy-server.jar in shared storage.
     * @param enableVerboseLogging  When true, logs relay throughput every second.
     * @return The read-end [ParcelFileDescriptor] of the audio pipe, or null on failure.
     */
    override fun startRecording(
        audioSource: String,
        audioCodec: String,
        audioBitRate: Int,
        serverPath: String,
        isDebuggingModeEnabled: Boolean,
        listener: ILogCallback
    ): ParcelFileDescriptor? {
        AppLogger.initAsRemote(listener, isDebuggingModeEnabled)

        if (isRecordingActive.get()) {
            AppLogger.w(TAG,"startRecording() rejected: a session is already active")
            return null
        }

        try {
            AppLogger.i(TAG,"Initialising the ShellService recording pipeline...")

            // 1. Security check
            // Verify the JAR's SHA-256, before exec.
            // Checking in the shell process, try to reduce TOCTOU but not perfect.
            val serverJarFile = File(serverPath)
            if (!serverJarFile.exists() || !ServerExtractor.verifyServerHash(serverJarFile)) {
                AppLogger.w(TAG,"Server JAR absent or SHA-256 mismatch at $serverPath - aborting")
                return null
            }

            // 2. Create Kernel pipe for Server ShellService --> App Process communication
            // We send readEnd to the app process and keep writeEnd here.
            val pipe = ParcelFileDescriptor.createPipe()
            val pipeReadEnd  = pipe[0] // --> returned to app process
            val pipeWriteEnd = pipe[1] // --> written by ShellService relay coroutine
            audioWriteEnd = pipeWriteEnd

            // 3. Create Local Unix-domain Socket Server for the Scrcpy-Server binary
            val socketName = ScrcpyConfig.getRandomSocketName();
            // Scrcpy server binary always add ScrcpyConfig.SERVER_SOCKET_NAME_PREFIX prefix to the socket name.
            val serverFullSocketName = ScrcpyConfig.SERVER_SOCKET_NAME_PREFIX + socketName
            serverSocket = LocalServerSocket(serverFullSocketName)
            AppLogger.d(TAG,"Listening on abstract socket '$serverFullSocketName'")

            // Create the scope for this session's coroutines.
            // (Similar to async Tasks in c#, shellScope will contain and manage all these tasks.)
            shellScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            // Start the relay coroutine BEFORE launching scrcpy-server so the accept() call is
            // already waiting when the server connects.
            spawnAudioRelayCoroutine(isDebuggingModeEnabled)

            // 4. Build and launch scrcpy-server
            // Convert the raw String parameters received via AIDL into type-safe enum values
            // before calling buildServerArgs.
            val audioSourceEnum = ScrcpyAudioSource.fromKey(audioSource)
            val audioCodecEnum  = ScrcpyAudioCodec.fromKey(audioCodec)
            val serverArgs    = ScrcpyConfig.buildServerArgs(socketName, audioSourceEnum, audioCodecEnum, audioBitRate)
            val launchCommand = mutableListOf("app_process", "/", ScrcpyConfig.SERVER_MAIN_CLASS)
            launchCommand.addAll(serverArgs)

            val scrcpyBuilder = ProcessBuilder(launchCommand).apply {
                // CLASSPATH tells app_process where to find the server binary file.
                environment()["CLASSPATH"] = serverPath
                // Merge stderr into stdout so the log-consumer coroutine only needs one stream.
                redirectErrorStream(true)
            }
            scrcpyProcess = scrcpyBuilder.start()
            isRecordingActive.set(true)
            AppLogger.i(TAG,"scrcpy-server launched successfully")

            // 5. Start Helper coroutines
            spawnLogConsumerCoroutine(scrcpyProcess!!)
            spawnProcessMonitorCoroutine(scrcpyProcess!!)

            AppLogger.i(TAG,"Recording pipeline established. Returning pipe read-end to app process.")
            return pipeReadEnd // ← returned to RecordingForegroundService via Binder
        } catch (e: Exception) {
            AppLogger.e(TAG,"Critical failure during pipeline startup: ${e.message}", e)
            stopRecording() // Best-effort cleanup of any partially-allocated resources.
            return null
        }
    }

    /**
     * Stops the recording pipeline and releases all resources.  Called from the app process
     * via Binder IPC, and also from internal coroutines when an error is detected.
     *
     * Cleanup order matters:
     *  1. Set the atomic flag to false first so the relay loop exits on its next iteration.
     *  2. Destroy scrcpy-server; give it a grace period to flush its last audio bytes.
     *  3. Cancel the coroutine scope (interrupts blocking I/O calls in relay/log/monitor).
     *  4. Close the client connection so the relay coroutine's read() unblocks.
     *  5. Close the server socket.
     *  6. Close the pipe write-end LAST so scrcpy-server can write its final bytes first.
     */
    override fun stopRecording() {
        // compareAndSet(true, false): atomically checks that we ARE recording and clears the flag.
        // Returns false (and skips the body) if we were already stopped, preventing double-free.
        if (!isRecordingActive.compareAndSet(true, false)) {
            AppLogger.w(TAG, "stopRecording() called but no active session - skipping redundant cleanup")
            return
        }

        AppLogger.i(TAG,"Stopping scrcpy-server process...")
        runCatching { scrcpyProcess?.destroy() }

        // Give the server up to PROCESS_STOP_GRACE_PERIOD_SEC to send its final audio bytes to the server socket.
        // waitFor() blocks until the process exits or the timeout elapses.
        try {
            scrcpyProcess?.waitFor(PROCESS_STOP_GRACE_PERIOD_SEC, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            AppLogger.w(TAG, "Interrupted while waiting for scrcpy-server exit: ${e.message}")
        }

        // Wait for the relay job to copy remaining socket data to the pipe for downstream app (Time-bounded).
        AppLogger.d(TAG,"Waiting for relay coroutine to finish copying late bytes...")
        runCatching {
            kotlinx.coroutines.runBlocking {
                // Time-bounded wait to strictly prevent any infinite loop if EOF is never reached or take too long
                kotlinx.coroutines.withTimeoutOrNull(2000L) { // 2s timeout
                    audioPipeRelayJob?.join()
                }
            }
        }

        AppLogger.d(TAG,"Cancelling all jobs running from the shell coroutine scope...")
        // This stops the execution of all of our coroutines tasks/threads.
        runCatching { shellScope?.cancel() }

        // Close in reverse-allocation order. The client socket connection should already have by closed
        // by scrcpy-server when it was requested to close itself, but we close it again here to force its closure if it's still open.
        AppLogger.d(TAG,"Ensuring client socket connection is closed...")
        runCatching { clientConnection?.close() }

        AppLogger.d(TAG,"Closing server socket...")
        runCatching { serverSocket?.close() }

        // Close the pipe write-end AFTER the process has had a chance to write final bytes.
        AppLogger.d(TAG,"Closing audio pipe write-end...")
        runCatching { audioWriteEnd?.close() }

        // Null out references to help GC.
        scrcpyProcess = null
        clientConnection  = null
        serverSocket      = null
        audioWriteEnd     = null
        shellScope        = null
        audioPipeRelayJob = null

        AppLogger.i(TAG,"Recording pipeline stopped and all ShellService resources were released")
    }

    /** Returns whether a recording session is currently active (thread-safe via AtomicBoolean). */
    override fun isRecording(): Boolean = isRecordingActive.get()

    override fun grantAppOps(packageName: String, opName: String, userProfileId: Int): Boolean {
        try {
            AppLogger.i(TAG, "Executing AppOps set --user $userProfileId $packageName $opName allow")
            val process = ProcessBuilder("appops", "set", "--user", userProfileId.toString(), packageName, opName, "allow").start()
            val errorOutput = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                AppLogger.w(TAG, "grantAppOps failed with exit code $exitCode. Error: $errorOutput")
            }
            return exitCode == 0
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error granting AppOps $opName to $packageName: ${e.message}", e)
        }
        return false
    }

    /**
     * Called by Shizuku when it wants to shut down this user service.
     * MUST call [exitProcess] so the entire shell process is terminated; otherwise Shizuku may
     * be unable to clean up the process, and it will linger in memory.
     */
    override fun destroy() {
        AppLogger.i(TAG,"ShellService.destroy() – terminating shell process")
        stopRecording()
        exitProcess(0)
    }

    // -- Private coroutine helpers

    /**
     * Launches a coroutine that:
     *  1. Calls [LocalServerSocket.accept] to wait for scrcpy-server to connect.
     *  2. Copies all bytes from the socket input stream into the pipe write-end.
     *  3. Calls [stopRecording] when the loop exits so the app-side muxer is notified.
     *
     * @param verbose When true, logs relay throughput roughly every second.
     */
    private fun spawnAudioRelayCoroutine(verbose: Boolean) {
        audioPipeRelayJob = shellScope?.launch(Dispatchers.IO) {
            try {
                // accept() blocks until scrcpy-server dials our socket.
                // This is safe on Dispatchers.IO because IO threads are designed for blocking calls.
                AppLogger.d(TAG,"AudioRelayCoroutine: waiting for scrcpy-server connection...")
                val connection = serverSocket?.accept() ?: run {
                    AppLogger.w(TAG, "AudioRelayCoroutine: server socket was null or closed")
                    return@launch
                }
                clientConnection = connection
                AppLogger.i(TAG,"AudioRelayCoroutine: scrcpy-server connected to our socket server")

                val sourceStream = connection.inputStream
                // AutoCloseOutputStream closes the underlying ParcelFileDescriptor on close(), which
                // will tell make it so the ReadPipe we returned to the downstream app reports EOF.
                // We do NOT use .use{} here because audioWriteEnd is shared – it is closed
                // explicitly in stopRecording() AFTER the scrscpy-server process exits.
                val destinationStream = ParcelFileDescriptor.AutoCloseOutputStream(audioWriteEnd)

                val buffer = ByteArray(RELAY_BUFFER_SIZE)
                var lastLogTimeMs = System.currentTimeMillis()

                // We read while isActive. We don't want to check isRecordingActive here since
                // we want keep reading late bytes from scrcpy until EOF is reached or scope is cancelled.
                while (isActive) {
                    val bytesRead = sourceStream.read(buffer)
                    if (bytesRead == -1) {
                        AppLogger.d(TAG,"AudioRelayCoroutine: socket EOF - scrcpy-server disconnected")
                        break
                    }
                    destinationStream.write(buffer, 0, bytesRead)

                    // Verbose throughput logging (≈ once per second) to aid debugging.
                    if (verbose && bytesRead > 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastLogTimeMs >= 1000) {
                            lastLogTimeMs = now
                            AppLogger.v(TAG, "AudioRelayCoroutine: relayed $bytesRead bytes. (Wrote to pipe).")
                        }
                    }
                }
            } catch (e: IOException) {
                // IOException here is expected when:
                //  a) stopRecording() closes the socket mid-read (produces a "Socket closed" error)
                //  b) scrcpy-server crashes and the socket is reset by peer
                if (isRecordingActive.get()) {
                    AppLogger.e(TAG,"AudioRelayCoroutine: unexpected I/O error: ${e.message}", e)
                } else {
                    AppLogger.d(TAG,"AudioRelayCoroutine: I/O error during shutdown (expected): ${e.message}")
                }
            } finally {
                // If we exit the relay (e.g. server crash), trigger a full stop so the app-side
                // muxer finalises the file.
                AppLogger.d(TAG,"AudioRelayCoroutine finished")
                stopRecording()
            }
        }
    }

    /**
     * Launches a daemon coroutine that drains scrcpy-server's stdout/stderr.
     *
     * @param process The running scrcpy-server [Process] whose output stream to consume.
     */
    private fun spawnLogConsumerCoroutine(process: Process) {
        shellScope?.launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    // Read one line at a time until EOF or the coroutine is cancelled.
                    var line = reader.readLine()
                    while (isActive && line != null) {
                        AppLogger.i(TAG,"[scrcpy-server] $line")
                        line = reader.readLine()
                    }
                }
            } catch (_: InterruptedIOException) {
                // Normal shutdown path: the scope was cancelled and the stream was closed.
                AppLogger.d(TAG,"LogConsumerCoroutine: interrupted (expected during shutdown)")
            } catch (e: IOException) {
                AppLogger.e(TAG,"LogConsumerCoroutine: I/O error: ${e.message}", e)
            } finally {
                AppLogger.d(TAG,"LogConsumerCoroutine finished")
            }
        }
    }

    /**
     * Launches a daemon coroutine that waits for scrcpy-server to exit.
     *
     * If scrcpy-server exits with a non-zero code while recording is still active, it crashed
     * unexpectedly.  We trigger [stopRecording] so the muxer can still finalise the file with
     * whatever audio was already captured.
     *
     * @param process The running scrcpy-server [Process] to monitor.
     */
    private fun spawnProcessMonitorCoroutine(process: Process) {
        shellScope?.launch(Dispatchers.IO) {
            try {
                // waitFor() blocks until the child process exits.
                val exitCode = process.waitFor()
                if (exitCode != 0 && isRecordingActive.get()) {
                    AppLogger.e(TAG,"ProcessMonitorCoroutine: scrcpy-server crashed (exit code $exitCode)")
                    stopRecording() // Trigger cleanup and file finalisation.
                } else {
                    AppLogger.i(TAG,"ProcessMonitorCoroutine: scrcpy-server exited normally (code $exitCode)")
                }
            } catch (_: InterruptedException) {
                // Normal: the scope was cancelled (service stopped) before the process exited.
                AppLogger.d(TAG,"ProcessMonitorCoroutine: interrupted (expected during shutdown)")
            } finally {
                AppLogger.d(TAG,"ProcessMonitorCoroutine finished")
            }
        }
    }
}