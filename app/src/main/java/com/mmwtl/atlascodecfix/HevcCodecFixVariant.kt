package com.mmwtl.atlascodecfix

enum class HevcCodecFixVariant(
    val argument: String,
    val title: String,
    val description: String
) {
    MSMNILE(
        argument = "msmnile",
        title = "Default",
        description = "Стандартный профиль msmnile"
    ),
    DIREWOLF(
        argument = "direwolf",
        title = "Direwolf",
        description = "Применить штатные direwolf-конфиги"
    ),
    MIN(
        argument = "min",
        title = "Min",
        description = "Стандартный набор, только раскомментирован HEVC"
    ),
    MAX(
        argument = "max",
        title = "Max",
        description = "Набор с лучшими цифрами производительности из имеющихся файлов"
    ),
    ULTRA(
        argument = "ultra",
        title = "Ultra",
        description = "Максимальный C2/QTI-first набор из найденного"
    );

    companion object {
        val DEFAULT = MSMNILE
        val USER_VISIBLE = listOf(MSMNILE, MIN, MAX)

        fun fromArgument(argument: String?): HevcCodecFixVariant? {
            return entries.firstOrNull { it.argument.equals(argument, ignoreCase = true) }
        }
    }
}
