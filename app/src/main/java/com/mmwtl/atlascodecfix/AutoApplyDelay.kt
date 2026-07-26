package com.mmwtl.atlascodecfix

object AutoApplyDelay {
    const val MIN_SECONDS = 0
    const val MAX_SECONDS = 3_600
    const val DEFAULT_SECONDS = 12

    fun normalize(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun parse(text: String): Int? {
        return text.toIntOrNull()?.takeIf { it in MIN_SECONDS..MAX_SECONDS }
    }

    fun toMilliseconds(seconds: Int): Long = normalize(seconds) * 1_000L
}
