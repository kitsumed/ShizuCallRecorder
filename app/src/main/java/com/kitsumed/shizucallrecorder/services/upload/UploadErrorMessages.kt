/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.upload

import com.jcraft.jsch.JSchException
import org.apache.commons.net.ftp.FTPConnectionClosedException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Turns a raw connection/upload exception into a specific, user-readable reason, and guarantees the stored
 * password / SFTP passphrase / SFTP private key are never present in the result, no matter where they came from.
 */
object UploadErrorMessages {

    fun describe(e: Throwable, config: RemoteConfig): String = redactSecrets(humanize(e, config), config)

    private fun humanize(e: Throwable, config: RemoteConfig): String {
        val hostPort = "${config.host}:${config.port}"
        return when (e) {
            is UnknownHostException ->
                "Could not resolve host \"${config.host}\". The Host field must contain only the domain or IP address " +
                        "(no ftp://, sftp://, username, password or path - use the separate Port/Username/Password fields for those)."
            is NoRouteToHostException, is ConnectException ->
                "Could not connect to $hostPort (connection refused or unreachable). Check the host, the port, and that the server allows connections from this network."
            is SocketTimeoutException ->
                "Connection to $hostPort timed out. Check the host, the port, and your network connection."
            is FTPConnectionClosedException ->
                "The FTP server at $hostPort closed the connection unexpectedly. Check the protocol (FTP vs FTPS) and port."
            is SSLException ->
                "TLS/SSL handshake with $hostPort failed${e.message?.let { ": $it" } ?: ""}. If the server only supports plain FTP, switch Protocol to FTP; " +
                        "if it needs implicit FTPS on a different port, check the port."
            is JSchException -> describeJsch(e, hostPort)
            is IOException -> describeFtpIo(e, hostPort)
            else -> "${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""} while connecting to $hostPort."
        }
    }

    /** JSch (SFTP) wraps most failures as a plain JSchException with only a short message; map the common ones. */
    private fun describeJsch(e: JSchException, hostPort: String): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("UnknownHostKey", ignoreCase = true) || msg.contains("HostKey", ignoreCase = true) -> msg
            msg.contains("Auth fail", ignoreCase = true) || msg.contains("Auth cancel", ignoreCase = true) ->
                "Authentication to $hostPort failed. Check the username, and the password or the private key/passphrase."
            msg.contains("timeout", ignoreCase = true) -> "Connection to $hostPort timed out."
            msg.contains("invalid privatekey", ignoreCase = true) || msg.contains("invalid key", ignoreCase = true) ->
                "The configured SSH private key could not be read. Make sure it is a full PEM/OpenSSH private key, and that the passphrase (if any) is correct."
            msg.isNotBlank() -> "SFTP connection to $hostPort failed: $msg"
            else -> "SFTP connection to $hostPort failed."
        }
    }

    /** Apache Commons Net (FTP/FTPS) reports most failures as an IOException whose message is our own "<Stage>: <server reply>" text. */
    private fun describeFtpIo(e: IOException, hostPort: String): String {
        val msg = e.message
        if (msg.isNullOrBlank()) return "Connection to $hostPort failed (no further details from the server)."
        return when {
            msg.startsWith("Login failed", ignoreCase = true) ->
                "Login to $hostPort was rejected by the server. Check the username and password. Server said: ${msg.substringAfter(":", msg).trim()}"
            else -> msg
        }
    }

    /** Defense in depth: whatever produced [message], strip out any configured secret value before it is shown or stored. */
    private fun redactSecrets(message: String, config: RemoteConfig): String {
        var out = message
        for (secret in listOf(config.password, config.passphrase, config.privateKey)) {
            if (secret.isNotBlank() && out.contains(secret)) {
                out = out.replace(secret, "[redacted]")
            }
        }
        return out
    }
}
