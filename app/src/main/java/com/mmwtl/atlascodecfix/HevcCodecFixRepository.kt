package com.mmwtl.atlascodecfix

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class HevcCodecFixRepository internal constructor(
    private val assets: HevcAssetSource,
    private val adb: AdbCommandExecutor
) {
    constructor(context: Context, adb: AdbCommandExecutor) : this(AndroidHevcAssetSource(context), adb)

    suspend fun checkCompatibility(): HevcCodecFixCompatibilityResult =
        withContext(Dispatchers.IO) {
            operationMutex.withLock { checkCompatibilityLocked() }
        }

    suspend fun detectCurrentVariant(): HevcCodecFixDetectResult = withContext(Dispatchers.IO) {
        operationMutex.withLock { detectCurrentVariantLocked() }
    }

    suspend fun collectDiagnostics(): HevcCodecFixDiagnosticResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val identity = executeCatching(
                buildDiagnosticIdentityCommand(),
                DIAGNOSTICS_IDENTITY_TIMEOUT_MS
            )
            val compatibility = checkCompatibilityLocked()
            val detected = detectCurrentVariantLocked()
            val output = buildString {
                appendLine("atlas_diagnostics:2")
                appendLine("section:identity")
                appendLine(identity.displayOutput.ifBlank { "identity:no_output" })
                appendLine("section:preflight")
                appendLine(compatibility.output.ifBlank { "preflight:no_output" })
                appendLine("section:detect")
                append(detected.output.ifBlank { "detect:no_output" })
            }.trim()
            HevcCodecFixDiagnosticResult(
                output = output,
                commandSuccess = identity.succeeded &&
                    compatibility.commandSuccess &&
                    detected.commandSuccess,
                variant = detected.variant
            )
        }
    }

    private suspend fun checkCompatibilityLocked(): HevcCodecFixCompatibilityResult {
        val commandResult = executeCatching(buildPreflightCommand(), PREFLIGHT_TIMEOUT_MS)
        val output = commandResult.displayOutput
        val commandSuccess = commandResult.succeeded
        val status = if (commandSuccess) {
            parseCompatibilityStatus(output)
        } else {
            HevcCodecFixCompatibilityStatus.UNKNOWN
        }

        return HevcCodecFixCompatibilityResult(
            status = status,
            autoApplyAllowed = commandSuccess &&
                status == HevcCodecFixCompatibilityStatus.SUPPORTED &&
                parseKey(output, "auto_apply").equals("yes", ignoreCase = true),
            output = output,
            commandSuccess = commandSuccess,
            reason = parseKey(output, "reason"),
            score = parseKey(output, "score")?.toIntOrNull(),
            variant = parseReportedVariant(output)
        )
    }

    private suspend fun detectCurrentVariantLocked(): HevcCodecFixDetectResult {
        val commandResult = executeCatching(buildDetectCommand(), DETECT_TIMEOUT_MS)
        val output = commandResult.displayOutput
        return HevcCodecFixDetectResult(
            variant = if (commandResult.succeeded) detectVariant(output) else null,
            output = output,
            commandSuccess = commandResult.succeeded
        )
    }

    suspend fun applyVariant(
        variant: HevcCodecFixVariant,
        allowRisky: Boolean = false,
        skipCompatibilityCheck: Boolean = false,
        allowExperimental: Boolean = false,
        requireAutoApplyAllowed: Boolean = false
    ): HevcCodecFixApplyResult =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                if (variant == HevcCodecFixVariant.MSMNILE) {
                    return@withLock restoreDefaultLocked()
                }

                if (variant.experimental && !allowExperimental && !skipCompatibilityCheck) {
                    return@withLock failedApply(
                        variant,
                        contextText(R.string.experimental_confirmation_required, variant.title)
                    )
                }

                val compatibility = if (skipCompatibilityCheck) {
                    null
                } else {
                    checkCompatibilityLocked()
                }
                val compatibilityAllowed = compatibility == null || if (requireAutoApplyAllowed) {
                    compatibility.autoApplyAllowed
                } else {
                    compatibility.canApply(allowRisky)
                }
                if (!compatibilityAllowed) {
                    return@withLock HevcCodecFixApplyResult(
                        requestedVariant = variant,
                        detectedVariant = compatibility.variant,
                        runOutput = compatibility.output,
                        detectOutput = "",
                        success = false,
                        compatibility = compatibility,
                        retryable = !compatibility.commandSuccess
                    )
                }

                val runResult = runCatching {
                    val stagingDir = assets.stage(variant)
                    adb.execute(buildApplyCommand(stagingDir, variant, skipCompatibilityCheck), APPLY_TIMEOUT_MS)
                }.getOrElse { t ->
                    if (t is CancellationException) throw t
                    Log.e(TAG, "HEVC apply failed", t)
                    AdbCommandResult.failure(
                        AdbCommandFailureKind.TRANSPORT,
                        t.message ?: t.javaClass.simpleName
                    )
                }

                val detected = detectCurrentVariantLocked()
                val success = detected.variant == variant && runResult.succeeded
                if (!success) {
                    val recovery = restoreDefaultLocked()
                    return@withLock HevcCodecFixApplyResult(
                        requestedVariant = variant,
                        detectedVariant = recovery.detectedVariant,
                        runOutput = runResult.displayOutput,
                        detectOutput = detected.output,
                        success = false,
                        compatibility = compatibility,
                        retryable = runResult.failure != null ||
                            !detected.commandSuccess ||
                            !recovery.success,
                        recoveryOutput = listOf(recovery.runOutput, recovery.detectOutput)
                            .filter(String::isNotBlank)
                            .joinToString("\n"),
                        restoredToDefault = recovery.success
                    )
                }
                HevcCodecFixApplyResult(
                    requestedVariant = variant,
                    detectedVariant = detected.variant,
                    runOutput = runResult.displayOutput,
                    detectOutput = detected.output,
                    success = success,
                    compatibility = compatibility,
                    retryable = runResult.failure != null || !detected.commandSuccess
                )
            }
        }

    private suspend fun restoreDefaultLocked(): HevcCodecFixApplyResult {
        val runResult = executeCatching(buildRestoreCommand(), APPLY_TIMEOUT_MS)
        val detected = detectCurrentVariantLocked()
        return HevcCodecFixApplyResult(
            requestedVariant = HevcCodecFixVariant.MSMNILE,
            detectedVariant = detected.variant,
            runOutput = runResult.displayOutput,
            detectOutput = detected.output,
            success = runResult.succeeded && detected.variant == HevcCodecFixVariant.MSMNILE,
            retryable = runResult.failure != null || !detected.commandSuccess
        )
    }

    private fun failedApply(
        variant: HevcCodecFixVariant,
        message: String
    ): HevcCodecFixApplyResult {
        return HevcCodecFixApplyResult(
            requestedVariant = variant,
            detectedVariant = null,
            runOutput = message,
            detectOutput = "",
            success = false
        )
    }

    private fun buildApplyCommand(
        stagingDir: File,
        variant: HevcCodecFixVariant,
        skipCompatibilityCheck: Boolean
    ): String {
        val preflightPrefix = if (skipCompatibilityCheck) {
            "SKIP_PREFLIGHT=1 "
        } else {
            "PREFLIGHT_VERIFIED=1 "
        }
        val script = """
            set -e
            NEW_DIR="/dev/hevc.new.${'$'}${'$'}"
            OLD_DIR="/dev/hevc.old.${'$'}${'$'}"
            OLD_MOVED=0
            SWAPPED=0

            cleanup_stage() {
                status="${'$'}?"
                trap - 0 HUP INT TERM
                rm -rf "${'$'}NEW_DIR"
                if [ "${'$'}status" -ne 0 ]; then
                    if [ "${'$'}SWAPPED" = "1" ]; then
                        rm -rf /dev/hevc
                    fi
                    if [ "${'$'}OLD_MOVED" = "1" ] && [ -e "${'$'}OLD_DIR" ]; then
                        rm -rf /dev/hevc
                        mv "${'$'}OLD_DIR" /dev/hevc
                    fi
                else
                    rm -rf "${'$'}OLD_DIR"
                fi
                exit "${'$'}status"
            }

            trap cleanup_stage 0
            trap 'exit 129' HUP
            trap 'exit 130' INT
            trap 'exit 143' TERM
            rm -rf "${'$'}NEW_DIR" "${'$'}OLD_DIR"
            cp -R ${stagingDir.absolutePath.shellQuote()} "${'$'}NEW_DIR"
            find "${'$'}NEW_DIR" -type d -exec chmod 0755 {} +
            find "${'$'}NEW_DIR" -type f -exec chmod 0644 {} +
            chmod 0755 "${'$'}NEW_DIR/preflight.sh"
            chmod 0755 "${'$'}NEW_DIR/codecfix.sh"
            chmod 0755 "${'$'}NEW_DIR/detect.sh"

            if [ -e /dev/hevc ]; then
                mv /dev/hevc "${'$'}OLD_DIR"
                OLD_MOVED=1
            fi
            mv "${'$'}NEW_DIR" /dev/hevc
            SWAPPED=1
            chmod 0755 /dev/hevc/preflight.sh
            chmod 0755 /dev/hevc/codecfix.sh
            chmod 0755 /dev/hevc/detect.sh
            cd /dev/hevc
            ${preflightPrefix}./codecfix.sh ${variant.argument}
            echo "phase:verify_variant"
            VERIFY_OUTPUT="${'$'}(sh ./detect.sh)"
            printf '%s\n' "${'$'}VERIFY_OUTPUT"
            DETECTED_VARIANT="${'$'}(printf '%s\n' "${'$'}VERIFY_OUTPUT" | sed -n 's/^variant://p' | head -n 1)"
            if [ "${'$'}DETECTED_VARIANT" != "${variant.argument}" ]; then
                echo "status:error"
                echo "reason:verification_mismatch:${variant.argument}:${'$'}{DETECTED_VARIANT:-unknown}"
                echo "phase:automatic_restore"
                if ./codecfix.sh restore; then
                    echo "rollback:ok"
                else
                    echo "rollback:failed"
                fi
                exit 1
            fi
        """.trimIndent()

        return "su root sh -c ${script.shellQuote()}"
    }

    private fun buildPreflightCommand(): String {
        return "su root sh -c ${readAssetText(PREFLIGHT_ASSET).shellQuote()}"
    }

    private fun buildRestoreCommand(): String {
        val script = "set -- restore\n${readAssetText(CODECFIX_ASSET)}"
        return "su root sh -c ${script.shellQuote()}"
    }

    private fun buildDetectCommand(): String {
        return "su root sh -c ${readAssetText(DETECT_ASSET).shellQuote()}"
    }

    private fun buildDiagnosticIdentityCommand(): String {
        val script = """
            echo "uid:${'$'}(id -u 2>/dev/null || echo unknown)"
            echo "board_platform:${'$'}(getprop ro.board.platform 2>/dev/null)"
            echo "soc_model:${'$'}(getprop ro.soc.model 2>/dev/null)"
            echo "product_device:${'$'}(getprop ro.product.device 2>/dev/null)"
            for target in \
                /vendor/etc/media_codecs_msmnile.xml \
                /vendor/etc/media_codecs_performance_msmnile.xml \
                /vendor/etc/media_profiles_msmnile.xml \
                /vendor/etc/video_system_specs.json \
                /vendor/etc/media_msmnile/video_system_specs.json; do
                if [ -f "${'$'}target" ]; then
                    echo "file:${'$'}target:present"
                else
                    echo "file:${'$'}target:missing"
                fi
            done
        """.trimIndent()
        return "su root sh -c ${script.shellQuote()}"
    }

    private fun readAssetText(assetPath: String): String {
        return assets.readText(assetPath)
    }

    internal fun detectVariant(output: String): HevcCodecFixVariant? {
        val text = output.lowercase()
        parseReportedVariant(text)?.let { return it }
        return when {
            "/dev/hevc/ultra/" in text -> HevcCodecFixVariant.ULTRA
            "/dev/hevc/max/" in text -> HevcCodecFixVariant.MAX
            "/dev/hevc/min/" in text -> HevcCodecFixVariant.MIN
            "/vendor/etc/media_codecs_direwolf.xml" in text ||
                "/vendor/etc/media_direwolf/" in text -> HevcCodecFixVariant.DIREWOLF
            "/dev/hevc/" in text -> null
            else -> HevcCodecFixVariant.MSMNILE
        }
    }

    private fun parseReportedVariant(output: String): HevcCodecFixVariant? {
        val match = Regex("""(?:^|\s)variant:([a-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(output)
            ?: return null
        return HevcCodecFixVariant.fromArgument(match.groupValues[1])
    }

    private fun parseCompatibilityStatus(output: String): HevcCodecFixCompatibilityStatus {
        return HevcCodecFixCompatibilityStatus.fromValue(parseKey(output, "status"))
    }

    internal fun parseKey(output: String, key: String): String? {
        return Regex("""(?m)^${Regex.escape(key)}:([^\r\n]*)""")
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    private suspend fun executeCatching(command: String, timeoutMs: Long): AdbCommandResult {
        return try {
            adb.execute(command, timeoutMs)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "ADB command invocation failed", t)
            AdbCommandResult.failure(
                AdbCommandFailureKind.TRANSPORT,
                t.message ?: t.javaClass.simpleName
            )
        }
    }

    private fun contextText(resource: Int, vararg arguments: Any): String {
        return assets.getString(resource, *arguments)
    }

    private fun String.shellQuote(): String = "'" + replace("'", "'\"'\"'") + "'"

    private companion object {
        private val operationMutex = Mutex()
        private const val TAG = "AtlasCodecFix"
        private const val PREFLIGHT_ASSET = "preflight.sh"
        private const val CODECFIX_ASSET = "codecfix.sh"
        private const val DETECT_ASSET = "detect.sh"
        private const val PREFLIGHT_TIMEOUT_MS = 45_000L
        private const val DETECT_TIMEOUT_MS = 12_000L
        private const val DIAGNOSTICS_IDENTITY_TIMEOUT_MS = 12_000L
        private const val APPLY_TIMEOUT_MS = 60_000L
    }
}

enum class HevcCodecFixCompatibilityStatus {
    SUPPORTED,
    RISKY,
    UNSUPPORTED,
    UNKNOWN;

    companion object {
        fun fromValue(value: String?): HevcCodecFixCompatibilityStatus {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: UNKNOWN
        }
    }
}

data class HevcCodecFixCompatibilityResult(
    val status: HevcCodecFixCompatibilityStatus,
    val autoApplyAllowed: Boolean,
    val output: String,
    val commandSuccess: Boolean,
    val reason: String?,
    val score: Int?,
    val variant: HevcCodecFixVariant?
) {
    fun canApply(allowRisky: Boolean): Boolean {
        return commandSuccess && when (status) {
            HevcCodecFixCompatibilityStatus.SUPPORTED -> true
            HevcCodecFixCompatibilityStatus.RISKY -> allowRisky
            HevcCodecFixCompatibilityStatus.UNSUPPORTED,
            HevcCodecFixCompatibilityStatus.UNKNOWN -> false
        }
    }
}

data class HevcCodecFixDetectResult(
    val variant: HevcCodecFixVariant?,
    val output: String,
    val commandSuccess: Boolean
)

data class HevcCodecFixDiagnosticResult(
    val output: String,
    val commandSuccess: Boolean,
    val variant: HevcCodecFixVariant?
)

data class HevcCodecFixApplyResult(
    val requestedVariant: HevcCodecFixVariant,
    val detectedVariant: HevcCodecFixVariant?,
    val runOutput: String,
    val detectOutput: String,
    val success: Boolean,
    val compatibility: HevcCodecFixCompatibilityResult? = null,
    val retryable: Boolean = false,
    val recoveryOutput: String = "",
    val restoredToDefault: Boolean = false
)
