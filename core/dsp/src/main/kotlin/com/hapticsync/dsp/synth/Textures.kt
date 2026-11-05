package com.hapticsync.dsp.synth

import com.hapticsync.dsp.Biquad
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Sustained textures: speech, rain, wind, machinery and room tone. */
object Textures {

    private data class Vowel(val f1: Double, val f2: Double, val f3: Double)

    private val VOWELS = listOf(
        Vowel(730.0, 1090.0, 2440.0), // a
        Vowel(530.0, 1840.0, 2480.0), // e
        Vowel(270.0, 2290.0, 3010.0), // i
        Vowel(570.0, 840.0, 2410.0),  // o
        Vowel(300.0, 870.0, 2240.0),  // u
    )

    /**
     * Speech-like babble: a glottal harmonic source at a varying pitch shaped by three formant
     * resonators that move from vowel to vowel at a syllabic rate of ~4–5 Hz, with fricative and
     * plosive consonant noise and pauses between phrases. It reproduces the acoustic signature
     * of dialogue (energy concentrated in 300–3400 Hz, syllabic modulation) without words.
     */
    fun speech(sr: Int, rng: Prng, durationSec: Double, baseF0: Double): FloatArray {
        val n = Dsp.samples(sr, durationSec)
        val out = FloatArray(n)
        val envelope = FloatArray(n)
        val consonant = FloatArray(n)
        val targetVowel = IntArray(n)

        // Plan syllables.
        var t = 0.05
        while (t < durationSec - 0.3) {
            if (rng.nextFloat() < 0.12f) { t += rng.range(0.25f, 0.6f); continue }
            val consonantLen = rng.range(0.025f, 0.07f).toDouble()
            val vowelLen = rng.range(0.1f, 0.22f).toDouble()
            val vowel = (rng.nextFloat() * VOWELS.size).toInt().coerceAtMost(VOWELS.size - 1)
            val fricative = rng.nextFloat() < 0.5f
            val c0 = (t * sr).toInt()
            val v0 = ((t + consonantLen) * sr).toInt()
            val v1 = minOf(n, ((t + consonantLen + vowelLen) * sr).toInt())
            for (i in c0 until minOf(v0, n)) {
                val local = (i - c0).toDouble() / sr
                consonant[i] = if (fricative) 0.35f else (0.6 * exp(-local / 0.008)).toFloat()
            }
            for (i in v0 until v1) {
                val local = (i - v0).toDouble() / sr
                val attack = minOf(1.0, local / 0.02)
                val release = minOf(1.0, (vowelLen - local) / 0.04).coerceAtLeast(0.0)
                envelope[i] = (attack * release).toFloat()
                targetVowel[i] = vowel
            }
            t += consonantLen + vowelLen + rng.range(0.01f, 0.06f)
        }

        val f1 = Biquad(); val f2 = Biquad(); val f3 = Biquad()
        val fricativeFilter = Biquad().apply { setBandPass(sr.toDouble(), 5200.0, 1.5) }
        var phase = 0.0
        var cur1 = VOWELS[0].f1; var cur2 = VOWELS[0].f2; var cur3 = VOWELS[0].f3
        val harmonicsLimit = 3500.0
        for (i in 0 until n) {
            val time = i.toDouble() / sr
            val f0 = baseF0 * (1 + 0.12 * sin(2 * PI * 0.7 * time) + 0.05 * sin(2 * PI * 3.1 * time)) *
                (1 - 0.1 * (time % 3.0) / 3.0)
            if (i % 64 == 0) {
                val v = VOWELS[targetVowel[i]]
                cur1 += (v.f1 - cur1) * 0.15; cur2 += (v.f2 - cur2) * 0.15; cur3 += (v.f3 - cur3) * 0.15
                f1.setBandPass(sr.toDouble(), cur1, 6.0)
                f2.setBandPass(sr.toDouble(), cur2, 8.0)
                f3.setBandPass(sr.toDouble(), cur3, 10.0)
            }
            // Glottal source: harmonic series with a 1/h spectral tilt.
            val count = (harmonicsLimit / f0).toInt().coerceIn(1, 40)
            val s1 = sin(phase); val c1 = cos(phase)
            var prev = 0.0; var curr = s1; var src = s1
            for (h in 2..count) {
                val next = 2 * c1 * curr - prev
                prev = curr; curr = next
                src += next / h
            }
            phase += 2 * PI * f0 / sr
            if (phase > 2 * PI) phase -= 2 * PI
            val voiced = src * envelope[i]
            val formants = f1.process(voiced) * 1.0 + f2.process(voiced) * 0.6 + f3.process(voiced) * 0.3
            val fric = fricativeFilter.process(rng.bipolar().toDouble()) * consonant[i]
            out[i] = (formants * 0.6 + fric).toFloat()
        }
        return Dsp.highPass(out, sr, 120.0)
    }

    /** Rain: dense high-frequency droplet impulses over a soft noise bed. */
    fun rain(sr: Int, rng: Prng, durationSec: Double, intensity: Float = 1f): FloatArray {
        val n = Dsp.samples(sr, durationSec)
        val bed = Dsp.highPass(Dsp.noise(rng, n, 0.08f * intensity), sr, 900.0)
        Dsp.lowPass(bed, sr, 9000.0)
        var t = 0.0
        val rate = 280.0 * intensity
        while (t < durationSec) {
            val freq = rng.range(2500f, 7500f).toDouble()
            val amp = rng.range(0.02f, 0.12f)
            val dn = Dsp.samples(sr, 0.006)
            val start = (t * sr).toInt()
            for (i in 0 until dn) {
                val j = start + i
                if (j >= n) break
                val local = i.toDouble() / sr
                bed[j] += (amp * sin(2 * PI * freq * local) * exp(-local / 0.0015)).toFloat()
            }
            t += -kotlin.math.ln(1.0 - rng.nextFloat().coerceAtMost(0.999f)) / rate
        }
        return bed
    }

    /** Wind: low-passed noise with slow gusting and a faint resonant whistle. */
    fun wind(sr: Int, rng: Prng, durationSec: Double): FloatArray {
        val n = Dsp.samples(sr, durationSec)
        val body = Dsp.lowPass(Dsp.noise(rng, n), sr, 380.0)
        val whistle = Dsp.bandPass(Dsp.noise(rng, n), sr, 650.0, 12.0)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val gust = 0.55 + 0.45 * sin(2 * PI * 0.21 * t + 0.8 * sin(2 * PI * 0.07 * t))
            body[i] = (body[i] * gust * 1.4 + whistle[i] * gust * gust * 0.5).toFloat()
        }
        return body
    }

    /** Machinery: mains hum with harmonics, motor whine and a periodic mechanical clank. */
    fun machinery(sr: Int, rng: Prng, durationSec: Double, clankPeriodSec: Double = 0.6): FloatArray {
        val n = Dsp.samples(sr, durationSec)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            out[i] = (0.22 * sin(2 * PI * 50 * t) + 0.12 * sin(2 * PI * 100 * t) + 0.06 * sin(2 * PI * 150 * t) +
                0.05 * sin(2 * PI * 420 * t) + 0.025 * sin(2 * PI * 840 * t)).toFloat()
        }
        var t = 0.1
        while (t < durationSec - 0.2) {
            val clank = Dsp.bandPass(Dsp.decay(Dsp.noise(rng, Dsp.samples(sr, 0.25)), sr, 0.04, attackSec = 0.001), sr, 1400.0, 3.0)
            Dsp.add(out, clank, 0.7f, (t * sr).toInt())
            t += clankPeriodSec
        }
        return out
    }

    /** Quiet room tone / ambience bed. */
    fun roomTone(sr: Int, rng: Prng, durationSec: Double, level: Float = 0.01f): FloatArray {
        val n = Dsp.samples(sr, durationSec)
        return Dsp.lowPass(Dsp.noise(rng, n, level), sr, 2500.0)
    }
}
