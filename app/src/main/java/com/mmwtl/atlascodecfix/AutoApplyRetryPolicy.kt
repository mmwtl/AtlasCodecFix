package com.mmwtl.atlascodecfix

internal object AutoApplyRetryPolicy {
    const val MAX_ATTEMPTS = 5

    fun nextRetryCount(currentRetryCount: Int): Int? {
        val next = currentRetryCount.coerceAtLeast(0) + 1
        return next.takeIf { it < MAX_ATTEMPTS }
    }
}
