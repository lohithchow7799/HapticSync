package com.hapticsync.model

import kotlinx.serialization.Serializable

/** Kind of component that isolation mode can single out. */
@Serializable
enum class IsolationType { CATEGORY, BAND, GROUP }

/** Predefined combinations of components that are useful to feel on their own. */
@Serializable
enum class IsolationGroup(
    val label: String,
    val categories: Set<SoundCategory>,
    val bands: Set<BandId>,
) {
    LOW_FREQUENCY("Low-frequency sounds", emptySet(), setOf(BandId.SUB_BASS, BandId.BASS)),
    SUDDEN_IMPACTS(
        "Sudden impacts",
        setOf(
            SoundCategory.EXPLOSION, SoundCategory.GUNSHOT, SoundCategory.PUNCH, SoundCategory.IMPACT,
            SoundCategory.DOOR_SLAM, SoundCategory.THUNDER,
        ),
        emptySet(),
    ),
    ENGINE(
        "Engine",
        setOf(
            SoundCategory.ENGINE_IDLE, SoundCategory.ENGINE_REV, SoundCategory.ACCELERATION,
            SoundCategory.DECELERATION, SoundCategory.GEAR_SHIFT, SoundCategory.VEHICLE_PASSING,
            SoundCategory.ENGINE_RUMBLE,
        ),
        emptySet(),
    ),
    DRUMS(
        "Drums & beats",
        setOf(
            SoundCategory.BASS_DRUM, SoundCategory.SNARE, SoundCategory.CYMBAL, SoundCategory.PERCUSSION,
            SoundCategory.BEAT, SoundCategory.BASS_DROP,
        ),
        emptySet(),
    ),
}

/**
 * A single component selected for isolation: one category, one band or one group.
 * [id] holds the enum name of the referenced [SoundCategory], [BandId] or [IsolationGroup].
 */
@Serializable
data class IsolationTarget(val type: IsolationType, val id: String) {

    val category: SoundCategory? get() = if (type == IsolationType.CATEGORY) SoundCategory.fromName(id) else null
    val band: BandId? get() = if (type == IsolationType.BAND) BandId.entries.firstOrNull { it.name == id } else null
    val group: IsolationGroup? get() = if (type == IsolationType.GROUP) IsolationGroup.entries.firstOrNull { it.name == id } else null

    val isValid: Boolean get() = category != null || band != null || group != null

    val label: String
        get() = category?.label ?: band?.label ?: group?.label ?: "Unknown"

    /** Categories this target lets through (empty for pure band targets). */
    fun allowedCategories(): Set<SoundCategory> = when {
        category != null -> setOf(category!!)
        group != null -> group!!.categories
        else -> emptySet()
    }

    /** Bands this target lets through (empty for pure category targets). */
    fun allowedBands(): Set<BandId> = when {
        band != null -> setOf(band!!)
        group != null -> group!!.bands
        else -> emptySet()
    }

    /** Whether this target is a frequency-band isolation (band or band group). */
    val isBandTarget: Boolean get() = allowedBands().isNotEmpty()

    companion object {
        fun of(category: SoundCategory) = IsolationTarget(IsolationType.CATEGORY, category.name)
        fun of(band: BandId) = IsolationTarget(IsolationType.BAND, band.name)
        fun of(group: IsolationGroup) = IsolationTarget(IsolationType.GROUP, group.name)

        /** All selectable targets in presentation order. */
        fun all(): List<IsolationTarget> =
            IsolationGroup.entries.map { of(it) } + SoundCategory.entries.filter { it != SoundCategory.DIALOGUE }.map { of(it) } +
                BandId.entries.map { of(it) }
    }
}

/**
 * Component isolation mode: when [enabled], the haptic engine ignores everything except
 * [target].
 */
@Serializable
data class IsolationSettings(
    val enabled: Boolean = false,
    val target: IsolationTarget = IsolationTarget.of(SoundCategory.ENGINE_REV),
) {
    fun validated(): IsolationSettings =
        if (target.isValid) this else copy(target = IsolationTarget.of(SoundCategory.ENGINE_REV))
}
