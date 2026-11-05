package com.hapticsync.analysis

/**
 * Small fuzzy-logic helpers used to turn measured features into evidence scores in [0, 1].
 */
internal object Fuzzy {

    /** 0 below [from], 1 above [to], linear in between (works for descending ranges too). */
    fun ramp(x: Float, from: Float, to: Float): Float {
        if (from == to) return if (x >= to) 1f else 0f
        return ((x - from) / (to - from)).coerceIn(0f, 1f)
    }

    /** 1 inside [lo, hi], falling linearly to 0 over [soft] outside the range. */
    fun band(x: Float, lo: Float, hi: Float, soft: Float): Float = when {
        x < lo -> (1f - (lo - x) / soft).coerceIn(0f, 1f)
        x > hi -> (1f - (x - hi) / soft).coerceIn(0f, 1f)
        else -> 1f
    }

    /** Linear interpolation. */
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Smooth Hermite step between [edge0] and [edge1]. */
    fun smoothstep(x: Float, edge0: Float, edge1: Float): Float {
        val t = ramp(x, edge0, edge1)
        return t * t * (3f - 2f * t)
    }
}
