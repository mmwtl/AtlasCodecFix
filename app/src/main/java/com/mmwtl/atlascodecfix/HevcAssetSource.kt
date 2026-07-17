package com.mmwtl.atlascodecfix

import android.content.Context
import java.io.File

internal interface HevcAssetSource {
    fun readText(assetPath: String): String
    fun stage(variant: HevcCodecFixVariant): File
    fun getString(resource: Int, vararg arguments: Any): String
}

internal class AndroidHevcAssetSource(
    private val context: Context
) : HevcAssetSource {
    override fun getString(resource: Int, vararg arguments: Any): String {
        return context.getString(resource, *arguments)
    }

    override fun readText(assetPath: String): String {
        return context.assets.open(assetPath).bufferedReader().use { it.readText() }
    }

    override fun stage(variant: HevcCodecFixVariant): File {
        val target = File(context.noBackupFilesDir, HEVC_DIR_NAME)
        if (target.exists() && !target.deleteRecursively()) {
            error("Unable to clear HEVC staging directory")
        }
        if (!target.mkdirs()) {
            error("Unable to create HEVC staging directory")
        }

        buildList {
            add(PREFLIGHT_ASSET)
            add(CODECFIX_ASSET)
            add(DETECT_ASSET)
            variant.assetDirectory?.let(::add)
        }.forEach { assetName ->
            copyAssetTree(assetName, File(target, assetName))
        }
        return target
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val name = assetPath.substringAfterLast('/')
        if (name.startsWith(".") || name == "__MACOSX") return

        val children = context.assets.list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            if (!target.isDirectory && !target.mkdirs()) {
                error("Unable to create asset directory $assetPath")
            }
            children.forEach { child ->
                copyAssetTree("$assetPath/$child", File(target, child))
            }
            return
        }

        target.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                error("Unable to create asset parent directory")
            }
        }
        context.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private companion object {
        private const val HEVC_DIR_NAME = "hevc"
        private const val PREFLIGHT_ASSET = "preflight.sh"
        private const val CODECFIX_ASSET = "codecfix.sh"
        private const val DETECT_ASSET = "detect.sh"
    }
}
