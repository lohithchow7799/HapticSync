package com.hapticsync.analysis

import com.hapticsync.dsp.PcmConverter
import com.hapticsync.dsp.PcmEncoding
import com.hapticsync.dsp.PcmFormat
import com.hapticsync.dsp.synth.SynthClip
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.SoundCategory

/** Runs the analyser over a synthesised clip, exactly as the engine feeds it hop by hop. */
object ClipRunner {

    class Snapshot(
        val timeUs: Long,
        val continuous: FloatArray,
        val engine: Float,
        val rpm: Float,
        val velocity: Float,
        val speech: Float,
        val rhythm: Float,
        val onset: Boolean,
        val rmsDb: Float,
        val dominant: Int,
    )

    class Result(val events: List<SoundEvent>, val frames: List<Snapshot>, val scores: Map<SoundEvent, FloatArray> = emptyMap()) {
        fun eventsOf(vararg categories: SoundCategory) = events.filter { it.category in categories }

        /** Mean level of a continuous category between two times (seconds). */
        fun meanLevel(category: SoundCategory, fromSec: Double, toSec: Double): Float {
            val selected = frames.filter { it.timeUs >= fromSec * 1e6 && it.timeUs < toSec * 1e6 }
            if (selected.isEmpty()) return 0f
            return selected.map { it.continuous[category.ordinal] }.average().toFloat()
        }

        fun meanOf(fromSec: Double, toSec: Double, selector: (Snapshot) -> Float): Float {
            val selected = frames.filter { it.timeUs >= fromSec * 1e6 && it.timeUs < toSec * 1e6 }
            return if (selected.isEmpty()) 0f else selected.map(selector).average().toFloat()
        }
    }

    fun run(
        clip: SynthClip,
        profile: HapticProfile = HapticProfile(),
        interleaved: FloatArray = clip.interleaved(),
        channels: Int = 2,
        sampleRate: Int = clip.sampleRate,
    ): Result {
        val events = mutableListOf<SoundEvent>()
        val scores = HashMap<SoundEvent, FloatArray>()
        lateinit var analyzer: AudioAnalyzer
        analyzer = AudioAnalyzer(profile, sampleRate) {
            events += it
            scores[it] = analyzer.lastScores.copyOf()
        }
        val converter = PcmConverter().apply { configure(PcmFormat(sampleRate, channels, PcmEncoding.PCM_FLOAT)) }
        val hop = analyzer.config.hopSize
        val totalFrames = interleaved.size / channels
        val mid = FloatArray(hop)
        val side = FloatArray(hop)
        val frames = ArrayList<Snapshot>(totalFrames / hop + 1)
        var pos = 0
        while (pos + hop <= totalFrames) {
            converter.convertFloats(interleaved, pos, hop, mid, side, 0)
            val timeUs = pos * 1_000_000L / sampleRate
            val f = analyzer.process(mid, side, 0, timeUs)
            frames += Snapshot(
                f.timeUs, f.continuous.copyOf(), f.engineLikelihood, f.rpmNormalized, f.pitchVelocity,
                f.speechLikelihood, f.rhythmConfidence, f.onsetDetected, f.rmsDb, f.dominantCategory,
            )
            pos += hop
        }
        return Result(events.sortedBy { it.timeUs }, frames, scores)
    }
}
