package com.mmwtl.atlascodecfix

internal class AutoApplyRunGuard {
    private var generation = 0L

    @Synchronized
    fun start(): Long = ++generation

    @Synchronized
    fun invalidate() {
        generation++
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = token == generation
}
