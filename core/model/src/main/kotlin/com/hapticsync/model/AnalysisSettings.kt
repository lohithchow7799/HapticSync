package com.hapticsync.model

import kotlinx.serialization.Serializable

/** How much analysis work the engine performs. */
@Serializable
enum class AnalysisQuality(val label: String) {
    /** Starts at full quality and degrades gracefully under CPU pressure. */
    AUTO("Automatic"),

    /** Full analysis on every frame. */
    HIGH("High"),

    /** Spectral analysis on every second frame. Lower CPU use. */
    BALANCED("Balanced"),

    /** Spectral analysis on every fourth frame. Lowest CPU use. */
    LOW("Battery saver"),
}

/**
 * Parameters of the audio-analysis front end. None of them affect what the user hears.
 *
 * @property windowMs analysis window length (20–40 ms recommended; 16–64 ms allowed).
 * @property hopMs hop between successive windows; windows overlap by `1 − hop / window`.
 * @property rangeLowHz lower limit of the frequency range considered by the analysis.
 * @property rangeHighHz upper limit of the frequency range considered by the analysis.
 * @property decisionDelayMs how long the classifier may observe an onset (attack and early decay)
 *   before deciding its category. The audio sink's look-ahead lets this happen *before* the sound
 *   is heard; the engine shortens the delay automatically when the look-ahead is small.
 * @property adaptiveNormalization normalises detection to the programme's loudness so that quiet
 *   and loud videos behave similarly.
 * @property noiseGate suppresses energy that stays close to each band's adaptive noise floor.
 * @property quality CPU/quality trade-off.
 */
@Serializable
data class AnalysisSettings(
    val windowMs: Float = 32f,
    val hopMs: Float = 10f,
    val rangeLowHz: Float = 20f,
    val rangeHighHz: Float = 12_000f,
    val decisionDelayMs: Float = 90f,
    val adaptiveNormalization: Boolean = true,
    val noiseGate: Boolean = true,
    val quality: AnalysisQuality = AnalysisQuality.AUTO,
) {
    fun validated(): AnalysisSettings {
        val window = windowMs.finiteOr(32f).coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
        val hop = hopMs.finiteOr(10f).coerceIn(MIN_HOP_MS, minOf(MAX_HOP_MS, window))
        val low = rangeLowHz.finiteOr(20f).coerceIn(BandId.MIN_HZ, 1000f)
        val high = rangeHighHz.finiteOr(12_000f).coerceIn(low * 2f, BandId.MAX_HZ)
        return copy(
            windowMs = window,
            hopMs = hop,
            rangeLowHz = low,
            rangeHighHz = high,
            decisionDelayMs = decisionDelayMs.finiteOr(90f).coerceIn(0f, MAX_DECISION_DELAY_MS),
        )
    }

    companion object {
        const val MIN_WINDOW_MS = 16f
        const val MAX_WINDOW_MS = 64f
        const val MIN_HOP_MS = 5f
        const val MAX_HOP_MS = 20f
        const val MAX_DECISION_DELAY_MS = 160f
    }
}
