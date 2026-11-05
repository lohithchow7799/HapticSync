package com.hapticsync.haptics

import com.hapticsync.analysis.AnalysisFrame
import com.hapticsync.analysis.SoundEvent
import com.hapticsync.dsp.onePoleCoefficient
import com.hapticsync.model.BandId
import com.hapticsync.model.EnvelopeShape
import com.hapticsync.model.SoundCategory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Translates analysis results into device-independent haptics:
 *
 * - [mapEvent]: a classified [SoundEvent] becomes a [TransientEffect] shaped by its category's
 *   [TransientTemplate], the event's strength, band balance and measured decay, and every
 *   relevant user setting (category intensity/duration/priority, band pulse strength, master
 *   intensity, attack, release, envelope shape, pulse repetition, isolation).
 * - [mapFrame]: the per-hop continuous levels become one [PlanSample] of the continuous texture
 *   layer (engine rumble, revs, cinematic rumble…), with release smoothing, rhythmic pulse
 *   modulation, dialogue suppression and fatigue protection.
 *
 * Amplitudes produced here are perceptual levels in [0, 1]; device calibration is applied by the
 * [HapticRenderer]. Used from the analysis thread only.
 */
class HapticMapper(config: HapticConfig, private val hopDurationMs: Float) {

    @Volatile
    var config: HapticConfig = config

    private var smoothed = 0f
    private var phase = 0.0
    private var fatigueAverage = 0f
    private val fatigueCoef = onePoleCoefficient(8000f, 1000f / hopDurationMs)
    private var lastCategory = -1

    /** Fatigue-protection gain currently applied to the continuous layer (1 = none). */
    var fatigueGain = 1f
        private set

    fun reset() {
        smoothed = 0f
        phase = 0.0
        fatigueAverage = 0f
        fatigueGain = 1f
        lastCategory = -1
    }

    /** Maps an event, or returns null when settings or isolation suppress it. */
    fun mapEvent(event: SoundEvent, epoch: Int): TransientEffect? {
        val c = config
        if (!c.transientLayer) return null
        val category = event.category
        val template: TransientTemplate
        var amplitude: Float
        if (c.isolationEnabled && c.bandIsolation) {
            // Band isolation: any event carried by the isolated bands is felt as a band pulse.
            var isolatedShare = 0f
            var pulse = 0f
            var lowShare = 0f
            for (b in 0 until BandId.COUNT) {
                if (!c.isolationBands[b]) continue
                isolatedShare += event.bandShares[b]
                pulse += event.bandShares[b] * c.bandPulseStrength[b]
                if (b <= BandId.BASS.ordinal) lowShare += event.bandShares[b]
            }
            if (isolatedShare < 0.35f) return null
            val pulseStrength = pulse / isolatedShare
            val deep = lowShare / isolatedShare
            template = TransientTemplate(
                attackMs = 1.5f,
                holdMs = 12f,
                decayMs = 30f + 50f * deep,
                intensity = 0.9f,
                sharpness = 0.8f - 0.6f * deep,
                primitive = if (deep > 0.5f) HapticPrimitive.THUD else HapticPrimitive.CLICK,
            )
            amplitude = event.strength.pow(0.8f) * template.intensity * isolatedShare.coerceAtMost(1f) * pulseStrength / 0.7f
        } else {
            if (!c.allows(category)) return null
            template = TransientTemplate.forCategory(category)
            amplitude = event.strength.pow(0.8f) * template.intensity * c.categoryIntensity[category.ordinal] * bandPulseWeight(event)
        }
        amplitude = (amplitude * c.masterIntensity).coerceIn(0f, 1f)
        if (amplitude < MIN_EVENT_AMPLITUDE) return null

        val curve = buildCurve(template, amplitude, event, category)
        val sharpness = (0.7f * template.sharpness + 0.3f * event.sharpness).coerceIn(0f, 1f)
        return TransientEffect(
            timeUs = event.timeUs,
            category = category,
            curve = HapticCurve(CURVE_STEP_MS, curve, sharpness),
            primitive = template.primitive,
            primitiveScale = (template.primitiveScale * amplitude).coerceIn(0f, 1f),
            priority = c.categoryPriority[category.ordinal],
            latenessToleranceUs = event.latenessToleranceUs,
            epoch = epoch,
        )
    }

    /** Weight of the event's band balance by the per-band pulse strengths (1 at default settings). */
    private fun bandPulseWeight(event: SoundEvent): Float {
        val c = config
        var weighted = 0f
        var total = 0f
        for (b in 0 until BandId.COUNT) {
            if (!c.bandEnabled[b]) continue
            weighted += event.bandShares[b] * c.bandPulseStrength[b]
            total += event.bandShares[b]
        }
        return if (total > 0f) (weighted / total / DEFAULT_PULSE_STRENGTH).coerceIn(0f, 1.45f) else 0f
    }

    private fun buildCurve(t: TransientTemplate, amplitude: Float, event: SoundEvent, category: SoundCategory): FloatArray {
        val c = config
        val timeScale = c.categoryDuration[category.ordinal] * c.pulseDurationScale
        val releaseScale = (c.releaseMs / DEFAULT_RELEASE_MS).coerceIn(0.3f, 3f)
        var decayScale = releaseScale
        if (t.followsAudioDecay) {
            decayScale *= (event.durationHintMs / max(1f, t.decayMs + t.holdMs)).coerceIn(0.5f, 1.8f)
        }
        val attack = max(0.5f, t.attackMs + (c.attackMs - DEFAULT_ATTACK_MS)) * timeScale
        val hold = t.holdMs * timeScale
        val decay = t.decayMs * timeScale * decayScale
        val repeats = (t.repeats * c.pulseRepetition).roundToInt()
        val mainLength = attack + hold + decay
        // Aftershocks happen inside the decay; the curve ends with whichever finishes last.
        val total = if (repeats > 0) max(mainLength, attack + hold + repeats * t.repeatGapMs * timeScale + AFTERSHOCK_MS) else mainLength
        val steps = max(1, (total / CURVE_STEP_MS).roundToInt())
        val out = FloatArray(steps)
        for (i in 0 until steps) {
            val time = (i + 0.5f) * CURVE_STEP_MS
            out[i] = amplitude * envelope(time, attack, hold, decay, c.envelope)
        }
        // Aftershocks (e.g. an explosion's secondary blasts), scaled by "pulse repetition".
        var strength = amplitude
        for (k in 1..repeats) {
            strength *= t.repeatDecay
            val start = attack + hold + k * t.repeatGapMs * timeScale
            for (i in 0 until steps) {
                val local = (i + 0.5f) * CURVE_STEP_MS - start
                if (local < 0f) continue
                val a = strength * envelope(local, 2f, 15f, AFTERSHOCK_MS - 17f, c.envelope)
                if (a > out[i]) out[i] = a
            }
        }
        return out
    }

    /** Envelope value at [time] ms: linear attack, hold, then the configured decay shape. */
    private fun envelope(time: Float, attack: Float, hold: Float, decay: Float, shape: EnvelopeShape): Float {
        if (time < attack) return time / attack
        if (time < attack + hold) return 1f
        val x = ((time - attack - hold) / max(decay, 1f)).coerceIn(0f, 1f)
        return when (shape) {
            EnvelopeShape.LINEAR -> 1f - x
            EnvelopeShape.EXPONENTIAL -> (exp(-4.6f * x) - 0.01f * x).coerceAtLeast(0f)
            EnvelopeShape.SHARP -> 1f - x * x * x
            EnvelopeShape.SMOOTH -> (0.5f + 0.5f * cos(PI.toFloat() * x))
        }
    }

    /** Computes the continuous-layer sample for one analysis hop. */
    fun mapFrame(frame: AnalysisFrame, epoch: Int, out: PlanSample) {
        val c = config
        var best = 0f
        var bestCategory = -1
        var sum = 0f
        var sharpness = 0.3f
        var pulseHz = 0f
        var depth = 0f
        val rpm = frame.rpmNormalized

        if (c.continuousLayer) {
            if (c.isolationEnabled && c.bandIsolation) {
                for (b in 0 until BandId.COUNT) {
                    if (!c.isolationBands[b]) continue
                    val a = frame.bandLevel[b] * c.bandIntensity[b] * 0.6f * c.continuousGain
                    sum += a
                    if (a > best) {
                        best = a
                        bestCategory = SoundCategory.GENERAL.ordinal
                        sharpness = 0.1f + 0.15f * b
                    }
                }
            } else {
                val bandWeight = bandIntensityWeight(frame)
                for (category in TextureTemplate.CONTINUOUS_CATEGORIES) {
                    val level = frame.continuous[category.ordinal]
                    if (level < MIN_TEXTURE_LEVEL || !c.allows(category)) continue
                    val tpl = TextureTemplate.forCategory(category)
                    val engine = category in TextureTemplate.ENGINE_CATEGORIES
                    // Engine texture follows the engine speed: revs build up, idle stays gentle.
                    val speedFactor = if (engine) 0.45f + 0.55f * rpm else 1f
                    val a = level * tpl.intensity * speedFactor * c.categoryIntensity[category.ordinal] * c.continuousGain * bandWeight
                    sum += a
                    if (a > best) {
                        best = a
                        bestCategory = category.ordinal
                        sharpness = if (engine) (tpl.sharpness + 0.4f * rpm).coerceAtMost(1f) else tpl.sharpness
                        pulseHz = if (engine) tpl.pulseHz * (0.7f + 0.8f * rpm) else tpl.pulseHz
                        depth = if (engine) tpl.pulseDepth * (1f - 0.6f * rpm) else tpl.pulseDepth
                    }
                }
            }
        }
        var target = best + 0.3f * (sum - best)
        if (bestCategory != SoundCategory.DIALOGUE.ordinal) target *= 1f - 0.7f * frame.speechLikelihood
        target = (target * c.masterIntensity).coerceIn(0f, 1f)

        // Release smoothing: fast attack, user-controlled release.
        val coef = onePoleCoefficient(if (target > smoothed) CONTINUOUS_ATTACK_MS else c.releaseMs, 1000f / hopDurationMs)
        smoothed += (target - smoothed) * coef

        // Fatigue protection: a slow average of the output; long, strong rumble is eased off.
        fatigueAverage += (smoothed - fatigueAverage) * fatigueCoef
        fatigueGain = if (c.fatigueProtection) 1f - 0.5f * ((fatigueAverage - FATIGUE_START) / FATIGUE_RANGE).coerceIn(0f, 1f) else 1f

        // Rhythmic pulsing (engine firing feel, machinery) in media time, so it is seamless.
        var modulation = 1f
        val rate = min(pulseHz, MAX_PULSE_HZ)
        if (rate > 0f && depth > 0f) {
            phase += rate * hopDurationMs / 1000.0
            phase -= floor(phase)
            val pulseDepth = (depth * c.pulseRepetition.coerceIn(0f, 1.5f)).coerceIn(0f, 1f)
            modulation = if (phase < 0.5) 1f else 1f - pulseDepth
        }

        out.timeUs = frame.timeUs
        out.amplitude = (smoothed * fatigueGain * modulation).coerceIn(0f, 1f)
        out.sharpness = sharpness
        out.category = if (out.amplitude > 0.01f) bestCategory else -1
        out.epoch = epoch
        lastCategory = out.category
    }

    /** Weight of the frame's band balance by the per-band haptic intensities (1 at defaults). */
    private fun bandIntensityWeight(frame: AnalysisFrame): Float {
        val c = config
        var weighted = 0f
        var total = 0f
        for (b in 0 until BandId.COUNT) {
            if (!c.bandEnabled[b]) continue
            weighted += frame.bandShare[b] * c.bandIntensity[b]
            total += frame.bandShare[b]
        }
        return if (total > 0f) (weighted / total / DEFAULT_BAND_INTENSITY).coerceIn(0f, 1.45f) else 0f
    }

    companion object {
        /** Resolution of transient curves (media milliseconds). */
        const val CURVE_STEP_MS = 5f
        private const val MIN_EVENT_AMPLITUDE = 0.02f
        private const val MIN_TEXTURE_LEVEL = 0.02f
        private const val CONTINUOUS_ATTACK_MS = 20f
        private const val MAX_PULSE_HZ = 25f
        private const val AFTERSHOCK_MS = 77f

        /** Long-term average output above which fatigue protection starts easing off. */
        private const val FATIGUE_START = 0.35f
        private const val FATIGUE_RANGE = 0.4f
        private const val DEFAULT_PULSE_STRENGTH = 0.7f
        private const val DEFAULT_BAND_INTENSITY = 0.7f
        private const val DEFAULT_RELEASE_MS = 140f
        private const val DEFAULT_ATTACK_MS = 4f
    }
}
