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
import java.lang.reflect.InvocationTargetException

private const val DEFAULT_SSH_PORT = 22
private const val SSH_CONNECT_TIMEOUT_MS = 10_000
private const val SSH_COMMAND_TIMEOUT_MS = 15_000
private const val DEFAULT_SSH_HOST_KEY_ALGORITHM = "ssh-ed25519"

private val SUPPORTED_SSH_HOST_KEY_ALGORITHMS =
    setOf(
        "ssh-ed25519",
        "ssh-rsa",
        "ssh-dss",
        "ecdsa-sha2-nistp256",
        "ecdsa-sha2-nistp384",
        "ecdsa-sha2-nistp521",
        "sk-ssh-ed25519@openssh.com",
        "sk-ecdsa-sha2-nistp256@openssh.com",
    )

private val INLINE_WHITESPACE_REGEX = Regex("\\s+")

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
    val knownHostsHostToken: String,
    val hostKeyAlgorithm: String,
    val privateKeyPem: String,
    val originalCommand: String = FORCED_COMMAND_UPLOAD_ORIGINAL_COMMAND,
)

internal data class ParsedKnownHostsEntry(
    val knownHostsEntry: String,
    val hostToken: String,
    val algorithm: String,
)

data class ForcedCommandSshUploadResult(
    val uploadedFileName: String,
    val remoteReceipt: String,
)

class ForcedCommandSshUploadConfigProvider(
    private val context: Context,
) {
    fun isConfigured(): Boolean {
        val sharedPreferences = context.sharedPrefs()
        return sharedPreferences.string(R.string.pref_forced_command_ssh_host_key).isNotBlank() &&
            sharedPreferences.string(R.string.pref_forced_command_ssh_username_key).isNotBlank() &&
            sharedPreferences.multilineString(R.string.pref_forced_command_ssh_known_hosts_key).isNotBlank() &&
            sharedPreferences.multilineString(R.string.pref_forced_command_ssh_private_key_pem_key).isNotBlank()
    }

    fun loadOrThrow(): ForcedCommandSshUploadConfig {
        val sharedPreferences = context.sharedPrefs()
        val host = sharedPreferences.string(R.string.pref_forced_command_ssh_host_key).trim()
        val rawPort = sharedPreferences.string(R.string.pref_forced_command_ssh_port_key).trim()
        val username = sharedPreferences.string(R.string.pref_forced_command_ssh_username_key).trim()
        val configuredHostKey = sharedPreferences.multilineString(R.string.pref_forced_command_ssh_known_hosts_key)
        val privateKeyPem = sharedPreferences.multilineString(R.string.pref_forced_command_ssh_private_key_pem_key)

        if (host.isBlank()) {
            throw ForcedCommandSshUploadException("Missing forced-command SSH host")
        }
        if (username.isBlank()) {
            throw ForcedCommandSshUploadException("Missing forced-command SSH username")
        }
        if (configuredHostKey.isBlank()) {
            throw ForcedCommandSshUploadException("Missing forced-command SSH host key")
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

        val parsedKnownHostsEntry =
            try {
                configuredHostKey.toParsedKnownHostsEntry(host, port)
            } catch (exception: IllegalArgumentException) {
                throw ForcedCommandSshUploadException(exception.message ?: "Invalid forced-command SSH host key", exception)
            }

        return ForcedCommandSshUploadConfig(
            host = host,
            port = port,
            username = username,
            knownHostsEntry = parsedKnownHostsEntry.knownHostsEntry,
            knownHostsHostToken = parsedKnownHostsEntry.hostToken,
            hostKeyAlgorithm = parsedKnownHostsEntry.algorithm,
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

internal fun String.toParsedKnownHostsEntry(
    host: String,
    port: Int,
): ParsedKnownHostsEntry {
    val normalized = inlineWhitespaceNormalized()
    val tokens = normalized.split(INLINE_WHITESPACE_REGEX).filter(String::isNotBlank)
    val expectedHostToken = knownHostsHost(host, port)

    return when {
        tokens.isEmpty() -> throw IllegalArgumentException("Missing forced-command SSH host key")
        tokens.size == 1 && tokens.first() in SUPPORTED_SSH_HOST_KEY_ALGORITHMS -> {
            throw IllegalArgumentException("Forced-command SSH host key is missing the base64 key body")
        }
        tokens.size == 1 ->
            ParsedKnownHostsEntry(
                knownHostsEntry = hostKeyEntryFor(host, port, DEFAULT_SSH_HOST_KEY_ALGORITHM, tokens.first()),
                hostToken = expectedHostToken,
                algorithm = DEFAULT_SSH_HOST_KEY_ALGORITHM,
            )
        tokens.first() in SUPPORTED_SSH_HOST_KEY_ALGORITHMS -> {
            val algorithm = tokens.first()
            val base64 = tokens.getOrNull(1) ?: throw IllegalArgumentException("Forced-command SSH host key is missing the base64 key body")
            ParsedKnownHostsEntry(
                knownHostsEntry = hostKeyEntryFor(host, port, algorithm, base64),
                hostToken = expectedHostToken,
                algorithm = algorithm,
            )
        }
        tokens.size >= 3 ->
            ParsedKnownHostsEntry(
                knownHostsEntry = normalized,
                hostToken = tokens.first(),
                algorithm = tokens[1],
            )
        else -> throw IllegalArgumentException(
            "Forced-command SSH host key must be base64 only, algorithm + base64, or a full known_hosts line",
        )
    }
}

internal fun String.toKnownHostsEntry(
    host: String,
    port: Int,
): String = toParsedKnownHostsEntry(host, port).knownHostsEntry

internal fun knownHostsHost(
    host: String,
    port: Int,
): String =
    if (port == DEFAULT_SSH_PORT) {
        host
    } else {
        "[$host]:$port"
    }

private fun String.inlineWhitespaceNormalized(): String =
    replace(INLINE_WHITESPACE_REGEX, " ")
        .trim()

private fun hostKeyEntryFor(
    host: String,
    port: Int,
    algorithm: String,
    base64: String,
): String = "${knownHostsHost(host, port)} $algorithm $base64"

private fun hostKeyServerProposalFor(hostKeyAlgorithm: String): String =
    when (hostKeyAlgorithm) {
        "ssh-rsa" -> "rsa-sha2-512,rsa-sha2-256,ssh-rsa"
        else -> hostKeyAlgorithm
    }

private fun verifyJschAlgorithmAvailable(algorithm: String) {
    val implementationClassName =
        JSch.getConfig(algorithm)
            ?: throw ForcedCommandSshUploadException("Forced-command SSH host key algorithm '$algorithm' is not mapped by JSch")

    try {
        val implementationClass = Class.forName(implementationClassName)
        val implementation = implementationClass.getDeclaredConstructor().newInstance()
        implementationClass.methods
            .firstOrNull { method -> method.name == "init" && method.parameterCount == 0 }
            ?.invoke(implementation)
    } catch (exception: InvocationTargetException) {
        val cause = exception.targetException ?: exception
        throw ForcedCommandSshUploadException(
            "Forced-command SSH host key algorithm '$algorithm' is unavailable in this runtime (JSch impl=$implementationClassName): ${cause.message ?: cause.javaClass.simpleName}",
            cause,
        )
    } catch (exception: ReflectiveOperationException) {
        throw ForcedCommandSshUploadException(
            "Forced-command SSH host key algorithm '$algorithm' is unavailable in this runtime (JSch impl=$implementationClassName): ${exception.message ?: exception.javaClass.simpleName}",
            exception,
        )
    } catch (exception: LinkageError) {
        throw ForcedCommandSshUploadException(
            "Forced-command SSH host key algorithm '$algorithm' is unavailable in this runtime (JSch impl=$implementationClassName): ${exception.message ?: exception.javaClass.simpleName}",
            exception,
        )
    }
}

class ForcedCommandSshAnkiDayUploadSink(
    private val config: ForcedCommandSshUploadConfig,
) : AnkiDayPayloadSink<ForcedCommandSshUploadResult> {
    override fun send(payload: AnkiDaySnapshotPayload): ForcedCommandSshUploadResult {
        verifyJschAlgorithmAvailable(config.hostKeyAlgorithm)

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
                "Invalid forced-command SSH key or host key configuration: ${exception.message ?: "unknown error"}",
                exception,
            )
        }

        val session = jsch.getSession(config.username, config.host, config.port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey")
        session.setConfig("server_host_key", hostKeyServerProposalFor(config.hostKeyAlgorithm))

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
                "Forced-command SSH connection failed: ${exception.message ?: "unknown SSH error"} (expected_host_token=${config.knownHostsHostToken}, expected_algorithm=${config.hostKeyAlgorithm})",
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
