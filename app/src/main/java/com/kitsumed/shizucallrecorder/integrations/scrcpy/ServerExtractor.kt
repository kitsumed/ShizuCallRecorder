/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.scrcpy

import android.annotation.SuppressLint
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.WorkerThread
import com.kitsumed.shizucallrecorder.BuildConfig
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * ServerExtractor extracts the bundled scrcpy-server.jar from the APK's assets folder and
 * writes it to shared storage so the privileged shell process can read and execute it.
 */
object ServerExtractor {

    private const val TAG = "SCR:ServerExtractor"

    /**
     * Ensures the scrcpy-server JAR exists at [serverPath] and has the expected SHA-256 hash.
     *
     * If the file is absent or its hash does not match, it is extracted from the APK's assets.
     * After extraction the hash is verified again to confirm.
     *
     * @param context    The app context (used to open the assets stream and resolve paths).
     * @param serverPath The absolute destination path for the JAR file.
     * @return true if the file exists and is verified; false if extraction or verification fails.
     */
    @WorkerThread // Performs file I/O and hashing, should not be called on the main thread.
    fun ensureServerFile(context: Context, serverPath: String): Boolean {
        val file = File(serverPath)
        if (file.exists() && verifyServerHash(file)) {
            AppLogger.d(TAG, "Server file already present and verified at $serverPath")
            return true
        }
        AppLogger.d(TAG, "Server file absent or hash mismatch, extracting from assets...")
        return extractFromAssets(context, file)
    }

    /**
     * Copies the server bytes from a Binder pipe into a shell-accessible destination.
     *
     * This is used for Shizuku user services because the shell process may not be able to read
     * the app's profile-scoped external storage, especially from work profiles.
     */
    @WorkerThread
    fun installFromPipe(serverData: ParcelFileDescriptor, serverPath: String): Boolean {
        val destFile = File(serverPath)
        if (destFile.exists() && verifyServerHash(destFile)) {
            AppLogger.d(TAG, "Shell server file already present and verified at $serverPath")
            runCatching { serverData.close() }
            return true
        }

        val tempFile = File("${destFile.path}.tmp.${android.os.Process.myPid()}")
        return try {
            destFile.parentFile?.mkdirs()
            ParcelFileDescriptor.AutoCloseInputStream(serverData).use { input ->
                writeFile(tempFile, input)
            }

            if (!verifyServerHash(tempFile)) {
                AppLogger.w(TAG, "Shell server pipe copy completed but hash verification FAILED")
                tempFile.delete()
                return false
            }

            if (destFile.exists() && !destFile.delete()) {
                AppLogger.w(TAG, "Unable to replace existing shell server file at $serverPath")
                tempFile.delete()
                return false
            }

            if (!tempFile.renameTo(destFile)) {
                AppLogger.w(TAG, "Unable to move shell server file into place at $serverPath")
                tempFile.delete()
                return false
            }

            destFile.setReadable(true, false)
            AppLogger.d(TAG, "Shell server installed and verified at $serverPath")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Shell server install failed: ${e.message}", e)
            tempFile.delete()
            false
        }
    }

    /**
     * Copies the server asset from the APK to the destination file, then verifies its hash.
     *
     * @param context  App context used to open assets.
     * @param destFile The [File] object to write to.
     * @return true if the file was written and its hash verified; false on any error.
     */
    private fun extractFromAssets(context: Context, destFile: File): Boolean {
        return try {
            // .use{} guarantees the asset InputStream is closed even if writeFile throws.
            // Android's low-level file descriptors are NOT garbage-collected automatically;
            // failing to close them leaks native FD handles.
            context.assets.open(BuildConfig.SCRCPY_SERVER_ASSET_NAME).use { inputStream ->
                writeFile(destFile, inputStream)
            }
            val verified = verifyServerHash(destFile)
            if (verified) {
                AppLogger.d(TAG, "Server extracted and verified: ${destFile.path}")
            } else {
                AppLogger.w(TAG, "Server extraction succeeded but hash verification FAILED")
            }
            verified
        } catch (e: Exception) {
            AppLogger.w(TAG, "Asset extraction failed: ${e.message}")
            false
        }
    }

    /**
     * Writes the contents of [input] to [destFile], creating parent directories as needed.
     * Sets the file world-readable so the shell process (UID 2000) can open it.
     *
     * @param destFile The destination [File] to create or overwrite.
     * @param input    The [java.io.InputStream] to read from (must be open; this function does not close it).
     */
    @SuppressLint("SetWorldReadable")
    private fun writeFile(destFile: File, input: InputStream) {
        destFile.parentFile?.mkdirs()
        // FileOutputStream.use{} closes the output stream even if an IOException occurs mid-write.
        FileOutputStream(destFile).use { output ->
            val buffer = ByteArray(8 * 1024) // 8 KB copy buffer
            var bytesRead = input.read(buffer)
            while (bytesRead > 0) {
                output.write(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        // Make the file readable by all users (including the shell, UID 2000).
        destFile.setReadable(true, false)
    }

    /**
     * Computes the SHA-256 hash of [file] and compares it to [ScrcpyConfig.EXPECTED_SERVER_SHA256].
     *
     * @param file The [File] whose hash will be computed.
     * @return true if the file's SHA-256 matches the expected value (case-insensitive); false otherwise.
     */
    fun verifyServerHash(file: File): Boolean {
        if (!file.exists()) {
            AppLogger.e(TAG, "Cannot verify: file not found at ${file.path}")
            return false
        }

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            // Read the file in chunks to avoid loading the entire JAR into memory at once.
            file.inputStream().use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                // .also{ bytesRead = it } assigns the return value of read() to bytesRead
                // AND returns it, allowing it to be used as the while-loop condition.
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }

            // Convert the raw digest bytes to a lowercase hex string.
            val actualHash = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            val matches = actualHash.equals(ScrcpyConfig.EXPECTED_SERVER_SHA256, ignoreCase = true)
            if (!matches) {
                AppLogger.w(TAG, "SHA-256 mismatch: expected=${ScrcpyConfig.EXPECTED_SERVER_SHA256} actual=$actualHash")
            }
            matches
        } catch (e: Exception) {
            AppLogger.e(TAG, "Hash verification error: ${e.message}", e)
            false
        }
    }
}
