package com.hapticsync.model

import kotlinx.serialization.Serializable

/**
 * The complete, user-editable configuration of the audio-analysis and haptic pipeline. A preset
 * is a named [HapticProfile]; every value in it changes processing, none is cosmetic.
 */
@Serializable
data class HapticProfile(
    val bands: List<BandSettings> = BandSettings.defaults(),
    val categories: List<CategorySettings> = CategorySettings.defaults(),
    val haptics: HapticParameters = HapticParameters(),
    val isolation: IsolationSettings = IsolationSettings(),
    val analysis: AnalysisSettings = AnalysisSettings(),
) {
    fun band(id: BandId): BandSettings = bands.firstOrNull { it.band == id } ?: BandSettings(id)

    fun category(category: SoundCategory): CategorySettings =
        categories.firstOrNull { it.category == category } ?: CategorySettings.defaultFor(category)

    fun withBand(settings: BandSettings): HapticProfile =
        copy(bands = BandId.entries.map { if (it == settings.band) settings else band(it) })

    fun withCategory(settings: CategorySettings): HapticProfile =
        copy(categories = SoundCategory.entries.map { if (it == settings.category) settings else category(it) })

    fun updateBand(id: BandId, transform: (BandSettings) -> BandSettings): HapticProfile = withBand(transform(band(id)))

    fun updateCategory(category: SoundCategory, transform: (CategorySettings) -> CategorySettings): HapticProfile =
        withCategory(transform(category(category)))

    /**
     * Whether isolation mode lets [category] through. With isolation disabled every category is
     * allowed (its own enabled flag still applies).
     */
    fun isolationAllowsCategory(category: SoundCategory): Boolean {
        if (!isolation.enabled) return true
        val target = isolation.target
        if (target.isBandTarget) return false
        return category in target.allowedCategories()
    }

    /** Whether isolation mode is restricting output to frequency bands. */
    val isBandIsolation: Boolean get() = isolation.enabled && isolation.target.isBandTarget

    /**
     * Returns a canonical copy: one entry per band and category in enum order, all values clamped
     * to valid ranges, band edges kept in ascending, non-overlapping order.
     */
    fun validated(): HapticProfile {
        val orderedBands = BandId.entries.map { band(it).validated() }.toMutableList()
        // Keep band ranges monotonic so the filter bank stays meaningful.
        for (i in 1 until orderedBands.size) {
            val previous = orderedBands[i - 1]
            val current = orderedBands[i]
            if (current.lowHz < previous.lowHz) {
                orderedBands[i] = current.copy(lowHz = previous.lowHz, highHz = maxOf(current.highHz, previous.lowHz + 1f))
            }
        }
        return HapticProfile(
            bands = orderedBands,
            categories = SoundCategory.entries.map { category(it).validated() },
            haptics = haptics.validated(),
            isolation = isolation.validated(),
            analysis = analysis.validated(),
        )
    }
}
