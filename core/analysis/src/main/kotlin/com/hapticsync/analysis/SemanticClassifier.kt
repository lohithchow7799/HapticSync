package com.hapticsync.analysis

import com.hapticsync.model.SoundCategory

/**
 * Optional on-device model that recognises *what* a sound is (for example a YAMNet-style
 * AudioSet classifier), complementing the DSP classifier, which recognises *how* it sounds.
 *
 * The engine calls [classify] on a background thread at a low rate (about twice per second, and
 * only when there is audible sound), never per analysis frame. The result is a vector of prior
 * multipliers per [SoundCategory] in [0.5, 1.5] (1 = no opinion) that tips the DSP classifier's
 * decisions between acoustically similar categories (e.g. a door slam vs. a punch).
 */
interface SemanticClassifier : AutoCloseable {
    /** Sample rate the model expects (mono). */
    val sampleRate: Int

    /** Number of samples per inference window. */
    val windowSamples: Int

    /** Display name of the model. */
    val name: String

    /**
     * Classifies [samples] (exactly [windowSamples] mono samples at [sampleRate], in [-1, 1]).
     * Returns per-category multipliers indexed by [SoundCategory.ordinal], or null on failure.
     */
    fun classify(samples: FloatArray): FloatArray?

    override fun close() {}
}

/**
 * Maps model class labels (AudioSet ontology names, case-insensitive substring match) to
 * HapticSync categories. Used to turn a model's class scores into [SemanticClassifier] priors.
 */
object SemanticLabelMap {

    /** (label keyword, category) pairs. Earlier entries win when several match. */
    val KEYWORDS: List<Pair<String, SoundCategory>> = listOf(
        "speech" to SoundCategory.DIALOGUE,
        "conversation" to SoundCategory.DIALOGUE,
        "narration" to SoundCategory.DIALOGUE,
        "race car" to SoundCategory.ENGINE_REV,
        "revving" to SoundCategory.ENGINE_REV,
        "accelerating" to SoundCategory.ACCELERATION,
        "idling" to SoundCategory.ENGINE_IDLE,
        "engine" to SoundCategory.ENGINE_RUMBLE,
        "motor vehicle" to SoundCategory.ENGINE_RUMBLE,
        "car passing" to SoundCategory.VEHICLE_PASSING,
        "vehicle" to SoundCategory.ENGINE_RUMBLE,
        "bass drum" to SoundCategory.BASS_DRUM,
        "kick" to SoundCategory.BASS_DRUM,
        "snare" to SoundCategory.SNARE,
        "cymbal" to SoundCategory.CYMBAL,
        "hi-hat" to SoundCategory.CYMBAL,
        "drum" to SoundCategory.PERCUSSION,
        "percussion" to SoundCategory.PERCUSSION,
        "music" to SoundCategory.BEAT,
        "explosion" to SoundCategory.EXPLOSION,
        "burst" to SoundCategory.EXPLOSION,
        "boom" to SoundCategory.EXPLOSION,
        "gunshot" to SoundCategory.GUNSHOT,
        "gunfire" to SoundCategory.GUNSHOT,
        "machine gun" to SoundCategory.GUNSHOT,
        "slap" to SoundCategory.PUNCH,
        "punch" to SoundCategory.PUNCH,
        "crash" to SoundCategory.IMPACT,
        "smash" to SoundCategory.IMPACT,
        "thump" to SoundCategory.IMPACT,
        "thunder" to SoundCategory.THUNDER,
        "rumble" to SoundCategory.CINEMATIC_RUMBLE,
        "footsteps" to SoundCategory.FOOTSTEPS,
        "walk" to SoundCategory.FOOTSTEPS,
        "door" to SoundCategory.DOOR_SLAM,
        "slam" to SoundCategory.DOOR_SLAM,
        "machine" to SoundCategory.MACHINERY,
        "mechanisms" to SoundCategory.MACHINERY,
        "rain" to SoundCategory.RAIN,
        "wind" to SoundCategory.WIND,
    )

    fun categoryFor(label: String): SoundCategory? {
        val l = label.lowercase()
        return KEYWORDS.firstOrNull { l.contains(it.first) }?.second
    }

    /**
     * Converts class scores (one per label, 0..1) into category priors in [0.5, 1.5]: categories
     * whose classes score highly are favoured, the rest are left neutral.
     */
    fun priors(labels: List<String>, scores: FloatArray): FloatArray {
        val best = FloatArray(SoundCategory.COUNT)
        for (i in labels.indices) {
            if (i >= scores.size) break
            val category = categoryFor(labels[i]) ?: continue
            if (scores[i] > best[category.ordinal]) best[category.ordinal] = scores[i]
        }
        return FloatArray(SoundCategory.COUNT) { (1f + best[it].coerceIn(0f, 1f) * 0.5f).coerceIn(0.5f, 1.5f) }
    }
}
