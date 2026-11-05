package com.hapticsync.dsp.synth

import com.hapticsync.dsp.Biquad
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small, fast, deterministic pseudo-random generator (xorshift32).
 *
 * Synthesis uses it so that demo clips and test fixtures are bit-identical on every run. It is
 * used only to *create* audio content; the analysis never uses random numbers.
 */
class Prng(seed: Int) {
    private var state = if (seed == 0) 0x2545F491 else seed

    fun nextInt(): Int {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        state = x
        return x
    }

    /** Uniform in [0, 1). */
    fun nextFloat(): Float = (nextInt() ushr 8) * (1f / 16_777_216f)

    /** Uniform in [-1, 1). */
    fun bipolar(): Float = nextFloat() * 2f - 1f

    /** Uniform in [lo, hi). */
    fun range(lo: Float, hi: Float): Float = lo + (hi - lo) * nextFloat()

    /** Approximately normal (Irwin–Hall with 4 terms), zero mean, unit variance. */
    fun gaussian(): Float = (nextFloat() + nextFloat() + nextFloat() + nextFloat() - 2f) * 1.7320508f
}

/** Stereo mixing canvas used to assemble synthesised scenes. */
class SynthBuffer(val sampleRate: Int, val durationSec: Double) {
    val frames: Int = (sampleRate * durationSec).roundToInt()
    val left = FloatArray(frames)
    val right = FloatArray(frames)

    /** Mixes a mono [source] at [atSec] with constant-power [pan] in [-1, 1]. */
    fun mix(source: FloatArray, atSec: Double, gain: Float = 1f, pan: Float = 0f) {
        val start = (atSec * sampleRate).roundToInt()
        val angle = (pan.coerceIn(-1f, 1f) + 1f) * (PI / 4).toFloat()
        val gl = gain * cos(angle)
        val gr = gain * sin(angle)
        for (i in source.indices) {
            val j = start + i
            if (j < 0) continue
            if (j >= frames) break
            left[j] += source[i] * gl
            right[j] += source[i] * gr
        }
    }

    /** Mixes a mono [source] whose pan varies over time ([panAt] receives seconds from the clip start). */
    fun mixPanned(source: FloatArray, atSec: Double, gain: Float, panAt: (Double) -> Float) {
        val start = (atSec * sampleRate).roundToInt()
        for (i in source.indices) {
            val j = start + i
            if (j < 0) continue
            if (j >= frames) break
            val pan = panAt(j.toDouble() / sampleRate).coerceIn(-1f, 1f)
            val angle = (pan + 1f) * (PI / 4).toFloat()
            left[j] += source[i] * gain * cos(angle)
            right[j] += source[i] * gain * sin(angle)
        }
    }

    /** Scales the mix so its absolute peak equals [peakDbfs]. */
    fun normalize(peakDbfs: Float = -1f) {
        var peak = 0f
        for (i in 0 until frames) peak = max(peak, max(abs(left[i]), abs(right[i])))
        if (peak <= 0f) return
        val target = 10f.pow(peakDbfs / 20f)
        val g = target / peak
        for (i in 0 until frames) {
            left[i] *= g
            right[i] *= g
        }
    }

    /** Interleaved stereo samples (L, R, L, R, …). */
    fun interleaved(): FloatArray {
        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            out[2 * i] = left[i]
            out[2 * i + 1] = right[i]
        }
        return out
    }
}

/** Reusable synthesis building blocks. All functions return mono buffers. */
internal object Dsp {

    fun samples(sr: Int, seconds: Double): Int = max(1, (sr * seconds).roundToInt())

    /** White noise. */
    fun noise(rng: Prng, n: Int, gain: Float = 1f): FloatArray = FloatArray(n) { rng.bipolar() * gain }

    /** Applies `exp(-t / tau)` with an optional linear attack. */
    fun decay(buffer: FloatArray, sr: Int, tauSec: Double, attackSec: Double = 0.0): FloatArray {
        val attackN = (attackSec * sr).toInt()
        for (i in buffer.indices) {
            val t = i.toDouble() / sr
            val env = exp(-max(0.0, t - attackSec) / tauSec) * if (i < attackN) i.toDouble() / attackN else 1.0
            buffer[i] *= env.toFloat()
        }
        return buffer
    }

    fun lowPass(buffer: FloatArray, sr: Int, hz: Double, q: Double = Biquad.BUTTERWORTH2_Q): FloatArray {
        val f = Biquad().apply { setLowPass(sr.toDouble(), hz, q) }
        for (i in buffer.indices) buffer[i] = f.process(buffer[i].toDouble()).toFloat()
        return buffer
    }

    fun highPass(buffer: FloatArray, sr: Int, hz: Double, q: Double = Biquad.BUTTERWORTH2_Q): FloatArray {
        val f = Biquad().apply { setHighPass(sr.toDouble(), hz, q) }
        for (i in buffer.indices) buffer[i] = f.process(buffer[i].toDouble()).toFloat()
        return buffer
    }

    fun bandPass(buffer: FloatArray, sr: Int, hz: Double, q: Double): FloatArray {
        val f = Biquad().apply { setBandPass(sr.toDouble(), hz, q) }
        for (i in buffer.indices) buffer[i] = f.process(buffer[i].toDouble()).toFloat()
        return buffer
    }

    /** Low-pass whose cutoff follows [cutoffAt] (seconds → Hz), updated every 32 samples. */
    fun sweptLowPass(buffer: FloatArray, sr: Int, cutoffAt: (Double) -> Double): FloatArray {
        val a = Biquad()
        val b = Biquad()
        for (i in buffer.indices) {
            if (i % 32 == 0) {
                val fc = cutoffAt(i.toDouble() / sr)
                a.setLowPass(sr.toDouble(), fc, Biquad.BUTTERWORTH2_Q)
                b.setLowPass(sr.toDouble(), fc, Biquad.BUTTERWORTH2_Q)
            }
            buffer[i] = b.process(a.process(buffer[i].toDouble())).toFloat()
        }
        return buffer
    }

    /** Sine whose frequency follows [freqAt] (seconds → Hz), phase-continuous. */
    fun sweptSine(sr: Int, n: Int, freqAt: (Double) -> Double): FloatArray {
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            out[i] = sin(phase).toFloat()
            phase += 2 * PI * freqAt(i.toDouble() / sr) / sr
            if (phase > 2 * PI) phase -= 2 * PI
        }
        return out
    }

    fun add(target: FloatArray, source: FloatArray, gain: Float = 1f, offset: Int = 0): FloatArray {
        for (i in source.indices) {
            val j = i + offset
            if (j >= target.size) break
            if (j >= 0) target[j] += source[i] * gain
        }
        return target
    }

    /** Soft saturation (tanh-like) used to add harmonics to synthetic bass. */
    fun saturate(buffer: FloatArray, drive: Float): FloatArray {
        val norm = 1f / softClip(drive)
        for (i in buffer.indices) buffer[i] = softClip(buffer[i] * drive) * norm
        return buffer
    }

    private fun softClip(x: Float): Float = x / sqrt(1f + x * x)
}
