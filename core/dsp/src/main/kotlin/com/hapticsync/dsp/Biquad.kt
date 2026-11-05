package com.hapticsync.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order IIR section (transposed direct form II) with double-precision state.
 *
 * Double precision matters here: the sub-bass band edge (20 Hz) at a 48 kHz sample rate puts
 * the poles extremely close to the unit circle, where single-precision coefficients lose
 * accuracy and the filter's gain drifts.
 *
 * Coefficient formulas follow R. Bristow-Johnson's "Audio EQ Cookbook".
 */
class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    /** Configures a low-pass section with cutoff [frequencyHz] and quality factor [q]. */
    fun setLowPass(sampleRate: Double, frequencyHz: Double, q: Double) {
        val w0 = 2.0 * PI * clampFrequency(frequencyHz, sampleRate) / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        setNormalized((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    /** Configures a high-pass section with cutoff [frequencyHz] and quality factor [q]. */
    fun setHighPass(sampleRate: Double, frequencyHz: Double, q: Double) {
        val w0 = 2.0 * PI * clampFrequency(frequencyHz, sampleRate) / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        setNormalized((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    /** Configures a band-pass section (0 dB peak gain) centred on [frequencyHz]. */
    fun setBandPass(sampleRate: Double, frequencyHz: Double, q: Double) {
        val w0 = 2.0 * PI * clampFrequency(frequencyHz, sampleRate) / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        setNormalized(alpha, 0.0, -alpha, 1 + alpha, -2 * c, 1 - alpha)
    }

    /** Makes the section a pass-through. */
    fun setIdentity() {
        b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
    }

    /** Makes the section output silence. */
    fun setMute() {
        b0 = 0.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
    }

    private fun setNormalized(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = nb0 / na0
        b1 = nb1 / na0
        b2 = nb2 / na0
        a1 = na1 / na0
        a2 = na2 / na0
    }

    /** Filters one sample. */
    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    /** Clears the filter state (call after discontinuities). */
    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    /**
     * Magnitude response at [frequencyHz], evaluated analytically from the coefficients.
     * Used by tests and by the filter-bank visualisation.
     */
    fun magnitudeAt(frequencyHz: Double, sampleRate: Double): Double {
        val w = 2.0 * PI * frequencyHz / sampleRate
        val cw = cos(w)
        val sw = sin(w)
        val c2w = cos(2 * w)
        val s2w = sin(2 * w)
        val numRe = b0 + b1 * cw + b2 * c2w
        val numIm = -(b1 * sw + b2 * s2w)
        val denRe = 1 + a1 * cw + a2 * c2w
        val denIm = -(a1 * sw + a2 * s2w)
        return sqrt((numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm))
    }

    /** Guards against denormal / runaway state, e.g. after extreme input. */
    fun sanitize() {
        if (!z1.isFinite() || abs(z1) < 1e-30) z1 = 0.0
        if (!z2.isFinite() || abs(z2) < 1e-30) z2 = 0.0
    }

    private fun clampFrequency(frequencyHz: Double, sampleRate: Double): Double =
        frequencyHz.coerceIn(1.0, sampleRate * 0.49)

    companion object {
        /** Q values of the two sections of a 4th-order Butterworth filter. */
        const val BUTTERWORTH4_Q1 = 0.5411961001461970
        const val BUTTERWORTH4_Q2 = 1.3065629648763766

        /** Q of a 2nd-order Butterworth section. */
        const val BUTTERWORTH2_Q = 0.7071067811865476
    }
}

/** A cascade of [Biquad] sections applied in series. */
class BiquadCascade(sectionCount: Int) {
    val sections: Array<Biquad> = Array(sectionCount) { Biquad() }

    fun process(x: Double): Double {
        var y = x
        for (section in sections) y = section.process(y)
        return y
    }

    fun reset() {
        for (section in sections) section.reset()
    }

    fun sanitize() {
        for (section in sections) section.sanitize()
    }

    fun magnitudeAt(frequencyHz: Double, sampleRate: Double): Double {
        var m = 1.0
        for (section in sections) m *= section.magnitudeAt(frequencyHz, sampleRate)
        return m
    }

    companion object {
        /** 4th-order Butterworth low-pass (two sections). */
        fun lowPass4(sampleRate: Double, cutoffHz: Double) = BiquadCascade(2).apply {
            sections[0].setLowPass(sampleRate, cutoffHz, Biquad.BUTTERWORTH4_Q1)
            sections[1].setLowPass(sampleRate, cutoffHz, Biquad.BUTTERWORTH4_Q2)
        }

        /** 4th-order Butterworth high-pass (two sections). */
        fun highPass4(sampleRate: Double, cutoffHz: Double) = BiquadCascade(2).apply {
            sections[0].setHighPass(sampleRate, cutoffHz, Biquad.BUTTERWORTH4_Q1)
            sections[1].setHighPass(sampleRate, cutoffHz, Biquad.BUTTERWORTH4_Q2)
        }
    }
}
