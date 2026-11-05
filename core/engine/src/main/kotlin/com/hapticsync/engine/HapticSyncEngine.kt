package com.hapticsync.engine

import com.hapticsync.analysis.AudioAnalyzer
import com.hapticsync.analysis.SemanticClassifier
import com.hapticsync.analysis.SoundEventSink
import com.hapticsync.dsp.LinearResampler
import com.hapticsync.dsp.PcmFormat
import com.hapticsync.haptics.CalibrationPattern
import com.hapticsync.haptics.HapticConfig
import com.hapticsync.haptics.HapticMapper
import com.hapticsync.haptics.HapticOutput
import com.hapticsync.haptics.HapticRenderer
import com.hapticsync.haptics.HapticScheduler
import com.hapticsync.haptics.PlanSample
import com.hapticsync.haptics.SchedulerStats
import com.hapticsync.model.AnalysisQuality
import com.hapticsync.model.CalibrationProfile
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.SoundCategory
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs

/** Creates the engine's worker threads (Android sets audio priorities here). */
fun interface EngineThreadFactory {
    fun newThread(name: String, realtime: Boolean, body: Runnable): Thread
}

/**
 * The real-time audio-to-haptics engine.
 *
 * Data flow:
 * ```
 * player audio sink ──onPcm──▶ PcmIngest ──▶ analysis thread: AudioAnalyzer → HapticMapper ─┐
 *        │ onPosition/onPlaying/onDiscontinuity                                              │
 *        ▼                                                                                   ▼
 *   PlaybackClock ───────────────────────────────────▶ scheduler thread: HapticScheduler ──▶ HapticOutput
 * ```
 *
 * The player integration calls the [MediaAudioInput] methods from its playback thread; they only
 * copy data or flip flags, so they never stall audio. The analysis thread converts each hop of
 * audio into features, events and continuous-plan samples; the scheduler thread dispatches
 * vibration commands on the media clock. Profile and calibration changes take effect on the
 * next hop, including during playback.
 *
 * Under CPU pressure (in [AnalysisQuality.AUTO]) the analysis reduces its spectral/pitch rate
 * instead of falling behind; playback is never affected.
 */
class HapticSyncEngine(
    private val output: HapticOutput,
    profile: HapticProfile,
    calibration: CalibrationProfile,
    private val threadFactory: EngineThreadFactory = DEFAULT_THREAD_FACTORY,
    private val nanoTime: () -> Long = System::nanoTime,
) : MediaAudioInput, AutoCloseable {

    /** The media clock (exposed for the UI's sync test and visualisation). */
    val clock = PlaybackClock()

    private val ingest = PcmIngest()
    private val renderer = HapticRenderer(output.capabilities)

    @Volatile private var currentProfile: HapticProfile = profile.validated()
    @Volatile private var currentCalibration: CalibrationProfile = calibration.validated()
    @Volatile private var hapticConfig = HapticConfig(currentProfile, currentCalibration)
    @Volatile private var profileVersion = 0

    private val scheduler = HapticScheduler(output, clock, renderer, hapticConfig, DEFAULT_HOP_US)
    private val visualization = VisualizationBuffer()
    private val eventLog = EventLog()

    // ---- analysis-thread state -------------------------------------------------------------
    private var analyzer: AudioAnalyzer? = null
    private var mapper: HapticMapper? = null
    private var analyzerFormatId = -1
    private var appliedProfileVersion = -1
    private var analysisEpoch = Int.MIN_VALUE
    private var lastHopEndPts = Long.MIN_VALUE
    private var hopMid = FloatArray(0)
    private var hopSide = FloatArray(0)
    private val position = PcmIngest.Position()
    private val planSample = PlanSample()
    private var loadEwma = 0f
    private var loadLevel = 0
    private var lastLoadChangeNanos = 0L
    private var lastStatusNanos = 0L
    private var lastFrontierUs = Long.MIN_VALUE
    private var analysedHops = 0L

    // ---- threads ---------------------------------------------------------------------------
    @Volatile private var running = false
    @Volatile private var analysisThread: Thread? = null
    @Volatile private var schedulerThread: Thread? = null

    // ---- optional semantic classifier ------------------------------------------------------
    @Volatile var semanticClassifier: SemanticClassifier? = null
        set(value) {
            field?.let { old -> if (old !== value) runCatching { old.close() } }
            field = value
            semanticReset = true
        }
    @Volatile private var semanticReset = true
    private var semanticExecutor: ExecutorService? = null
    private val semanticBusy = AtomicBoolean(false)
    private val semanticResult = AtomicReference<FloatArray?>(null)
    private var resampler: LinearResampler? = null
    private var semanticRing = FloatArray(0)
    private var semanticWrite = 0
    private var semanticFilled = 0
    private var resampled = FloatArray(0)
    private var hopsSinceInference = 0
    private var hopsSincePriors = 0

    /** Latest engine status (updated a few times per second while analysing). */
    @Volatile var status: EngineStatus = EngineStatus()
        private set

    /** Scheduler statistics (dispatch accuracy, drops). */
    val schedulerStats: SchedulerStats get() = scheduler.stats

    /** Master haptics switch. Analysis and visualisation continue when off. */
    var hapticsEnabled: Boolean
        get() = scheduler.enabled
        set(value) { scheduler.enabled = value }

    val profile: HapticProfile get() = currentProfile
    val calibration: CalibrationProfile get() = currentCalibration
    val capabilities get() = output.capabilities

    private val eventSink = SoundEventSink { event ->
        eventLog.add(event)
        val effect = mapper?.mapEvent(event, analysisEpoch) ?: return@SoundEventSink
        if (clock.isValid) effect.queuedAtMediaUs = clock.mediaTimeUs(nanoTime())
        scheduler.submit(effect)
        schedulerThread?.let { LockSupport.unpark(it) }
    }

    // ==========================================================================================
    // Lifecycle

    /** Starts the worker threads. Idempotent. */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        analysisThread = threadFactory.newThread("HapticSync-analysis", true) { analysisLoop() }.also { it.start() }
        schedulerThread = threadFactory.newThread("HapticSync-scheduler", true) { schedulerLoop() }.also { it.start() }
    }

    /** Stops the worker threads and silences the vibrator. */
    @Synchronized
    override fun close() {
        if (!running) {
            scheduler.playbackStopped()
            return
        }
        running = false
        analysisThread?.let { LockSupport.unpark(it); it.join(500) }
        schedulerThread?.let { LockSupport.unpark(it); it.join(500) }
        analysisThread = null
        schedulerThread = null
        scheduler.playbackStopped()
        semanticExecutor?.shutdownNow()
        semanticExecutor = null
        semanticClassifier?.let { runCatching { it.close() } }
    }

    // ==========================================================================================
    // MediaAudioInput — called on the player's playback thread

    override fun onFormat(format: PcmFormat?, unsupportedReason: String?) {
        ingest.setFormat(format)
        status = status.copy(format = format, unsupportedReason = unsupportedReason, analysing = false)
        analysisThread?.let { LockSupport.unpark(it) }
    }

    override fun onPcm(buffer: ByteBuffer, startByte: Int, endByte: Int, presentationTimeUs: Long) {
        ingest.write(buffer, startByte, endByte, presentationTimeUs, scheduler.currentEpoch)
        analysisThread?.let { LockSupport.unpark(it) }
    }

    override fun onDiscontinuity() {
        scheduler.discontinuity()
        clock.invalidate()
        analysisThread?.let { LockSupport.unpark(it) }
        schedulerThread?.let { LockSupport.unpark(it) }
    }

    override fun onPlaying(playing: Boolean) {
        clock.setPlaying(playing, nanoTime())
        if (!playing) scheduler.playbackStopped()
        schedulerThread?.let { LockSupport.unpark(it) }
    }

    override fun onPosition(positionUs: Long, systemNanoTime: Long) {
        clock.update(positionUs, systemNanoTime)
    }

    override fun onSpeed(speed: Float) {
        clock.setSpeed(speed, nanoTime())
    }

    override fun onReset() {
        clock.setPlaying(false, nanoTime())
        scheduler.discontinuity()
        clock.invalidate()
        status = status.copy(analysing = false)
    }

    // ==========================================================================================
    // Control — any thread

    /** Applies a new profile (takes effect on the next analysis hop). */
    fun updateProfile(profile: HapticProfile) {
        currentProfile = profile.validated()
        hapticConfig = HapticConfig(currentProfile, currentCalibration)
        scheduler.config = hapticConfig
        mapper?.config = hapticConfig
        profileVersion++
    }

    /** Applies a new calibration (takes effect immediately). */
    fun updateCalibration(calibration: CalibrationProfile) {
        currentCalibration = calibration.validated()
        hapticConfig = HapticConfig(currentProfile, currentCalibration)
        scheduler.config = hapticConfig
        mapper?.config = hapticConfig
    }

    /** Plays a calibration test pattern right now (independent of playback). */
    fun preview(pattern: CalibrationPattern): Boolean {
        val effect = renderer.renderTransient(pattern.build(), hapticConfig, 1f) ?: return false
        return output.play(effect)
    }

    /** Plays the haptic response of [category] with the current settings, right now. */
    fun previewCategory(category: SoundCategory, strength: Float = 0.9f): Boolean {
        val m = HapticMapper(hapticConfig, DEFAULT_HOP_US / 1000f)
        val event = com.hapticsync.analysis.SoundEvent(
            category = category, timeUs = 0L, strength = strength, confidence = 1f, sharpness = 0.5f,
            attackMs = 5f, decayDbPerSecond = 60f, durationHintMs = 700f,
            dominantBand = com.hapticsync.model.BandId.BASS,
            bandShares = floatArrayOf(0.25f, 0.35f, 0.2f, 0.12f, 0.06f, 0.02f),
        )
        val effect = m.mapEvent(event, 0)
        val rendered = if (effect != null) {
            renderer.renderTransient(effect, hapticConfig, 1f)
        } else {
            // Continuous categories: preview a second of their texture.
            val frame = com.hapticsync.analysis.AnalysisFrame()
            frame.continuous[category.ordinal] = 1f
            frame.rpmNormalized = 0.7f
            for (b in frame.bandShare.indices) frame.bandShare[b] = 1f / frame.bandShare.size
            val sample = PlanSample()
            val amps = FloatArray(100)
            val sharp = FloatArray(100)
            for (i in 0 until 100) { m.mapFrame(frame, 0, sample); amps[i] = sample.amplitude; sharp[i] = sample.sharpness }
            renderer.renderContinuous(amps, sharp, 100, 10f, 10f, hapticConfig, 1f)
        } ?: return false
        return output.play(rendered)
    }

    /** Stops any ongoing vibration (e.g. a long preview). */
    fun stopOutput() = output.cancel()

    /**
     * Fills [out] with visualisation data for the media time being heard right now.
     * Returns false when there is nothing to show yet.
     */
    fun snapshot(out: VisualizationSnapshot, systemNanoTime: Long = nanoTime()): Boolean {
        val now = clock.mediaTimeUs(systemNanoTime)
        out.mediaTimeUs = now
        if (!clock.isValid) {
            out.valid = false
            return false
        }
        out.valid = visualization.fill(now, out)
        eventLog.fill(now, out)
        val windowUs = (VisualizationSnapshot.HISTORY_SECONDS * 1_000_000f).toLong()
        val stepUs = windowUs / VisualizationSnapshot.HISTORY_POINTS
        var t = now - windowUs
        for (p in 0 until VisualizationSnapshot.HISTORY_POINTS) {
            out.hapticHistory[p] = scheduler.history.intensityAt(t)
            t += stepUs
        }
        out.hapticIntensity = if (hapticsEnabled && clock.isPlaying) scheduler.history.intensityAt(now) else 0f
        return out.valid
    }

    // ==========================================================================================
    // Analysis thread

    private fun analysisLoop() {
        while (running) {
            val worked = try {
                processAvailable()
            } catch (t: Throwable) {
                // Never let an analysis failure take down playback: reset and continue.
                analyzer?.reset()
                mapper?.reset()
                false
            }
            if (!worked) LockSupport.parkNanos(this, IDLE_PARK_NANOS)
        }
    }

    private fun processAvailable(): Boolean {
        var worked = false
        while (true) {
            if (!ingest.peekPosition(position)) break
            val format = position.format
            if (format == null || position.epoch != scheduler.currentEpoch) {
                // Unanalysable stream, or audio queued before a seek: discard it.
                ingest.skip(minOf(ingest.available(), position.framesUntilBoundary))
                continue
            }
            val a = ensureAnalyzer(format, position.formatId)
            val m = mapper!!
            if (position.epoch != analysisEpoch) {
                a.reset()
                m.reset()
                analysisEpoch = position.epoch
                lastHopEndPts = Long.MIN_VALUE
                resetSemanticBuffer()
            }
            if (appliedProfileVersion != profileVersion) {
                appliedProfileVersion = profileVersion
                a.updateProfile(currentProfile, format.sampleRate)
                ensureHopBuffers(a.config.hopSize)
                scheduler.hopDurationUs = a.config.hopDurationUs.toLong()
            }
            val hop = a.config.hopSize
            if (position.framesUntilBoundary < hop) {
                // A partial hop before a format/epoch boundary: drop it and restart cleanly.
                ingest.skip(position.framesUntilBoundary)
                a.reset()
                m.reset()
                lastHopEndPts = Long.MIN_VALUE
                continue
            }
            if (ingest.available() < hop) break
            if (lastHopEndPts != Long.MIN_VALUE && abs(position.ptsUs - lastHopEndPts) > PTS_DISCONTINUITY_US) {
                a.reset()
                m.reset()
            }
            ingest.read(hopMid, hopSide, 0, hop)
            val started = nanoTime()
            updateDecisionWindow(a, position.ptsUs, started)
            applySemanticPriors(a)
            val frame = a.process(hopMid, hopSide, 0, position.ptsUs)
            m.mapFrame(frame, analysisEpoch, planSample)
            scheduler.submitPlan(planSample)
            visualization.push(frame)
            feedSemantic(format.sampleRate, frame.rmsDb)
            lastHopEndPts = position.ptsUs + a.config.hopDurationUs.toLong()
            lastFrontierUs = lastHopEndPts
            analysedHops++
            measureLoad(a, nanoTime() - started)
            worked = true
        }
        return worked
    }

    private fun ensureAnalyzer(format: PcmFormat, formatId: Int): AudioAnalyzer {
        val existing = analyzer
        if (existing != null && analyzerFormatId == formatId) return existing
        val a = if (existing != null && existing.config.sampleRate == format.sampleRate) {
            existing.also { it.reset() }
        } else {
            AudioAnalyzer(currentProfile, format.sampleRate, eventSink)
        }
        analyzer = a
        analyzerFormatId = formatId
        appliedProfileVersion = profileVersion
        a.updateProfile(currentProfile, format.sampleRate)
        if (mapper == null) mapper = HapticMapper(hapticConfig, a.config.hopDurationUs.toFloat() / 1000f) else mapper!!.reset()
        ensureHopBuffers(a.config.hopSize)
        scheduler.hopDurationUs = a.config.hopDurationUs.toLong()
        resampler = null
        return a
    }

    private fun ensureHopBuffers(hop: Int) {
        if (hopMid.size != hop) {
            hopMid = FloatArray(hop)
            hopSide = FloatArray(hop)
        }
    }

    /** Bounds the classifier's decision window by how far ahead of the speaker we are. */
    private fun updateDecisionWindow(a: AudioAnalyzer, hopPtsUs: Long, nowNanos: Long) {
        if (!clock.isValid || !clock.isPlaying) {
            a.maxDecisionHops = Int.MAX_VALUE
            return
        }
        val lookaheadUs = hopPtsUs - clock.mediaTimeUs(nowNanos)
        val usable = lookaheadUs - DECISION_SAFETY_US
        a.maxDecisionHops = (usable / a.config.hopDurationUs).toInt().coerceAtLeast(3)
    }

    private fun measureLoad(a: AudioAnalyzer, elapsedNanos: Long) {
        val hopNanos = a.config.hopDurationUs * 1000.0
        val ratio = (elapsedNanos / hopNanos).toFloat()
        loadEwma = if (analysedHops <= 1) ratio else loadEwma * 0.97f + ratio * 0.03f
        val now = nanoTime()
        if (currentProfile.analysis.quality == AnalysisQuality.AUTO && now - lastLoadChangeNanos > LOAD_CHANGE_INTERVAL_NANOS) {
            val behind = ingest.fillRatio > 0.5f
            if ((loadEwma > 0.45f || behind) && loadLevel < 2) {
                loadLevel++
                lastLoadChangeNanos = now
            } else if (loadEwma < 0.12f && !behind && loadLevel > 0 && now - lastLoadChangeNanos > 4 * LOAD_CHANGE_INTERVAL_NANOS) {
                loadLevel--
                lastLoadChangeNanos = now
            }
            a.loadLevel = loadLevel
        }
        if (now - lastStatusNanos > STATUS_INTERVAL_NANOS) {
            lastStatusNanos = now
            val stats = scheduler.stats
            val lookaheadMs = if (clock.isValid) (lastFrontierUs - clock.mediaTimeUs(now)) / 1000f else 0f
            status = status.copy(
                analysing = true,
                lookaheadMs = lookaheadMs.coerceAtLeast(0f),
                loadLevel = loadLevel,
                processingLoad = loadEwma,
                droppedChunks = ingest.droppedChunks,
                transientsDispatched = stats.transientsDispatched,
                droppedStale = stats.droppedStale,
                suppressed = stats.suppressedPriority + stats.suppressedSpacing,
                meanDispatchErrorMs = (stats.meanDispatchErrorUs / 1000.0).toFloat(),
                meanLeadMs = (stats.meanLeadUs / 1000.0).toFloat(),
                semanticActive = semanticClassifier != null,
            )
        }
    }

    // ---- Semantic classification (optional) -------------------------------------------------

    private fun resetSemanticBuffer() {
        semanticFilled = 0
        semanticWrite = 0
        hopsSinceInference = 0
        resampler?.reset()
    }

    private fun feedSemantic(sampleRate: Int, levelDb: Float) {
        val classifier = semanticClassifier ?: return
        if (semanticReset || resampler == null || resampler!!.outputRate != classifier.sampleRate) {
            semanticReset = false
            resampler = LinearResampler(sampleRate, classifier.sampleRate)
            semanticRing = FloatArray(classifier.windowSamples)
            resampled = FloatArray(resampler!!.maxOutput(hopMid.size) + 4)
            resetSemanticBuffer()
        }
        val r = resampler!!
        val n = r.process(hopMid, 0, hopMid.size, resampled, 0)
        for (i in 0 until n) {
            semanticRing[semanticWrite] = resampled[i]
            semanticWrite = (semanticWrite + 1) % semanticRing.size
        }
        semanticFilled = minOf(semanticRing.size, semanticFilled + n)
        hopsSinceInference++
        val hopsPerInference = (SEMANTIC_INTERVAL_US / scheduler.hopDurationUs).toInt().coerceAtLeast(1)
        if (semanticFilled < semanticRing.size || hopsSinceInference < hopsPerInference || levelDb < SEMANTIC_MIN_LEVEL_DB) return
        if (!semanticBusy.compareAndSet(false, true)) return
        hopsSinceInference = 0
        val window = FloatArray(semanticRing.size)
        for (i in window.indices) window[i] = semanticRing[(semanticWrite + i) % semanticRing.size]
        val executor = semanticExecutor ?: Executors.newSingleThreadExecutor { body ->
            threadFactory.newThread("HapticSync-semantic", false, body)
        }.also { semanticExecutor = it }
        executor.execute {
            try {
                semanticResult.set(runCatching { classifier.classify(window) }.getOrNull())
            } finally {
                semanticBusy.set(false)
            }
        }
    }

    private fun applySemanticPriors(a: AudioAnalyzer) {
        val priors = semanticResult.getAndSet(null)
        if (priors != null) {
            a.setSemanticPriors(priors)
            hopsSincePriors = 0
        } else if (++hopsSincePriors == SEMANTIC_STALE_HOPS) {
            a.setSemanticPriors(null) // stale opinion: back to neutral
        }
    }

    /** Runs the analysis on all buffered audio on the calling thread (deterministic tests). */
    internal fun processPending(): Boolean = processAvailable()

    /** Runs one scheduler step on the calling thread (deterministic tests). */
    internal fun tickScheduler(systemNanoTime: Long): Long = scheduler.tick(systemNanoTime)

    // ==========================================================================================
    // Scheduler thread

    private fun schedulerLoop() {
        while (running) {
            val waitNanos = try {
                scheduler.tick(nanoTime())
            } catch (t: Throwable) {
                scheduler.playbackStopped()
                IDLE_PARK_NANOS
            }
            LockSupport.parkNanos(this, waitNanos)
        }
        scheduler.playbackStopped()
    }

    companion object {
        private const val DEFAULT_HOP_US = 10_000L
        private const val IDLE_PARK_NANOS = 20_000_000L
        private const val PTS_DISCONTINUITY_US = 50_000L
        private const val DECISION_SAFETY_US = 60_000L
        private const val LOAD_CHANGE_INTERVAL_NANOS = 2_000_000_000L
        private const val STATUS_INTERVAL_NANOS = 250_000_000L
        private const val SEMANTIC_INTERVAL_US = 480_000L
        private const val SEMANTIC_MIN_LEVEL_DB = -45f
        private const val SEMANTIC_STALE_HOPS = 300

        val DEFAULT_THREAD_FACTORY = EngineThreadFactory { name, realtime, body ->
            Thread(body, name).apply {
                isDaemon = true
                priority = if (realtime) Thread.MAX_PRIORITY else Thread.NORM_PRIORITY
            }
        }
    }
}
