package com.freedom.adapters.social.momo

import java.util.concurrent.atomic.AtomicLong

/** Invalidates callbacks from a previous AI run without changing durable traversal state. */
internal class MomoSayHiRunGenerationGuard {
    private val generation = AtomicLong(0L)

    fun capture(): Long = generation.get()

    fun invalidatePreviousRun(): Long = generation.incrementAndGet()

    fun isCurrent(capturedGeneration: Long): Boolean = generation.get() == capturedGeneration
}
