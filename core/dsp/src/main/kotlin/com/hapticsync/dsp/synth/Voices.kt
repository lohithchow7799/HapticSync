package com.hapticsync.dsp.synth

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Percussive, cinematic and environmental one-shot sounds, modelled on the acoustic
 * characteristics that distinguish them (spectral balance, attack, decay, noisiness). They are
 * deliberately physically plausible rather than perfectly realistic.
 */
object Voices {

    /** Bass drum: pitch-swept sine "thump" (~140 → 48 Hz) with a short beater click. */
    fun kick(sr: Int, rng: Prng, decaySec: Double = 0.28): FloatArray {
        val n = Dsp.samples(sr, decaySec * 2.2)
        val body = Dsp.sweptSine(sr, n) { t -> 48 + 92 * exp(-t / 0.028) }
        Dsp.decay(body, sr, decaySec / 2.2, attackSec = 0.0015)
        val click = Dsp.highPass(Dsp.decay(Dsp.noise(rng, Dsp.samples(sr, 0.012), 0.35f), sr, 0.0025), sr, 1800.0)
        return Dsp.add(body, click)
    }

    /** Snare drum: ~185 Hz shell tone plus band-limited snare-wire noise. */
    fun snare(sr: Int, rng: Prng): FloatArray {
        val n = Dsp.samples(sr, 0.45)
        val tone = Dsp.sweptSine(sr, n) { t -> 185 + 40 * exp(-t / 0.01) }
        Dsp.decay(tone, sr, 0.055, attackSec = 0.001)
        val wires = Dsp.decay(Dsp.noise(rng, n, 0.9f), sr, 0.11, attackSec = 0.001)
        Dsp.bandPass(wires, sr, 3800.0, 0.6)
        Dsp.highPass(wires, sr, 1200.0)
        return Dsp.add(Dsp.add(FloatArray(n), tone, 0.55f), wires, 0.9f)
    }

    /** Hi-hat: high-passed metallic noise, closed (short) or open (long). */
    fun hiHat(sr: Int, rng: Prng, open: Boolean = false): FloatArray {
        val tau = if (open) 0.22 else 0.028
        val n = Dsp.samples(sr, tau * 5)
        val x = Dsp.decay(Dsp.noise(rng, n), sr, tau, attackSec = 0.0005)
        Dsp.highPass(x, sr, 7000.0)
        Dsp.highPass(x, sr, 7000.0)
        return x
    }

    /** Crash cymbal: bright noise with inharmonic partials and a long decay. */
    fun crash(sr: Int, rng: Prng): FloatArray {
        val n = Dsp.samples(sr, 2.6)
        val x = Dsp.noise(rng, n)
        Dsp.highPass(x, sr, 4200.0)
        Dsp.highPass(x, sr, 3000.0)
        val partials = doubleArrayOf(3170.0, 4410.0, 5230.0, 6870.0, 8120.0)
        for ((idx, f) in partials.withIndex()) {
            for (i in 0 until n) x[i] += 0.06f * sin(2 * PI * f * i / sr + idx).toFloat()
        }
        return Dsp.decay(x, sr, 0.85, attackSec = 0.002)
    }

    /** Sustained synth bass note with saturation (sub-bass fundamental + harmonics). */
    fun bassNote(sr: Int, frequencyHz: Double, durationSec: Double, drive: Float = 2.2f): FloatArray {
        val n = Dsp.samples(sr, durationSec)
        val x = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val v = sin(2 * PI * frequencyHz * t) + 0.35 * sin(2 * PI * 2 * frequencyHz * t)
            val env = minOf(1.0, t / 0.008) * minOf(1.0, (durationSec - t) / 0.05).coerceAtLeast(0.0)
            x[i] = (v * env).toFloat()
        }
        Dsp.saturate(x, drive)
        return Dsp.lowPass(x, sr, 400.0)
    }

    /**
     * Explosion: broadband blast whose brightness collapses quickly (low-pass sweeping from
     * ~7 kHz down to ~120 Hz), a sub-bass pressure thump and a long low-frequency rumble tail
     * with debris crackle. [size] scales loudness and length.
     */
    fun explosion(sr: Int, rng: Prng, size: Double = 1.0): FloatArray {
        val length = 2.6 + 1.6 * size
        val n = Dsp.samples(sr, length)
        val blast = Dsp.noise(rng, n)
        Dsp.sweptLowPass(blast, sr) { t -> 120 + 7000 * exp(-t / (0.09 * size)) }
        Dsp.decay(blast, sr, 0.55 * size, attackSec = 0.004)
        val thump = Dsp.sweptSine(sr, n) { t -> 36 + 30 * exp(-t / 0.05) }
        Dsp.decay(thump, sr, 0.75 * size, attackSec = 0.006)
        val out = Dsp.add(Dsp.add(FloatArray(n), blast, 1.0f), thump, 0.9f)
        // Debris: sparse decaying clicks whose rate falls off over time.
        var t = 0.05
        while (t < length * 0.8) {
            val click = Dsp.highPass(Dsp.decay(Dsp.noise(rng, Dsp.samples(sr, 0.02), 0.25f), sr, 0.004), sr, 2000.0)
            Dsp.add(out, click, (0.6 * exp(-t / 0.9)).toFloat(), (t * sr).toInt())
            t += rng.range(0.015f, 0.09f) * (1 + t)
        }
        return out
    }

    /** Gunshot: extremely fast broadband blast, mid-frequency body and a short room tail. */
    fun gunshot(sr: Int, rng: Prng): FloatArray {
        val n = Dsp.samples(sr, 0.7)
        val blast = Dsp.decay(Dsp.noise(rng, n), sr, 0.007, attackSec = 0.0004)
        val body = Dsp.bandPass(Dsp.decay(Dsp.noise(rng, n), sr, 0.022, attackSec = 0.0005), sr, 1800.0, 0.8)
        val thump = Dsp.decay(Dsp.sweptSine(sr, n) { t -> 110 + 90 * exp(-t / 0.01) }, sr, 0.03, attackSec = 0.0008)
        val tail = Dsp.lowPass(Dsp.decay(Dsp.noise(rng, n), sr, 0.13, attackSec = 0.01), sr, 2500.0)
        return Dsp.add(Dsp.add(Dsp.add(FloatArray(n), blast, 1f), body, 0.8f), thump, 0.5f).also { Dsp.add(it, tail, 0.12f) }
    }

    /** Punch: short, dull mid/low-mid thud without much high-frequency content. */
    fun punch(sr: Int, rng: Prng): FloatArray {
        val n = Dsp.samples(sr, 0.3)
        val slap = Dsp.lowPass(Dsp.decay(Dsp.noise(rng, n), sr, 0.016, attackSec = 0.001), sr, 1300.0)
        val thud = Dsp.decay(Dsp.sweptSine(sr, n) { t -> 95 + 50 * exp(-t / 0.012) }, sr, 0.045, attackSec = 0.001)
        return Dsp.add(Dsp.add(FloatArray(n), slap, 0.9f), thud, 0.8f)
    }

    /**
     * Heavy impact / collision: violent broadband crash, low thud and metallic ringing debris.
     */
    fun impact(sr: Int, rng: Prng): FloatArray {
        val n = Dsp.samples(sr, 1.1)
        val crashNoise = Dsp.decay(Dsp.noise(rng, n), sr, 0.03, attackSec = 0.0008)
        val thud = Dsp.decay(Dsp.sweptSine(sr, n) { t -> 70 + 60 * exp(-t / 0.02) }, sr, 0.09, attackSec = 0.001)
        val ring = FloatArray(n)
        val partials = doubleArrayOf(523.0, 911.0, 1370.0, 2210.0)
        for (f in partials) for (i in 0 until n) ring[i] += sin(2 * PI * f * i / sr).toFloat() * 0.25f
        Dsp.decay(ring, sr, 0.3)
        return Dsp.add(Dsp.add(Dsp.add(FloatArray(n), crashNoise, 1f), thud, 0.9f), ring, 0.35f)
    }

    /** Thunder: optional close "crack" followed by a slow-rising, long, deep rumble. */
    fun thunder(sr: Int, rng: Prng, close: Boolean): FloatArray {
        val n = Dsp.samples(sr, 6.0)
        val rumble = Dsp.noise(rng, n)
        Dsp.lowPass(rumble, sr, 170.0)
        Dsp.lowPass(rumble, sr, 170.0)
        val attack = if (close) 0.18 else 0.6
        // Slow irregular amplitude modulation gives the characteristic rolling quality.
        var mod = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            if (i % 256 == 0) mod = 0.6 + 0.4 * sin(2 * PI * 0.9 * t + 1.7 * sin(2 * PI * 0.31 * t))
            val env = minOf(1.0, t / attack) * exp(-maxOf(0.0, t - attack) / 1.5)
            rumble[i] *= (env * mod).toFloat() * 3.2f
        }
        if (close) {
            val crack = Dsp.highPass(Dsp.decay(Dsp.noise(rng, Dsp.samples(sr, 0.4)), sr, 0.035, attackSec = 0.001), sr, 900.0)
            Dsp.add(rumble, crack, 0.8f)
        }
        return rumble
    }

    /** Footstep on a hard floor: low heel thud plus a small click and scuff. */
    fun footstep(sr: Int, rng: Prng, weight: Float = 1f): FloatArray {
        val n = Dsp.samples(sr, 0.18)
        val thud = Dsp.lowPass(Dsp.decay(Dsp.noise(rng, n), sr, 0.016, attackSec = 0.002), sr, 260.0)
        val heel = Dsp.bandPass(Dsp.decay(Dsp.noise(rng, n), sr, 0.005, attackSec = 0.0005), sr, 2600.0, 1.2)
        val scuff = Dsp.bandPass(Dsp.decay(Dsp.noise(rng, n), sr, 0.03, attackSec = 0.004), sr, 1200.0, 0.9)
        return Dsp.add(Dsp.add(Dsp.add(FloatArray(n), thud, 1.6f * weight), heel, 0.35f), scuff, 0.18f)
    }

    /** Door slam: dull low thud, wooden rattle and a short room tail. */
    fun doorSlam(sr: Int, rng: Prng): FloatArray {
        val n = Dsp.samples(sr, 1.0)
        val thud = Dsp.lowPass(Dsp.decay(Dsp.noise(rng, n), sr, 0.04, attackSec = 0.001), sr, 190.0)
        val rattle = Dsp.bandPass(Dsp.decay(Dsp.noise(rng, n), sr, 0.08, attackSec = 0.002), sr, 720.0, 2.0)
        val tail = Dsp.lowPass(Dsp.decay(Dsp.noise(rng, n), sr, 0.22, attackSec = 0.02), sr, 1500.0)
        return Dsp.add(Dsp.add(Dsp.add(FloatArray(n), thud, 2.2f), rattle, 0.6f), tail, 0.12f)
    }

    /** Sync-test click: sharp full-scale transient followed by a 1 kHz beep. */
    fun syncClick(sr: Int): FloatArray {
        val n = Dsp.samples(sr, 0.06)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val click = if (t < 0.0015) (1 - t / 0.0015) else 0.0
            val beep = sin(2 * PI * 1000 * t) * exp(-t / 0.02)
            out[i] = (0.9 * click + 0.5 * beep).toFloat()
        }
        return out
    }

    /** Musical riser: noise whose high-pass cutoff climbs, plus a rising tone. */
    fun riser(sr: Int, rng: Prng, durationSec: Double): FloatArray {
        val n = Dsp.samples(sr, durationSec)
        val x = Dsp.noise(rng, n, 0.5f)
        val hp = com.hapticsync.dsp.Biquad()
        for (i in 0 until n) {
            if (i % 64 == 0) hp.setHighPass(sr.toDouble(), 200.0 + 5000.0 * (i.toDouble() / n), 0.9)
            x[i] = hp.process(x[i].toDouble()).toFloat() * (i.toFloat() / n)
        }
        val tone = Dsp.sweptSine(sr, n) { t -> 300 + 900 * t / durationSec }
        for (i in 0 until n) x[i] += tone[i] * 0.15f * (i.toFloat() / n)
        return x
    }

    /** Bullet ricochet: descending whistle. */
    fun ricochet(sr: Int): FloatArray {
        val n = Dsp.samples(sr, 0.45)
        val x = Dsp.sweptSine(sr, n) { t -> 3200 - 1900 * (t / 0.45) }
        return Dsp.decay(x, sr, 0.15, attackSec = 0.004)
    }
}
