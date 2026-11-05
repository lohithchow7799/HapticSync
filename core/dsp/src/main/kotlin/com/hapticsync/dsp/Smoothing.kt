package com.hapticsync.dsp

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Decibel helpers. Silence is floored at [FLOOR_DB] to keep arithmetic finite. */
object Db {
    const val FLOOR_DB = -120f
    private const val MIN_POWER = 1e-12f

    /** Linear power (mean square) → dB. */
    fun fromPower(power: Float): Float = if (power <= MIN_POWER) FLOOR_DB else 10f * log10(power)

    /** Linear amplitude → dB. */
    fun fromAmplitude(amplitude: Float): Float =
        if (amplitude <= 1e-6f) FLOOR_DB else 20f * log10(amplitude)

    /** dB → linear amplitude. */
    fun toAmplitude(db: Float): Float = 10f.pow(db / 20f)

    /** dB → linear power. */
    fun toPower(db: Float): Float = 10f.pow(db / 10f)
}

/**
 * Converts a time constant into the per-update coefficient of a one-pole smoother updated at
 * [updateRateHz]. Returns 1 (no smoothing) for non-positive times.
 */
fun onePoleCoefficient(timeMs: Float, updateRateHz: Float): Float =
    if (timeMs <= 0f) 1f else (1.0 - exp(-1000.0 / (timeMs * updateRateHz))).toFloat()

/**
 * Asymmetric one-pole envelope follower with separate attack and release times.
 */
class EnvelopeFollower(attackMs: Float, releaseMs: Float, updateRateHz: Float) {
    private var attackCoef = onePoleCoefficient(attackMs, updateRateHz)
    private var releaseCoef = onePoleCoefficient(releaseMs, updateRateHz)

    var value: Float = 0f
        private set

    fun setTimes(attackMs: Float, releaseMs: Float, updateRateHz: Float) {
        attackCoef = onePoleCoefficient(attackMs, updateRateHz)
        releaseCoef = onePoleCoefficient(releaseMs, updateRateHz)
    }

    fun process(input: Float): Float {
        val coef = if (input > value) attackCoef else releaseCoef
        value += (input - value) * coef
        return value
    }

    fun reset(to: Float = 0f) {
        value = to
    }
}

/**
 * Exponentially weighted running mean and variance, used for adaptive thresholds.
 */
class RunningStats(timeConstantMs: Float, updateRateHz: Float) {
    private var alpha = onePoleCoefficient(timeConstantMs, updateRateHz)
    private var initialized = false

    var mean: Float = 0f
        private set
    var variance: Float = 0f
        private set
    val stdDev: Float get() = sqrt(max(0f, variance))

    fun setTimeConstant(timeConstantMs: Float, updateRateHz: Float) {
        alpha = onePoleCoefficient(timeConstantMs, updateRateHz)
    }

    fun update(x: Float) {
        if (!initialized) {
            mean = x
            variance = 0f
            initialized = true
            return
        }
        val delta = x - mean
        mean += alpha * delta
        variance = (1f - alpha) * (variance + alpha * delta * delta)
    }

    fun reset() {
        initialized = false
        mean = 0f
        variance = 0f
    }
}

/**
 * Tracks the noise floor of a level signal (in dB): follows decreases quickly and increases
 * slowly, so transients and sustained programme material do not lift the floor.
 */
class NoiseFloorTracker(
    private var riseMs: Float,
    private var fallMs: Float,
    private val updateRateHz: Float,
    private val initialDb: Float = -90f,
) {
    private var riseCoef = onePoleCoefficient(riseMs, updateRateHz)
    private var fallCoef = onePoleCoefficient(fallMs, updateRateHz)
    private var initialized = false

    var floorDb: Float = initialDb
        private set

    fun update(levelDb: Float): Float {
        if (!initialized) {
            floorDb = levelDb
            initialized = true
            return floorDb
        }
        val coef = if (levelDb < floorDb) fallCoef else riseCoef
        floorDb += (levelDb - floorDb) * coef
        return floorDb
    }

    fun reset() {
        initialized = false
        floorDb = initialDb
    }
}

/**
 * Fixed-capacity history of the most recent float values (oldest values are overwritten).
 * Allocation-free; index 0 is the most recent value.
 */
class FloatHistory(val capacity: Int) {
    private val data = FloatArray(capacity)
    private var head = -1
    var size: Int = 0
        private set

    fun push(value: Float) {
        head = (head + 1) % capacity
        data[head] = value
        if (size < capacity) size++
    }

    /** Value pushed [age] updates ago (0 = latest). */
    operator fun get(age: Int): Float {
        require(age in 0 until size) { "age $age out of range (size $size)" }
        var idx = head - age
        if (idx < 0) idx += capacity
        return data[idx]
    }

    fun latest(): Float = if (size == 0) 0f else data[head]

    fun mean(count: Int = size): Float {
        val n = minOf(count, size)
        if (n == 0) return 0f
        var s = 0f
        for (i in 0 until n) s += get(i)
        return s / n
    }

    fun max(count: Int = size): Float {
        val n = minOf(count, size)
        if (n == 0) return 0f
        var m = Float.NEGATIVE_INFINITY
        for (i in 0 until n) m = max(m, get(i))
        return m
    }

    fun min(count: Int = size): Float {
        val n = minOf(count, size)
        if (n == 0) return 0f
        var m = Float.POSITIVE_INFINITY
        for (i in 0 until n) m = minOf(m, get(i))
        return m
    }

    fun clear() {
        head = -1
        size = 0
    }
}
