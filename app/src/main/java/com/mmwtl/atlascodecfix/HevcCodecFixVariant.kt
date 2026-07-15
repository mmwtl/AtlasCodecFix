package com.mmwtl.atlascodecfix

import androidx.annotation.StringRes

enum class HevcCodecFixVariant(
    val argument: String,
    val title: String,
    @param:StringRes val descriptionRes: Int,
    val assetDirectory: String? = null,
    val experimental: Boolean = false
) {
    MSMNILE(
        argument = "msmnile",
        title = "Default",
        descriptionRes = R.string.variant_msmnile_description
    ),
    DIREWOLF(
        argument = "direwolf",
        title = "Direwolf",
        descriptionRes = R.string.variant_direwolf_description,
        experimental = true
    ),
    MIN(
        argument = "min",
        title = "Min",
        descriptionRes = R.string.variant_min_description,
        assetDirectory = "min"
    ),
    MAX(
        argument = "max",
        title = "Max",
        descriptionRes = R.string.variant_max_description,
        assetDirectory = "max",
        experimental = true
    ),
    ULTRA(
        argument = "ultra",
        title = "Ultra",
        descriptionRes = R.string.variant_ultra_description,
        assetDirectory = "ultra",
        experimental = true
    );

    companion object {
        val DEFAULT = MSMNILE
        val USER_VISIBLE = listOf(MSMNILE, MIN, DIREWOLF, MAX, ULTRA)

        fun fromArgument(argument: String?): HevcCodecFixVariant? {
            return entries.firstOrNull { it.argument.equals(argument, ignoreCase = true) }
        }
    }
}
