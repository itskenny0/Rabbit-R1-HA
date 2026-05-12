package com.github.itskenny0.r1ha.core.prefs

/**
 * Per-card client-side customization. Each field is nullable so the absence of an
 * override means "fall through to the global setting" — that way a brand new card picks
 * up whatever UI option the user has set, but a card the user has customized stays
 * customized even when global options change.
 *
 * Stored alongside (not inside) the [AppSettings.nameOverrides] map. Names live in their
 * own map because that field shipped earlier and renaming it would force a migration of
 * users' existing renames; this struct adds the rest of the customizable surface.
 */
data class EntityOverride(
    /**
     * Visual size multiplier for the card's big readout (the percent number, the ON/OFF
     * word, the sensor value). 1.0 = default. Constrained on the way out to a small set
     * of presets (0.7 / 0.85 / 1.0 / 1.15 / 1.3) — letting the user type an arbitrary
     * float would defeat the segmented-picker UX and risk weird edge cases.
     */
    val textScale: Float = 1.0f,
    /** Per-card override for [UiOptions.showOnOffPill]; null = inherit global. */
    val showOnOffPill: Boolean? = null,
    /** Per-card override for [UiOptions.showAreaLabel]; null = inherit global. */
    val showAreaLabel: Boolean? = null,
    /**
     * Entity to fire on long-press of this card. e.g. long-press a light card to trigger
     * a `scene.movie_night` or run a `script.bedtime`. Empty string = no long-press
     * action (the default). Validation: the entity_id must contain a "." and the
     * prefix must be a domain we know how to dispatch (anything supported, basically).
     */
    val longPressTarget: String? = null,
) {
    companion object {
        /** Allowed text-scale steps. Picker offers a chip per step. */
        val TEXT_SCALES = listOf(0.7f, 0.85f, 1.0f, 1.15f, 1.3f)

        val NONE = EntityOverride()
    }
}
