package com.mmwtl.atlascodecfix

interface AdbCommandExecutor {
    suspend fun execute(
        command: String,
        timeoutMs: Long = AdbClient.DEFAULT_COMMAND_TIMEOUT_MS
    ): AdbCommandResult
}

data class AdbCommandResult(
    val stdout: String,
    val exitCode: Int?,
    val failure: AdbCommandFailure? = null
) {
    val succeeded: Boolean
        get() = failure == null && exitCode == 0

    val displayOutput: String
        get() = buildList {
            failure?.message?.takeIf(String::isNotBlank)?.let(::add)
            if (failure == null && exitCode != null && exitCode != 0) {
                add("ADB command failed (exit=$exitCode)")
            }
            stdout.takeIf(String::isNotBlank)?.let(::add)
        }.joinToString("\n")

    companion object {
        fun failure(kind: AdbCommandFailureKind, message: String): AdbCommandResult {
            return AdbCommandResult(
                stdout = "",
                exitCode = null,
                failure = AdbCommandFailure(kind, message)
            )
        }
    }
}

data class AdbCommandFailure(
    val kind: AdbCommandFailureKind,
    val message: String
)

enum class AdbCommandFailureKind {
    DISABLED,
    CONNECT,
    TIMEOUT,
    TRANSPORT,
    INVALID_COMMAND
}
