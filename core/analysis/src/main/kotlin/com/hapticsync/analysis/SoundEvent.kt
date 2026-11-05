package com.hapticsync.analysis

import com.hapticsync.model.BandId
import com.hapticsync.model.SoundCategory

/**
 * A discrete, classified sound event.
 *
 * @property category inferred category.
 * @property timeUs media time of the event's onset, in the audio sink's time base.
 * @property strength loudness of the event in [0, 1] after normalisation.
 * @property confidence evidence score of the winning category in [0, 1].
 * @property sharpness how abrupt and bright the event is in [0, 1] (fast attack, high content).
 * @property attackMs time from onset to peak.
 * @property decayDbPerSecond measured decay rate after the peak (positive = decaying).
 * @property durationHintMs estimated perceptual duration of the sound.
 * @property dominantBand band holding the largest share of the event's energy.
 * @property bandShares energy share of each band at the event peak (sums to ≈1).
 * @property latenessToleranceUs how late the event may still be rendered (slow events such as a
 *   pass-by are only recognised after their peak).
 */
class SoundEvent(
    val category: SoundCategory,
    val timeUs: Long,
    val strength: Float,
    val confidence: Float,
    val sharpness: Float,
    val attackMs: Float,
    val decayDbPerSecond: Float,
    val durationHintMs: Float,
    val dominantBand: BandId,
    val bandShares: FloatArray,
    val latenessToleranceUs: Long = 0L,
) {
    /** Share of energy in the sub-bass and bass bands. */
    val lowFrequencyShare: Float get() = bandShares[BandId.SUB_BASS.ordinal] + bandShares[BandId.BASS.ordinal]

    override fun toString(): String =
        "SoundEvent(${category.name} @${timeUs / 1000}ms s=${"%.2f".format(strength)} c=${"%.2f".format(confidence)} " +
            "attack=${"%.0f".format(attackMs)}ms decay=${"%.0f".format(decayDbPerSecond)}dB/s band=${dominantBand.name})"
}

/** Receives events from the analyser on the analysis thread. */
fun interface SoundEventSink {
    fun onSoundEvent(event: SoundEvent)
}
