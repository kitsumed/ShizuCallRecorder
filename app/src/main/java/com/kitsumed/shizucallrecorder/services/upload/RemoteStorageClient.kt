/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.upload

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.kitsumed.shizucallrecorder.data.AddonPreferences
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.time.Duration

/** Snapshot of the remote storage settings, read once per worker run. */
data class RemoteConfig(
    val protocol: AddonPreferences.Protocol,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val useKeyAuth: Boolean,
    val privateKey: String,
    val passphrase: String,
    val remoteDir: String,
    /** Previously pinned "host:port|fingerprint" for SFTP trust-on-first-use. */
    val pinnedHostKey: String?
) {
    companion object {
        fun from(prefs: AddonPreferences) = RemoteConfig(
            protocol = prefs.getProtocol(),
            host = prefs.getHost(),
            port = prefs.getPort(),
            username = prefs.getUsername(),
            password = prefs.getPassword(),
            useKeyAuth = prefs.isSftpKeyAuth(),
            privateKey = prefs.getSftpPrivateKey(),
            passphrase = prefs.getSftpPassphrase(),
            remoteDir = prefs.getRemoteDir(),
            pinnedHostKey = prefs.getSftpHostKeyFingerprint()
        )
    }
}

/** One open connection to the remote server. */
interface RemoteStorageClient : Closeable {
    /** Creates (if needed) and enters the configured remote directory. */
    fun ensureRemoteDir()

    /** Uploads [input] as [fileName] in the remote directory and returns the remote file size reported by the server (-1 if unknown). */
    fun upload(input: InputStream, fileName: String): Long

    /** Host key fingerprint to pin (SFTP only). */
    val newHostKeyPin: String? get() = null

    companion object {
        fun connect(config: RemoteConfig): RemoteStorageClient = when (config.protocol) {
            AddonPreferences.Protocol.FTP -> FtpClientImpl(config, secure = false)
            AddonPreferences.Protocol.FTPS -> FtpClientImpl(config, secure = true)
            AddonPreferences.Protocol.SFTP -> SftpClientImpl(config)
        }
    }
}

private const val CONNECT_TIMEOUT_MS = 15_000
private const val IO_TIMEOUT_MS = 60_000

private fun dirSegments(dir: String): List<String> = dir.split('/').filter { it.isNotBlank() && it != "." }

// ───────────────────────────── FTP / FTPS (Apache Commons Net) ─────────────────────────────

private class FtpClientImpl(private val config: RemoteConfig, secure: Boolean) : RemoteStorageClient {
    private val client: FTPClient = if (secure) FTPSClient("TLS", false) else FTPClient()
    private var remoteDirPath: String = "/"

    init {
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.defaultTimeout = IO_TIMEOUT_MS
        client.setDataTimeout(Duration.ofMillis(IO_TIMEOUT_MS.toLong()))
        try {
            client.connect(config.host, config.port)
            if (!FTPReply.isPositiveCompletion(client.replyCode)) throw IOException("Server refused connection: ${client.replyString?.trim()}")
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P") // encrypt the data channel too
            }
            if (!client.login(config.username, config.password)) throw IOException("Login failed: ${client.replyString?.trim()}")
            client.enterLocalPassiveMode()
            if (!client.setFileType(FTP.BINARY_FILE_TYPE)) throw IOException("Could not switch to binary mode: ${client.replyString?.trim()}")
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun ensureRemoteDir() {
        val absolute = config.remoteDir.startsWith("/")
        if (absolute && !client.changeWorkingDirectory("/")) throw IOException("Cannot access /: ${client.replyString?.trim()}")
        for (segment in dirSegments(config.remoteDir)) {
            if (!client.changeWorkingDirectory(segment)) {
                client.makeDirectory(segment)
                if (!client.changeWorkingDirectory(segment)) throw IOException("Cannot create remote directory '$segment': ${client.replyString?.trim()}")
            }
        }
        remoteDirPath = client.printWorkingDirectory() ?: config.remoteDir
    }

    override fun upload(input: InputStream, fileName: String): Long {
        if (!client.storeFile(fileName, input)) throw IOException("Upload of $fileName failed: ${client.replyString?.trim()}")
        // Verify through the server's own listing.
        val size = runCatching { client.mlistFile(fileName)?.size }.getOrNull()
            ?: runCatching { client.listFiles(fileName)?.firstOrNull()?.size }.getOrNull()
            ?: runCatching { client.getSize(fileName)?.trim()?.toLongOrNull() }.getOrNull()
        return size ?: -1L
    }

    override fun close() {
        runCatching { if (client.isConnected) client.logout() }
        runCatching { if (client.isConnected) client.disconnect() }
    }
}

// ───────────────────────────── SFTP (JSch) ─────────────────────────────

private class SftpClientImpl(private val config: RemoteConfig) : RemoteStorageClient {
    private val jsch = JSch()
    private val session: Session
    private lateinit var channel: ChannelSftp
    private var remoteDirPath: String = "."
    private var pinToStore: String? = null
    override val newHostKeyPin: String?
        get() = pinToStore

    init {
        if (config.useKeyAuth) {
            jsch.addIdentity(
                "shizucallrecorder-upload",
                config.privateKey.toByteArray(Charsets.UTF_8),
                null,
                config.passphrase.ifEmpty { null }?.toByteArray(Charsets.UTF_8)
            )
        }
        session = jsch.getSession(config.username, config.host, config.port)
        try {
            if (!config.useKeyAuth) {
                session.setPassword(config.password.toByteArray(Charsets.UTF_8))
                session.userInfo = PasswordUserInfo(config.password)
            }
            // Host key is verified below with trust-on-first-use pinning instead of a known_hosts file.
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", if (config.useKeyAuth) "publickey" else "password,keyboard-interactive")
            session.timeout = IO_TIMEOUT_MS
            session.connect(CONNECT_TIMEOUT_MS)

            val hostKey = session.hostKey
            val pinId = "${config.host}:${config.port}"
            val fingerprint = "${hostKey.type} ${hostKey.getFingerPrint(jsch)}"
            val pinned = config.pinnedHostKey
            if (pinned != null && pinned.substringBefore('|') == pinId && pinned.substringAfter('|') != fingerprint) {
                throw IOException("SFTP host key changed for $pinId (possible man-in-the-middle). Expected ${pinned.substringAfter('|')}, got $fingerprint. Clear the pinned key in Settings if this change is legitimate.")
            }
            if (pinned == null || pinned.substringBefore('|') != pinId) pinToStore = "$pinId|$fingerprint"

            channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            runCatching { session.disconnect() }
            throw e
        }
    }

    override fun ensureRemoteDir() {
        var path = if (config.remoteDir.startsWith("/")) "" else "."
        for (segment in dirSegments(config.remoteDir)) {
            path = if (path.isEmpty()) "/$segment" else "$path/$segment"
            try {
                channel.stat(path)
            } catch (_: SftpException) {
                channel.mkdir(path)
            }
        }
        if (path.isEmpty()) path = "/"
        channel.cd(path)
        remoteDirPath = path
    }

    override fun upload(input: InputStream, fileName: String): Long {
        channel.put(input, fileName, ChannelSftp.OVERWRITE)
        return runCatching { channel.stat(fileName).size }.getOrDefault(-1L)
    }

    override fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }

    private class PasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?) = true
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?, prompt: Array<String>?, echo: BooleanArray?
        ): Array<String> = Array(prompt?.size ?: 0) { password }
    }
}
