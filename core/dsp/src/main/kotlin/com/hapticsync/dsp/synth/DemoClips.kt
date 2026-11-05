package com.hapticsync.dsp.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Acoustic events placed in a synthesised scene (ground truth for tests and the demo UI). */
enum class SynthLabel {
    ENGINE_IDLE, ENGINE_REV, ACCELERATION, DECELERATION, GEAR_SHIFT, VEHICLE_PASSING,
    KICK, SNARE, HIHAT, CRASH, BASS_DROP, SUSTAINED_BASS,
    EXPLOSION, GUNSHOT, PUNCH, IMPACT, THUNDER, RUMBLE,
    FOOTSTEP, DOOR_SLAM, SPEECH, RAIN, WIND, MACHINERY, CLICK,
}

/** A labelled event or segment in a synthesised scene. */
data class GroundTruth(val label: SynthLabel, val startSec: Double, val endSec: Double = startSec)

/** Built-in demo scenes. */
enum class DemoScenario(val title: String, val description: String) {
    RACING_ONBOARD("Race car on-board", "Idle, rev blips, a full-throttle launch with four upshifts, lift-off and downshifts."),
    TRACKSIDE("Trackside pass-bys", "Three cars passing at speed with Doppler pitch drop and stereo sweep."),
    DRUM_GROOVE("Drum groove", "Kick, snare, hi-hats and crashes over a sub-bass line at 110 BPM."),
    BASS_DROP("Bass drop", "A rising build-up, a pause, then a heavy sub-bass drop with four-on-the-floor kicks."),
    EXPLOSIONS("Explosions & impacts", "Two explosions with debris, heavy impacts, a door slam and a punch combo."),
    FIREFIGHT("Firefight", "Single gunshots, automatic bursts and ricochets."),
    FOOTSTEPS("Footsteps", "Walking, then running, on a hard floor."),
    DIALOGUE("Dialogue scene", "Two speakers talking in a quiet room — should produce little or no vibration."),
    THUNDERSTORM("Thunderstorm", "Steady rain and wind with a distant and a close thunderclap."),
    WORKSHOP("Workshop", "Running machinery with mechanical clanks, door slams and wind."),
    TRAILER_MIX("Trailer mix", "Dialogue, a car pass-by, music, an explosion, a bass drop, gunfire and a final impact."),
    SYNC_CLICKS("Sync test clicks", "A sharp click every second, used to calibrate the synchronisation offset."),
}

/** A rendered scene: stereo samples plus ground-truth labels. */
class SynthClip(
    val scenario: DemoScenario,
    val sampleRate: Int,
    val left: FloatArray,
    val right: FloatArray,
    val truth: List<GroundTruth>,
) {
    val durationSec: Double get() = left.size.toDouble() / sampleRate

    fun interleaved(): FloatArray {
        val out = FloatArray(left.size * 2)
        for (i in left.indices) {
            out[2 * i] = left[i]
            out[2 * i + 1] = right[i]
        }
        return out
    }

    fun times(label: SynthLabel): List<Double> = truth.filter { it.label == label }.map { it.startSec }
}

/**
 * Deterministic generator for the demo scenes. Every scene is rendered from fixed seeds, so the
 * same scene always produces identical audio.
 */
object DemoClips {

    fun render(scenario: DemoScenario, sampleRate: Int = 44_100): SynthClip = when (scenario) {
        DemoScenario.RACING_ONBOARD -> racingOnboard(sampleRate)
        DemoScenario.TRACKSIDE -> trackside(sampleRate)
        DemoScenario.DRUM_GROOVE -> drumGroove(sampleRate)
        DemoScenario.BASS_DROP -> bassDrop(sampleRate)
        DemoScenario.EXPLOSIONS -> explosions(sampleRate)
        DemoScenario.FIREFIGHT -> firefight(sampleRate)
        DemoScenario.FOOTSTEPS -> footsteps(sampleRate)
        DemoScenario.DIALOGUE -> dialogue(sampleRate)
        DemoScenario.THUNDERSTORM -> thunderstorm(sampleRate)
        DemoScenario.WORKSHOP -> workshop(sampleRate)
        DemoScenario.TRAILER_MIX -> trailerMix(sampleRate)
        DemoScenario.SYNC_CLICKS -> syncClicks(sampleRate)
    }

    // ------------------------------------------------------------------------------------------
    // Vehicles

    /** Upshift times for [racingOnboard]. */
    val RACING_UPSHIFTS = doubleArrayOf(7.3, 8.9, 10.9, 13.4)

    private fun racingOnboard(sr: Int): SynthClip {
        val duration = 22.0
        val buffer = SynthBuffer(sr, duration)
        val truth = mutableListOf<GroundTruth>()
        val shifts = RACING_UPSHIFTS
        val rpm = EngineSynth.curve(
            0.0 to 4500.0, 2.0 to 4500.0, 2.22 to 11500.0, 2.8 to 4700.0, 3.8 to 4600.0,
            4.05 to 12500.0, 4.6 to 5000.0, 5.5 to 5000.0, 6.0 to 6200.0,
            shifts[0] to 11800.0, shifts[0] + 0.05 to 9300.0,
            shifts[1] to 11900.0, shifts[1] + 0.05 to 9600.0,
            shifts[2] to 12000.0, shifts[2] + 0.05 to 9800.0,
            shifts[3] to 12100.0, shifts[3] + 0.05 to 10000.0,
            16.0 to 11800.0, 16.8 to 8200.0,
            17.0 to 9700.0, 17.6 to 7600.0, 17.8 to 9100.0, 18.4 to 6800.0, 18.5 to 8200.0, 19.0 to 5200.0,
            22.0 to 5000.0,
        )
        val throttle = { t: Double ->
            when {
                t < 2.0 -> 0.15
                t < 2.25 -> 1.0
                t < 3.8 -> 0.15
                t < 4.08 -> 1.0
                t < 5.5 -> 0.18
                t < 16.0 -> if (shifts.any { t >= it && t < it + 0.035 }) 0.25 else 1.0
                t < 19.0 -> if (t in 17.0..17.06 || t in 17.8..17.86 || t in 18.5..18.56) 0.7 else 0.0
                else -> 0.15
            }
        }
        val engine = EngineSynth(sr, cylinders = 6, seed = 11).render(duration, rpm, throttle)
        buffer.mix(engine, 0.0, 0.9f, 0f)
        val rng = Prng(5)
        // Shift "crack" and rising road/wind noise with speed.
        for (s in shifts) {
            val crack = Dsp.highPass(Dsp.decay(Dsp.noise(rng, Dsp.samples(sr, 0.05)), sr, 0.008, attackSec = 0.0005), sr, 600.0)
            buffer.mix(crack, s, 0.35f, 0f)
        }
        val road = Dsp.lowPass(Dsp.noise(rng, buffer.frames), sr, 700.0)
        for (i in road.indices) {
            val t = i.toDouble() / sr
            val speed = ((t - 5.5) / 10.5).coerceIn(0.0, 1.0) * (if (t > 16.0) exp(-(t - 16.0) / 4.0) else 1.0)
            road[i] *= (0.05 + 0.25 * speed).toFloat()
        }
        buffer.mix(road, 0.0, 1f, 0f)
        buffer.normalize(-1f)

        truth += GroundTruth(SynthLabel.ENGINE_IDLE, 0.0, 2.0)
        truth += GroundTruth(SynthLabel.ENGINE_REV, 2.0, 2.8)
        truth += GroundTruth(SynthLabel.ENGINE_IDLE, 2.8, 3.8)
        truth += GroundTruth(SynthLabel.ENGINE_REV, 3.8, 4.6)
        truth += GroundTruth(SynthLabel.ENGINE_IDLE, 4.6, 5.5)
        truth += GroundTruth(SynthLabel.ACCELERATION, 6.0, 16.0)
        for (s in shifts) truth += GroundTruth(SynthLabel.GEAR_SHIFT, s)
        truth += GroundTruth(SynthLabel.DECELERATION, 16.0, 19.0)
        truth += GroundTruth(SynthLabel.ENGINE_IDLE, 19.0, 22.0)
        return SynthClip(DemoScenario.RACING_ONBOARD, sr, buffer.left, buffer.right, truth)
    }

    private fun passBy(
        sr: Int,
        buffer: SynthBuffer,
        closestSec: Double,
        speedMps: Double,
        distanceM: Double,
        rpm: Double,
        seed: Int,
        gain: Float,
    ) {
        val c = 343.0
        val window = 3.2
        val start = closestSec - window / 2
        val doppler = { t: Double ->
            val x = speedMps * (t - window / 2) // position along the track relative to closest point
            val r = sqrt(x * x + distanceM * distanceM)
            val radial = speedMps * x / r // positive when moving away
            c / (c + radial)
        }
        val amp = { t: Double ->
            val x = speedMps * (t - window / 2)
            val r = sqrt(x * x + distanceM * distanceM)
            (distanceM / r).let { it * it }.coerceAtMost(1.0) * sin(PI * (t / window)).coerceAtLeast(0.0)
        }
        val engine = EngineSynth(sr, cylinders = 6, seed = seed).render(
            window,
            rpmAt = { t -> rpm + 400 * sin(2 * PI * 0.4 * t) },
            throttleAt = { 1.0 },
            dopplerAt = doppler,
            gainAt = amp,
        )
        buffer.mixPanned(engine, start, gain) { t ->
            val x = speedMps * (t - start - window / 2)
            (2 / PI * atan2(x, distanceM)).toFloat()
        }
    }

    private fun trackside(sr: Int): SynthClip {
        val buffer = SynthBuffer(sr, 16.0)
        val rng = Prng(21)
        buffer.mix(Textures.wind(sr, rng, 16.0), 0.0, 0.08f)
        val passes = doubleArrayOf(3.0, 8.0, 12.5)
        passBy(sr, buffer, passes[0], 75.0, 9.0, 11000.0, 31, 1f)
        passBy(sr, buffer, passes[1], 70.0, 7.0, 11500.0, 32, 1f)
        passBy(sr, buffer, passes[2], 80.0, 10.0, 10800.0, 33, 0.9f)
        buffer.normalize(-1f)
        val truth = passes.map { GroundTruth(SynthLabel.VEHICLE_PASSING, it) }
        return SynthClip(DemoScenario.TRACKSIDE, sr, buffer.left, buffer.right, truth)
    }

    // ------------------------------------------------------------------------------------------
    // Music

    private fun drumGroove(sr: Int): SynthClip {
        val bpm = 110.0
        val beat = 60.0 / bpm
        val bars = 7
        val duration = 0.5 + bars * 4 * beat + 1.5
        val buffer = SynthBuffer(sr, duration)
        val rng = Prng(41)
        val truth = mutableListOf<GroundTruth>()
        val kick = Voices.kick(sr, rng)
        val snare = Voices.snare(sr, rng)
        val hat = Voices.hiHat(sr, rng)
        val openHat = Voices.hiHat(sr, rng, open = true)
        val crash = Voices.crash(sr, rng)
        val start = 0.5
        for (bar in 0 until bars) {
            val barStart = start + bar * 4 * beat
            val kicks = mutableListOf(0.0, 2.0)
            if (bar % 2 == 1) kicks += 2.5
            for (k in kicks) {
                buffer.mix(kick, barStart + k * beat, 1.0f)
                truth += GroundTruth(SynthLabel.KICK, barStart + k * beat)
                val note = if (bar % 4 < 2) 55.0 else 41.2
                buffer.mix(Voices.bassNote(sr, note, beat * 0.9), barStart + k * beat, 0.45f)
            }
            for (s in listOf(1.0, 3.0)) {
                buffer.mix(snare, barStart + s * beat, 0.8f, 0.05f)
                truth += GroundTruth(SynthLabel.SNARE, barStart + s * beat)
            }
            for (e in 0 until 8) {
                val t = barStart + e * beat / 2
                if (e == 7) buffer.mix(openHat, t, 0.22f, 0.3f) else buffer.mix(hat, t, 0.25f, 0.3f)
                truth += GroundTruth(SynthLabel.HIHAT, t)
            }
            if (bar == 0 || bar == 4) {
                buffer.mix(crash, barStart, 0.45f, -0.3f)
                truth += GroundTruth(SynthLabel.CRASH, barStart)
            }
        }
        buffer.normalize(-1f)
        return SynthClip(DemoScenario.DRUM_GROOVE, sr, buffer.left, buffer.right, truth)
    }

    private fun bassDrop(sr: Int): SynthClip {
        val duration = 14.0
        val buffer = SynthBuffer(sr, duration)
        val rng = Prng(51)
        val truth = mutableListOf<GroundTruth>()
        buffer.mix(Voices.riser(sr, rng, 4.0), 0.0, 0.5f)
        val drop = 4.5
        val beat = 60.0 / 128.0
        buffer.mix(Voices.bassNote(sr, 43.65, 8.0, drive = 3f), drop, 0.6f)
        val kick = Voices.kick(sr, rng, decaySec = 0.32)
        val clap = Voices.snare(sr, rng)
        var t = drop
        var i = 0
        while (t < drop + 8.0) {
            buffer.mix(kick, t, 1f)
            truth += GroundTruth(SynthLabel.KICK, t)
            if (i % 2 == 1) {
                buffer.mix(clap, t, 0.5f, 0.1f)
                truth += GroundTruth(SynthLabel.SNARE, t)
            }
            t += beat
            i++
        }
        buffer.normalize(-1f)
        truth += GroundTruth(SynthLabel.BASS_DROP, drop)
        truth += GroundTruth(SynthLabel.SUSTAINED_BASS, drop, drop + 8.0)
        return SynthClip(DemoScenario.BASS_DROP, sr, buffer.left, buffer.right, truth)
    }

    // ------------------------------------------------------------------------------------------
    // Cinematic

    private fun explosions(sr: Int): SynthClip {
        val duration = 18.0
        val buffer = SynthBuffer(sr, duration)
        val rng = Prng(61)
        val truth = mutableListOf<GroundTruth>()
        buffer.mix(Textures.wind(sr, rng, duration), 0.0, 0.05f)
        buffer.mix(Voices.explosion(sr, rng, 1.0), 1.5, 0.8f, -0.1f); truth += GroundTruth(SynthLabel.EXPLOSION, 1.5)
        buffer.mix(Voices.impact(sr, rng), 5.0, 0.7f, 0.3f); truth += GroundTruth(SynthLabel.IMPACT, 5.0)
        buffer.mix(Voices.impact(sr, rng), 6.2, 0.6f, -0.2f); truth += GroundTruth(SynthLabel.IMPACT, 6.2)
        buffer.mix(Voices.doorSlam(sr, rng), 8.0, 0.55f, 0.4f); truth += GroundTruth(SynthLabel.DOOR_SLAM, 8.0)
        buffer.mix(Voices.explosion(sr, rng, 1.6), 10.5, 1f, 0f); truth += GroundTruth(SynthLabel.EXPLOSION, 10.5)
        for (p in listOf(14.0, 14.4, 14.9)) {
            buffer.mix(Voices.punch(sr, rng), p, 0.6f, 0.1f)
            truth += GroundTruth(SynthLabel.PUNCH, p)
        }
        buffer.normalize(-1f)
        return SynthClip(DemoScenario.EXPLOSIONS, sr, buffer.left, buffer.right, truth)
    }

    private fun firefight(sr: Int): SynthClip {
        val duration = 14.0
        val buffer = SynthBuffer(sr, duration)
        val rng = Prng(71)
        val truth = mutableListOf<GroundTruth>()
        buffer.mix(Textures.roomTone(sr, rng, duration, 0.02f), 0.0, 1f)
        val shots = mutableListOf(1.0, 2.2, 3.1, 8.0, 8.4)
        for (k in 0 until 11) shots += 5.0 + k * 0.09
        for (k in 0 until 9) shots += 10.0 + k * 0.095
        for ((idx, s) in shots.withIndex()) {
            buffer.mix(Voices.gunshot(sr, rng), s, 0.8f, if (idx % 2 == 0) -0.2f else 0.25f)
            truth += GroundTruth(SynthLabel.GUNSHOT, s)
        }
        buffer.mix(Voices.ricochet(sr), 6.4, 0.25f, 0.6f)
        buffer.normalize(-1f)
        return SynthClip(DemoScenario.FIREFIGHT, sr, buffer.left, buffer.right, truth.sortedBy { it.startSec })
    }

    // ------------------------------------------------------------------------------------------
    // Environment & dialogue

    private fun footsteps(sr: Int): SynthClip {
        val duration = 14.0
        val buffer = SynthBuffer(sr, duration)
        val rng = Prng(81)
        val truth = mutableListOf<GroundTruth>()
        buffer.mix(Textures.roomTone(sr, rng, duration, 0.015f), 0.0, 1f)
        var t = 0.5
        var step = 0
        while (t < 7.0) {
            buffer.mix(Voices.footstep(sr, rng, if (step % 2 == 0) 1f else 0.85f), t, 0.7f, if (step % 2 == 0) -0.15f else 0.15f)
            truth += GroundTruth(SynthLabel.FOOTSTEP, t)
            t += 0.55 + 0.02 * rng.bipolar()
            step++
        }
        t = 8.0
        while (t < 13.0) {
            buffer.mix(Voices.footstep(sr, rng, 1.2f), t, 0.85f, if (step % 2 == 0) -0.15f else 0.15f)
            truth += GroundTruth(SynthLabel.FOOTSTEP, t)
            t += 0.33 + 0.015 * rng.bipolar()
            step++
        }
        buffer.normalize(-3f)
        return SynthClip(DemoScenario.FOOTSTEPS, sr, buffer.left, buffer.right, truth)
    }

    private fun dialogue(sr: Int): SynthClip {
        val duration = 16.0
        val buffer = SynthBuffer(sr, duration)
        val rng = Prng(91)
        buffer.mix(Textures.roomTone(sr, rng, duration, 0.01f), 0.0, 1f)
        buffer.mix(Textures.speech(sr, Prng(92), 4.7, 115.0), 0.3, 0.9f, -0.15f)
        buffer.mix(Textures.speech(sr, Prng(93), 4.5, 205.0), 5.5, 0.85f, 0.2f)
        buffer.mix(Textures.speech(sr, Prng(94), 5.0, 120.0), 10.5, 0.9f, -0.15f)
        buffer.normalize(-3f)
        val truth = listOf(
            GroundTruth(SynthLabel.SPEECH, 0.3, 5.0),
            GroundTruth(SynthLabel.SPEECH, 5.5, 10.0),
            GroundTruth(SynthLabel.SPEECH, 10.5, 15.5),
        )
        return SynthClip(DemoScenario.DIALOGUE, sr, buffer.left, buffer.right, truth)
    }

    private fun thunderstorm(sr: Int): SynthClip {
        val duration = 20.0
        val buffer = SynthBuffer(sr, duration)
        val rng = Prng(101)
        buffer.mix(Textures.rain(sr, rng, duration, 1f), 0.0, 0.8f, 0f)
        buffer.mix(Textures.wind(sr, rng, duration), 0.0, 0.25f)
        buffer.mix(Voices.thunder(sr, rng, close = false), 3.0, 0.6f, -0.3f)
        buffer.mix(Voices.thunder(sr, rng, close = true), 11.0, 1f, 0.2f)
        buffer.normalize(-1f)
        val truth = listOf(
            GroundTruth(SynthLabel.RAIN, 0.0, duration),
            GroundTruth(SynthLabel.WIND, 0.0, duration),
            GroundTruth(SynthLabel.THUNDER, 3.0),
            GroundTruth(SynthLabel.THUNDER, 11.0),
        )
        return SynthClip(DemoScenario.THUNDERSTORM, sr, buffer.left, buffer.right, truth)
    }

    private fun workshop(sr: Int): SynthClip {
        val duration = 16.0
        val buffer = SynthBuffer(sr, duration)
        val rng = Prng(111)
        buffer.mix(Textures.machinery(sr, rng, 10.0), 0.0, 0.7f, -0.2f)
        buffer.mix(Voices.doorSlam(sr, rng), 11.0, 0.8f, 0.3f)
        buffer.mix(Voices.doorSlam(sr, rng), 13.0, 0.7f, -0.3f)
        buffer.mix(Textures.wind(sr, rng, 4.0), 12.0, 0.4f)
        buffer.normalize(-2f)
        val truth = listOf(
            GroundTruth(SynthLabel.MACHINERY, 0.0, 10.0),
            GroundTruth(SynthLabel.DOOR_SLAM, 11.0),
            GroundTruth(SynthLabel.DOOR_SLAM, 13.0),
            GroundTruth(SynthLabel.WIND, 12.0, 16.0),
        )
        return SynthClip(DemoScenario.WORKSHOP, sr, buffer.left, buffer.right, truth)
    }

    private fun trailerMix(sr: Int): SynthClip {
        val duration = 26.0
        val buffer = SynthBuffer(sr, duration)
        val rng = Prng(121)
        val truth = mutableListOf<GroundTruth>()
        buffer.mix(Textures.roomTone(sr, rng, duration, 0.012f), 0.0, 1f)
        buffer.mix(Textures.speech(sr, Prng(122), 3.4, 118.0), 0.2, 0.8f)
        truth += GroundTruth(SynthLabel.SPEECH, 0.2, 3.6)
        passBy(sr, buffer, 4.6, 72.0, 8.0, 11200.0, 123, 0.9f)
        truth += GroundTruth(SynthLabel.VEHICLE_PASSING, 4.6)
        val beat = 60.0 / 120.0
        val kick = Voices.kick(sr, rng)
        val snare = Voices.snare(sr, rng)
        var t = 6.5
        var i = 0
        while (t < 12.0) {
            if (i % 2 == 0) { buffer.mix(kick, t, 0.8f); truth += GroundTruth(SynthLabel.KICK, t) }
            else { buffer.mix(snare, t, 0.6f); truth += GroundTruth(SynthLabel.SNARE, t) }
            t += beat
            i++
        }
        buffer.mix(Voices.explosion(sr, rng, 1.2), 9.25, 0.9f); truth += GroundTruth(SynthLabel.EXPLOSION, 9.25)
        buffer.mix(Voices.riser(sr, rng, 2.5), 12.5, 0.4f)
        buffer.mix(Voices.bassNote(sr, 41.2, 5.0, drive = 3f), 15.0, 0.6f)
        truth += GroundTruth(SynthLabel.BASS_DROP, 15.0)
        t = 15.0
        while (t < 20.0) { buffer.mix(kick, t, 0.9f); truth += GroundTruth(SynthLabel.KICK, t); t += beat }
        for (s in listOf(21.0, 21.35, 21.6)) { buffer.mix(Voices.gunshot(sr, rng), s, 0.8f, 0.2f); truth += GroundTruth(SynthLabel.GUNSHOT, s) }
        buffer.mix(Voices.impact(sr, rng), 23.5, 1f); truth += GroundTruth(SynthLabel.IMPACT, 23.5)
        buffer.mix(Voices.thunder(sr, rng, close = false), 23.6, 0.5f)
        buffer.normalize(-1f)
        return SynthClip(DemoScenario.TRAILER_MIX, sr, buffer.left, buffer.right, truth.sortedBy { it.startSec })
    }

    private fun syncClicks(sr: Int): SynthClip {
        val duration = 30.0
        val buffer = SynthBuffer(sr, duration)
        val click = Voices.syncClick(sr)
        val truth = mutableListOf<GroundTruth>()
        var t = 1.0
        while (t < duration - 0.5) {
            buffer.mix(click, t, 1f)
            truth += GroundTruth(SynthLabel.CLICK, t)
            t += 1.0
        }
        return SynthClip(DemoScenario.SYNC_CLICKS, sr, buffer.left, buffer.right, truth)
    }

    /** Peak absolute sample of a buffer (test helper). */
    fun peak(samples: FloatArray): Float {
        var p = 0f
        for (s in samples) p = maxOf(p, abs(s))
        return p
    }
}
