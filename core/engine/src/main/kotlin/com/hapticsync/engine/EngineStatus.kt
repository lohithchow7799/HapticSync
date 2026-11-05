package com.hapticsync.engine

import com.hapticsync.dsp.PcmFormat

/** Snapshot of the engine's health, for the UI and diagnostics. */
data class EngineStatus(
    /** Format of the audio being analysed, or null when none / not analysable. */
    val format: PcmFormat? = null,
    /** Why the current audio cannot be analysed, if it cannot. */
    val unsupportedReason: String? = null,
    /** Whether audio is currently being analysed (live indicator). */
    val analysing: Boolean = false,
    /** How far ahead of the speaker the analysis currently runs (ms). */
    val lookaheadMs: Float = 0f,
    /** Analysis load level: 0 = full quality, 1 = reduced, 2 = minimal. */
    val loadLevel: Int = 0,
    /** Mean analysis cost per hop relative to the hop duration (0.1 = 10 % of real time). */
    val processingLoad: Float = 0f,
    /** PCM chunks dropped because analysis fell behind. */
    val droppedChunks: Long = 0,
    /** Transients dispatched / dropped as stale / suppressed. */
    val transientsDispatched: Long = 0,
    val droppedStale: Long = 0,
    val suppressed: Long = 0,
    /** Mean |dispatch time − target time| in ms. */
    val meanDispatchErrorMs: Float = 0f,
    /** How far ahead of their fire time transients were queued (ms). */
    val meanLeadMs: Float = 0f,
    /** Whether the semantic (ML) classifier is active. */
    val semanticActive: Boolean = false,
)
