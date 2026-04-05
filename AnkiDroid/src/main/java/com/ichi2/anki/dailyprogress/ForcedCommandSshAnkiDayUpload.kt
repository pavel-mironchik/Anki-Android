package com.ichi2.anki.dailyprogress

import android.content.Context
import com.ichi2.anki.R
import com.ichi2.anki.preferences.sharedPrefs
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

private const val DEFAULT_SSH_PORT = 22
private const val SSH_CONNECT_TIMEOUT_MS = 10_000
private const val SSH_COMMAND_TIMEOUT_MS = 15_000

const val FORCED_COMMAND_UPLOAD_ORIGINAL_COMMAND = "ankidroid-daily-progress-upload"

class ForcedCommandSshUploadException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class ForcedCommandSshUploadConfig(
    val host: String,
    val port: Int,
    val username: String,
    val knownHostsEntry: String,
    val privateKeyPem: String,
    val originalCommand: String = FORCED_COMMAND_UPLOAD_ORIGINAL_COMMAND,
)

data class ForcedCommandSshUploadResult(
    val uploadedFileName: String,
    val remoteReceipt: String,
)

class ForcedCommandSshUploadConfigProvider(
    private val context: Context,
) {
    fun loadOrThrow(): ForcedCommandSshUploadConfig {
        val sharedPreferences = context.sharedPrefs()
        val host = sharedPreferences.string(R.string.pref_forced_command_ssh_host_key).trim()
        val rawPort = sharedPreferences.string(R.string.pref_forced_command_ssh_port_key).trim()
        val username = sharedPreferences.string(R.string.pref_forced_command_ssh_username_key).trim()
        val knownHostsEntry = sharedPreferences.multilineString(R.string.pref_forced_command_ssh_known_hosts_key)
        val privateKeyPem = sharedPreferences.multilineString(R.string.pref_forced_command_ssh_private_key_pem_key)

        if (host.isBlank()) {
            throw ForcedCommandSshUploadException("Missing forced-command SSH host")
        }
        if (username.isBlank()) {
            throw ForcedCommandSshUploadException("Missing forced-command SSH username")
        }
        if (knownHostsEntry.isBlank()) {
            throw ForcedCommandSshUploadException("Missing forced-command SSH known_hosts entry")
        }
        if (privateKeyPem.isBlank()) {
            throw ForcedCommandSshUploadException("Missing forced-command SSH private key PEM")
        }
        if (!privateKeyPem.contains("PRIVATE KEY")) {
            throw ForcedCommandSshUploadException("Forced-command SSH private key must contain a PEM/OpenSSH PRIVATE KEY block")
        }

        val port =
            when {
                rawPort.isBlank() -> DEFAULT_SSH_PORT
                else ->
                    rawPort.toIntOrNull()
                        ?: throw ForcedCommandSshUploadException("Forced-command SSH port must be a number between 1 and 65535")
            }
        if (port !in 1..65535) {
            throw ForcedCommandSshUploadException("Forced-command SSH port must be between 1 and 65535")
        }

        return ForcedCommandSshUploadConfig(
            host = host,
            port = port,
            username = username,
            knownHostsEntry = knownHostsEntry,
            privateKeyPem = privateKeyPem,
        )
    }

    private fun android.content.SharedPreferences.string(resId: Int): String = getString(context.getString(resId), null).orEmpty()

    private fun android.content.SharedPreferences.multilineString(resId: Int): String =
        string(resId)
            .replace("\r\n", "\n")
            .replace("\\n", "\n")
            .trim()
}

class ForcedCommandSshAnkiDayUploadSink(
    private val config: ForcedCommandSshUploadConfig,
) : AnkiDayPayloadSink<ForcedCommandSshUploadResult> {
    override fun send(payload: AnkiDaySnapshotPayload): ForcedCommandSshUploadResult {
        val jsch = JSch()
        try {
            jsch.setKnownHosts(ByteArrayInputStream((config.knownHostsEntry + "\n").toByteArray(Charsets.UTF_8)))
            jsch.addIdentity(
                "ankidroid-daily-progress-upload",
                config.privateKeyPem.toByteArray(Charsets.UTF_8),
                null,
                null,
            )
        } catch (exception: JSchException) {
            throw ForcedCommandSshUploadException(
                "Invalid forced-command SSH key or known_hosts configuration: ${exception.message ?: "unknown error"}",
                exception,
            )
        }

        val session = jsch.getSession(config.username, config.host, config.port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey")

        var channel: ChannelExec? = null
        return try {
            session.connect(SSH_CONNECT_TIMEOUT_MS)

            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(config.originalCommand)
            channel.setInputStream(null)

            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            channel.setOutputStream(stdout, true)
            channel.setErrStream(stderr, true)

            val remoteStdin = channel.outputStream
            channel.connect(SSH_CONNECT_TIMEOUT_MS)

            remoteStdin.use {
                it.write(payload.utf8Bytes)
                it.write('\n'.code)
                it.flush()
            }

            waitForRemoteExit(channel)

            val exitStatus = channel.exitStatus
            val stdoutText = stdout.toString(Charsets.UTF_8.name()).trim()
            val stderrText = stderr.toString(Charsets.UTF_8.name()).trim()
            if (exitStatus != 0) {
                val remoteError = stderrText.ifBlank { stdoutText.ifBlank { "remote ingest exited with status $exitStatus" } }
                throw ForcedCommandSshUploadException("Forced-command SSH upload failed: $remoteError")
            }

            ForcedCommandSshUploadResult(
                uploadedFileName = payload.fileName,
                remoteReceipt = stdoutText.ifBlank { "uploaded ${payload.fileName}" },
            )
        } catch (exception: JSchException) {
            throw ForcedCommandSshUploadException(
                "Forced-command SSH connection failed: ${exception.message ?: "unknown SSH error"}",
                exception,
            )
        } catch (exception: IOException) {
            throw ForcedCommandSshUploadException(
                "Forced-command SSH upload I/O failed: ${exception.message ?: "unknown I/O error"}",
                exception,
            )
        } finally {
            channel?.disconnect()
            session.disconnect()
        }
    }

    private fun waitForRemoteExit(channel: ChannelExec) {
        val startedAt = System.currentTimeMillis()
        while (!channel.isClosed) {
            if (System.currentTimeMillis() - startedAt > SSH_COMMAND_TIMEOUT_MS) {
                throw ForcedCommandSshUploadException("Forced-command SSH upload timed out waiting for server confirmation")
            }
            Thread.sleep(50)
        }
    }
}
