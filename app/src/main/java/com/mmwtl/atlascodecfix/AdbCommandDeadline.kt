package com.mmwtl.atlascodecfix

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class AdbCommandDeadline(
    timeoutMs: Long,
    onTimeout: () -> Unit,
    scheduler: ScheduledExecutorService = sharedScheduler
) : Closeable {
    private val state = AtomicInteger(STATE_ACTIVE)
    private val future: ScheduledFuture<*> = scheduler.schedule(
        {
            if (state.compareAndSet(STATE_ACTIVE, STATE_TIMED_OUT)) {
                onTimeout()
            }
        },
        timeoutMs.coerceAtLeast(1L),
        TimeUnit.MILLISECONDS
    )

    val timedOut: Boolean
        get() = state.get() == STATE_TIMED_OUT

    override fun close() {
        if (state.compareAndSet(STATE_ACTIVE, STATE_COMPLETED)) {
            future.cancel(false)
        }
    }

    private companion object {
        private const val STATE_ACTIVE = 0
        private const val STATE_COMPLETED = 1
        private const val STATE_TIMED_OUT = 2

        private val sharedScheduler = Executors.newSingleThreadScheduledExecutor(
            ThreadFactory { runnable ->
                Thread(runnable, "AtlasCodecFix-AdbDeadline").apply { isDaemon = true }
            }
        )
    }
}
