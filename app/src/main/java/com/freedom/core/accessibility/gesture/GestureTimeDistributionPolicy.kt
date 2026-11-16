package com.freedom.core.accessibility.gesture

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/** Distribution families for bounded waits. The distribution is a scheduling model, not a promise
 * that a sampled value represents a human response time. */
enum class GestureTimeDistributionKind {
    UNIFORM,
    TRUNCATED_NORMAL,
    TRUNCATED_LOG_NORMAL
}

data class GestureTimeDistributionProfile(
    val enabled: Boolean = true,
    val kind: GestureTimeDistributionKind = GestureTimeDistributionKind.TRUNCATED_LOG_NORMAL,
    /** Arithmetic mean for normal; median for log-normal; ignored for uniform. */
    val centerMs: Double = 400.0,
    /** Standard deviation in milliseconds for normal distribution. */
    val standardDeviationMs: Double = 80.0,
    /** Sigma in log-space for log-normal distribution. */
    val logNormalSigma: Double = 0.25,
    val minimumMs: Long = 100L,
    val maximumMs: Long = 1_000L
) {
    fun isValid(): Boolean {
        if (!centerMs.isFinite() || centerMs < 0.0 ||
            !standardDeviationMs.isFinite() || standardDeviationMs < 0.0 ||
            !logNormalSigma.isFinite() || logNormalSigma < 0.0 ||
            minimumMs < 0L || maximumMs < minimumMs || maximumMs > MAX_WAIT_MS
        ) return false
        return when (kind) {
            GestureTimeDistributionKind.UNIFORM -> true
            GestureTimeDistributionKind.TRUNCATED_NORMAL -> centerMs in minimumMs.toDouble()..maximumMs.toDouble()
            GestureTimeDistributionKind.TRUNCATED_LOG_NORMAL ->
                centerMs > 0.0 && centerMs in minimumMs.toDouble()..maximumMs.toDouble()
        }
    }

    companion object {
        const val MAX_WAIT_MS = 120_000L
    }
}

/** Shared bounded sampler used for pre-dispatch, intra-gesture and inter-stage waits. */
object GestureTimeDistributionPolicy {
    private const val MAX_REJECTION_ATTEMPTS = 64
    private const val MIN_UNIFORM = 1.0e-12

    fun sampleMillis(
        profile: GestureTimeDistributionProfile,
        random: Random = Random.Default
    ): Long? {
        if (!profile.isValid()) return null
        if (!profile.enabled) return 0L
        val lower = profile.minimumMs.toDouble()
        val upper = profile.maximumMs.toDouble()
        repeat(MAX_REJECTION_ATTEMPTS) {
            val sample = when (profile.kind) {
                GestureTimeDistributionKind.UNIFORM -> {
                    if (profile.maximumMs == profile.minimumMs) lower
                    else random.nextLong(profile.minimumMs, profile.maximumMs + 1L).toDouble()
                }
                GestureTimeDistributionKind.TRUNCATED_NORMAL -> {
                    val z = standardNormal(random)
                    profile.centerMs + profile.standardDeviationMs * z
                }
                GestureTimeDistributionKind.TRUNCATED_LOG_NORMAL -> {
                    if (profile.centerMs <= 0.0) return null
                    exp(ln(profile.centerMs) + profile.logNormalSigma * standardNormal(random))
                }
            }
            if (sample.isFinite() && sample >= lower && sample <= upper) {
                return sample.roundToLong().coerceIn(profile.minimumMs, profile.maximumMs)
            }
        }
        // A rejected truncated sample is a configuration/distribution failure; do not clamp it
        // onto a boundary and create artificial probability spikes.
        return null
    }

    fun sampleLogNormalMillis(
        medianMs: Double,
        sigma: Double,
        minimumMs: Long,
        maximumMs: Long,
        random: Random = Random.Default
    ): Long? = sampleMillis(
        GestureTimeDistributionProfile(
            enabled = true,
            kind = GestureTimeDistributionKind.TRUNCATED_LOG_NORMAL,
            centerMs = medianMs,
            logNormalSigma = sigma,
            minimumMs = minimumMs,
            maximumMs = maximumMs
        ),
        random
    )

    private fun standardNormal(random: Random): Double {
        val u1 = random.nextDouble().coerceAtLeast(MIN_UNIFORM)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }
}
