package com.hapticsync.model

import kotlinx.serialization.SerialName
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
 * Component isolation mode: when [enabled], the haptic engine ignores everything except the
 * selected [targets]. Any number of categories, bands and groups can be combined; a sound is
 * felt when it matches at least one of them.
 *
 * @property legacyTarget the single target stored by the first data version; [validated] folds
 *   it into [targets].
 */
@Serializable
data class IsolationSettings(
    val enabled: Boolean = false,
    val targets: List<IsolationTarget> = emptyList(),
    @SerialName("target") val legacyTarget: IsolationTarget? = null,
) {
    /** One target (convenience for code and tests). */
    constructor(enabled: Boolean, target: IsolationTarget) : this(enabled, listOf(target))

    /** Whether isolation is on and restricts output to at least one target. */
    val isActive: Boolean get() = enabled && targets.any { it.isValid }

    /** Categories let through by the targets (union over all selected categories and groups). */
    val allowedCategories: Set<SoundCategory> get() = targets.flatMapTo(mutableSetOf()) { it.allowedCategories() }

    /** Bands let through by the targets (union over all selected bands and groups). */
    val allowedBands: Set<BandId> get() = targets.flatMapTo(mutableSetOf()) { it.allowedBands() }

    fun contains(target: IsolationTarget): Boolean = target in targets

    /** Adds [target] if it is not selected, removes it otherwise. Selecting turns isolation on. */
    fun toggled(target: IsolationTarget): IsolationSettings {
        val next = if (target in targets) targets - target else targets + target
        return IsolationSettings(enabled = if (target in targets) enabled && next.isNotEmpty() else true, targets = next)
    }

    /** Short description of the selection, e.g. "Gear shifts, Bass drums +2". */
    fun summary(maxNames: Int = 2): String {
        val valid = targets.filter { it.isValid }
        if (valid.isEmpty()) return "Nothing selected"
        val names = valid.take(maxNames).joinToString(", ") { it.label }
        return if (valid.size > maxNames) "$names +${valid.size - maxNames}" else names
    }

    fun validated(): IsolationSettings {
        val merged = (targets + listOfNotNull(legacyTarget)).filter { it.isValid }.distinct()
        return IsolationSettings(enabled = enabled && merged.isNotEmpty(), targets = merged, legacyTarget = null)
    }
}
