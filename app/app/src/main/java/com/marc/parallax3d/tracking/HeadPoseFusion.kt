package com.marc.parallax3d.tracking

import kotlin.math.exp

/**
 * Complementary filter: gyro tilt high-passed (fast, drifts to neutral),
 * face position low-passed (slow absolute anchor). Sum = responsive parallax
 * with no perceived camera latency.
 */
class HeadPoseFusion {
    enum class Mode { FUSED, GYRO_ONLY, FACE_ONLY }

    @Volatile var mode = Mode.FUSED
    @Volatile var faceVisible = false

    // Face offset, normalized ~[-1,1], already One-Euro filtered by FaceTracker.
    @Volatile var faceX = 0f
    @Volatile var faceY = 0f

    var gyro: GyroTracker? = null

    private val tau = 0.7f  // seconds; split point between gyro and face authority
    private var gyroLowX = 0f
    private var gyroLowY = 0f
    private var faceLowX = 0f
    private var faceLowY = 0f
    private var lastNs = 0L

    // Tilt radians -> full offset. ~0.35 rad (20 deg) = max parallax.
    private val tiltGain = 1f / 0.35f

    /** Called on GL thread per frame. Returns head offset (x, y) ~[-1,1]. */
    fun offset(): Pair<Float, Float> {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(1e-4f, 0.5f)
        lastNs = now

        val a = 1f - exp(-dt / tau)

        val g = gyro
        var gx = 0f
        var gy = 0f
        if (g != null && mode != Mode.FACE_ONLY) {
            val rawX = (g.tiltX * tiltGain).coerceIn(-1.5f, 1.5f)
            val rawY = (g.tiltY * tiltGain).coerceIn(-1.5f, 1.5f)
            gyroLowX += a * (rawX - gyroLowX)
            gyroLowY += a * (rawY - gyroLowY)
            gx = rawX - gyroLowX  // high-pass: static tilt decays out
            gy = rawY - gyroLowY
        }

        var fx = 0f
        var fy = 0f
        val useFace = mode != Mode.GYRO_ONLY && faceVisible
        if (useFace) {
            faceLowX += a * (faceX - faceLowX)
            faceLowY += a * (faceY - faceLowY)
            fx = faceLowX
            fy = faceLowY
        } else {
            // Face lost: anchor decays to neutral instead of freezing stale
            faceLowX += a * (0f - faceLowX)
            faceLowY += a * (0f - faceLowY)
            fx = faceLowX
            fy = faceLowY
        }

        return Pair((gx + fx).coerceIn(-1f, 1f), (gy + fy).coerceIn(-1f, 1f))
    }
}
