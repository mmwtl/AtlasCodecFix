package com.mmwtl.atlascodecfix

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class HevcCodecFixRepository(
    private val context: Context,
    private val adb: AdbClient
) {
    suspend fun checkCompatibility(): HevcCodecFixCompatibilityResult =
        withContext(Dispatchers.IO) {
            val output = runCatching {
                adb.execute(buildPreflightCommand())
            }.getOrElse { t ->
                Log.e(TAG, "HEVC preflight failed", t)
                t.message ?: t.javaClass.simpleName
            }
            val commandSuccess = output.isCommandSuccess()
            val status = if (commandSuccess) {
                parseCompatibilityStatus(output)
            } else {
                HevcCodecFixCompatibilityStatus.UNKNOWN
            }

            HevcCodecFixCompatibilityResult(
                status = status,
                autoApplyAllowed = commandSuccess &&
                    status == HevcCodecFixCompatibilityStatus.SUPPORTED &&
                    parseKey(output, "auto_apply").equals("yes", ignoreCase = true),
                output = output,
                commandSuccess = commandSuccess,
                reason = parseKey(output, "reason"),
                score = parseKey(output, "score")?.toIntOrNull(),
                variant = detectVariant(output)
            )
        }

    suspend fun detectCurrentVariant(): HevcCodecFixDetectResult = withContext(Dispatchers.IO) {
        val output = adb.execute(buildDetectCommand())
        HevcCodecFixDetectResult(
            variant = detectVariant(output),
            output = output,
            commandSuccess = output.isCommandSuccess()
        )
    }

    suspend fun applyVariant(
        variant: HevcCodecFixVariant,
        allowRisky: Boolean = true
    ): HevcCodecFixApplyResult =
        withContext(Dispatchers.IO) {
            val compatibility = checkCompatibility()
            if (!compatibility.canApply(allowRisky)) {
                return@withContext HevcCodecFixApplyResult(
                    requestedVariant = variant,
                    detectedVariant = compatibility.variant,
                    runOutput = compatibility.output,
                    detectOutput = "",
                    success = false,
                    compatibility = compatibility
                )
            }

            val runOutput = runCatching {
                val stagingDir = stageHevcAssets()
                adb.execute(buildApplyCommand(stagingDir, variant))
            }.getOrElse { t ->
                Log.e(TAG, "HEVC apply failed", t)
                t.message ?: t.javaClass.simpleName
            }

            val detected = detectCurrentVariant()
            val success = detected.variant == variant && runOutput.isCommandSuccess()
            HevcCodecFixApplyResult(
                requestedVariant = variant,
                detectedVariant = detected.variant,
                runOutput = runOutput,
                detectOutput = detected.output,
                success = success,
                compatibility = compatibility
            )
        }

    private suspend fun stageHevcAssets(): File = withContext(Dispatchers.IO) {
        val target = File(context.noBackupFilesDir, HEVC_DIR_NAME)
        if (target.exists()) {
            target.deleteRecursively()
        }
        target.mkdirs()

        ASSET_ROOTS.forEach { assetName ->
            copyAssetTree(assetName, File(target, assetName))
        }
        target
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val name = assetPath.substringAfterLast('/')
        if (name.startsWith(".") || name == "__MACOSX") return

        val children = context.assets.list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            target.mkdirs()
            children.forEach { child ->
                copyAssetTree("$assetPath/$child", File(target, child))
            }
            return
        }

        target.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun buildApplyCommand(stagingDir: File, variant: HevcCodecFixVariant): String {
        val script = """
            set -e
            for target in \
                /vendor/etc/media_codecs_msmnile.xml \
                /vendor/etc/media_codecs_performance_msmnile.xml \
                /vendor/etc/media_profiles_msmnile.xml \
                /vendor/etc/video_system_specs.json \
                /vendor/etc/media_msmnile/video_system_specs.json; do
                umount "${'$'}target" 2>/dev/null || true
            done
            rm -rf /dev/hevc
            mkdir -p /dev
            cp -R ${stagingDir.absolutePath.shellQuote()} /dev/hevc
            find /dev/hevc -type d -exec chmod 0755 {} +
            find /dev/hevc -type f -exec chmod 0644 {} +
            chmod 0755 /dev/hevc/preflight.sh
            chmod 0755 /dev/hevc/codecfix.sh
            cd /dev/hevc
            ./codecfix.sh ${variant.argument}
        """.trimIndent()

        return "su root sh -c ${script.shellQuote()}"
    }

    private fun buildPreflightCommand(): String {
        return "su root sh -c ${readAssetText(PREFLIGHT_ASSET).shellQuote()}"
    }

    private fun buildDetectCommand(): String {
        return "su root sh -c ${DETECT_SCRIPT.shellQuote()}"
    }

    private fun readAssetText(assetPath: String): String {
        return context.assets.open(assetPath).bufferedReader().use { it.readText() }
    }

    private fun detectVariant(output: String): HevcCodecFixVariant? {
        val text = output.lowercase()
        if (!output.isCommandSuccess()) return null
        Regex("""(?:^|\s)variant:([a-z0-9_-]+)""").find(text)?.let { match ->
            return HevcCodecFixVariant.fromArgument(match.groupValues[1])
        }
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

    private fun parseCompatibilityStatus(output: String): HevcCodecFixCompatibilityStatus {
        return HevcCodecFixCompatibilityStatus.fromValue(parseKey(output, "status"))
    }

    private fun parseKey(output: String, key: String): String? {
        return Regex("""(?m)^${Regex.escape(key)}:([^\r\n]*)""")
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    private fun String.isCommandSuccess(): Boolean {
        return !contains("ADB helper disabled", ignoreCase = true) &&
            !contains("ADB disconnected", ignoreCase = true) &&
            !contains("ADB connect failed", ignoreCase = true) &&
            !contains("ADB command failed", ignoreCase = true) &&
            !contains("ADB execute error", ignoreCase = true)
    }

    private fun String.shellQuote(): String = "'" + replace("'", "'\"'\"'") + "'"

    private companion object {
        private const val TAG = "AtlasCodecFix"
        private const val HEVC_DIR_NAME = "hevc"
        private const val PREFLIGHT_ASSET = "preflight.sh"
        private val ASSET_ROOTS = listOf(PREFLIGHT_ASSET, "codecfix.sh", "default", "min", "max", "ultra")
        private val DETECT_SCRIPT = """
            TARGET_CODECS="/vendor/etc/media_codecs_msmnile.xml"
            TARGET_PERFORMANCE="/vendor/etc/media_codecs_performance_msmnile.xml"
            TARGET_PROFILES="/vendor/etc/media_profiles_msmnile.xml"
            TARGET_SPECS="/vendor/etc/video_system_specs.json"
            TARGET_MSMNILE_SPECS="/vendor/etc/media_msmnile/video_system_specs.json"
            BASE_DIR="/dev/hevc"

            for target in \
                "${'$'}TARGET_CODECS" \
                "${'$'}TARGET_PERFORMANCE" \
                "${'$'}TARGET_PROFILES" \
                "${'$'}TARGET_SPECS" \
                "${'$'}TARGET_MSMNILE_SPECS"; do
                if [ ! -f "${'$'}target" ]; then
                    echo "variant:unknown"
                    echo "missing:${'$'}target"
                    exit 0
                fi
            done

            mounted_targets="${'$'}(mount | grep -E "/vendor/etc/(media_codecs_msmnile.xml|media_codecs_performance_msmnile.xml|media_profiles_msmnile.xml|video_system_specs.json|media_msmnile/video_system_specs.json)" || true)"
            if [ -z "${'$'}mounted_targets" ]; then
                echo "variant:msmnile"
                exit 0
            fi

            matches_folder() {
                NAME="${'$'}1"
                SRC_DIR="${'$'}BASE_DIR/${'$'}NAME"
                [ -f "${'$'}SRC_DIR/media_codecs_${'$'}NAME.xml" ] || return 1
                [ -f "${'$'}SRC_DIR/media_codecs_performance_${'$'}NAME.xml" ] || return 1
                [ -f "${'$'}SRC_DIR/media_profiles_${'$'}NAME.xml" ] || return 1
                [ -f "${'$'}SRC_DIR/video_system_specs_${'$'}NAME.json" ] || return 1
                cmp -s "${'$'}TARGET_CODECS" "${'$'}SRC_DIR/media_codecs_${'$'}NAME.xml" &&
                    cmp -s "${'$'}TARGET_PERFORMANCE" "${'$'}SRC_DIR/media_codecs_performance_${'$'}NAME.xml" &&
                    cmp -s "${'$'}TARGET_PROFILES" "${'$'}SRC_DIR/media_profiles_${'$'}NAME.xml" &&
                    cmp -s "${'$'}TARGET_SPECS" "${'$'}SRC_DIR/video_system_specs_${'$'}NAME.json" &&
                    cmp -s "${'$'}TARGET_MSMNILE_SPECS" "${'$'}SRC_DIR/video_system_specs_${'$'}NAME.json"
            }

            if matches_folder ultra; then echo "variant:ultra"; exit 0; fi
            if matches_folder max; then echo "variant:max"; exit 0; fi
            if matches_folder min; then echo "variant:min"; exit 0; fi

            if [ -f /vendor/etc/media_codecs_direwolf.xml ] &&
                [ -f /vendor/etc/media_codecs_performance_direwolf.xml ] &&
                [ -f /vendor/etc/media_profiles_direwolf.xml ] &&
                [ -f /vendor/etc/media_direwolf/video_system_specs.json ] &&
                cmp -s "${'$'}TARGET_CODECS" /vendor/etc/media_codecs_direwolf.xml &&
                cmp -s "${'$'}TARGET_PERFORMANCE" /vendor/etc/media_codecs_performance_direwolf.xml &&
                cmp -s "${'$'}TARGET_PROFILES" /vendor/etc/media_profiles_direwolf.xml &&
                cmp -s "${'$'}TARGET_SPECS" /vendor/etc/media_direwolf/video_system_specs.json &&
                cmp -s "${'$'}TARGET_MSMNILE_SPECS" /vendor/etc/media_direwolf/video_system_specs.json; then
                echo "variant:direwolf"
                exit 0
            fi

            echo "variant:unknown"
            echo "${'$'}mounted_targets"
        """.trimIndent()
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

data class HevcCodecFixApplyResult(
    val requestedVariant: HevcCodecFixVariant,
    val detectedVariant: HevcCodecFixVariant?,
    val runOutput: String,
    val detectOutput: String,
    val success: Boolean,
    val compatibility: HevcCodecFixCompatibilityResult? = null
)
