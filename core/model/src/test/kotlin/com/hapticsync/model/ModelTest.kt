package com.hapticsync.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    @Test
    fun everyBuiltInPresetRoundTripsThroughJson() {
        val json = ModelJson.encodePresets(BuiltInPresets.all)
        val decoded = ModelJson.decodePresets(json)
        assertNotNull(decoded)
        assertEquals(BuiltInPresets.all.size, decoded!!.size)
        for ((original, restored) in BuiltInPresets.all.zip(decoded)) {
            assertEquals(original.id, restored.id)
            assertEquals(original.profile.validated(), restored.profile)
        }
    }

    @Test
    fun builtInPresetsContainDistinctProcessingValues() {
        val profiles = BuiltInPresets.all.filter { it.id != BuiltInPresets.CUSTOM_ID }.map { it.profile }
        // Every pair of presets must differ in actual parameters, not just names.
        for (i in profiles.indices) for (j in i + 1 until profiles.size) {
            assertNotEquals("presets $i and $j are identical", profiles[i], profiles[j])
        }
        val racing = BuiltInPresets.RACING.profile
        val music = BuiltInPresets.MUSIC.profile
        assertTrue(racing.category(SoundCategory.GEAR_SHIFT).priority > music.category(SoundCategory.GEAR_SHIFT).priority)
        assertTrue(music.category(SoundCategory.BASS_DRUM).intensity > racing.category(SoundCategory.BASS_DRUM).intensity)
        assertFalse(BuiltInPresets.MINIMAL.profile.haptics.continuousLayer)
        assertFalse(BuiltInPresets.MINIMAL.profile.category(SoundCategory.FOOTSTEPS).enabled)
        assertFalse("dialogue must be silent by default", BuiltInPresets.CINEMATIC.profile.category(SoundCategory.DIALOGUE).enabled)
    }

    @Test
    fun validationClampsOutOfRangeValues() {
        val broken = HapticProfile(
            bands = listOf(BandSettings(BandId.BASS, lowHz = Float.NaN, highHz = -5f, sensitivity = 7f, thresholdDb = 20f)),
            categories = listOf(CategorySettings(SoundCategory.EXPLOSION, intensity = -1f, duration = 99f, priority = 42)),
            haptics = HapticParameters(masterIntensity = 3f, pulseSpacingMs = 0f, maxIntensity = 0f, releaseMs = 1e9f),
            analysis = AnalysisSettings(windowMs = 1f, hopMs = 100f, rangeLowHz = 5000f, rangeHighHz = 10f),
        ).validated()
        assertEquals(BandId.entries.size, broken.bands.size)
        assertEquals(SoundCategory.entries.size, broken.categories.size)
        val bass = broken.band(BandId.BASS)
        assertEquals(BandId.BASS.defaultLowHz, bass.lowHz, 0f)
        assertTrue(bass.highHz > bass.lowHz)
        assertEquals(1f, bass.sensitivity, 0f)
        assertEquals(BandSettings.MAX_THRESHOLD_DB, bass.thresholdDb, 0f)
        val explosion = broken.category(SoundCategory.EXPLOSION)
        assertEquals(0f, explosion.intensity, 0f)
        assertEquals(CategorySettings.MAX_DURATION, explosion.duration, 0f)
        assertEquals(CategorySettings.MAX_PRIORITY, explosion.priority)
        assertEquals(1f, broken.haptics.masterIntensity, 0f)
        assertEquals(HapticParameters.MIN_SPACING_MS, broken.haptics.pulseSpacingMs, 0f)
        assertTrue(broken.haptics.maxIntensity > 0f)
        assertEquals(AnalysisSettings.MIN_WINDOW_MS, broken.analysis.windowMs, 0f)
        assertTrue(broken.analysis.hopMs <= broken.analysis.windowMs)
        assertTrue(broken.analysis.rangeHighHz > broken.analysis.rangeLowHz)
    }

    @Test
    fun bandEdgesStayAscending() {
        val profile = HapticProfile().updateBand(BandId.LOW_MID) { it.copy(lowHz = 30f, highHz = 50f) }.validated()
        val lows = profile.bands.map { it.lowHz }
        assertEquals(lows.sorted(), lows)
    }

    @Test
    fun decodingIsLenient() {
        assertNull(ModelJson.decodeProfile("not json"))
        assertNull(ModelJson.decodeSettings("{\"hapticsEnabled\": \"maybe\""))
        // Unknown keys and unknown enum values do not break decoding.
        val profile = ModelJson.decodeProfile(
            """{"haptics":{"masterIntensity":0.4,"envelope":"FUTURE_SHAPE","newKey":1},"futureSection":{}}""",
        )
        assertNotNull(profile)
        assertEquals(0.4f, profile!!.haptics.masterIntensity, 1e-6f)
        assertEquals(EnvelopeShape.EXPONENTIAL, profile.haptics.envelope)
        assertEquals(BandId.entries.size, profile.bands.size)
    }

    @Test
    fun isolationSemantics() {
        val base = HapticProfile()
        assertTrue(base.isolationAllowsCategory(SoundCategory.EXPLOSION))

        val onlyRevs = base.copy(isolation = IsolationSettings(true, IsolationTarget.of(SoundCategory.ENGINE_REV)))
        assertTrue(onlyRevs.isolationAllowsCategory(SoundCategory.ENGINE_REV))
        assertFalse(onlyRevs.isolationAllowsCategory(SoundCategory.EXPLOSION))
        assertFalse(onlyRevs.isolationAllowsCategory(SoundCategory.GEAR_SHIFT))
        assertFalse(onlyRevs.isBandIsolation)

        val impacts = base.copy(isolation = IsolationSettings(true, IsolationTarget.of(IsolationGroup.SUDDEN_IMPACTS)))
        assertTrue(impacts.isolationAllowsCategory(SoundCategory.GUNSHOT))
        assertFalse(impacts.isolationAllowsCategory(SoundCategory.ENGINE_IDLE))

        val lows = base.copy(isolation = IsolationSettings(true, IsolationTarget.of(IsolationGroup.LOW_FREQUENCY)))
        assertTrue(lows.isBandIsolation)
        assertFalse("band isolation bypasses categories", lows.isolationAllowsCategory(SoundCategory.BASS_DRUM))
        assertEquals(setOf(BandId.SUB_BASS, BandId.BASS), lows.isolation.target.allowedBands())

        val invalid = IsolationSettings(true, IsolationTarget(IsolationType.CATEGORY, "NOPE")).validated()
        assertTrue(invalid.target.isValid)
        assertTrue(IsolationTarget.all().none { it.category == SoundCategory.DIALOGUE })
    }

    @Test
    fun calibrationAndSettingsRoundTrip() {
        val calibration = CalibrationProfile(deviceKey = "Acme Phone", syncOffsetMs = -40f, usage = VibrationUsage.TOUCH)
        assertEquals(calibration, ModelJson.decodeCalibration(ModelJson.encodeCalibration(calibration)))
        assertEquals(-CalibrationProfile.MAX_SYNC_OFFSET_MS, CalibrationProfile(syncOffsetMs = -1000f).validated().syncOffsetMs, 0f)
        val settings = AppSettings(hapticsEnabled = false, activePresetId = BuiltInPresets.RACING_ID)
        assertEquals(settings, ModelJson.decodeSettings(ModelJson.encodeSettings(settings)))
        val recents = listOf(RecentMedia("content://a/1", "Lap", 60_000, 30_000))
        assertEquals(recents, ModelJson.decodeRecents(ModelJson.encodeRecents(recents)))
        assertEquals(0.5f, recents[0].progress, 1e-6f)
    }

    @Test
    fun categoriesAreGroupedByFamily() {
        val all = SoundFamily.entries.flatMap { SoundCategory.byFamily(it) }
        assertEquals(SoundCategory.entries.toSet(), all.toSet())
        assertEquals(SoundCategory.GEAR_SHIFT, SoundCategory.fromName("GEAR_SHIFT"))
        assertNull(SoundCategory.fromName("UNKNOWN"))
    }
}
