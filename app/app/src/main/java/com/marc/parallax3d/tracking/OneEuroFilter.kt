package com.marc.parallax3d.tracking

import kotlin.math.PI
import kotlin.math.abs

/**
 * One-Euro filter: low jitter at rest, low lag in motion.
 * https://gery.casiez.net/1euro/
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.0f,
    private val beta: Float = 0.02f,
    private val dCutoff: Float = 1.0f,
) {
    private var hasPrev = false
    private var prev = 0f
    private var prevDeriv = 0f
    private var prevTimeNs = 0L

    fun filter(value: Float, timeNs: Long): Float {
        if (!hasPrev) {
            hasPrev = true
            prev = value
            prevTimeNs = timeNs
            return value
        }
        val dt = ((timeNs - prevTimeNs) / 1e9f).coerceIn(1e-4f, 0.5f)
        prevTimeNs = timeNs

        val deriv = (value - prev) / dt
        val dAlpha = alpha(dCutoff, dt)
        prevDeriv += dAlpha * (deriv - prevDeriv)

        val cutoff = minCutoff + beta * abs(prevDeriv)
        val a = alpha(cutoff, dt)
        prev += a * (value - prev)
        return prev
    }

    fun reset() {
        hasPrev = false
        prevDeriv = 0f
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}
